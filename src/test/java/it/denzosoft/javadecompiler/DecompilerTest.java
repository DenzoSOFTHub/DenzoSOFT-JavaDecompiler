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
        // START_CHANGE: BUG-2026-0067-20260610-49 - Sealed-exhaustive switch + unnamed components.
        testExhaustiveSwitchRecordPattern(); // BUG-2026-0067: no-default record switch (tail-arm reclaim)
        testUnnamedPatternComponent();       // BUG-2026-0067: dead component folds to `Type _`
        // END_CHANGE: BUG-2026-0067-49
        // START_CHANGE: BUG-2026-0067-20260610-57 - Unnamed type-pattern switch arms.
        testUnnamedTypePatternArm(); // BUG-2026-0067: `case Integer _ ->` (no cast-bind in arm)
        // END_CHANGE: BUG-2026-0067-57
        testInstanceofAmpersand();  // BUG-2026-0067: `o instanceof X v && v.m()` binding
        testUndeclaredAssignPromotion(); // BUG-2026-0077: reused slot gets a declaration
        testWhileTrueBreak();       // BUG-2026-0078: break out of while(true)
        // START_CHANGE: BUG-2026-0097-20260610-1 - Inner classes: local class emission, anonymous
        // capture substitution, member-inner synthetic outer parameter stripping.
        testInnerClassCaptures();   // BUG-2026-0097: local/anon/member-inner round-trip
        // END_CHANGE: BUG-2026-0097-1
        // START_CHANGE: v1.11.0-20260905-1 - Correctness regressions found by the Java 25+ audit.
        testNestedSynchronized();      // BUG-2026-0101: synchronized nested in if/loop kept
        testNoDuplicateDeclaration();  // BUG-2026-0106: no `int sum; int sum = 0;` with -g
        testCatchWithoutDebugInfo();   // BUG-2026-0100: handlers survive -g:none (stripped jars)
        testCategory2Dup();            // BUG-2026-0104: dup2_x1/dup2_x2 on long/double
        testLambdaCompoundBody();      // BUG-2026-0102: lambda bodies keep loops/switch/try/throw
        testSharedSlotScopes();        // BUG-2026-0103: LVT scope ranges give each variable its name/type
        testNoReDeclaration();         // BUG-2026-0107: same name never declared twice in one scope
        // END_CHANGE: v1.11.0-1
        // START_CHANGE: v1.12.0-20260906-1 - Pattern switch (Java 21+).
        testQualifiedEnumCaseLabel();  // BUG-2026-0108: condy label -> case Enum.CONSTANT
        testStatementPatternSwitch();  // BUG-2026-0109: statement-form pattern switch rebuilt
        // END_CHANGE: v1.12.0-1
        // START_CHANGE: v1.13.0-20260907-1 - Remaining pattern-switch label shapes.
        testConstantLabelSwitch();       // BUG-2026-0123: string labels + case null
        testArrayClassPatternSwitch();   // BUG-2026-0123: `case byte[] _`
        testThreadSafetyDeterminism();   // BUG-2026-0124: shared instance, many threads, same output
        testTryBodyNotDisplaced();       // BUG-2026-0122: synthesised statement stays inside the try
        // END_CHANGE: v1.13.0-1
        // START_CHANGE: BUG-2026-0129-20260907-3 - Inlined finally copy before a NESTED exit.
        testNestedFinallyCopyRemoved();  // BUG-2026-0129: finally body must not run twice
        testShowBytecodeEmitted();       // BUG-2026-0201: --show-bytecode prints the instructions
        testGenericParamForEach();       // BUG-2026-0202: for-each over a List<T> parameter stays typed
        // START_CHANGE: BUG-2026-0171-20261018-2 - rt.jar semantic campaign guards (BUG-2026-0171..0178).
        testRtJarSemanticGuards();
        // END_CHANGE: BUG-2026-0171-2
        // END_CHANGE: BUG-2026-0129-3

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

    // START_CHANGE: BUG-2026-0067-20260610-50 - Sealed-exhaustive record switch: the synthetic
    // `default -> throw MatchException` is skipped and the fall-out tail arm is reclaimed into the
    // switch expression (no default arm in the source, none in the output).
    private static void testExhaustiveSwitchRecordPattern() {
        runTestFull("ExhSw",
            "sealed interface ShE permits CiE, ReE, TrE {}\n" +
            "record CiE(double r) implements ShE {}\n" +
            "record ReE(double w, double h) implements ShE {}\n" +
            "record TrE(double b, double h) implements ShE {}\n" +
            "public class ExhSw {\n" +
            "    double area(ShE s) {\n" +
            "        return switch (s) {\n" +
            "            case CiE(double r) -> r * r;\n" +
            "            case ReE(double w, double h) -> w * h;\n" +
            "            case TrE(double b, double h) -> 0.5 * b * h;\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "CiE(", "ReE(", "TrE(", "->"},
            new String[]{"SwitchBootstraps", "MatchException", "while (true)", "default"});
    }

    // BUG-2026-0067: a record component the source never bound (unnamed `_`) folds to `Type _`
    // instead of aborting the deconstruction (dead scratch slot, no live copy).
    private static void testUnnamedPatternComponent() {
        runTestFull("UnComp",
            "public class UnComp {\n" +
            "    record P(int x, int y) {}\n" +
            "    record Q(P a, P b) {}\n" +
            "    int f(Object o) {\n" +
            "        if (o instanceof P(int x, int _)) return x;\n" +
            "        return -1;\n" +
            "    }\n" +
            "    int g(Object o) {\n" +
            "        if (o instanceof Q(P(int x, var _), P _)) return x;\n" +
            "        return -1;\n" +
            "    }\n" +
            "}\n",
            new String[]{"instanceof", "P(", "int _", "P _"},
            new String[]{"MatchException", "catch (Throwable"});
    }
    // END_CHANGE: BUG-2026-0067-50

    // START_CHANGE: BUG-2026-0067-20260610-57 - Unnamed type-pattern switch arm: the binding is
    // dead so javac emits NO cast-bind statement in the arm block; the pattern type must be
    // synthesized from the typeSwitch bootstrap labels (`case Integer _ -> "int"`).
    private static void testUnnamedTypePatternArm() {
        runTestFull("UnArm",
            "public class UnArm {\n" +
            "    String kind(Object o) {\n" +
            "        return switch (o) {\n" +
            "            case Integer _ -> \"int\";\n" +
            "            case String _ -> \"string\";\n" +
            "            default -> \"other\";\n" +
            "        };\n" +
            "    }\n" +
            "}\n",
            new String[]{"switch", "case Integer _", "case String _", "default", "->"},
            new String[]{"SwitchBootstraps", "typeSwitch"});
    }
    // END_CHANGE: BUG-2026-0067-57

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
    // START_CHANGE: BUG-2026-0097-20260610-2 - Inner-class capture round-trip: (1) method-local
    // classes are emitted (as nested `_1Local` with their capture fields); (2) the anonymous
    // class's captured local recovers its source name from the val$ field, so the inlined body
    // resolves; (3) the member-inner constructor drops the synthetic outer-instance parameter,
    // the Objects.requireNonNull(outer) preamble, and the leading `this` call-site argument.
    private static void testInnerClassCaptures() {
        runTestNested("InnerCap",
            "public class InnerCap {\n" +
            "    private int outerField = 42;\n" +
            "    class Member {\n" +
            "        int offset;\n" +
            "        Member(int offset) { this.offset = offset; }\n" +
            "        int compute() { return outerField + offset; }\n" +
            "    }\n" +
            "    Member makeMember(int o) { return new Member(o); }\n" +
            "    int useLocal(final int multiplier) {\n" +
            "        class Local { int run(int x) { return x * multiplier + outerField; } }\n" +
            "        Local l = new Local();\n" +
            "        return l.run(5);\n" +
            "    }\n" +
            "    Runnable makeAnon(final int captured) {\n" +
            "        return new Runnable() { public void run() { System.out.println(captured + outerField); } };\n" +
            "    }\n" +
            "}",
            new String[]{
                "Member(int arg1)",            // ctor: synthetic outer param stripped
                "new InnerCap.Member(arg0)",   // call site: leading `this` argument dropped
                "class _1Local",               // local class emitted with sanitized name
                "final int multiplier",        // capture field declared (val$ prefix stripped)
                "makeAnon(int captured)",      // enclosing param renamed to the captured name
                "captured + "                  // inlined anonymous body resolves the capture
            });
    }
    // END_CHANGE: BUG-2026-0097-2

    // START_CHANGE: v1.11.0-20260905-2 - Regression tests for the audit fixes.
    /**
     * BUG-2026-0101: a monitor region nested inside any compound statement used to be dropped,
     * leaving the raw marker comment and silently removing the lock from otherwise valid code.
     */
    private static void testNestedSynchronized() {
        runTestFull("NestedSync",
            "public class NestedSync {\n" +
            "    private final Object lock = new Object();\n" +
            "    private int counter;\n" +
            "    int inIf(boolean f) {\n" +
            "        if (f) {\n" +
            "            synchronized (lock) { counter++; }\n" +
            "        }\n" +
            "        return counter;\n" +
            "    }\n" +
            "    int inLoop(int n) {\n" +
            "        for (int i = 0; i < n; i++) {\n" +
            "            synchronized (this) { counter += i; }\n" +
            "        }\n" +
            "        return counter;\n" +
            "    }\n" +
            "}",
            new String[]{"synchronized (this.lock)", "synchronized (this)"},
            new String[]{"__MONITORENTER__", "__MONITOREXIT__"});
    }

    /**
     * BUG-2026-0106: with a LocalVariableTable the pre-declaration pass and the
     * undeclared-assignment promotion both emitted a declaration for the same slot, producing
     * the uncompilable `int sum; int sum = 0;`.
     */
    private static void testNoDuplicateDeclaration() {
        runTestFull("DupDecl",
            "public class DupDecl {\n" +
            "    int sumWhile(int n) {\n" +
            "        int sum = 0;\n" +
            "        int i = 1;\n" +
            "        while (i <= n) { sum += i; i++; }\n" +
            "        return sum;\n" +
            "    }\n" +
            "}",
            "-g",
            new String[]{"int sum = 0"},
            new String[]{"int sum;"});
    }

    /**
     * BUG-2026-0100: compiled with -g:none there is no LineNumberTable, and try-region membership
     * was decided by comparing source lines -- so every catch clause was silently deleted while
     * the remaining happy path still compiled. This is the shape of stripped/obfuscated jars.
     */
    private static void testCatchWithoutDebugInfo() {
        runTestFull("NoDebugCatch",
            "public class NoDebugCatch {\n" +
            "    int f(int[] a, int idx) {\n" +
            "        int result = 0;\n" +
            "        try {\n" +
            "            result = a[idx];\n" +
            "        } catch (ArrayIndexOutOfBoundsException e) {\n" +
            "            result = -1;\n" +
            "        } finally {\n" +
            "            result += 1000;\n" +
            "        }\n" +
            "        return result;\n" +
            "    }\n" +
            "}",
            "-g:none",
            new String[]{"try {", "catch (ArrayIndexOutOfBoundsException", "finally"},
            new String[]{});
    }
    /**
     * BUG-2026-0104: `dup2_x1` / `dup2_x2` were no-ops and `dup2` always popped two values, so a
     * duplicated long/double was lost. `long next = state = s + 1L;` decompiled to the uncompilable
     * `long var3 = null;` behind a STACK_UNDERFLOW note.
     */
    private static void testCategory2Dup() {
        runTestFull("Dup2Cat",
            "public class Dup2Cat {\n" +
            "    private long state;\n" +
            "    long chained(long s) { long next = state = s + 1L; return next; }\n" +
            "    double arrayChained(double[] a, int i) { double v = a[i] = 3.0; return v; }\n" +
            "    boolean cmp(long deadline, long now) { long nanos; return (nanos = deadline - now) <= 0L; }\n" +
            "}",
            new String[]{"this.state = ", "long ", "double "},
            new String[]{"= null", "STACK_UNDERFLOW"});
    }
    /**
     * BUG-2026-0102: a lambda body containing anything other than an expression, a declaration, a
     * return or an if had that statement replaced by `/* inline stmt *\/` — silent code loss that
     * still compiled. 36 files / 66 sites in JDK 25 java.base.
     */
    private static void testLambdaCompoundBody() {
        runTestFull("LamBody",
            "import java.util.function.*;\n" +
            "public class LamBody {\n" +
            "    private final Object lock = new Object();\n" +
            "    private int counter;\n" +
            "    Runnable thr(String p) { return () -> { if (p == null) throw new IllegalArgumentException(); System.out.println(p); }; }\n" +
            "    Runnable loop(int n) { return () -> { for (int i = 0; i < n; i++) System.out.println(i); }; }\n" +
            "    Runnable sw(int n) { return () -> { switch (n) { case 1: System.out.println(\"a\"); break; default: System.out.println(\"b\"); } }; }\n" +
            "    Runnable syn() { return () -> { synchronized (lock) { counter++; } }; }\n" +
            "    Runnable tryc() { return () -> { try { System.out.println(\"x\"); } catch (RuntimeException e) { e.printStackTrace(); } finally { System.out.println(\"f\"); } }; }\n" +
            "    Supplier<Integer> whl(int n) { return () -> { int i = 0; while (i < n) { i++; } return i; }; }\n" +
            "}",
            // NB: the `while` loop is legitimately reconstructed as an equivalent `for`, so the
            // loop assertion is on `for (` only.
            new String[]{"throw new IllegalArgumentException", "for (", "switch (", "synchronized (",
                         "catch (", "finally"},
            new String[]{"inline stmt"});
    }
    /**
     * BUG-2026-0103: the LocalVariableTable was flattened to one name/type per SLOT (last entry
     * wins), so two variables javac assigned to the same slot collapsed into one. The pre-fix build
     * emits `Integer count; Integer count;` followed by `count = "yes";` — a duplicate declaration
     * with the wrong name AND the wrong type, none of which compiles.
     */
    private static void testSharedSlotScopes() {
        runTestFull("SlotShare",
            "import java.util.*;\n" +
            "public class SlotShare {\n" +
            "    String pick(boolean f) {\n" +
            "        if (f) { String label = \"yes\"; return label.toUpperCase(); }\n" +
            "        else { Integer count = 42; return count.toString(); }\n" +
            "    }\n" +
            "    int scopes(List<String> xs) {\n" +
            "        int total = 0;\n" +
            "        { String first = xs.get(0); total += first.length(); }\n" +
            "        { Integer size = xs.size(); total += size; }\n" +
            "        return total;\n" +
            "    }\n" +
            "}",
            "-g",
            new String[]{"String label", "Integer count", "String first", "Integer size"},
            new String[]{"Integer label", "String count", "Integer first"});
    }
    /**
     * BUG-2026-0107: two consecutive `for (int i = ...)` loops share one bytecode slot; with the
     * declarations hoisted out of the for-headers the same name was declared twice in one block.
     * The pre-fix build emits `int total; int i; int total = 0; int i = 0;` — four declarations for
     * two variables, which does not compile.
     */
    private static void testNoReDeclaration() {
        runTestFull("ReDecl",
            "public class ReDecl {\n" +
            "    int twoLoops(int[] a, int[] b) {\n" +
            "        int total = 0;\n" +
            "        for (int i = 0; i < a.length; i++) { total += a[i]; }\n" +
            "        for (int i = 0; i < b.length; i++) { total += b[i]; }\n" +
            "        return total;\n" +
            "    }\n" +
            "}",
            "-g",
            new String[]{"int total = 0"},
            new String[]{"int total;"});
    }
    // END_CHANGE: v1.11.0-2

    // START_CHANGE: v1.12.0-20260906-2 - Pattern-switch regression tests.
    /**
     * BUG-2026-0108: a qualified enum constant used as a pattern-switch label is stored by javac as
     * a CONSTANT_Dynamic entry (ConstantBootstraps.invoke + Enum$EnumDesc.of). The decoder read only
     * Class/String/Integer bootstrap arguments, so the label was lost and the arm was rendered as the
     * uncompilable `case  _ ->`.
     */
    private static void testQualifiedEnumCaseLabel() {
        runTestFull("QualEnum",
            "import java.time.DayOfWeek;\n" +
            "public class QualEnum {\n" +
            "    static String f(Object o) {\n" +
            "        return switch (o) {\n" +
            "            case DayOfWeek.MONDAY -> \"mon\";\n" +
            "            case DayOfWeek d -> \"day\";\n" +
            "            case String s -> s;\n" +
            "            default -> \"?\";\n" +
            "        };\n" +
            "    }\n" +
            "}",
            new String[]{"case DayOfWeek.MONDAY"},
            new String[]{"case  _", "MONDAY _"});
    }
    /**
     * BUG-2026-0109: a pattern switch used as a STATEMENT has no value merge, so the flow builder
     * left the raw `switch (SwitchBootstraps.typeSwitch(sel, idx))` dispatch with integer indices —
     * uncompilable. Unlike the expression form the arm bodies are all present, so the switch can be
     * rebuilt by mapping each index to its bootstrap label and folding the arm's leading cast into
     * the pattern binding.
     */
    private static void testStatementPatternSwitch() {
        runTestFull("StmtPat",
            "public class StmtPat {\n" +
            "    static void f(Object o) {\n" +
            "        switch (o) {\n" +
            "            case Integer i -> System.out.println(\"i\" + i);\n" +
            "            case String s -> System.out.println(\"s\" + s);\n" +
            "            default -> System.out.println(\"?\");\n" +
            "        }\n" +
            "    }\n" +
            "}",
            // -g so the LocalVariableTable supplies the real binding names; without it the
            // reconstruction is identical but the bindings read `var3`/`var4`.
            "-g",
            new String[]{"case Integer i", "case String s", "default"},
            new String[]{"SwitchBootstraps", "PATTERN_SWITCH_NOT_RECONSTRUCTED"});
    }
    // END_CHANGE: v1.12.0-2

    // START_CHANGE: v1.13.0-20260907-2 - Constant and array-class case labels.
    /**
     * BUG-2026-0123: javac routes an ordinary constant switch through SwitchBootstraps as soon as it
     * has a `case null`, so STRING labels arrive on the pattern path. They were rejected, leaving the
     * raw dispatch with every arm value dropped — the method returned an unconditional value.
     */
    private static void testConstantLabelSwitch() {
        runTestFull("KonstSw",
            "public class KonstSw {\n" +
            "    static boolean f(String flag) {\n" +
            "        return switch (flag) {\n" +
            "            case \"true\" -> true;\n" +
            "            case \"false\" -> false;\n" +
            "            case null -> false;\n" +
            "            default -> throw new RuntimeException(\"bad\");\n" +
            "        };\n" +
            "    }\n" +
            "}",
            new String[]{"case \"true\"", "case \"false\"", "case null"},
            new String[]{"SwitchBootstraps", "PATTERN_SWITCH_NOT_RECONSTRUCTED"});
    }

    /**
     * BUG-2026-0123: an array-class bootstrap label (`[I`, `[Ljava/lang/String;`) is an ordinary type
     * pattern once its descriptor is parsed; rejecting it left whole dispatches raw.
     */
    private static void testArrayClassPatternSwitch() {
        runTestFull("ArrSw",
            "public class ArrSw {\n" +
            "    static String f(Object o) {\n" +
            "        return switch (o) {\n" +
            // Unnamed bindings are the shape that actually failed: with named bindings the arm
            // matched a different path and already worked.
            "            case byte[] _ -> \"byte\";\n" +
            "            case int[] _ -> \"int\";\n" +
            "            case String[] _ -> \"str\";\n" +
            "            default -> \"?\";\n" +
            "        };\n" +
            "    }\n" +
            "}",
            new String[]{"case byte[]", "case int[]", "case String[]"},
            new String[]{"SwitchBootstraps", "PATTERN_SWITCH_NOT_RECONSTRUCTED"});
    }
    /**
     * BUG-2026-0124: `BooleanSimplifier` kept the "this method returns boolean" flag in a plain
     * STATIC field, written at the start of each simplify() and reset at the end. Two threads
     * decompiling different methods raced on it, so a boolean method's `return 0` was sometimes left
     * as an int -- output that does not compile. Batch decompilation was therefore non-deterministic:
     * the same build over JDK 25 java.base differed in 211 of 3,376 files run to run.
     *
     * This test decompiles the same classes sequentially and then concurrently through ONE SHARED
     * DenzoDecompiler, and requires the results to be identical. On the pre-fix build it diverges
     * (11-21 of 400 classes in measured runs, varying per run).
     */
    private static void testThreadSafetyDeterminism() {
        total++;
        try {
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test-conc");
            tmpDir.mkdirs();
            // Boolean-returning shapes are what the racy flag governed; mix in non-boolean ones so a
            // stale flag from a neighbouring class is observable.
            final String[] names = new String[]{"TsA", "TsB", "TsC", "TsD"};
            String[] sources = new String[]{
                "public class TsA { boolean f(int x) { return x > 1; } boolean g() { return true; } }",
                "public class TsB { int f(int x) { return x > 1 ? 1 : 0; } int g() { return 0; } }",
                "public class TsC { boolean f(Object o) { if (o == null) return false; return o.hashCode() > 0; } }",
                "public class TsD { int f(boolean b) { return b ? 1 : 0; } boolean g(int i) { return i == 0; } }"
            };
            final java.util.Map<String, byte[]> data = new java.util.LinkedHashMap<String, byte[]>();
            for (int i = 0; i < names.length; i++) {
                java.io.File src = new java.io.File(tmpDir, names[i] + ".java");
                java.io.FileWriter fw = new java.io.FileWriter(src);
                fw.write(sources[i]);
                fw.close();
                ProcessBuilder pb = new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), src.getAbsolutePath());
                pb.redirectErrorStream(true);
                if (pb.start().waitFor() != 0) {
                    System.out.println("[SKIP] ThreadSafety - compilation failed");
                    return;
                }
                java.io.FileInputStream fis = new java.io.FileInputStream(new java.io.File(tmpDir, names[i] + ".class"));
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                fis.close();
                data.put(names[i], baos.toByteArray());
                src.delete();
            }

            // Sequential reference through one instance.
            DenzoDecompiler seq = new DenzoDecompiler();
            final java.util.Map<String, String> ref = new java.util.LinkedHashMap<String, String>();
            for (int r = 0; r < 40; r++) {
                for (java.util.Iterator<String> it = data.keySet().iterator(); it.hasNext(); ) {
                    String k = it.next();
                    if (!ref.containsKey(k)) ref.put(k, decompileOne(seq, k, data.get(k)));
                }
            }

            // Concurrent through ONE SHARED instance.
            final DenzoDecompiler shared = new DenzoDecompiler();
            final java.util.Map<String, String> mismatches = new java.util.concurrent.ConcurrentHashMap<String, String>();
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int r = 0; r < 40; r++) {
                for (java.util.Iterator<String> it = data.keySet().iterator(); it.hasNext(); ) {
                    final String k = it.next();
                    final byte[] bytes = data.get(k);
                    futures.add(ex.submit(new Runnable() {
                        public void run() {
                            try {
                                String out = decompileOne(shared, k, bytes);
                                if (!ref.get(k).equals(out)) mismatches.put(k, out);
                            } catch (Exception e) {
                                mismatches.put(k, "EXCEPTION " + e);
                            }
                        }
                    }));
                }
            }
            for (int i = 0; i < futures.size(); i++) futures.get(i).get();
            ex.shutdown();

            if (mismatches.isEmpty()) {
                System.out.println("[PASS] ThreadSafety");
                passed++;
            } else {
                System.out.println("[FAIL] ThreadSafety - " + mismatches.size()
                    + " class(es) decompiled differently under concurrency: " + mismatches.keySet());
                failed++;
            }
        } catch (Exception e) {
            System.out.println("[FAIL] ThreadSafety - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    private static String decompileOne(DenzoDecompiler d, final String name, final byte[] bytes) throws Exception {
        StringPrinter p = new StringPrinter();
        d.decompile(new Loader() {
            public boolean canLoad(String internalName) { return name.equals(internalName); }
            public byte[] load(String internalName) { return bytes; }
        }, p, name);
        return p.getResult();
    }
    /**
     * BUG-2026-0122: without a LineNumberTable, a statement the flow builder SYNTHESISED (a merged
     * ternary is the common case) carries neither a recorded pc nor a line, so it could not be
     * classified and was pushed past the catch — `r = o == null ? 1 : 2;`, whose bytecode sits inside
     * the protected range, was emitted after the handler along with the declaration it depends on.
     */
    private static void testTryBodyNotDisplaced() {
        runTestFull("TryPos",
            "public class TryPos {\n" +
            "    int f(String s) {\n" +
            "        int r = 0;\n" +
            "        try {\n" +
            "            Object o = null;\n" +
            "            r = o == null ? 1 : 2;\n" +
            "        } catch (RuntimeException e) {\n" +
            "            r = -1;\n" +
            "        }\n" +
            "        return r;\n" +
            "    }\n" +
            "}",
            "-g:none",
            // Containment alone cannot express "before": both substrings are present in the broken
            // output too. Assert a single substring SPANNING the ternary and the closing brace of the
            // try, which only holds when the ternary is the last statement inside it.
            new String[]{"? 1 : 2;\n        } catch (RuntimeException"},
            new String[]{});
    }
    // END_CHANGE: v1.13.0-2

    // START_CHANGE: BUG-2026-0171-20261018-3 - Guards for the silent defects found by the rt.jar
    // bytecode-fingerprint gate. Each source is the runtime-oracle fixture of the same name in
    // src/test/resources/runtime-oracle/ (trimmed to the method that shows the defect); every
    // assertion FAILS on the build before the matching fix.
    private static void testRtJarSemanticGuards() {
        // BUG-2026-0171: the then-branch (ending in a loop) of an if/else was dropped
        runTestFull("IfElseLoopTail171",
            "public class IfElseLoopTail171 {\n" +
            "    int type; int[] r;\n" +
            "    boolean b(int ch) {\n" +
            "        boolean ret;\n" +
            "        if (type == 4) { ret = false; for (int i = 0; i < r.length; i++) { ch++; } }\n" +
            "        else { ret = true; for (int i = 0; i < r.length; i++) { ch--; } }\n" +
            "        return ret;\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"} else {", "arg0++;", "arg0--;"}, new String[]{"this.type != 4"});
        // BUG-2026-0172: nested ternary stored to a field took the pending receiver as an arm
        runTestFull("TernPending172",
            "public class TernPending172 {\n" +
            "    int op;\n" +
            "    void swap() { this.op = this.op == 2 ? 3 : this.op == 3 ? 2 : this.op == 4 ? 5 : 4; }\n" +
            "}", "-g:none",
            new String[]{"this.op == 3 ? 2"}, new String[]{": this;"});
        // BUG-2026-0173: an Object-typed value must keep the local Object (no unchecked narrowing cast)
        runTestFull("LubLocal173",
            "public class LubLocal173 {\n" +
            "    static String f(Object[] args, int i, boolean sub) {\n" +
            "        Object obj = args[i];\n" +
            "        String arg = null;\n" +
            "        if (obj == null) { arg = \"null\"; }\n" +
            "        else if (sub) { arg = String.valueOf(obj); if (arg.indexOf('{') >= 0) { obj = args; arg = null; } }\n" +
            "        return arg + obj;\n" +
            "    }\n" +
            "}", "-g:source,lines",
            new String[]{"Object var3 = arg0[arg1];"}, new String[]{"(Object[]) arg0[arg1]"});
        // BUG-2026-0174: a later <clinit> assignment of an inlined static field must survive
        runTestFull("TryBoolClinit174",
            "public class TryBoolClinit174 {\n" +
            "    static String prop = System.getProperty(\"p\");\n" +
            "    private static boolean compat = false;\n" +
            "    static String mode;\n" +
            "    static {\n" +
            "        try { String form = prop.trim(); compat = (form != null && form.equals(\"1.0\")); }\n" +
            "        catch (Exception e) { }\n" +
            "        if (compat) mode = \"old\"; else mode = \"new\";\n" +
            "    }\n" +
            "}", "-g:source,lines",
            new String[]{"equals(\"1.0\")"}, new String[0]);
        // BUG-2026-0175: a synthesised boolean merge statement stays inside its try
        runTestFull("TryBoolTail175",
            "public class TryBoolTail175 {\n" +
            "    static boolean flag;\n" +
            "    static boolean check(String s) { return s.startsWith(\"1\"); }\n" +
            "    static String run(String p) {\n" +
            "        flag = false;\n" +
            "        try { String form = p.trim(); flag = form != null && check(form); }\n" +
            "        catch (RuntimeException e) { return \"caught\"; }\n" +
            "        return \"ok\";\n" +
            "    }\n" +
            "}", "-g:source,lines",
            new String[]{"String var1 = arg0.trim();"}, new String[]{"var1 = arg0.trim();\n        } catch"});
        // BUG-2026-0176: (a && b && c) || ... is rebuilt, not expanded into an exponential ternary
        runTestFull("OrAndChain176",
            "public class OrAndChain176 {\n" +
            "    static boolean en(long m, int id) {\n" +
            "        return ((m & 1L) != 0 && id >= 0 && id <= 3) || ((m & 2L) != 0 && id >= 100 && id <= 103)\n" +
            "            || ((m & 4L) != 0 && id >= 200 && id <= 203);\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"arg1 >= 200 && arg1 <= 203;"}, new String[]{" ? "});
        // BUG-2026-0177: dcmpg/fcmpl comparisons keep NaN semantics
        runTestFull("NanCmp177",
            "public class NanCmp177 {\n" +
            "    static boolean a(double x, double y) { return !(x < y); }\n" +
            "    static boolean d(float x, float y) { return !(x >= y); }\n" +
            "}", "-g:none",
            new String[]{"return !(arg0 < arg1);", "return !(arg0 >= arg1);"}, new String[]{"return arg0 >= arg1;"});
        // BUG-2026-0178: two ternary arguments in one call chain stay separate
        runTestFull("ChainTern178",
            "public class ChainTern178 {\n" +
            "    static boolean f(int a) { return a > 0; }\n" +
            "    static boolean g(int a) { return a > 1; }\n" +
            "    static String s(int a) { return new StringBuilder().append(f(a) ? 1 : 0).append(g(a) ? 1 : 0).toString(); }\n" +
            "}", "-g:none",
            new String[]{"append(f(arg0) ? 1 : 0).append(g(arg0) ? 1 : 0)"}, new String[]{"append(1)"});
        // START_CHANGE: BUG-2026-0179-20261018-3 - rt.jar campaign guards, second round.
        // BUG-2026-0179: constant final instance field assigned again in the constructor
        runTestFull("FinalConst179",
            "public class FinalConst179 {\n" +
            "    private final int CAP = 2;\n" +
            "    private Object o;\n" +
            "    FinalConst179(Object o) { this.o = o; }\n" +
            "    int cap() { return CAP; }\n" +
            "}",
            new String[]{"private final int CAP = 2;", "this.o = "}, new String[]{"this.CAP = 2;"});
        // BUG-2026-0180: javac 7/8 try-with-resources desugaring (needs a JDK 8 javac)
        runTestJavac8("TwrJ8180",
            "public class TwrJ8180 {\n" +
            "    static class R implements AutoCloseable { int use(int x) { return x; } public void close() {} }\n" +
            "    static int single(int x) {\n" +
            "        int r;\n" +
            "        try (R a = new R()) { r = a.use(x); }\n" +
            "        return r + 1;\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"try (TwrJ8180.R ", " = new TwrJ8180.R()) {"},
            new String[]{"addSuppressed", ".close()", "Throwable"});
        // BUG-2026-0181: a ternary argument in the try body made the catch disappear at -g:none
        runTestFull("TernInTry181",
            "public class TernInTry181 {\n" +
            "    static void call(int k, boolean b) {}\n" +
            "    static int one(int k) {\n" +
            "        try { call(k, k == 2); } catch (RuntimeException t) { return -1; }\n" +
            "        return 0;\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"try {", "call(arg0, arg0 == 2);", "catch (RuntimeException"}, new String[]{});
        // BUG-2026-0182: an all-assignment try body lost its catch (Throwable) / finally { close(); }
        runTestFull("TrivialBody182",
            "public class TrivialBody182 {\n" +
            "    static int parse(String s) {\n" +
            "        int r;\n" +
            "        try { r = Integer.parseInt(s); } catch (Throwable t) { r = -1; }\n" +
            "        return r;\n" +
            "    }\n" +
            "    static String read(java.io.Reader in) throws java.io.IOException {\n" +
            "        String v = \"x\";\n" +
            "        try { v = \"y\" + in.read(); } finally { in.close(); }\n" +
            "        return v;\n" +
            "    }\n" +
            "}", "-g",
            new String[]{"catch (Throwable", "r = -1;", "} finally {", "in.close();"}, new String[]{});
        // BUG-2026-0183: a member type inherited from the superclass shadows java.lang.Double
        runTestFull("InheritedShadow183",
            "public class InheritedShadow183 extends java.awt.geom.Point2D.Double {\n" +
            "    int cmp(double a) { return java.lang.Double.compare(a, getX()); }\n" +
            "}", "-g",
            new String[]{"java.lang.Double.compare("}, new String[]{});
        // BUG-2026-0184: `Object first = null; .. first = x;` with catch (IOException x) -> typed IOException
        runTestFull("CatchTypedThrow184",
            "import java.io.*;\n" +
            "public class CatchTypedThrow184 {\n" +
            "    static void closeAll(Closeable[] cs) throws IOException {\n" +
            "        IOException first = null;\n" +
            "        for (Closeable c : cs) {\n" +
            "            try { c.close(); } catch (IOException x) { if (first == null) first = x; else first.addSuppressed(x); }\n" +
            "        }\n" +
            "        if (first != null) throw first;\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"IOException var1 = null;"}, new String[]{"Object var1"});
        // BUG-2026-0185: `(a <=>l b) <= 0` built after BooleanSimplifier rendered as Double.compare
        runTestFull("DcmpIf185",
            "public class DcmpIf185 {\n" +
            "    static int type = 1;\n" +
            "    static boolean contains(double w, double h, boolean z) {\n" +
            "        if (w <= 0.0) { return false; } else {\n" +
            "            if (type == 2) { return z; } else if (z) { if (h >= 180.0) return true; }\n" +
            "            else if (h <= 180.0) { return false; }\n" +
            "            return !z;\n" +
            "        }\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"arg0 <= 0.0", "arg1 >= 180.0", "arg1 <= 180.0"}, new String[]{"compare("});
        // BUG-2026-0186: for-each over a 2-D array declared its element with the base type
        runTestFull("ForEach2D186",
            "public class ForEach2D186 {\n" +
            "    static int n(String[][] cs) { int k = 0; for (String[] c : cs) { k += c.length; } return k; }\n" +
            "}", "-g",
            new String[]{"for (String[] c : cs)"}, new String[]{"for (String c "});
        // BUG-2026-0187: the code after `if (a || b) { if (c) x(); return; }` was dropped
        runTestFull("OrRetTail187",
            "public class OrRetTail187 {\n" +
            "    static StringBuilder log = new StringBuilder();\n" +
            "    static boolean dbg = true;\n" +
            "    static void f(String s, String p) {\n" +
            "        if (s == null || !s.equals(p)) {\n" +
            "            if (dbg) log.append(\"bad;\");\n" +
            "            return;\n" +
            "        }\n" +
            "        log.append(\"tail;\");\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"log.append(\"bad;\");", "log.append(\"tail;\");"}, new String[]{});
        // BUG-2026-0188: switch arms leaving only through conditional jumps had no merge
        runTestFull("SwitchCondExit188",
            "public class SwitchCondExit188 {\n" +
            "    int type;\n" +
            "    SwitchCondExit188(int t, int d) {\n" +
            "        switch (t) {\n" +
            "            case 0: if (d != 0 && d != 1) throw new IllegalArgumentException(); break;\n" +
            "            case 1: if (d != 2) throw new IllegalArgumentException(); break;\n" +
            "        }\n" +
            "        this.type = t;\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"break;", "}\n        this.type = arg0;"}, new String[]{"return;"});
        // BUG-2026-0189: a try inside a loop body was wrapped around the whole loop
        runTestFull("LoopTry189",
            "public class LoopTry189 {\n" +
            "    static int f(int d) { return d; }\n" +
            "    static void m() {\n" +
            "        for (int d = 0; d < 3; d++) {\n" +
            "            String r;\n" +
            "            try { r = \"v\" + f(d); } catch (IllegalArgumentException e) { r = \"E\"; }\n" +
            "            System.out.println(r);\n" +
            "        }\n" +
            "    }\n" +
            "}", "-g:source,lines",
            new String[]{"for (int var0 = 0; var0 < 3; var0++) {", "            try {\n                var1 = \"v\" + f(var0);"},
            new String[]{"        try {\n            while", "        try {\n            for"});
        // BUG-2026-0190: a method-top pre-declaration was placed before super(..) in a constructor
        runTestFull("SuperFirst190",
            "public class SuperFirst190 extends java.util.ArrayList<Object> {\n" +
            "    int a;\n" +
            "    SuperFirst190(int n, boolean b) {\n" +
            "        super(n);\n" +
            "        int k;\n" +
            "        if (b) { k = 1; } else { k = 2; }\n" +
            "        while (n > 0) { if (b) k = n; n--; }\n" +
            "        a = k;\n" +
            "    }\n" +
            "}", "-g",
            new String[]{"super(n);"}, new String[]{"int k;\n        super(n);", "int k = 0;\n        super(n);"});
        // START_CHANGE: BUG-2026-0191-20261018-7 - rt.jar campaign guards 0191..0199
        // BUG-2026-0191: javac for-each temporaries (no LVT entry) took the name/type of another variable of their slot
        runTestFull("ForEachSlot191",
            "import java.util.*;\n" +
            "\n" +
            "public class ForEachSlot191 {\n" +
            "    static String run(String[][] groups, List<String> extra) {\n" +
            "        StringBuilder sb = new StringBuilder();\n" +
            "        for (String[] g : groups) {\n" +
            "            if (g.length > 0) {\n" +
            "                for (String v : g) {\n" +
            "                    sb.append(v).append(',');\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "        for (String[] g : groups) {\n" +
            "            if (g.length > 1) {\n" +
            "                String head = g[0];\n" +
            "                String tail = head.length() > 1 ? head.substring(1) : \"-\";\n" +
            "                Integer count = Integer.valueOf(g.length);\n" +
            "                sb.append(tail).append(count);\n" +
            "                for (String w : extra) {\n" +
            "                    sb.append(w.toUpperCase());\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "        return sb.toString();\n" +
            "    }\n" +
            "\n" +
            "    static int sum(int[] a, int[] b) {\n" +
            "        int t = 0;\n" +
            "        for (int x : a) t += x;\n" +
            "        String label = \"s\" + t;\n" +
            "        long big = label.length();\n" +
            "        for (int y : b) big += y * 3;\n" +
            "        return (int) big + t;\n" +
            "    }\n" +
            "\n" +
            "    public static void main(String[] args) {\n" +
            "        System.out.println(run(new String[][]{{\"ab\", \"c\"}, {}, {\"xyz\", \"q\", \"r\"}}, Arrays.asList(\"m\", \"n\")));\n" +
            "        System.out.println(sum(new int[]{1, 2, 3}, new int[]{4, 5}));\n" +
            "    }\n" +
            "}", "-g",
            new String[]{"int var8 = var7.length;"},
            new String[]{"tail = head.length;"});
        // BUG-2026-0196: an inner anonymous-method local shadowed the captured outer local of the same slot name
        runTestNested("AnonShadow196",
            "import java.util.concurrent.Callable;\n" +
            "\n" +
            "public class AnonShadow196 {\n" +
            "    static String f(String a, String b) throws Exception {\n" +
            "        String t = a;\n" +
            "        if (b != null) t = b;\n" +
            "        final String p = t;\n" +
            "        Callable<String> c = new Callable<String>() {\n" +
            "            public String call() {\n" +
            "                String q = \"in\";\n" +
            "                String r = q + \"-\";\n" +
            "                String s = r + p.length();\n" +
            "                return p + \"/\" + s;\n" +
            "            }\n" +
            "        };\n" +
            "        return c.call();\n" +
            "    }\n" +
            "\n" +
            "    public static void main(String[] args) throws Exception {\n" +
            "        System.out.println(f(\"a\", null));\n" +
            "        System.out.println(f(\"a\", \"b\"));\n" +
            "    }\n" +
            "}",
            new String[]{"var3_a"});
        // BUG-2026-0197: two-counter for-init mixed an assignment with a declaration
        runTestFull("TwoCounter197",
            "public class TwoCounter197 {\n" +
            "    static int common(String[] a, String[] b) {\n" +
            "        int m = a.length - 1;\n" +
            "        int n = b.length - 1;\n" +
            "        while (m >= 0 && n >= 0 && a[m].equals(b[n])) {\n" +
            "            m--;\n" +
            "            n--;\n" +
            "        }\n" +
            "        return a.length - 1 - m;\n" +
            "    }\n" +
            "\n" +
            "    public static void main(String[] args) {\n" +
            "        System.out.println(common(new String[]{\"x\", \"a\", \"b\"}, new String[]{\"y\", \"z\", \"a\", \"b\"}));\n" +
            "        System.out.println(common(new String[]{\"q\"}, new String[]{\"r\"}));\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"for (int var3 = arg1.length - 1;"},
            new String[]{"for (var2 = "});
        // BUG-2026-0198/0199: fragmented outer try (dropped protection fragment) + return at range end
        runTestFull("CatchLoop198",
            "import java.util.*;\n" +
            "\n" +
            "public class CatchLoop198 {\n" +
            "    static int calls;\n" +
            "\n" +
            "    static String get(String s) {\n" +
            "        calls++;\n" +
            "        if (s.startsWith(\"b\")) throw new IllegalArgumentException(s);\n" +
            "        return s.toUpperCase();\n" +
            "    }\n" +
            "\n" +
            "    static String pick(List<String[]> providers) {\n" +
            "        for (int i = 0; i < providers.size(); i++) {\n" +
            "            try {\n" +
            "                String[] infos = providers.get(i);\n" +
            "                for (int j = 0; j < infos.length; j++) {\n" +
            "                    try {\n" +
            "                        return get(infos[j]) + i;\n" +
            "                    } catch (IllegalArgumentException e) {\n" +
            "                    }\n" +
            "                }\n" +
            "            } catch (IllegalArgumentException e) {\n" +
            "            } catch (NullPointerException e) {\n" +
            "            }\n" +
            "        }\n" +
            "        return \"miss\" + calls;\n" +
            "    }\n" +
            "\n" +
            "    public static void main(String[] args) {\n" +
            "        List<String[]> p = new ArrayList<String[]>();\n" +
            "        p.add(new String[]{\"b1\", \"b2\"});\n" +
            "        p.add(null);\n" +
            "        p.add(new String[]{\"b3\"});\n" +
            "        p.add(new String[]{\"b4\"});\n" +
            "        p.add(new String[]{\"ok\"});\n" +
            "        System.out.println(pick(p) + \" \" + calls);\n" +
            "    }\n" +
            "}", "-g:none",
            new String[]{"return \"\" + get(var2[var3]) + var1;\n                    } catch (IllegalArgumentException e) {", "            } catch (IllegalArgumentException e) {\n            } catch (NullPointerException e) {\n            }"},
            new String[]{"                var1++;\n            } catch"});
        // BUG-2026-0200: types declared inside synchronized / labeled bodies got no import
        runTestFull("SyncImport200",
            "public class SyncImport200 {\n" +
            "    private final Object lock = new Object();\n" +
            "    int f(java.util.List<String> l) {\n" +
            "        synchronized (lock) {\n" +
            "            java.util.Iterator<String> it = l.iterator();\n" +
            "            int n = 0;\n" +
            "            while (it.hasNext()) { it.next(); n++; }\n" +
            "            return n;\n" +
            "        }\n" +
            "    }\n" +
            "}", "-g",
            new String[]{"import java.util.Iterator;"}, new String[]{});
        // END_CHANGE: BUG-2026-0191-7
        // END_CHANGE: BUG-2026-0179-3
    }
    // END_CHANGE: BUG-2026-0171-3

    // START_CHANGE: BUG-2026-0202-20261030-4 - A for-each over a parameter declared List<Double>
    // keeps its typed loop variable in every debug mode: the parameter's type arguments come from
    // the method Signature, so it is not a raw Iterable (no `Object x$e` + cast rewrite).
    private static void testGenericParamForEach() {
        String src =
            "import java.util.List;\n" +
            "public class GenParamFe {\n" +
            "    static double sum(List<Double> xs) {\n" +
            "        double total = 0;\n" +
            "        for (Double x : xs) { total += x; }\n" +
            "        return total;\n" +
            "    }\n" +
            "}\n";
        String[] modes = {"-g", "-g:source,lines", "-g:none"};
        for (int i = 0; i < modes.length; i++) {
            runTestFull("GenParamFe", src, modes[i],
                new String[]{"for (Double "}, new String[]{"$e :", "for (Object "});
        }
    }
    // END_CHANGE: BUG-2026-0202-4

    // START_CHANGE: BUG-2026-0201-20261030-2 - The showBytecode configuration must put the
    // disassembled instructions in front of the statements. The reference-collecting dry render
    // consumed the per-method instruction map, so the real render printed none of them.
    private static void testShowBytecodeEmitted() {
        total++;
        String className = "ShowBc201";
        try {
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write("public class ShowBc201 {\n" +
                "    private int offset;\n" +
                "    public void skip(int count) {\n" +
                "        offset += count;\n" +
                "    }\n" +
                "}\n");
            fw.close();
            Process p = new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath())
                .redirectErrorStream(true).start();
            if (p.waitFor() != 0) {
                System.out.println("[SKIP] " + className + " - compilation failed");
                return;
            }
            java.io.File classFile = new java.io.File(tmpDir, className + ".class");
            final byte[] data = java.nio.file.Files.readAllBytes(classFile.toPath());
            Loader loader = new Loader() {
                public boolean canLoad(String internalName) { return "ShowBc201".equals(internalName); }
                public byte[] load(String internalName) { return data; }
            };
            java.util.Map<String, Object> config = new java.util.HashMap<String, Object>();
            config.put("showBytecode", Boolean.TRUE);
            StringPrinter printer = new StringPrinter();
            new DenzoDecompiler().decompile(loader, printer, className, config);
            String result = printer.getResult();
            // The getfield / iadd / putfield of `offset += count` must precede the statement.
            int ins = result.indexOf(": iadd");
            int stmt = result.indexOf("this.offset += ");
            if (ins >= 0 && result.contains(": putfield") && stmt > ins) {
                System.out.println("[PASS] " + className);
                passed++;
            } else {
                System.out.println("[FAIL] " + className + " - no bytecode instructions before the statement");
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
    // END_CHANGE: BUG-2026-0201-2

    // START_CHANGE: BUG-2026-0180-20261018-6 - runTestFull with a JDK 8 javac: the javac 7/8
    // try-with-resources desugaring differs from what javac 9+ emit even with --release 8.
    // The path comes from -Djavac8=..., default the Debian/Ubuntu location; SKIP when missing.
    private static void runTestJavac8(String className, String sourceCode, String javacDebugFlag,
                                      String[] mustContain, String[] mustNotContain) {
        String j8 = System.getProperty("javac8", "/usr/lib/jvm/java-8-openjdk-amd64/bin/javac");
        if (!new java.io.File(j8).canExecute()) {
            System.out.println("[SKIP] " + className + " - no JDK 8 javac at " + j8);
            return;
        }
        String saved = javacPath;
        javacPath = j8;
        try {
            runTestFull(className, sourceCode, javacDebugFlag, mustContain, mustNotContain);
        } finally {
            javacPath = saved;
        }
    }
    // END_CHANGE: BUG-2026-0180-6

    // START_CHANGE: BUG-2026-0129-20260907-4 - Guard for the inlined-finally-copy defect.
    /**
     * BUG-2026-0129: javac inlines the finally body before EVERY exit from the protected region.
     * `removeDuplicatedFinally` only stripped the copy at the top level of the try body, so a copy
     * placed before a `return`/`break`/`continue` nested inside an `if`/loop/switch survived and the
     * action ran twice - for the `lock.unlock()` family, an IllegalMonitorStateException on output
     * that compiles cleanly.
     *
     * Runtime oracle (SCRATCH/w1): the original prints `oneNestedReturn=6 u=1`; the pre-fix
     * decompilation, recompiled and run, printed `u=2` in all three debug modes.
     *
     * Verified against the bytecode: for this shape javac emits the copy in the GAP between the two
     * protected ranges of the `any` handler (`LinkedBlockingDeque.pollFirst`: unlock at pc 40, ranges
     * [18,38) and [46,66)), so it is provably not part of the try body.
     */
    private static void testNestedFinallyCopyRemoved() {
        runTestFull("DupFin",
            "public class DupFin {\n" +
            "    static int calls = 0;\n" +
            "    static void release() { calls++; }\n" +
            "    static int f(int n) {\n" +
            "        try {\n" +
            "            if (n > 0) { return n * 2; }\n" +
            "        } finally { release(); }\n" +
            "        return -1;\n" +
            "    }\n" +
            "}",
            "-g",
            // The nested return must follow its declaration directly: nothing inlined in between.
            new String[]{
                "int var1 = n * 2;\n                return var1;",
                "} finally {"},
            // The inlined copy immediately before the nested return - the defect itself.
            new String[]{"release();\n                return var1;"});
    }
    // END_CHANGE: BUG-2026-0129-4


    private static void runTestFull(String className, String sourceCode,
                                    String[] mustContain, String[] mustNotContain) {
        runTestFull(className, sourceCode, null, mustContain, mustNotContain);
    }

    // START_CHANGE: BUG-2026-0100-20260905-9 - Debug-mode aware variant. The decompiler behaves
    // very differently depending on what javac emitted: `-g` adds a LocalVariableTable, `-g:none`
    // removes even the LineNumberTable (the shape of stripped/obfuscated jars). Several defects
    // only reproduce in one specific mode, so tests must be able to pick it.
    private static void runTestFull(String className, String sourceCode, String javacDebugFlag,
                                    String[] mustContain, String[] mustNotContain) {
        total++;
        try {
            java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"), "decompiler-test");
            tmpDir.mkdirs();
            java.io.File srcFile = new java.io.File(tmpDir, className + ".java");
            java.io.FileWriter fw = new java.io.FileWriter(srcFile);
            fw.write(sourceCode);
            fw.close();

            ProcessBuilder pb = javacDebugFlag == null
                ? new ProcessBuilder(javacPath, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath())
                : new ProcessBuilder(javacPath, javacDebugFlag, "-d", tmpDir.getAbsolutePath(), srcFile.getAbsolutePath());
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
    // END_CHANGE: BUG-2026-0100-9

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
