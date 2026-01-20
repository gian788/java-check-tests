package io.testomat.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import io.testomat.model.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TestMethodExtractor {

    private static final Pattern COMMENT_LABEL_PATTERN =
            Pattern.compile("@(\\w+)(?::(\\w+))?|#(\\w+)");

    public List<TestCase> extractTestCases(CompilationUnit cu, String filepath, String framework) {
        List<MethodDeclaration> testMethods = cu.findAll(MethodDeclaration.class).stream()
                .filter(method -> isTestMethod(method, framework))
                .collect(Collectors.toList());

        return testMethods.stream()
                .map(method -> createTestCase(method, filepath, framework))
                .collect(Collectors.toList());
    }

    private boolean isTestMethod(MethodDeclaration method, String framework) {
        return method.getAnnotations().stream()
                .anyMatch(ann -> {
                    String name = ann.getNameAsString();

                    if ("junit".equals(framework)) {
                        return "Test".equals(name)
                                || "ParameterizedTest".equals(name)
                                || "RepeatedTest".equals(name)
                                || "TestFactory".equals(name);
                    } else if ("testng".equals(framework)) {
                        return "Test".equals(name);
                    }

                    return false;
                });
    }

    private TestCase createTestCase(MethodDeclaration method, String filepath, String framework) {
        TestCase testCase = new TestCase();

        testCase.setName(getTestName(method));
        testCase.setCode(getMethodCode(method));
        testCase.setSkipped(isTestSkipped(method));
        testCase.setSuites(getSuites(method));
        testCase.setLabels(getLabels(method, framework));
        testCase.setFile(PathUtils.extractRelativeFilePath(filepath));

        return testCase;
    }

    private String getTestName(MethodDeclaration method) {
        return method.getNameAsString();
    }

    private String getMethodCode(MethodDeclaration method) {
        StringBuilder code = new StringBuilder();

        method.getAnnotations().forEach(annotation ->
                code.append(annotation.toString()).append("\n"));

        method.getModifiers().forEach(modifier ->
                code.append(modifier.getKeyword().asString()).append(" "));

        code.append(method.getTypeAsString())
                .append(" ")
                .append(method.getNameAsString())
                .append("(");

        for (int i = 0; i < method.getParameters().size(); i++) {
            if (i > 0) {
                code.append(", ");
            }
            code.append(method.getParameter(i).toString());
        }
        code.append(")");

        if (!method.getThrownExceptions().isEmpty()) {
            code.append(" throws ");
            for (int i = 0; i < method.getThrownExceptions().size(); i++) {
                if (i > 0) {
                    code.append(", ");
                }
                code.append(method.getThrownException(i).toString());
            }
        }

        Optional<BlockStmt> body = method.getBody();
        body.ifPresent(blockStmt -> code.append(" ").append(blockStmt));

        return code.toString();
    }

    private boolean isTestSkipped(MethodDeclaration method) {
        boolean hasSkipAnnotation = method.getAnnotations().stream()
                .anyMatch(ann -> "Disabled".equals(ann.getNameAsString())
                        || "Ignore".equals(ann.getNameAsString()));

        String methodName = method.getNameAsString();
        boolean hasSkipMethodName = methodName.startsWith("ignore")
                || methodName.startsWith("skip");

        return hasSkipAnnotation || hasSkipMethodName;
    }

    private List<String> getSuites(MethodDeclaration method) {
        List<String> suites = new ArrayList<>();

        ClassOrInterfaceDeclaration currentClass =
                method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);

        List<ClassOrInterfaceDeclaration> classHierarchy = new ArrayList<>();
        while (currentClass != null) {
            classHierarchy.add(0, currentClass);
            currentClass =
                    currentClass.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        }

        for (ClassOrInterfaceDeclaration clazz : classHierarchy) {
            suites.add(clazz.getNameAsString());
        }

        return suites;
    }

    private List<String> getLabels(MethodDeclaration method, String framework) {
        List<String> labels = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String annName = annotation.getNameAsString();
            addFrameworkLabels(labels, annName, annotation, framework);
        }

        addCommentLabels(method, labels);
        addNamePatternLabels(method, labels);

        return labels;
    }

    private void addFrameworkLabels(List<String> labels, String annName,
                                    AnnotationExpr annotation, String framework) {
        if ("junit".equals(framework)) {
            switch (annName) {
                case "Test":
                    labels.add("unit");
                    break;
                case "IntegrationTest":
                case "SpringBootTest":
                    labels.add("integration");
                    break;
                case "ParameterizedTest":
                    labels.add("parameterized");
                    break;
                case "RepeatedTest":
                    labels.add("repeated");
                    break;
                case "TestFactory":
                    labels.add("dynamic");
                    break;
                case "Disabled":
                case "Ignore":
                    labels.add("disabled");
                    break;
                case "Timeout":
                    labels.add("timeout");
                    break;
                case "WebMvcTest":
                    labels.add("web");
                    break;
                case "DataJpaTest":
                    labels.add("jpa");
                    break;
                case "JsonTest":
                    labels.add("json");
                    break;
                case "Tag":
                    String tagValue = getAnnotationValue(annotation);
                    if (tagValue != null) {
                        labels.add(tagValue);
                    }
                    break;
                default:
                    if (annName.endsWith("Test")) {
                        labels.add(annName.toLowerCase().replace("test", ""));
                    }
                    break;
            }
        } else if ("testng".equals(framework)) {
            switch (annName) {
                case "Test":
                    labels.add("unit");
                    String groups = getTestNgGroups(annotation);
                    if (groups != null) {
                        for (String group : groups.split(",")) {
                            labels.add(group.trim());
                        }
                    }
                    break;
                case "DataProvider":
                    labels.add("parameterized");
                    break;
                case "BeforeMethod":
                case "AfterMethod":
                case "BeforeClass":
                case "AfterClass":
                case "BeforeTest":
                case "AfterTest":
                case "BeforeSuite":
                case "AfterSuite":
                    labels.add("lifecycle");
                    break;
                default:
                    if (annName.endsWith("Test")) {
                        labels.add(annName.toLowerCase().replace("test", ""));
                    }
                    break;
            }
        }
    }

    private void addCommentLabels(MethodDeclaration method, List<String> labels) {
        method.getComment().ifPresent(comment -> {
            Matcher matcher = COMMENT_LABEL_PATTERN.matcher(comment.getContent());

            while (matcher.find()) {
                if (matcher.group(3) != null) {
                    labels.add(matcher.group(3));
                } else {
                    String tag = matcher.group(1);
                    String value = matcher.group(2);
                    labels.add(value != null ? tag + ":" + value : tag);
                }
            }
        });
    }

    private void addNamePatternLabels(MethodDeclaration method, List<String> labels) {
        String methodName = method.getNameAsString().toLowerCase();
        if (methodName.contains("integration")) {
            labels.add("integration");
        }
        if (methodName.contains("smoke")) {
            labels.add("smoke");
        }
        if (methodName.contains("performance")) {
            labels.add("performance");
        }
        if (methodName.contains("acceptance")) {
            labels.add("acceptance");
        }
        if (methodName.contains("regression")) {
            labels.add("regression");
        }
    }

    private String getAnnotationValue(AnnotationExpr annotation) {
        try {
            if (annotation instanceof SingleMemberAnnotationExpr) {
                var memberValue = ((SingleMemberAnnotationExpr) annotation).getMemberValue();
                if (memberValue.isStringLiteralExpr()) {
                    return memberValue.asStringLiteralExpr().getValue();
                } else if (memberValue.isFieldAccessExpr()) {
                    // Handle constant references like MultiTags.SMOKE
                    return memberValue.asFieldAccessExpr().getNameAsString();
                } else if (memberValue.isNameExpr()) {
                    // Handle simple constant references
                    return memberValue.asNameExpr().getNameAsString();
                }
            } else if (annotation instanceof NormalAnnotationExpr) {
                return ((NormalAnnotationExpr) annotation)
                        .getPairs().stream()
                        .filter(pair -> "value".equals(pair.getNameAsString()))
                        .findFirst()
                        .map(pair -> {
                            var value = pair.getValue();
                            if (value.isStringLiteralExpr()) {
                                return value.asStringLiteralExpr().getValue();
                            } else if (value.isFieldAccessExpr()) {
                                return value.asFieldAccessExpr().getNameAsString();
                            } else if (value.isNameExpr()) {
                                return value.asNameExpr().getNameAsString();
                            }
                            return null;
                        })
                        .orElse(null);
            }
        } catch (Exception e) {
            // Ignore annotation parsing errors, return null
        }
        return null;
    }

    private String getTestNgGroups(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            return ((NormalAnnotationExpr) annotation)
                    .getPairs().stream()
                    .filter(pair -> "groups".equals(pair.getNameAsString()))
                    .findFirst()
                    .map(pair -> pair.getValue().toString().replaceAll("[\"{}\\[\\]]", ""))
                    .orElse(null);
        }
        return null;
    }
}
