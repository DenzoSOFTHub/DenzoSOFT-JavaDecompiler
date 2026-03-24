/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

/**
 * Comprehensive test suite for DenzoSOFT Java Decompiler.
 *
 * Run with: java -cp target/classes it.denzosoft.javadecompiler.DecompilerTest /path/to/jdk/bin/javac
 *
 * Tests all supported Java features by compiling test classes and decompiling them.
 */
public class DecompilerTest {

    private static String javacPath;
    private static int passed = 0;
    private static int failed = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java DecompilerTest <path-to-javac>");
            System.out.println("Example: java DecompilerTest /usr/lib/jvm/jdk-25/bin/javac");
            System.exit(1);
        }
        javacPath = args[0];

        System.out.println("DenzoSOFT Java Decompiler - Test Suite");
        System.out.println("=====================================\n");

        // Core tests
        testBasicClass();
        testAnnotations();
        testGenerics();
        testRecord();
        testSealedClass();
        testStaticInit();
        testStringConcat();
        testEnum();
        testInterface();
        testAbstractClass();
        testInheritance();
        testFieldTypes();
        testArithmetic();
        testTryCatch();
        testTryCatchFinally();
        testMultiCatch();

        // Summary
        System.out.println("\n=====================================");
        System.out.println("Results: " + passed + "/" + total + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testBasicClass() {
        runTest("BasicClass",
            "public class BasicClass {\n" +
            "    private String name;\n" +
            "    public BasicClass(String name) { this.name = name; }\n" +
            "    public String getName() { return name; }\n" +
            "}",
            new String[]{"class BasicClass", "private", "String name", "getName", "super()"});
    }

    private static void testAnnotations() {
        runTest("TestAnnot",
            "import java.lang.annotation.*;\n" +
            "@Deprecated\n" +
            "public class TestAnnot {\n" +
            "    @Deprecated public void old() {}\n" +
            "}",
            new String[]{"@Deprecated", "class TestAnnot", "void old"});
    }

    private static void testGenerics() {
        runTest("TestGen",
            "import java.util.*;\n" +
            "public class TestGen<T> {\n" +
            "    private List<String> items;\n" +
            "    public <E> E first(List<E> list) { return list.get(0); }\n" +
            "}",
            new String[]{"class TestGen<T>", "List<String>"});
    }

    private static void testRecord() {
        runTest("TestRecordC",
            "public record TestRecordC(String name, int value) {}",
            new String[]{"record TestRecordC", "String name", "int value"});
    }

    private static void testSealedClass() {
        // Create multiple files for sealed class hierarchy
        runTest("SealedBase",
            "public abstract sealed class SealedBase permits SealedSub {\n" +
            "    abstract int compute();\n" +
            "}\n" +
            "final class SealedSub extends SealedBase {\n" +
            "    int compute() { return 42; }\n" +
            "}",
            new String[]{"sealed class SealedBase", "permits SealedSub", "abstract"});
    }

    private static void testStaticInit() {
        runTest("TestStaticI",
            "public class TestStaticI {\n" +
            "    static final int X;\n" +
            "    static { X = 42; }\n" +
            "}",
            new String[]{"static final int X = 42"});
    }

    private static void testStringConcat() {
        runTest("TestStr",
            "public class TestStr {\n" +
            "    public String format(String a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}",
            new String[]{"class TestStr", "String format"});
    }

    private static void testEnum() {
        runTest("TestEnumC",
            "public enum TestEnumC {\n" +
            "    RED, GREEN, BLUE;\n" +
            "    public boolean isPrimary() { return this == RED || this == BLUE; }\n" +
            "}",
            new String[]{"enum TestEnumC", "RED", "GREEN", "BLUE"});
    }

    private static void testInterface() {
        runTest("TestIface",
            "public interface TestIface {\n" +
            "    void doSomething();\n" +
            "    default String name() { return \"default\"; }\n" +
            "}",
            new String[]{"interface TestIface", "void doSomething", "String name"});
    }

    private static void testAbstractClass() {
        runTest("TestAbstract",
            "public abstract class TestAbstract {\n" +
            "    protected int value;\n" +
            "    public abstract void process();\n" +
            "    public int getValue() { return value; }\n" +
            "}",
            new String[]{"abstract class TestAbstract", "protected", "abstract void process", "int getValue"});
    }

    private static void testInheritance() {
        runTest("TestChild",
            "public class TestChild extends java.util.ArrayList<String> implements java.io.Serializable {\n" +
            "    public TestChild() { super(); }\n" +
            "}",
            new String[]{"class TestChild", "extends", "implements", "Serializable"});
    }

    private static void testFieldTypes() {
        runTest("TestFields",
            "public class TestFields {\n" +
            "    public int a;\n" +
            "    private double b;\n" +
            "    protected static final String C = \"hello\";\n" +
            "    volatile boolean d;\n" +
            "    transient long e;\n" +
            "}",
            new String[]{"public int", "private double", "static final String C", "volatile boolean", "transient long"});
    }

    private static void testArithmetic() {
        runTest("TestMath",
            "public class TestMath {\n" +
            "    public int add(int a, int b) { return a + b; }\n" +
            "    public double div(double a, double b) { return a / b; }\n" +
            "    public float neg(float f) { return -f; }\n" +
            "    public int mod(int a, int b) { return a % b; }\n" +
            "}",
            new String[]{"int add", "double div", "float neg", "int mod"});
    }

    private static void testTryCatch() {
        runTest("TestTryCatch",
            "public class TestTryCatch {\n" +
            "    public void safe() {\n" +
            "        try {\n" +
            "            System.out.println(\"try\");\n" +
            "        } catch (Exception e) {\n" +
            "            System.out.println(\"catch\");\n" +
            "        }\n" +
            "    }\n" +
            "}",
            new String[]{"try", "catch", "Exception"});
    }

    private static void testTryCatchFinally() {
        runTest("TestTryCatchF",
            "public class TestTryCatchF {\n" +
            "    public void doIt() {\n" +
            "        try {\n" +
            "            System.out.println(\"try\");\n" +
            "        } catch (RuntimeException e) {\n" +
            "            System.out.println(\"catch\");\n" +
            "        } finally {\n" +
            "            System.out.println(\"finally\");\n" +
            "        }\n" +
            "    }\n" +
            "}",
            new String[]{"try", "catch", "RuntimeException", "finally"});
    }

    private static void testMultiCatch() {
        runTest("TestMultiCatch",
            "public class TestMultiCatch {\n" +
            "    public void multi() {\n" +
            "        try {\n" +
            "            System.out.println(\"try\");\n" +
            "        } catch (IllegalArgumentException e) {\n" +
            "            System.out.println(\"arg\");\n" +
            "        } catch (NullPointerException e) {\n" +
            "            System.out.println(\"null\");\n" +
            "        }\n" +
            "    }\n" +
            "}",
            new String[]{"try", "catch", "IllegalArgumentException", "NullPointerException"});
    }

    // Test infrastructure

    private static void runTest(String className, String sourceCode, String[] expectedContains) {
        total++;
        String testName = className;
        try {
            // Write source
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write(sourceCode);
            fw.close();

            // Compile
            ProcessBuilder pb = new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                java.io.InputStream is = p.getInputStream();
                byte[] buf = new byte[4096];
                int n = is.read(buf);
                String output = n > 0 ? new String(buf, 0, n) : "";
                System.out.println("[SKIP] " + testName + " - compilation failed: " + output.trim());
                return;
            }

            // Decompile
            java.io.File classFile = new java.io.File(tmpDir, className + ".class");
            if (!classFile.exists()) {
                System.out.println("[SKIP] " + testName + " - .class not found");
                return;
            }

            java.io.FileInputStream fis = new java.io.FileInputStream(classFile);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            fis.close();
            final byte[] data = baos.toByteArray();
            final String cName = className;

            DenzoDecompiler decompiler = new DenzoDecompiler();
            StringPrinter printer = new StringPrinter();

            Loader loader = new Loader() {
                public boolean canLoad(String internalName) { return cName.equals(internalName); }
                public byte[] load(String internalName) { return data; }
            };

            decompiler.decompile(loader, printer, className);
            String result = printer.getResult();

            // Check expected strings
            boolean allFound = true;
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < expectedContains.length; i++) {
                if (!result.contains(expectedContains[i])) {
                    allFound = false;
                    if (missing.length() > 0) missing.append(", ");
                    missing.append("\"").append(expectedContains[i]).append("\"");
                }
            }

            if (allFound) {
                System.out.println("[PASS] " + testName);
                passed++;
            } else {
                System.out.println("[FAIL] " + testName + " - missing: " + missing);
                System.out.println("       Output (first 300 chars): " + result.substring(0, Math.min(300, result.length())).replace("\n", "\\n"));
                failed++;
            }

            // Cleanup
            srcFile.delete();
            classFile.delete();

        } catch (Exception e) {
            System.out.println("[FAIL] " + testName + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    private static void runTestVerbose(String className, String sourceCode, String[] expectedContains) {
        total++;
        String testName = className;
        try {
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write(sourceCode);
            fw.close();

            ProcessBuilder pb = new ProcessBuilder(javacPath, "-g", "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                java.io.InputStream is = p.getInputStream();
                byte[] buf = new byte[4096];
                int n = is.read(buf);
                String output = n > 0 ? new String(buf, 0, n) : "";
                System.out.println("[SKIP] " + testName + " - compilation failed: " + output.trim());
                return;
            }

            java.io.File classFile = new java.io.File(tmpDir, className + ".class");
            if (!classFile.exists()) {
                System.out.println("[SKIP] " + testName + " - .class not found");
                return;
            }

            java.io.FileInputStream fis = new java.io.FileInputStream(classFile);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            fis.close();
            final byte[] data = baos.toByteArray();
            final String cName = className;

            DenzoDecompiler decompiler = new DenzoDecompiler();
            StringPrinter printer = new StringPrinter();

            Loader loader = new Loader() {
                public boolean canLoad(String internalName) { return cName.equals(internalName); }
                public byte[] load(String internalName) { return data; }
            };

            decompiler.decompile(loader, printer, className);
            String result = printer.getResult();

            // Always print output for verbose tests
            System.out.println("--- Decompiled output for " + testName + " ---");
            System.out.println(result);
            System.out.println("--- End output ---");

            boolean allFound = true;
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < expectedContains.length; i++) {
                if (!result.contains(expectedContains[i])) {
                    allFound = false;
                    if (missing.length() > 0) missing.append(", ");
                    missing.append("\"").append(expectedContains[i]).append("\"");
                }
            }

            if (allFound) {
                System.out.println("[PASS] " + testName);
                passed++;
            } else {
                System.out.println("[FAIL] " + testName + " - missing: " + missing);
                failed++;
            }

            srcFile.delete();
            classFile.delete();
        } catch (Exception e) {
            System.out.println("[FAIL] " + testName + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    static class StringPrinter implements Printer {
        private final StringBuilder sb = new StringBuilder();
        private int indentLevel = 0;

        public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
        public void end() {}
        public void printText(String text) { sb.append(text); }
        public void printNumericConstant(String constant) { sb.append(constant); }
        public void printStringConstant(String constant, String ownerInternalName) { sb.append(constant); }
        public void printKeyword(String keyword) { sb.append(keyword); }
        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) { sb.append(name); }
        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) { sb.append(name); }
        public void indent() { indentLevel++; }
        public void unindent() { indentLevel--; }
        public void startLine(int lineNumber) {
            for (int i = 0; i < indentLevel; i++) sb.append("    ");
        }
        public void endLine() { sb.append("\n"); }
        public void extraLine(int count) { for (int i = 0; i < count; i++) sb.append("\n"); }
        public void startMarker(int type) {}
        public void endMarker(int type) {}
        public String getResult() { return sb.toString(); }
    }
}
