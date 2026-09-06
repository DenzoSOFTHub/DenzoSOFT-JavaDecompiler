# Changelog

All notable changes to DenzoSOFT Java Decompiler.

## [1.11.0] - 2026-09-05

Correctness release driven by an evidence-based audit of the decompilation process. The v1.10.0
headline numbers held only for javac's DEFAULT debug settings and only for the recompile metric;
measuring production jars (`-g`) and stripped/obfuscated jars (`-g:none`) exposed three defects that
silently changed program semantics while still producing compilable output.

### Fixed
- **Exception handlers no longer disappear from classes without a LineNumberTable** (BUG-2026-0100).
  Try-region membership was decided by comparing SOURCE LINES, so `-g:none` code — the shape of every
  stripped or obfuscated jar — had the region rejected outright and lost **every** `catch` body while
  the remaining happy path still compiled. Statements are now tagged with the bytecode pc they were
  decoded at, and regions are classified on exact bytecode ranges (the JVM's own rule) whenever line
  information is absent. Construct matrix at `-g:none`: `catch` clauses recovered 0 -> 15 of 15.
- **`synchronized` nested inside any compound statement is no longer dropped** (BUG-2026-0101).
  Reconstruction ran only on a method's top-level statement list, so a monitor region inside an
  `if`/loop/`try`/`switch` kept its internal markers and the lock silently vanished. It now descends
  into every compound body — after the enclosing list has consumed its own markers, which is what
  keeps an enclosing region from losing its end. JDK 25 `java.base`: leaked `__MONITORENTER__` markers
  98 files -> 0, reconstructed `synchronized` blocks 720 -> 932.
- **No more `int sum; int sum = 0;` on classes with a LocalVariableTable** (BUG-2026-0106). The
  pre-declaration pass and the undeclared-assignment promotion each emitted a declaration for the same
  slot, producing output that does not compile. Construct matrix at `-g`: 44/55 -> 53/55 recompile-clean;
  Together with BUG-2026-0107 this takes `java.base` from 1,120 files / 8,055 sites of
  re-declared locals down to 15 / 28.

- **The same local is never declared twice in one scope** (BUG-2026-0107). Two consecutive
  `for (int i = ...)` loops share one bytecode slot, and with each declaration hoisted out of its
  for-header the output declared `i` (and the accumulator) twice — four declarations for two
  variables, which does not compile. A new `DuplicateDeclarationDemoter` pass, run last in both flow
  paths, rewrites a same-type re-declaration as an assignment using Java's own scoping rules.
  `java.base`: 1,120 files / 8,055 sites -> 15 / 28.
- **Variables sharing a bytecode slot keep their own identity** (BUG-2026-0103). The
  LocalVariableTable was flattened to one name and type per slot, so two variables javac placed in the
  same slot collapsed into one: `String label = "yes"` came out as `Integer count; Integer count;` …
  `count = "yes"`, with the wrong name, the wrong type and a duplicate declaration. Slots holding more
  than one variable are now resolved by bytecode position; single-variable slots are untouched.
  It also repairs types: `String[] da = 0;` used as an int loop counter in `java/lang/Package`
  becomes `int i = 0;`.
- **Long/double duplication no longer lost** (BUG-2026-0104). `dup2_x1` and `dup2_x2` were no-ops and
  `dup2`/`dup_x2` chose their form on stack depth rather than on computational type category, so a
  duplicated category-2 value vanished: `StampedLock.releaseWrite` decompiled to the uncompilable
  `long nextState = null;` behind a STACK_UNDERFLOW note. All JVMS 6.5 forms are implemented, and the
  `dup;putfield;store` shape now aliases the duplicate to a field read so the right-hand side is
  evaluated once instead of twice. `java.base` files with STACK_UNDERFLOW: 16 -> 7.
- **Lambda bodies no longer lose statements** (BUG-2026-0102). A lambda whose body contained a loop,
  `switch`, `try`, `throw`, `synchronized`, label, `assert` or `yield` had that statement replaced by
  the placeholder `/* inline stmt */;` — the code was gone, and since the placeholder is a valid empty
  statement the output still compiled. The inline lambda writer now renders the full statement model.
  JDK 25 `java.base`: 36 files / 66 sites -> **0**.
- **Class files newer than Java 25 are decompiled instead of refused** (BUG-2026-0118). Any major
  version above 69 produced no output at all; the format is stable across releases, so newer files are
  now parsed on a best-effort basis (verified on major 70, 71 and 99) and Java 26/27 are recognized
  explicitly. Genuinely unreadable content still fails at the precise construct rather than discarding
  the class.

### Changed
- Per-method bytecode disassembly is computed only when `--show-bytecode` is requested (OPT-0007).
  `java.base` batch: 2,856-4,399 ms -> 2,150-2,342 ms, with byte-identical default output.

### Added
- `docs/reports/report-java25-plus-audit.md` — the full audit: three-mode construct matrix, 3,376
  `java.base` classes, Java 21-25 probe fixtures, class-file headroom probes (major 70+ is rejected
  today), performance profile, and a prioritized backlog of 30 items with file:line anchors.
- Seven regression tests (`testCatchWithoutDebugInfo`, `testNestedSynchronized`,
  `testNoDuplicateDeclaration`, `testCategory2Dup`, `testLambdaCompoundBody`, `testSharedSlotScopes`,
  `testNoReDeclaration`) and a debug-mode aware test harness, so tests can pin behaviour under
  `-g`, default and `-g:none` compilation. Suite is now 44/45.

### Known issues
- BUG-2026-0121: a `synchronized` inside an `if` followed by further statements can still lose the
  method's trailing `return` (pre-existing, found while verifying BUG-2026-0101).
- BUG-2026-0122: the bytecode-pc criterion is applied only when line information is missing; enabling
  it for line-bearing classes regressed try-with-resources collapse and is tracked separately.

## [1.10.0] - 2026-06-10

### Highlights
- **Construct matrix 57/57 clean** (Java 1.0–25, strict decompile→recompile; was 37/57) — first full sweep.
- **All 11 verified silent miscompilations eliminated** (wrong code that recompiled cleanly), including
  `super.m()`→`this.m()` infinite recursion, `return a++` wrong value, deleted ternary branches, switch
  fall-through cascades, double-evaluated `(b = in.read())` reads, deleted record-constructor validation.
- **Breadth on 1,674 real JDK 25 classes: 0 crashes, 99.7% marker-clean** (was 93.3%).
- Java 25: JEP 513 flexible constructor bodies, JEP 512 compact source files, `module-info`
  `requires transitive`/`static`.

### Added
- Switch-expression reconstruction: enum-ordinal MatchException defaults, `yield`-block arms, throwing arms,
  nested switch-expressions, in-place merge substitution (BUG-2026-0066).
- Pattern-switch folding: guarded `when` arms, synthetic-default skip, tail-case reclaim, unnamed
  `case Type _` synthesis (BUG-2026-0067).
- Erasure-bound generic locals without `-g`: for-each element back-prop, generic-factory table,
  invokedynamic `instantiatedMethodType` SAM unification → `Function<Integer, Integer> f = x -> ...`
  (BUG-2026-0069 stages A/B/C).
- Local classes loaded and emitted; anonymous-class `val$` captures substituted with call-site
  expressions; member-inner synthetic outer parameter stripped (BUG-2026-0097).
- sealed/permits/non-sealed on nested types with subtype-modifier inference (BUG-2026-0071);
  class-header and record-component generic signatures (BUG-2026-0094).
- `wide` opcode decode (BUG-2026-0084); module-info requires flags (BUG-2026-0099);
  annotation @Retention/@Target/AnnotationDefault emission (BUG-2026-0090).

### Fixed
- 25 tracked items closed: BUG-2026-0053, 0066, 0067, 0068, 0071, 0080, 0081–0099
  (full details in `docs/releases/v1.10.0/release-notes.md`).
- Structural finally dedup (count-based truncation deleted real `return`s); nested synchronized
  balanced pairing + `static synchronized`; modern TWR handler-protection coalescing and nested
  collapse; cast-operand precedence; `&&`/`||` merge statement preservation; text-block `\s`
  trailing-whitespace escapes; dup-store aliasing; record compact-constructor body preservation.

## [1.9.0] - 2026-06-10

### Added
- **SWITCH-form record patterns** (BUG-2026-0079, IMP-2026-0063) — `switch (o) { case Line(Point(int x, int y), ...) -> ... }`
  now reconstructs on the JD-Core pipeline (return tail-duplication + dead-guard elimination + typeSwitch arm
  folder), with selective activation that keeps the legacy path byte-identical for non-record-switch methods.
- **Java 1.0–25 coverage assurance** — `docs/reports/report-coverage-assurance.md` + a standing
  `src/test/resources/construct-matrix/` suite. Breadth test on 1,674 real JDK 25 classes: 0 crashes, 93.3%
  marker-clean.

### Fixed
- **Post-Java-8 constructs reconstructed on the default path** (BUG-2026-0057 … 0078): records (compact/validating
  canonical constructors), pattern `instanceof` (binding, `&&`-tail, record deconstruction flat/nested/generic),
  switch expressions, pattern switch (sealed, `when` guards, `case null`), modern try-with-resources, interface
  `default`, `sealed`, enum constant bodies, lambdas/method references.
- **`StructuredFlowBuilder.canFormTernary`** no longer mutates the shared `visited` set (was silently truncating
  if-cascades and loop bodies) — recovered ~6,300 lines on real code.
- **Construct-matrix gaps** (BUG-2026-0080): annotations (`@interface`, nested annotation types),
  multidimensional arrays, array-foreach element types, `Double.NaN`/`Infinity` literals, record canonical-ctor
  access, for-loop variable scope, catch-variable rename recursion, switch-case variable hoisting, boolean
  comparison simplification in call arguments.

### Quality
- Java 8→21 construct corpus: 0/13 → 5/13 round-trip; construct matrix 35/55; DecompilerTest 32/33; regression
  (decompiler's own 160 classes) 0 crashes.

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
