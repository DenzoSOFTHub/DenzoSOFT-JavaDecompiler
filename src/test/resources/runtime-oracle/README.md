# Runtime-oracle fixtures

Fixtures for the `original -> run -> A` vs `decompile -> recompile -> run -> B` gate described in
CLAUDE.md ("Verifying a decompiler fix"). Each pins ONE shape and prints observable state, so a
divergence in MEANING shows up as a diff instead of hiding behind a clean recompile.

Run them with the runner in CLAUDE.md. Exit 0 = runtime-identical, 2 = does not recompile,
3 = compiles but behaves differently.

| Fixture | Shape | commit `e032be5`, `-g` |
|---|---|---|
| `RTwr.java` | single resource, `return` inside the try | **0** — passes; do not assume every TWR is broken |
| `RTwrA.java` | multi-resource `try (R a; R b)` | **2** — emits `a = null;`, `auto-closeable resource a may not be assigned` |
| `RTwrB.java` | TWR + `catch` + `finally`, body throws | **3** — recompiles cleanly, but the exception is NO LONGER CAUGHT |

`RTwrB` is the proof that the gate fires on the silent class: the original prints
`open;close;caught;fin;`, the decompiled-and-recompiled program dies with an uncaught
`RuntimeException`. A recompile-only gate sees nothing wrong with it.

`RTwrA` and `RTwrB` are reproducers for the try-with-resources item (Q2 of
`docs/reports/report-java25-and-beyond-analysis.md`).

## Fixtures added by the rt.jar semantic campaign (2026-10-18)

Each one reproduced a SILENT defect (exit 3, or a recompile failure that hid a wrong body) on the
build before its fix, and passes (exit 0) in all three debug modes after it. They were found with
the bytecode-fingerprint gate (original class vs class recompiled from the decompiled source).

| Fixture | Shape | Item |
|---|---|---|
| `IfElseLoopTail.java` | `if/else` whose then-body ends in a `for` loop: the then-branch was dropped | BUG-2026-0171 |
| `TernPending.java` | nested ternary stored to a field (`this.op = c ? 3 : d ? 2 : 4`): arm became `this` | BUG-2026-0172 |
| `LubLocal.java` | local holding `args[i]` and later `args`: narrowed to `Object[]`, ClassCastException | BUG-2026-0173 |
| `TryBoolClinit.java` | static field inlined `= false`, re-assigned after a try in `<clinit>`: assignment lost | BUG-2026-0174 |
| `TryBoolTail.java` | `flag = s != null && check(s);` last in a try: moved after the catch, exception escaped | BUG-2026-0175 |
| `OrAndChain.java` | `(a && b && c) || ...` chain: exponential ternary (1.5 MB in `JLayer`) | BUG-2026-0176 |
| `NanCmp.java` | `!(x < y)` on double/float: rendered `x >= y`, wrong for NaN | BUG-2026-0177 |
| `ChainTern.java` | `sb.append(f() ? 1 : 0).append(g() ? 1 : 0)`: ternaries merged through shared constants | BUG-2026-0178 |
| `BoolPrec.java` | mixed `&&`/`||`/`!`, NaN and chained ternary arguments (regression guard) | BUG-2026-0176..0178 |

## Results on the v1.13.0 release build (2026-10-30)

Exit codes of the CLAUDE.md runner, per debug mode. Every fixture not listed returns **0** in all
three modes (`BoolPrec`, `ChainTern`, `IfElseLoopTail`, `LubLocal`, `NanCmp`, `OrAndChain`, `RTwr`,
`TernPending`, `TryBoolClinit`, `TryBoolTail`).

| Fixture | `-g` | `-g:source,lines` | `-g:none` | Item |
|---|---|---|---|---|
| `RSyncLoopExit.java` | **3** | **3** | **3** | BUG-2026-0132 — was 2 (`missing return statement`) at `e032be5`; now recompiles and runs the loop tail inside the lock |
| `RTwrA.java` | 2 | 2 | 0 | BUG-2026-0111 / 0130 — `auto-closeable resource ... may not be assigned` |
| `RTwrB.java` | 2 | 2 | 0 | BUG-2026-0111 / 0130 — was 3 at `e032be5` (exception no longer caught); now an honest recompile failure |

