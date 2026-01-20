package io.testomat.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.testomat.service.TestIdAnnotationManager.TestMethodInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestIdAnnotationManagerTest {

    private TestIdAnnotationManager testIdAnnotationManager;
    private JavaParser javaParser;

    @TempDir
    Path tempDir;

    // Test class without TestId annotation
    private static final String TEST_CLASS_WITHOUT_TESTID = 
            "package com.example.test;\n" +
            "\n" +
            "import org.junit.jupiter.api.Test;\n" +
            "\n" +
            "class SampleTest {\n" +
            "\n" +
            "    @Test\n" +
            "    void testMethod() {\n" +
            "        // Test implementation\n" +
            "    }\n" +
            "\n" +
            "    @Test\n" +
            "    void anotherTestMethod() {\n" +
            "        // Another test\n" +
            "    }\n" +
            "}";

    // Test class with existing TestId annotation
    private static final String TEST_CLASS_WITH_TESTID = 
            "package com.example.test;\n" +
            "\n" +
            "import org.junit.jupiter.api.Test;\n" +
            "import io.testomat.core.annotation.TestId;\n" +
            "\n" +
            "class ExistingTestIdTest {\n" +
            "\n" +
            "    @Test\n" +
            "    @TestId(\"existing-id\")\n" +
            "    void testWithExistingId() {\n" +
            "        // Test with existing TestId\n" +
            "    }\n" +
            "\n" +
            "    @Test\n" +
            "    void testWithoutId() {\n" +
            "        // Test without TestId\n" +
            "    }\n" +
            "}";

    // Multiple classes in different files
    private static final String FIRST_TEST_CLASS = 
            "package com.example.test;\n" +
            "\n" +
            "import org.junit.jupiter.api.Test;\n" +
            "\n" +
            "class FirstTest {\n" +
            "\n" +
            "    @Test\n" +
            "    void firstTestMethod() {\n" +
            "        // First test\n" +
            "    }\n" +
            "}";

    private static final String SECOND_TEST_CLASS = 
            "package com.example.test;\n" +
            "\n" +
            "import org.junit.jupiter.api.Test;\n" +
            "\n" +
            "class SecondTest {\n" +
            "\n" +
            "    @Test\n" +
            "    void secondTestMethod() {\n" +
            "        // Second test\n" +
            "    }\n" +
            "\n" +
            "    @Test\n" +
            "    void firstTestMethod() {\n" +
            "        // Same method name as in FirstTest\n" +
            "    }\n" +
            "}";

    @BeforeEach
    void setUp() {
        testIdAnnotationManager = new TestIdAnnotationManager();
        javaParser = new JavaParser();
    }

    @Test
    @DisplayName("Should find method in compilation units by file path and method info")
    void shouldFindMethodInCompilationUnitsByFilePathAndMethodInfo() throws IOException {
        // Given
        Path firstTestFile = tempDir.resolve("FirstTest.java");
        Path secondTestFile = tempDir.resolve("SecondTest.java");
        Files.write(firstTestFile, FIRST_TEST_CLASS.getBytes());
        Files.write(secondTestFile, SECOND_TEST_CLASS.getBytes());

        CompilationUnit firstCu = parseCodeWithStorage(FIRST_TEST_CLASS, firstTestFile);
        CompilationUnit secondCu = parseCodeWithStorage(SECOND_TEST_CLASS, secondTestFile);
        List<CompilationUnit> compilationUnits = Arrays.asList(firstCu, secondCu);

        TestMethodInfo methodInfo = new TestMethodInfo(
                firstTestFile.toString(), "FirstTest", "firstTestMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertTrue(result.isPresent(), "Should find the method");
        assertEquals("firstTestMethod", result.get().getNameAsString());
        
        // Verify it's from the correct class
        assertEquals("FirstTest", result.get()
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .get().getNameAsString());
    }

    @Test
    @DisplayName("Should return empty when method not found in compilation units")
    void shouldReturnEmptyWhenMethodNotFoundInCompilationUnits() throws IOException {
        // Given
        Path testFile = tempDir.resolve("FirstTest.java");
        Files.write(testFile, FIRST_TEST_CLASS.getBytes());

        CompilationUnit cu = parseCodeWithStorage(FIRST_TEST_CLASS, testFile);
        List<CompilationUnit> compilationUnits = Collections.singletonList(cu);

        TestMethodInfo methodInfo = new TestMethodInfo(
                testFile.toString(), "FirstTest", "nonExistentMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertFalse(result.isPresent(), "Should not find non-existent method");
    }

    @Test
    @DisplayName("Should return empty when file path doesn't match")
    void shouldReturnEmptyWhenFilePathDoesntMatch() throws IOException {
        // Given
        Path testFile = tempDir.resolve("FirstTest.java");
        Files.write(testFile, FIRST_TEST_CLASS.getBytes());

        CompilationUnit cu = parseCodeWithStorage(FIRST_TEST_CLASS, testFile);
        List<CompilationUnit> compilationUnits = Collections.singletonList(cu);

        TestMethodInfo methodInfo = new TestMethodInfo(
                "DifferentTest.java", "FirstTest", "firstTestMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertFalse(result.isPresent(), "Should not find method in different file");
    }

    @Test
    @DisplayName("Should return empty when class name doesn't match")
    void shouldReturnEmptyWhenClassNameDoesntMatch() throws IOException {
        // Given
        Path testFile = tempDir.resolve("FirstTest.java");
        Files.write(testFile, FIRST_TEST_CLASS.getBytes());

        CompilationUnit cu = parseCodeWithStorage(FIRST_TEST_CLASS, testFile);
        List<CompilationUnit> compilationUnits = Collections.singletonList(cu);

        TestMethodInfo methodInfo = new TestMethodInfo(
                testFile.toString(), "DifferentClass", "firstTestMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertFalse(result.isPresent(), "Should not find method in different class");
    }

    @Test
    @DisplayName("Should add new TestId annotation to method without existing annotation")
    void shouldAddNewTestIdAnnotationToMethodWithoutExistingAnnotation() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITHOUT_TESTID);
        MethodDeclaration method = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> "testMethod".equals(m.getNameAsString()))
                .findFirst().get();

        // When
        testIdAnnotationManager.addTestIdAnnotationToMethod(method, "@T12345");

        // Then
        Optional<AnnotationExpr> testIdAnnotation = method.getAnnotationByName("TestId");
        assertTrue(testIdAnnotation.isPresent(), "TestId annotation should be added");
        
        assertTrue(testIdAnnotation.get().isSingleMemberAnnotationExpr());
        SingleMemberAnnotationExpr annotation = testIdAnnotation.get().asSingleMemberAnnotationExpr();
        assertEquals("12345", annotation.getMemberValue().asStringLiteralExpr().getValue());
    }

    @Test
    @DisplayName("Should NOT update existing TestId annotation with new value (preserve original)")
    void shouldNotUpdateExistingTestIdAnnotationWithNewValue() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITH_TESTID);
        MethodDeclaration method = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> "testWithExistingId".equals(m.getNameAsString()))
                .findFirst().get();

        // When - try to update with a different TestId
        testIdAnnotationManager.addTestIdAnnotationToMethod(method, "@T67890");

        // Then - original TestId should be preserved, not updated
        Optional<AnnotationExpr> testIdAnnotation = method.getAnnotationByName("TestId");
        assertTrue(testIdAnnotation.isPresent(), "TestId annotation should exist");

        assertTrue(testIdAnnotation.get().isSingleMemberAnnotationExpr());
        SingleMemberAnnotationExpr annotation = testIdAnnotation.get().asSingleMemberAnnotationExpr();
        assertEquals("existing-id", annotation.getMemberValue().asStringLiteralExpr().getValue(),
                "Existing TestId should NOT be overwritten");
    }

    @Test
    @DisplayName("Should not update TestId annotation if value is the same")
    void shouldNotUpdateTestIdAnnotationIfValueIsTheSame() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITH_TESTID);
        MethodDeclaration method = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> "testWithExistingId".equals(m.getNameAsString()))
                .findFirst().get();

        // Get original annotation reference
        AnnotationExpr originalAnnotation = method.getAnnotationByName("TestId").get();

        // When
        testIdAnnotationManager.addTestIdAnnotationToMethod(method, "@Texisting-id");

        // Then
        Optional<AnnotationExpr> testIdAnnotation = method.getAnnotationByName("TestId");
        assertTrue(testIdAnnotation.isPresent(), "TestId annotation should exist");
        
        // Verify value hasn't changed
        SingleMemberAnnotationExpr annotation = testIdAnnotation.get().asSingleMemberAnnotationExpr();
        assertEquals("existing-id", annotation.getMemberValue().asStringLiteralExpr().getValue());
    }

    @Test
    @DisplayName("Should clean TestId prefix when adding annotation")
    void shouldCleanTestIdPrefixWhenAddingAnnotation() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITHOUT_TESTID);
        MethodDeclaration method = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> "testMethod".equals(m.getNameAsString()))
                .findFirst().get();

        // When
        testIdAnnotationManager.addTestIdAnnotationToMethod(method, "@T@T@Tmultiple-prefix");

        // Then
        Optional<AnnotationExpr> testIdAnnotation = method.getAnnotationByName("TestId");
        assertTrue(testIdAnnotation.isPresent(), "TestId annotation should be added");
        
        SingleMemberAnnotationExpr annotation = testIdAnnotation.get().asSingleMemberAnnotationExpr();
        assertEquals("multiple-prefix", annotation.getMemberValue().asStringLiteralExpr().getValue());
    }

    @Test
    @DisplayName("Should add TestId import when not present")
    void shouldAddTestIdImportWhenNotPresent() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITHOUT_TESTID);
        
        // Verify import doesn't exist initially
        boolean hasImportBefore = cu.getImports().stream()
                .anyMatch(imp -> "io.testomat.core.annotation.TestId".equals(imp.getNameAsString()));
        assertFalse(hasImportBefore, "TestId import should not exist initially");

        // When
        testIdAnnotationManager.ensureTestIdImportExists(cu);

        // Then
        boolean hasImportAfter = cu.getImports().stream()
                .anyMatch(imp -> "io.testomat.core.annotation.TestId".equals(imp.getNameAsString()));
        assertTrue(hasImportAfter, "TestId import should be added");
    }

    @Test
    @DisplayName("Should not add duplicate TestId import when already present")
    void shouldNotAddDuplicateTestIdImportWhenAlreadyPresent() {
        // Given
        CompilationUnit cu = parseCode(TEST_CLASS_WITH_TESTID);
        
        // Count imports before
        long importCountBefore = cu.getImports().stream()
                .filter(imp -> "io.testomat.core.annotation.TestId".equals(imp.getNameAsString()))
                .count();
        assertEquals(1, importCountBefore, "Should have exactly one TestId import initially");

        // When
        testIdAnnotationManager.ensureTestIdImportExists(cu);

        // Then
        long importCountAfter = cu.getImports().stream()
                .filter(imp -> "io.testomat.core.annotation.TestId".equals(imp.getNameAsString()))
                .count();
        assertEquals(1, importCountAfter, "Should still have exactly one TestId import");
    }

    @Test
    @DisplayName("TestMethodInfo should store and return correct values")
    void testMethodInfoShouldStoreAndReturnCorrectValues() {
        // Given
        String filePath = "/path/to/TestClass.java";
        String className = "TestClass";
        String methodName = "testMethod";

        // When
        TestMethodInfo methodInfo = new TestMethodInfo(filePath, className, methodName);

        // Then
        assertEquals(filePath, methodInfo.getFilePath());
        assertEquals(className, methodInfo.getClassName());
        assertEquals(methodName, methodInfo.getMethodName());
    }

    @Test
    @DisplayName("Should handle empty compilation units list")
    void shouldHandleEmptyCompilationUnitsList() {
        // Given
        List<CompilationUnit> emptyList = Collections.emptyList();
        TestMethodInfo methodInfo = new TestMethodInfo("Test.java", "TestClass", "testMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(emptyList, methodInfo);

        // Then
        assertFalse(result.isPresent(), "Should return empty for empty compilation units list");
    }

    @Test
    @DisplayName("Should handle compilation unit without storage")
    void shouldHandleCompilationUnitWithoutStorage() {
        // Given
        CompilationUnit cu = parseCode(FIRST_TEST_CLASS); // No storage set
        List<CompilationUnit> compilationUnits = Collections.singletonList(cu);
        TestMethodInfo methodInfo = new TestMethodInfo("FirstTest.java", "FirstTest", "firstTestMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertFalse(result.isPresent(), "Should return empty when compilation unit has no storage");
    }

    @Test
    @DisplayName("Should distinguish between methods with same name in different classes")
    void shouldDistinguishBetweenMethodsWithSameNameInDifferentClasses() throws IOException {
        // Given
        Path secondTestFile = tempDir.resolve("SecondTest.java");
        Files.write(secondTestFile, SECOND_TEST_CLASS.getBytes());

        CompilationUnit cu = parseCodeWithStorage(SECOND_TEST_CLASS, secondTestFile);
        List<CompilationUnit> compilationUnits = Collections.singletonList(cu);

        TestMethodInfo methodInfo = new TestMethodInfo(
                secondTestFile.toString(), "SecondTest", "firstTestMethod");

        // When
        Optional<MethodDeclaration> result = testIdAnnotationManager
                .findMethodInCompilationUnits(compilationUnits, methodInfo);

        // Then
        assertTrue(result.isPresent(), "Should find method in SecondTest");
        assertEquals("firstTestMethod", result.get().getNameAsString());
        
        // Verify it's from SecondTest, not FirstTest
        assertEquals("SecondTest", result.get()
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .get().getNameAsString());
    }

    // Helper methods
    
    private CompilationUnit parseCode(String code) {
        return javaParser.parse(code).getResult()
                .orElseThrow(() -> new RuntimeException("Failed to parse test code"));
    }

    private CompilationUnit parseCodeWithStorage(String code, Path filePath) throws IOException {
        Files.write(filePath, code.getBytes());
        return javaParser.parse(filePath).getResult()
                .orElseThrow(() -> new RuntimeException("Failed to parse test code from file"));
    }
}