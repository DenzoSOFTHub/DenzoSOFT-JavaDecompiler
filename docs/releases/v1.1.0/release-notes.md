# Release Notes

---

## v1.0.0-SNAPSHOT (2026-03-24) - Bug Fixes

### Resolved Issues

| ID | Summary |
|---|---|
| ISS-2026-0001 | Nested if-else chains: deeply nested if-else structures now correctly reconstructed without duplicate blocks or misplaced branches |
| ISS-2026-0002 | Array initializers: `new int[] {1, 2, 3}` patterns reconstructed from `newarray` + store sequences |
| ISS-2026-0003 | Assert statements: `assert condition` and `assert condition : message` reconstructed from `$assertionsDisabled` guard pattern |
| ISS-2026-0009 | Constructor delegation: `this(args)` calls in constructors correctly identified and emitted |
| ISS-2026-0010 | Long/double comparisons: `lcmp`, `dcmpg`, `dcmpl`, `fcmpg`, `fcmpl` opcodes correctly handled in conditional expressions |
| ISS-2026-0011 | Cast precedence: cast expressions correctly parenthesized in complex expressions to avoid compilation errors |

### Improvements
- Output quality: 6 decompilation correctness fixes improving compilability of output
- Control flow: nested if-else chains handle arbitrary depth without merging errors
- Expressions: comparison and cast operators produce correct Java syntax

---

## v1.0.0-SNAPSHOT (2026-03-23) - Initial Release

### Features
- Java bytecode decompiler supporting class file versions 45.0 (Java 1.0) through 69.0 (Java 25)
- Zero external dependencies, Java 1.6 source compatibility
- Control Flow Graph (CFG) based decompilation with structured control flow reconstruction
- If/else, while, do-while, for, for-each, switch, ternary, try-catch-finally reconstruction
- Compound boolean operators (`&&`, `||`) from branch chain patterns
- Annotations with values (primitives, strings, enums, arrays, nested)
- Generics from Signature attribute (`List<String>`, `<T extends Comparable<T>>`, wildcards)
- Records (Java 16+), sealed classes (Java 17+), enums, interfaces
- Lambda body reconstruction from synthetic methods via BootstrapMethods
- String concatenation template reconstruction from `makeConcatWithConstants`
- String switch reconstruction from hashCode/equals 2-phase pattern
- Module declarations (`module-info.java`)
- Inner class and anonymous class inlining in outer class source
- Variable declaration merging, compound assignment simplification (`+=`, `-=`)
- Boolean simplification (!=0, ==0, true/false literals, ternary reduction)
- Autoboxing suppression (`Integer.valueOf(1)` -> `1`)
- Redundant cast removal
- Native method JNI comments
- Preview features detection
- Batch decompilation with parallel thread pool (`--batch`)
- Trace mode for troubleshooting (`--trace`)
- Javassist-like API: ClassPool, CtClass, CtMethod, CtField, CtConstructor, CtRecordComponent
- Diagnostic error reporting with `DecompilationException`
- Security limits (max bytecode, recursion depth, AST nodes)
- Bounds checking in ByteReader for malformed class files
- Attribute length validation with auto-recovery
- Performance: ~4,000 classes/sec, pre-sized collections, expression caching

### Architecture
- Modular pipeline: Deserializer -> CFG -> StructuredFlowBuilder -> Transformers -> Writer
- 6 AST transform modules: BooleanSimplifier, ForEachDetector, ForLoopDetector, TryCatchReconstructor, StringSwitchReconstructor, CompoundAssignmentSimplifier
- Shared OpcodeInfo utility (eliminates code duplication)
- Thread-safe (new converter per decompile call)
- 126 source files, 166 class files, 16 automated tests

### Known Limitations
- LIM-0001: String switch edge cases with hash collisions
- LIM-0002: Try-with-resources falls back to linear code
- LIM-0003: @Override not reconstructable (SOURCE retention)
- LIM-0004: Type annotations parsed but not rendered
- LIM-0005: Pattern matching for switch (Java 21+) incomplete
- LIM-0006: Text blocks not distinguished from regular strings
- LIM-0007: Complex lambda captures may show synthetic field accesses
