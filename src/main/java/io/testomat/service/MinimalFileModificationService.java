package io.testomat.service;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.testomat.exception.CliException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service that modifies Java source files with minimal changes to preserve original code style.
 * Instead of using JavaParser's pretty printer which reformats the entire file,
 * this service makes surgical text insertions only where needed.
 */
public class MinimalFileModificationService {

    private static final String TEST_ID_IMPORT = "io.testomat.core.annotation.TestId";
    private static final String TEST_ID_ANNOTATION = "TestId";

    /**
     * Tracks modifications needed for a single compilation unit.
     */
    public static class FileModification {
        private final CompilationUnit compilationUnit;
        private final Map<MethodDeclaration, String> methodAnnotations = new IdentityHashMap<>();
        private final List<AnnotationExpr> annotationsToRemove = new ArrayList<>();
        private final List<ImportDeclaration> importsToRemove = new ArrayList<>();
        private boolean needsImport = false;

        public FileModification(CompilationUnit compilationUnit) {
            this.compilationUnit = compilationUnit;
        }

        public void addMethodAnnotation(MethodDeclaration method, String testId) {
            methodAnnotations.put(method, testId);
        }

        public void removeAnnotation(AnnotationExpr annotation) {
            annotationsToRemove.add(annotation);
        }

        public void removeImport(ImportDeclaration importDecl) {
            importsToRemove.add(importDecl);
        }

        public void setNeedsImport(boolean needsImport) {
            this.needsImport = needsImport;
        }

        public CompilationUnit getCompilationUnit() {
            return compilationUnit;
        }

        public Map<MethodDeclaration, String> getMethodAnnotations() {
            return methodAnnotations;
        }

        public List<AnnotationExpr> getAnnotationsToRemove() {
            return annotationsToRemove;
        }

        public List<ImportDeclaration> getImportsToRemove() {
            return importsToRemove;
        }

        public boolean needsImport() {
            return needsImport;
        }

        public boolean hasModifications() {
            return !methodAnnotations.isEmpty() || needsImport || !annotationsToRemove.isEmpty()
                    || !importsToRemove.isEmpty();
        }
    }

    /**
     * Applies modifications to a file while preserving original formatting.
     * If the CompilationUnit doesn't have storage (e.g., in tests), modifications are skipped.
     */
    public void applyModifications(FileModification modification) {
        if (!modification.hasModifications()) {
            return;
        }

        CompilationUnit cu = modification.getCompilationUnit();

        // Check if storage exists - if not, skip (typically for in-memory test cases)
        if (!cu.getStorage().isPresent()) {
            return;
        }

        Path filePath = cu.getStorage().get().getPath();

        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<TextModification> modifications = new ArrayList<>();

            // Step 1: Remove annotations
            for (AnnotationExpr annotation : modification.getAnnotationsToRemove()) {
                TextModification removal = createAnnotationRemoval(annotation);
                if (removal != null) {
                    modifications.add(removal);
                }
            }

            // Step 2: Remove imports
            for (ImportDeclaration importDecl : modification.getImportsToRemove()) {
                TextModification removal = createImportRemoval(importDecl);
                if (removal != null) {
                    modifications.add(removal);
                }
            }

            // Step 3: Add import if needed
            if (modification.needsImport()) {
                TextModification importInsertion = createImportInsertion(lines, cu);
                if (importInsertion != null) {
                    modifications.add(importInsertion);
                }
            }

            // Step 4: Add or update annotations for methods
            for (Map.Entry<MethodDeclaration, String> entry :
                    modification.getMethodAnnotations().entrySet()) {
                MethodDeclaration method = entry.getKey();
                String testId = entry.getValue();

                TextModification annotationInsertion = createAnnotationInsertion(lines, method,
                        testId);
                if (annotationInsertion != null) {
                    modifications.add(annotationInsertion);
                }
            }

            // Step 5: Apply all modifications (sorted by line number in descending order)
            modifications.sort((a, b) -> Integer.compare(b.lineNumber, a.lineNumber));
            applyModifications(lines, modifications);

            // Step 6: Write the file back
            Files.write(filePath, lines, StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new CliException("Failed to modify file: " + filePath, e);
        }
    }

    /**
     * Applies all modifications to the lines list.
     */
    private void applyModifications(List<String> lines, List<TextModification> modifications) {
        for (TextModification modification : modifications) {
            if (modification.type == ModificationType.INSERT) {
                // Insert new line
                lines.add(modification.lineNumber, modification.text);
            } else if (modification.type == ModificationType.REPLACE) {
                // Replace existing line
                lines.set(modification.lineNumber, modification.text);
            } else if (modification.type == ModificationType.DELETE) {
                // Delete line
                lines.remove(modification.lineNumber);
            }
        }
    }

    /**
     * Creates a removal for an annotation.
     */
    private TextModification createAnnotationRemoval(AnnotationExpr annotation) {
        Position annotationStart = annotation.getBegin()
                .orElseThrow(() -> new IllegalStateException("Annotation has no position"));

        int annotationLineIndex = annotationStart.line - 1; // Convert to 0-based

        return new TextModification(annotationLineIndex, null, ModificationType.DELETE);
    }

    /**
     * Creates a removal for an import.
     */
    private TextModification createImportRemoval(ImportDeclaration importDecl) {
        Position importStart = importDecl.getBegin()
                .orElseThrow(() -> new IllegalStateException("Import has no position"));

        int importLineIndex = importStart.line - 1; // Convert to 0-based

        return new TextModification(importLineIndex, null, ModificationType.DELETE);
    }

    /**
     * Creates an insertion for the TestId import statement if it doesn't exist.
     */
    private TextModification createImportInsertion(List<String> lines, CompilationUnit cu) {
        // Check if import already exists
        boolean hasImport = cu.getImports().stream()
                .anyMatch(imp -> TEST_ID_IMPORT.equals(imp.getNameAsString()));

        if (hasImport) {
            return null;
        }

        // Find the position to insert the import
        int insertLineIndex = findImportInsertPosition(lines, cu);

        // Create import line
        String importLine = "import " + TEST_ID_IMPORT + ";";

        return new TextModification(insertLineIndex, importLine, ModificationType.INSERT);
    }

    /**
     * Finds the best position to insert the TestId import.
     */
    private int findImportInsertPosition(List<String> lines, CompilationUnit cu) {
        List<ImportDeclaration> imports = cu.getImports();

        if (imports.isEmpty()) {
            // No imports exist - add after package declaration
            Optional<Position> packagePos = cu.getPackageDeclaration()
                    .flatMap(Node::getEnd);

            if (packagePos.isPresent()) {
                // Insert after package line
                return packagePos.get().line; // 1-based line number
            } else {
                // No package - insert at the beginning
                return 0;
            }
        } else {
            // Find the last import and insert after it
            Optional<Position> lastImportPos = imports.get(imports.size() - 1)
                    .getEnd();

            if (lastImportPos.isPresent()) {
                return lastImportPos.get().line; // 1-based, will insert after this line
            }
        }

        // Fallback: insert at the beginning
        return 0;
    }

    /**
     * Creates an insertion for adding or updating a @TestId annotation.
     */
    private TextModification createAnnotationInsertion(List<String> lines,
                                                       MethodDeclaration method, String testId) {
        // Check if annotation already exists
        Optional<AnnotationExpr> existingAnnotation =
                method.getAnnotationByName(TEST_ID_ANNOTATION);

        String cleanTestId = cleanTestId(testId);

        if (existingAnnotation.isPresent()) {
            // Update existing annotation with new ID
            return createAnnotationUpdateInsertion(lines, existingAnnotation.get(), cleanTestId);
        } else {
            // Add new annotation
            return createNewAnnotationInsertion(lines, method, cleanTestId);
        }
    }

    /**
     * Creates an insertion to add a new @TestId annotation before a method.
     */
    private TextModification createNewAnnotationInsertion(List<String> lines,
                                                       MethodDeclaration method, String testId) {
        // Get the method's starting position
        Position methodStart = method.getBegin()
                .orElseThrow(() -> new IllegalStateException("Method has no position"));

        int methodLineIndex = methodStart.line - 1; // Convert to 0-based

        // Detect indentation of the method line
        String methodLine = lines.get(methodLineIndex);
        String indentation = detectIndentation(methodLine);

        // Create annotation line with same indentation
        String annotationLine = indentation + "@TestId(\"" + testId + "\")";

        // Insert before the method (use 1-based line number for consistency)
        return new TextModification(methodLineIndex, annotationLine, ModificationType.INSERT);
    }

    /**
     * Creates an insertion to update an existing @TestId annotation.
     */
    private TextModification createAnnotationUpdateInsertion(List<String> lines,
                                                          AnnotationExpr annotation,
                                                          String newTestId) {
        Position annotationStart = annotation.getBegin()
                .orElseThrow(() -> new IllegalStateException("Annotation has no position"));

        int annotationLineIndex = annotationStart.line - 1; // Convert to 0-based
        String line = lines.get(annotationLineIndex);

        // Replace the annotation value in the line
        String updatedLine = replaceAnnotationValue(line, newTestId);

        return new TextModification(annotationLineIndex, updatedLine, ModificationType.REPLACE);
    }

    /**
     * Replaces the @TestId annotation value in a line of text.
     */
    private String replaceAnnotationValue(String line, String newTestId) {
        // Handle various annotation formats:
        // @TestId("oldValue")
        // @TestId( "oldValue" )
        // @TestId("oldValue") // with comment
        return line.replaceFirst(
                "@TestId\\s*\\(\\s*\"[^\"]*\"\\s*\\)",
                "@TestId(\"" + newTestId + "\")"
        );
    }

    /**
     * Detects the indentation (leading whitespace) of a line.
     */
    private String detectIndentation(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Cleans the test ID by removing the @T prefix if present.
     */
    private String cleanTestId(String testId) {
        return testId.replace("@T", "");
    }

    /**
     * Represents a text modification at a specific line.
     */
    private static class TextModification {
        private final int lineNumber; // 0-based line index
        private final String text;
        private final ModificationType type;

        TextModification(int lineNumber, String text, ModificationType type) {
            this.lineNumber = lineNumber;
            this.text = text;
            this.type = type;
        }
    }

    /**
     * Type of text modification.
     */
    private enum ModificationType {
        INSERT, // Insert a new line
        REPLACE, // Replace an existing line
        DELETE // Delete a line
    }
}
