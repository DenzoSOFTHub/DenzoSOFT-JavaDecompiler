# Changelog

All notable changes to DenzoSOFT Java Decompiler.

## [1.8.0] - 2026-04-20

### Added
- **Decompilation diagnostics in generated source** (IMP-2026-0002): every silent recovery path now records a machine-readable note visible to the reader.
  - Class-level `// WARNING: This class was NOT fully decompiled.` banner listing class-scoped issues.
  - Per-method `// === DECOMPILATION NOTES ===` block immediately before the method body.
  - Event types: `STACK_UNDERFLOW`, `DECODE_ERROR`, `CFG_BUILD_FAILED`, `STRUCTURED_FLOW_FAILED`, `INNER_CLASS_SKIPPED`.

### Fixed
- **Exception handler operand-stack seed** (BUG-2026-0050): handler blocks pre-seeded with the caught exception reference so the opening `astore` captures it correctly (hundreds of spurious `catch (e) { null = e; ... }` patterns eliminated on Spring Boot bytecode).
- **Multi-value exit-stack inheritance** (BUG-2026-0051): full operand-stack snapshot saved at block exit and restored at successor entry. Fixes compound arithmetic around ternaries, including Lombok `hashCode()` pattern `result = result * PRIME + (x == null ? 43 : x.hashCode())`.
- **Malformed `import ::Ljava...;`** (BUG-2026-0043): signature scanner rewritten as a structural parser that handles interface-only bounds (`<L::L...>`) without mistaking the type-parameter name for a class descriptor.
- **`<L extends A extends B>` generics** (BUG-2026-0046): class-bound-then-interface-bound now correctly renders as `<L extends A & B>`.
- **Text-block emission safety** (BUG-2026-0044): `isTextBlockSafe` guard rejects content with trailing quote, `\r`, `"""`, backslash, or source-level line terminators other than `\n`. Falls back to escaped string literal.
- **Control-char / line-terminator escape** (BUG-2026-0045): `escapeString` now `\u`-escapes every char < 0x20 (except `\n` / `\t`), 0x7f, and U+0085 / U+2028 / U+2029.
- **Interface `static { }` initializer** (BUG-2026-0047): static-final assignments from `<clinit>` now inline into the field declaration for inner classes too; clinit is suppressed entirely in interface bodies.
- **`package-info` class output** (BUG-2026-0048): rendered as `package X;` declaration with annotations instead of illegal `interface package-info {}`.
- **Reserved-word class / field names** (BUG-2026-0049): names colliding with Java keywords (e.g., `SystemModules$default`) are now prefixed with `_` regardless of `--deobfuscate`.

### Performance
- java.base: 3,368/3,372 non-permits-clean (99.88%); remaining 4 files carry explicit decompilation notes.
- Spring Boot uber-jars: 2,803/2,803 classes decompiled, **0 decompilation diagnostics**, **0 stack underflows**.

## [1.7.0] - 2026-03-27

### Added
- GUI multi-tab navigation: Content, Classes, Libraries tabs in archive browser
- WAR/EAR support: WEB-INF/classes, WEB-INF/lib, BOOT-INF (Spring Boot), EAR modules
- APK Android support: open .apk files, DEX class structure parser, DEX Classes tab
- Deobfuscation transformer: encrypted string detection, opaque predicate removal, control flow flattening detection, reflection annotation, return-type overloading rename
- Compilability section in README with test results on 6,372 JDK classes

### Fixed
- Enum switch map: `$SwitchMap$[expr.ordinal()]` simplified to `expr` as selector (52 errors)
- Record fields: suppress component fields already declared in `record(...)` (40 errors)
- MONITORENTER/MONITOREXIT: emit as comment instead of string literal (21 errors)
- `<=>` comparison: emit as `Long.compare()`/`Double.compare()` instead of invalid operator (10 errors)
- Type names: strip trailing `;` from descriptor-derived names (12 errors)
- Numeric inner class names: `$1CleanupAction` → `_1CleanupAction` in emitRef, SignatureParser, writeExpressionSimple (65 errors)
- Array class literals: `[S.class` → `short[].class`, `[Lcom/Foo;.class` → `Foo[].class` (21 errors)
- `this$0` → `OuterClass.this` qualified reference in anonymous class bodies (4 errors)
- Boolean ternary: `cond ? 1 : 0` simplified to `cond`, `cond ? true : false` → `cond`
- `access$NNN` resolution: read accessor body to find private member name (e.g. `doOpenJar()`)
- `access$NNN` double emission: fixed missing else-block around standard static call path

### Performance
- java.base: 3,355/3,372 compile (99.5%)
- Other JDK modules: 2,996/3,000 compile (99.9%)
- Total: 6,351/6,372 compile (99.7%)

## [1.6.0] - 2026-03-27

### Added
- `--deobfuscate` CLI option: sanitize obfuscated identifiers (Java keywords, illegal chars) for compilable output
- `--show-bytecode` now shows inline bytecode instructions with Java-level explanations before each decompiled line
- Options menu in GUI: Compact, Show Bytecode, Show Native Info, Deobfuscate checkboxes
- Default JAR launch is now GUI mode (no arguments = GUI)
- Identifier sanitizer: `do` → `_do`, `if` → `_if`, illegal chars → `_`
- Anonymous inner class body inlining: `new ActionListener() { public void actionPerformed(...) { ... } }`
- Synthetic `this$0` and `val$xxx` fields resolved to outer class reference and captured variables
- Synthetic `access$NNN` methods inlined as direct outer class calls

### Fixed
- Variable naming without LocalVariableTable: `arg0`/`var1` mismatch (parameters now consistent between signature and body)
- Import collector: now traverses fields, methods, body expressions, generic signatures, and inner class results
- Multi-dimensional array syntax: `new T[n][]` instead of `new T[][n]`
- Array initializer without dimension: `new int[]{1,2}` instead of `new int{1,2}`
- Inner class `$` handling: imports use outer class, type references use `Outer.Inner` dot notation
- Generic signature parser: preserves `Outer.Inner` format instead of truncating to `Inner`
- Boolean/int conversion: `iconst_0`/`iconst_1` correctly emitted as `false`/`true` for boolean fields and method params
- Char/int conversion: `bipush 46` emitted as `(char)46` for char method parameters (fixes `String.replace`)
- Batch decompilation: inner classes no longer decompiled as separate files (shared Loader resolves inner class bytecode)
- Anonymous inner class filter: correctly detects `$N` suffix after outer class name
- Ternary-as-statement workaround: orphan ternary expressions wrapped in variable assignment

### Performance
- 15,071 JDK 25 top-level classes decompiled with ZERO errors
- Project self-decompilation: 130/135 files compile (96%)

## [1.5.0] - 2026-03-26

### Added
- Line number alignment is now the default output mode (preserves original source line numbers)
- `--compact` CLI option: produces dense output without line number alignment
- `--show-bytecode` CLI option (metadata only, enhanced in v1.6.0)
- `--show-native-info` CLI option: shows JNI function names and parameter types on native methods

### Fixed
- Printer currentLine tracking: `endLine()` now advances line position, fixing line number drift in aligned mode

## [1.4.0] - 2026-03-26

### Added
- Try-with-resources reconstruction: resource variables extracted from finally/close() patterns
- Pattern matching for switch (Java 21+): type patterns from SwitchBootstraps reconstructed
- Type annotations (Java 8+): rendered on field types and method return types
- If-else-if chain rendering: `else if` instead of nested `else { if }`
- While with assignment in condition: `while((line = readLine()) != null)`
- Assignment expressions parenthesized correctly in conditions

### Fixed
- LIM-0004: Type annotations now parsed and rendered (field/method return types)
- LIM-0005: Pattern matching for switch now detected via SwitchBootstraps
- LIM-0008: Try-with-resources resource extraction from close() patterns
- IMP-2026-0002: If-else-if chains show proper else-if syntax
- BUG-2026-0016: While loops with assignment in condition reconstructed

## [1.3.0] - 2026-03-26

### Added
- Line number alignment in decompiled output (default since v1.5.0, use `--compact` to disable)
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
