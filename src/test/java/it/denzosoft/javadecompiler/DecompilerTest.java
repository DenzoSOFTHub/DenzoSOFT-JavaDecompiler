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

        // Regression tests for BUG-2026-0057/0058/0059 (2026-06-08)
        testSequentialIfReturn();   // BUG-2026-0057: if-cascade not truncated
        testRecordImplicitMembers(); // BUG-2026-0059/0058: record members suppressed
        testMakeConcat();            // BUG-2026-0058: bare makeConcat string concat
        testInstanceOfPattern();    // BUG-2026-0064: instanceof pattern binding
        testEnumConstantBody();     // BUG-2026-0070: enum constant body inlined
        testLambdaNoShadow();       // BUG-2026-0065: lambda param does not shadow method param
        testSwitchExpression();     // BUG-2026-0066: switch expression reconstructed (value arms)
        testPatternSwitch();        // BUG-2026-0067: pattern switch (sealed, no guards)
        testModernTwr();            // BUG-2026-0068: try-with-resources collapsed
        testGuardedPatternSwitch(); // BUG-2026-0067: pattern switch with `when` guards + `case null`
        testRecordPatternCleanup(); // BUG-2026-0067: flat record deconstruction
        testNestedRecordPattern();  // BUG-2026-0067: nested record deconstruction
        testGenericRecordPattern(); // BUG-2026-0067: generic record deconstruction (cast type)
        testSwitchRecordPattern();  // BUG-2026-0079: SWITCH-form record deconstruction (JD pipeline)
        testInstanceofAmpersand();  // BUG-2026-0067: `o instanceof X v && v.m()` binding
        testUndeclaredAssignPromotion(); // BUG-2026-0077: reused slot gets a declaration
        testWhileTrueBreak();       // BUG-2026-0078: break out of while(true)

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
        // START_CHANGE: BUG-2026-0071-20260610-7 - sealed/permits/non-sealed round-trip.
        // `sealed ... permits` is only emitted when the full permitted hierarchy is visible in
        // the same compilation unit (nested types), because every permitted subclass must then
        // carry a valid final/sealed/non-sealed modifier. The multi-class loader serves the
        // nested class files.
        runTestNested("SealedBase",
            "public class SealedBase {\n" +
            "    public sealed interface Expr permits Fixed, Open {\n" +
            "    }\n" +
            "    public static final class Fixed implements Expr {\n" +
            "    }\n" +
            "    public static non-sealed class Open implements Expr {\n" +
            "    }\n" +
            "    public sealed static abstract class Shape permits Square {\n" +
            "        abstract int compute();\n" +
            "    }\n" +
            "    public static final class Square extends Shape {\n" +
            "        int compute() { return 42; }\n" +
            "    }\n" +
            "}",
            new String[]{"sealed interface Expr permits SealedBase.Fixed, SealedBase.Open",
                         "non-sealed class Open",
                         "sealed class Shape permits SealedBase.Square"});
        // Isolated parent: the permitted subclass is NOT visible in this compilation unit
        // (single-class loader), so sealed/permits must be omitted entirely — emitting them
        // would require modifiers on subclasses decompiled elsewhere (uncompilable batch
        // output). Raw but compilable.
        runTestFull("SealedIso",
            "public sealed class SealedIso permits SealedIsoSub {\n" +
            "    int compute() { return 1; }\n" +
            "}\n" +
            "final class SealedIsoSub extends SealedIso {\n" +
            "}",
            new String[]{"class SealedIso"},
            new String[]{"sealed", "permits"});
        // END_CHANGE: BUG-2026-0071-7
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

    // START_CHANGE: BUG-2026-0057-20260608-2 - Regression test: a sequence of `if (cond) return X;`
    // statements must NOT be truncated to the first branch (the canFormTernary probe used to poison
    // the visited set and drop every branch after the first, producing "missing return").
    private static void testSequentialIfReturn() {
        runTestFull("SeqIf",
            "public class SeqIf {\n" +
            "    int f(Object o) {\n" +
            "        if (o instanceof String) return 1;\n" +
            "        if (o instanceof Integer) return 2;\n" +
            "        if (o instanceof Long) return 3;\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n",
            new String[]{"return 1", "return 2", "return 3", "return 0"},
            new String[0]);
    }
    // END_CHANGE: BUG-2026-0057-2

    // START_CHANGE: BUG-2026-0059-20260608-4 - Regression test: a record's compiler-generated
    // members (canonical ctor + ObjectMethods-backed toString/hashCode/equals) must be suppressed,
    // never emitted as `arg0 -> { }` lambdas.
    private static void testRecordImplicitMembers() {
        runTestFull("RecPoint",
            "public record RecPoint(int x, int y) {}\n",
            new String[]{"record RecPoint", "int x", "int y"},
            new String[]{"-> {", "ObjectMethods", "invalid"});
    }
    // END_CHANGE: BUG-2026-0059-4

    // START_CHANGE: BUG-2026-0058-20260608-2 - Regression test: string concatenation lowered to a
    // bare StringConcatFactory.makeConcat bootstrap must reconstruct as `a + b`, not a lambda.
    private static void testMakeConcat() {
        runTestFull("ConcatT",
            "public class ConcatT {\n" +
            "    String j(String a, String b) { return a + b; }\n" +
            "}\n",
            new String[]{"return", "+"},
            new String[]{"-> {", "makeConcat"});
    }
    // END_CHANGE: BUG-2026-0058-2

    // BUG-2026-0064: instanceof pattern bindings are reconstructed and scoped per branch.
    private static void testInstanceOfPattern() {
        runTestFull("IoPat",
            "public class IoPat {\n" +
            "    double a(Object o) {\n" +
            "        if (o instanceof String s) return s.length();\n" +
            "        if (o instanceof Integer i) return i.intValue();\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof String", "instanceof Integer"},
            new String[]{"= (String)", "= (Integer)"});
    }

    // BUG-2026-0070: an enum constant with a body inlines that body.
    private static void testEnumConstantBody() {
        runTestFull("EnumBody",
            "public enum EnumBody {\n" +
            "    ADD { int f() { return 1; } },\n" +
            "    SUB { int f() { return 2; } };\n" +
            "    abstract int f();\n" +
            "}\n",
            new String[]{"enum EnumBody", "ADD", "SUB", "abstract int f"},
            new String[]{"abstract enum", "permits"});
    }

    // BUG-2026-0065: a non-capturing lambda parameter must not shadow the enclosing method parameter.
    private static void testLambdaNoShadow() {
        runTestFull("LamShadow",
            "import java.util.function.Predicate;\n" +
            "public class LamShadow {\n" +
            "    Predicate<String> p(String x) { return s -> s.isEmpty(); }\n" +
            "}\n",
            new String[]{"->"},
            new String[0]);
    }

    // BUG-2026-0066: a value-arm switch expression is reconstructed, not collapsed to a constant.
    private static void testSwitchExpression() {
        runTestFull("SwExpr",
            "public class SwExpr {\n" +
            "    int f(int x) {\n" +
            "        return switch (x) {\n" +
            "            case 1 -> 10;\n" +
            "            case 2 -> 20;\n" +
            "            default -> 0;\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "-> 10", "-> 20", "-> 0"},
            new String[0]);
    }

    // BUG-2026-0067: a sealed pattern switch reconstructs `case Type b -> ...` with bindings.
    private static void testPatternSwitch() {
        runTestFull("PatSw",
            "sealed interface Sh permits Ci, Sq {}\n" +
            "record Ci(double r) implements Sh {}\n" +
            "record Sq(double s) implements Sh {}\n" +
            "public class PatSw {\n" +
            "    double area(Sh sh) {\n" +
            "        return switch (sh) {\n" +
            "            case Ci c -> c.r() * c.r();\n" +
            "            case Sq s -> s.s() * s.s();\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "case Ci", "case Sq", "->"},
            new String[]{"SwitchBootstraps", "typeSwitch", "MatchException"});
    }

    // BUG-2026-0068: the Java 9+ try-with-resources desugar is collapsed back to `try (res) {...}`.
    private static void testModernTwr() {
        runTestFull("TwrT",
            "import java.io.*;\n" +
            "public class TwrT {\n" +
            "    void f(String p) throws IOException {\n" +
            "        try (BufferedReader r = new BufferedReader(new FileReader(p))) {\n" +
            "            r.readLine();\n" +
            "        }\n" +
            "    }\n" +
            "}\n",
            new String[]{"try (", "BufferedReader", "readLine"},
            new String[]{"catch (Throwable"});
    }

    // BUG-2026-0067: a pattern switch with `when` guards and `case null` reconstructs cleanly.
    private static void testGuardedPatternSwitch() {
        runTestFull("GuardSw",
            "public class GuardSw {\n" +
            "    String f(Object o) {\n" +
            "        return switch (o) {\n" +
            "            case null -> \"null\";\n" +
            "            case Integer i when i < 0 -> \"neg\";\n" +
            "            case Integer i -> \"int\";\n" +
            "            case String s when s.isEmpty() -> \"empty\";\n" +
            "            default -> \"other\";\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "case null", "when", "case Integer", "default"},
            new String[]{"SwitchBootstraps", "while (true)", "!= 0"});
    }

    // BUG-2026-0067: flat record deconstruction `instanceof P(int x, int y)` is reconstructed.
    private static void testRecordPatternCleanup() {
        runTestFull("RecPat",
            "public class RecPat {\n" +
            "    record P(int x, int y) {}\n" +
            "    int f(Object o) {\n" +
            "        if (o instanceof P(int x, int y)) return x + y;\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof", "P(", ", "},
            new String[]{"MatchException", "catch (Throwable", "if (1 != 0)"});
    }

    // BUG-2026-0067: nested record deconstruction `instanceof L(P(...), P(...))`.
    private static void testNestedRecordPattern() {
        runTestFull("RecNest",
            "public class RecNest {\n" +
            "    record P(int x, int y) {}\n" +
            "    record L(P a, P b) {}\n" +
            "    int f(Object o) {\n" +
            "        if (o instanceof L(P(int x1, int y1), P(int x2, int y2))) return x1 + y1 + x2 + y2;\n" +
            "        return 0;\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof", "L(", "P("},
            new String[]{"MatchException", ".x()", ".start("});
    }

    // BUG-2026-0067: generic record deconstruction binds the component with the cast type.
    private static void testGenericRecordPattern() {
        runTestFull("RecGen",
            "public class RecGen {\n" +
            "    record Box<T>(T value) {}\n" +
            "    String f(Box<String> b) {\n" +
            "        if (b instanceof Box<String>(String v)) return v.toUpperCase();\n" +
            "        return \"\";\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof", "Box(", "String", "toUpperCase"},
            new String[]{"MatchException", "Object var"});
    }

    // BUG-2026-0079: the SWITCH form of record patterns reconstructs to `return switch(s){case T(...) -> v}`
    // via the JD pipeline (selectively activated for typeSwitch + MatchException methods).
    private static void testSwitchRecordPattern() {
        runTestFull("SwRec",
            "public class SwRec {\n" +
            "    record P(int x, int y) {}\n" +
            "    record L(P a, P b) {}\n" +
            "    int sum(Object o) {\n" +
            "        return switch (o) {\n" +
            "            case L(P(int x1, int y1), P(int x2, int y2)) -> x1 + y1 + x2 + y2;\n" +
            "            case P(int x, int y) -> x + y;\n" +
            "            default -> 0;\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "L(", "P(", "->", "default"},
            new String[]{"SwitchBootstraps", "typeSwitch", "MatchException"});
    }

    // BUG-2026-0067: a pattern binding used in the `&&` tail is recovered: `o instanceof X v && v.m()`.
    private static void testInstanceofAmpersand() {
        runTestFull("AmpPat",
            "public class AmpPat {\n" +
            "    boolean f(Object o) {\n" +
            "        return o instanceof Integer i && i.intValue() > 0;\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof Integer", "&&", "intValue"},
            new String[0]);
    }

    // BUG-2026-0077: a slot reused after a for-each (whose iterator decl was removed) still gets declared.
    private static void testUndeclaredAssignPromotion() {
        runTestFull("Undecl",
            "import java.util.*;\n" +
            "public class Undecl {\n" +
            "    void f(List<String> a) {\n" +
            "        for (String s : a) System.out.println(s);\n" +
            "        Map<String,String> m = new HashMap<String,String>();\n" +
            "        for (Map.Entry<String,String> e : m.entrySet()) System.out.println(e.getKey());\n" +
            "    }\n" +
            "}\n",
            new String[]{"HashMap", "entrySet"},
            new String[0]);
    }

    // BUG-2026-0078: a `break` out of `while(true)` is recovered (loop terminates, return reachable).
    private static void testWhileTrueBreak() {
        runTestFull("WtBreak",
            "public class WtBreak {\n" +
            "    int f(int[] a) {\n" +
            "        int i = 0, t = 0;\n" +
            "        while (true) {\n" +
            "            if (i >= a.length) break;\n" +
            "            t += a[i];\n" +
            "            i++;\n" +
            "        }\n" +
            "        return t;\n" +
            "    }\n" +
            "}\n",
            new String[]{"break"},
            new String[0]);
    }

    // Helper: assert all of `mustContain` present AND none of `mustNotContain` present.
    private static void runTestFull(String className, String sourceCode,
                                    String[] mustContain, String[] mustNotContain) {
        total++;
        try {
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write(sourceCode);
            fw.close();

            ProcessBuilder pb = new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() != 0) {
                System.out.println("[SKIP] " + className + " - compilation failed");
                return;
            }
            java.io.File classFile = new java.io.File(tmpDir, className + ".class");
            java.io.FileInputStream fis = new java.io.FileInputStream(classFile);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = fis.read(buffer)) != -1) baos.write(buffer, 0, n);
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

            StringBuilder problems = new StringBuilder();
            for (int i = 0; i < mustContain.length; i++) {
                if (!result.contains(mustContain[i])) {
                    if (problems.length() > 0) problems.append(", ");
                    problems.append("missing \"").append(mustContain[i]).append("\"");
                }
            }
            for (int i = 0; i < mustNotContain.length; i++) {
                if (result.contains(mustNotContain[i])) {
                    if (problems.length() > 0) problems.append(", ");
                    problems.append("unexpected \"").append(mustNotContain[i]).append("\"");
                }
            }
            if (problems.length() == 0) {
                System.out.println("[PASS] " + className);
                passed++;
            } else {
                System.out.println("[FAIL] " + className + " - " + problems);
                System.out.println("       Output: " + result.replace("\n", "\\n"));
                failed++;
            }
            srcFile.delete();
            classFile.delete();
        } catch (Exception e) {
            System.out.println("[FAIL] " + className + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    // START_CHANGE: BUG-2026-0071-20260610-8 - Variant of runTestFull whose loader serves every
    // class file produced in the temp dir, so nested/inner classes are decompiled too (the
    // single-class loader of runTest/runTestFull cannot exercise nested-type emission such as
    // sealed hierarchies).
    private static void runTestNested(String className, String sourceCode, String[] mustContain) {
        total++;
        try {
            final java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write(sourceCode);
            fw.close();

            ProcessBuilder pb = new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() != 0) {
                System.out.println("[SKIP] " + className + " - compilation failed");
                return;
            }

            DenzoDecompiler decompiler = new DenzoDecompiler();
            StringPrinter printer = new StringPrinter();
            Loader loader = new Loader() {
                public boolean canLoad(String internalName) {
                    return new java.io.File(tmpDir, internalName + ".class").exists();
                }
                public byte[] load(String internalName) throws Exception {
                    java.io.FileInputStream fis = new java.io.FileInputStream(
                        new java.io.File(tmpDir, internalName + ".class"));
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = fis.read(buffer)) != -1) baos.write(buffer, 0, n);
                    fis.close();
                    return baos.toByteArray();
                }
            };
            decompiler.decompile(loader, printer, className);
            String result = printer.getResult();

            StringBuilder problems = new StringBuilder();
            for (int i = 0; i < mustContain.length; i++) {
                if (!result.contains(mustContain[i])) {
                    if (problems.length() > 0) problems.append(", ");
                    problems.append("missing \"").append(mustContain[i]).append("\"");
                }
            }
            if (problems.length() == 0) {
                System.out.println("[PASS] " + className);
                passed++;
            } else {
                System.out.println("[FAIL] " + className + " - " + problems);
                System.out.println("       Output: " + result.replace("\n", "\\n"));
                failed++;
            }
            srcFile.delete();
            java.io.File[] produced = tmpDir.listFiles();
            if (produced != null) {
                for (int i = 0; i < produced.length; i++) {
                    String fname = produced[i].getName();
                    if (fname.endsWith(".class")
                            && (fname.equals(className + ".class") || fname.startsWith(className + "$"))) {
                        produced[i].delete();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[FAIL] " + className + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }
    // END_CHANGE: BUG-2026-0071-8

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
