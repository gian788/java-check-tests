package io.testomat.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class TestIdAnnotationManager {

    private static final String TEST_ID_IMPORT = "io.testomat.core.annotation.TestId";
    private static final String TEST_ID_ANNOTATION = "TestId";
    private static final String TEST_ID_PREFIX = "@T";

    public Optional<MethodDeclaration> findMethodInCompilationUnits(
            List<CompilationUnit> compilationUnits, TestMethodInfo methodInfo) {
        return findMethodInCompilationUnits(compilationUnits, methodInfo, false);
    }

    public Optional<MethodDeclaration> findMethodInCompilationUnits(
            List<CompilationUnit> compilationUnits, TestMethodInfo methodInfo, boolean verbose) {

        if (verbose) {
            System.out.println("  Looking for method: " + methodInfo.getMethodName()
                             + " in class: " + methodInfo.getClassName()
                             + " from file: " + methodInfo.getFilePath());
        }

        Optional<MethodDeclaration> result;

        // First try: match by full path (most accurate for duplicate filenames)
        result = compilationUnits.stream()
                .filter(cu -> isMatchingFileByPath(cu, methodInfo.getFilePath(), verbose))
                .flatMap(cu -> findMethodsInCompilationUnit(cu, methodInfo, verbose).stream())
                .findFirst();

        if (result.isPresent()) {
            if (verbose) {
                System.out.println("  Found method using path-based matching");
            }
            return result;
        }

        // No filename fallback - path matching is required to avoid wrong file matches
        // when multiple files have the same name across different directories
        if (verbose) {
            System.out.println("  Method not found - path does not match any scanned file");
        }

        return Optional.empty();
    }

    public void addTestIdAnnotationToMethod(MethodDeclaration method, String testId) {
        String cleanTestId = cleanTestIdValue(testId);
        Optional<AnnotationExpr> existingAnnotation =
                method.getAnnotationByName(TEST_ID_ANNOTATION);

        if (existingAnnotation.isPresent()) {
            updateExistingTestIdAnnotation(existingAnnotation.get(), method, cleanTestId);
        } else {
            addNewTestIdAnnotation(method, cleanTestId);
        }
    }

    public void ensureTestIdImportExists(CompilationUnit compilationUnit) {
        boolean hasTestIdImport = compilationUnit.getImports().stream()
                .anyMatch(importDecl ->
                        TEST_ID_IMPORT.equals(importDecl.getNameAsString()));

        if (!hasTestIdImport) {
            ImportDeclaration testIdImport =
                    new ImportDeclaration(TEST_ID_IMPORT, false, false);
            compilationUnit.addImport(testIdImport);
        }
    }

    private boolean isMatchingFile(CompilationUnit compilationUnit, String expectedFileName,
                                    boolean verbose) {
        boolean matches = compilationUnit.getStorage()
                .map(storage -> storage.getPath()
                        .getFileName()
                        .toString()
                        .equals(expectedFileName))
                .orElse(false);
        
        if (verbose) {
            String actualFileName = compilationUnit.getStorage()
                    .map(storage -> storage.getPath().getFileName().toString())
                    .orElse("Unknown");
            System.out.println("    Checking file: " + actualFileName + " vs expected: "
                    + expectedFileName + " -> " + matches);
        }
        
        return matches;
    }

    private boolean isMatchingFileByPath(CompilationUnit compilationUnit, String expectedPath,
                                          boolean verbose) {
        return compilationUnit.getStorage()
                .map(storage -> {
                    Path actualPath = storage.getPath().normalize();
                    Path expectedNormalizedPath = Paths.get(expectedPath).normalize();
                    
                    boolean exactMatch = actualPath.equals(expectedNormalizedPath);
                    if (exactMatch) {
                        if (verbose) {
                            System.out.println("    Path exact match: " + actualPath);
                        }
                        return true;
                    }
                    
                    String actualPathStr = actualPath.toString().replace('\\', '/');
                    String expectedPathStr = expectedNormalizedPath.toString().replace('\\', '/');
                    
                    boolean endsWithMatch = actualPathStr.endsWith(expectedPathStr)
                                          || expectedPathStr.endsWith(actualPathStr);
                    
                    if (verbose) {
                        System.out.println("    Path comparison: " + actualPathStr + " vs "
                                + expectedPathStr + " -> " + endsWithMatch);
                    }
                    
                    return endsWithMatch;
                })
                .orElse(false);
    }

    private List<MethodDeclaration> findMethodsInCompilationUnit(
            CompilationUnit compilationUnit, TestMethodInfo methodInfo, boolean verbose) {
        List<MethodDeclaration> allMethods = compilationUnit.findAll(MethodDeclaration.class);
        
        List<MethodDeclaration> matchingMethods = allMethods.stream()
                .filter(method -> method.getNameAsString().equals(methodInfo.getMethodName()))
                .filter(method -> isMethodInCorrectClass(method, methodInfo.getClassName(),
                        verbose))
                .collect(Collectors.toList());
        
        if (verbose) {
            System.out.println("    Found " + allMethods.size() + " total methods, "
                             + matchingMethods.size() + " matching methods");
        }
        
        return matchingMethods;
    }

    private boolean isMethodInCorrectClass(MethodDeclaration method, String expectedClassName,
                                            boolean verbose) {
        Optional<ClassOrInterfaceDeclaration> classDecl = method
                .findAncestor(ClassOrInterfaceDeclaration.class);
        
        if (classDecl.isPresent()) {
            String actualClassName = classDecl.get().getNameAsString();
            boolean matches = actualClassName.equals(expectedClassName);
            
            if (verbose) {
                System.out.println("      Class match: " + actualClassName + " vs "
                        + expectedClassName + " -> " + matches);
            }
            
            return matches;
        } else {
            if (verbose) {
                System.out.println("      Method has no containing class");
            }
            return false;
        }
    }

    private String extractFileName(String filePath) {
        return java.nio.file.Paths.get(filePath).getFileName().toString();
    }

    private String cleanTestIdValue(String testId) {
        return testId.replace(TEST_ID_PREFIX, "");
    }

    private void updateExistingTestIdAnnotation(AnnotationExpr existingAnnotation,
                                                MethodDeclaration method, String cleanTestId) {
        if (existingAnnotation.isSingleMemberAnnotationExpr()) {
            updateSingleMemberAnnotation(existingAnnotation.asSingleMemberAnnotationExpr(),
                    cleanTestId);
        } else {
            replaceAnnotation(method, cleanTestId);
        }
    }

    private void updateSingleMemberAnnotation(SingleMemberAnnotationExpr annotation,
                                              String cleanTestId) {
        Expression currentValue = annotation.getMemberValue();

        if (currentValue.isStringLiteralExpr()) {
            StringLiteralExpr stringLiteral = currentValue.asStringLiteralExpr();
            if (!stringLiteral.getValue().equals(cleanTestId)) {
                annotation.setMemberValue(new StringLiteralExpr(cleanTestId));
            }
        } else {
            annotation.setMemberValue(new StringLiteralExpr(cleanTestId));
        }
    }

    private void replaceAnnotation(MethodDeclaration method, String cleanTestId) {
        method.getAnnotationByName(TEST_ID_ANNOTATION).ifPresent(method::remove);
        addNewTestIdAnnotation(method, cleanTestId);
    }

    private void addNewTestIdAnnotation(MethodDeclaration method, String cleanTestId) {
        SingleMemberAnnotationExpr newAnnotation = new SingleMemberAnnotationExpr(
                new Name(TEST_ID_ANNOTATION),
                new StringLiteralExpr(cleanTestId)
        );
        method.addAnnotation(newAnnotation);
    }

    public static class TestMethodInfo {
        private final String filePath;
        private final String className;
        private final String methodName;

        public TestMethodInfo(String filePath, String className, String methodName) {
            this.filePath = filePath;
            this.className = className;
            this.methodName = methodName;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }
    }
}
