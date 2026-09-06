# Release Notes - v1.11.0

**Status**: RELEASED

Correctness release. An evidence-based audit of the decompilation process (construct matrix in three
debug modes, 3,376 top-level classes of JDK 25 `java.base`, purpose-built Java 21-25 probe fixtures)
established that the v1.10.0 headline numbers held only for javac's DEFAULT debug settings and only
for the recompile metric. Measuring the two other real-world shapes — production jars built with `-g`
and stripped/obfuscated jars built with `-g:none` — exposed three defects that silently changed
program semantics while still producing compilable output.

Full audit: [docs/reports/report-java25-plus-audit.md](../../reports/report-java25-plus-audit.md).

## Summary

| ID | Severity | Summary |
|---|---|---|
| [BUG-2026-0100](BUG-2026-0100.md) | CRITICAL | try/catch/finally membership was decided by source line, so a class without a LineNumberTable lost **every** catch clause. Statements are now tagged with their bytecode pc and regions are classified on exact bytecode ranges when line information is absent. |
| [BUG-2026-0101](BUG-2026-0101.md) | CRITICAL | A `synchronized` region nested inside any compound statement was dropped, silently removing the lock. Reconstruction now descends into every compound body, after the enclosing list has consumed its own monitor markers. |
| [BUG-2026-0106](BUG-2026-0106.md) | HIGH | Classes with a LocalVariableTable (every Maven/Gradle jar) emitted `int sum; int sum = 0;`, which does not compile. The promotion pass is now aware of pending pre-declarations. |
| [BUG-2026-0103](BUG-2026-0103.md) | CRITICAL | LocalVariableTable scope ranges were discarded, so two variables sharing a slot collapsed into one wrong name and type (`Integer count; Integer count;` … `count = "yes"`). Slots holding several variables are now resolved by position. |
| [BUG-2026-0107](BUG-2026-0107.md) | HIGH | The same local was declared twice in one scope (two consecutive `for (int i = ...)` loops sharing a slot), which does not compile. A new late pass demotes a same-type re-declaration to an assignment. |
| [BUG-2026-0104](BUG-2026-0104.md) | HIGH | `dup2_x1` and `dup2_x2` were no-ops and `dup2`/`dup_x2` ignored long/double category, losing duplicated values (`long nextState = null;`). All JVMS 6.5 forms implemented, with aliasing so the duplicated right-hand side is evaluated once. |
| [BUG-2026-0118](BUG-2026-0118.md) | HIGH | Class files newer than Java 25 were refused outright, producing no output. They are now parsed on a best-effort basis; Java 26/27 are recognized explicitly. |
| [BUG-2026-0102](BUG-2026-0102.md) | CRITICAL | Lambda bodies containing a loop, `switch`, `try`, `throw` or `synchronized` had that statement replaced by `/* inline stmt */` — code silently deleted from output that still compiled. Every statement kind is now rendered. |
| [OPT-0007](OPT-0007.md) | - | Per-method bytecode disassembly ran even without `--show-bytecode` (~12% of batch CPU). Now gated on the flag. |

## Measured impact

| Gate | v1.10.0 | v1.11.0 |
|---|---|---|
| Construct matrix, `-g:none` — `catch (` recovered (source has 15) | 0 | **15** |
| Construct matrix, `-g:none` — recompile-clean | 53/55 | **54/55** |
| Construct matrix, `-g` — recompile-clean | 44/55 | **53/55** |
| Construct matrix, default debug — recompile-clean | 55/55 | 55/55 |
| `java.base` — classes with a leaked `__MONITORENTER__` | 98 | **0** |
| `java.base` — reconstructed `synchronized` blocks | 720 | **932** |
| `java.base` — files with a local re-declared in scope | 1,120 (8,055 sites) | **15** (28 sites) |
| `java.base` — files with `/* inline stmt */` lambda truncation | 36 (66 sites) | **0** |
| `java.base` — files with STACK_UNDERFLOW | 16 | **7** |
| `java.base` — uncompilable `long/double x = null;` | 1 | **0** |
| `java.base` — decompile errors (3,376 classes) | 0 | 0 |
| `java.base` — batch time (3 runs) | 2,856-4,399 ms | **2,150-2,342 ms** |
| Java 26+ class files (major 70/71/99) | no output | **decompiled** |
| Automated tests | 37/38 | **44/45** |

The one failing test remains `BasicClass`, whose expectation of an explicit `super()` is stale: the
decompiler correctly elides the implicit call.

## Known residuals opened by this work

- **BUG-2026-0121** — a `synchronized` inside an `if` followed by further statements can still lose the
  method's trailing `return`. Pre-existing and unrelated to monitors; found while verifying 0101.
- **BUG-2026-0122** — the bytecode-pc criterion introduced by 0100 is applied only when line
  information is missing, because switching it on for line-bearing classes regressed
  try-with-resources collapse. The displacement defect on `-g` code therefore remains.
