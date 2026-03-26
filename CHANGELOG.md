# Changelog

All notable changes to DenzoSOFT Java Decompiler.

## [1.3.0] - 2026-03-26

### Added
- Line number alignment: decompiled output preserves original source line numbers
- Implicit `super()` to Object suppressed (not shown when redundant)
- `while(true)` loop reconstruction
- Nested ternary support (3+ levels): `x > 0 ? "pos" : x < 0 ? "neg" : "zero"`
- 4+ chained `&&`/`||` operators fully combined
- Anonymous class display name: `new Comparator()` instead of `new 1()`
- Generic cast preservation: `return (T) obj`

### Fixed
- StackOverflow on complex JDK classes (iterative expression writing)
- Mutual recursion in CFG merge point computation eliminated
- Lambda predicate body: `s -> !s.isEmpty()` correctly decompiled
- Multi-field `equals()`: `id == t.id && Double.compare(score, t.score) == 0`
- Method reference: `String::compareToIgnoreCase`

### Performance
- 27,034 JDK 25 classes decompiled with ZERO errors in 7.2 seconds
- 3,770 classes/sec throughput on JDK

## [1.2.0] - 2026-03-25

### Added
- **Swing GUI** (JD-GUI style): open JARs via menu or drag-and-drop, browse package tree, decompile with syntax highlighting, Ctrl+F find, closeable tabs, multiple JARs in tabs
- CLI flag `--gui` to launch graphical interface

### Fixed
- Operator precedence parenthesization (bitwise, arithmetic, mixed)
- For loop with `continue` statement: body no longer lost
- Ternary expression in void method argument: `println(x > 0 ? "pos" : "neg")`
- Switch fall-through case grouping: `case 1: case 2: ... case 5:` combined
- Lambda block body rendering: actual code shown instead of placeholder
- Method reference detection: `String::compareToIgnoreCase`
- Try-catch body with conditional throw preserved
- Enum constants with constructor arguments: `MERCURY(3.303E23, 2.4397E6)`
- Text block detection: requires 2+ newlines to trigger

## [1.1.0] - 2026-03-24

### Added
- Java bytecode decompiler supporting Java 1.0 through Java 25 (class file v45-v69)
- CFG-based control flow reconstruction (if/else, while, do-while, for, for-each, switch, ternary, try-catch)
- Compound boolean operators (`&&`, `||`), labeled break, synchronized blocks
- Annotations, generics, records, sealed classes, enums, lambda body, module declarations
- String switch reconstruction, string concatenation templates, text blocks
- Array initializers, assert statements, this() delegation
- Inner class inlining, autoboxing suppression, compound assignments
- Javassist-like API (ClassPool, CtClass, CtMethod, CtField)
- Batch parallel decompilation (`--batch`), trace mode (`--trace`)
- Performance benchmark tool
- Thread-safe, security limits, bounds checking, attribute validation
