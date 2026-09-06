# Release Notes - v1.12.0

**Status**: READY (not yet tagged)

Focus: pattern switch (Java 21+) on real-world bytecode. The v1.11.0 audit measured that 27 of the
31 JDK 25 `java.base` classes using `SwitchBootstraps.typeSwitch` still emitted the raw bootstrap
call instead of a reconstructed `switch` — the largest remaining modern-Java gap.

## Summary

| ID | Severity | Summary |
|---|---|---|
| [BUG-2026-0109](BUG-2026-0109.md) | HIGH | Statement-form pattern switches kept the raw `typeSwitch` dispatch with integer indices. `SwitchStatement` now carries pattern labels and a new pass rebuilds the switch from the bootstrap labels and the arms' own casts. `java.base` raw dispatches 28 -> 7. |
| [BUG-2026-0108](BUG-2026-0108.md) | HIGH | Pattern-switch labels stored as `CONSTANT_Dynamic` were lost, rendering the arm as the uncompilable `case  _`. Qualified enum constants (`case DayOfWeek.MONDAY`, Java 21 GA) and the JEP 507 boolean previews now resolve, and constant labels are emitted as real constant cases rather than type patterns with a binding. |

## What the audit called one bug was two

Measuring against isolated fixtures split BUG-2026-0109 in half. Expression-form switches (type
patterns, record patterns, named or unnamed components) already worked. The statement form failed for
a benign reason — no value merge to match — but its arm bodies were all present, so an AST pass could
rebuild it. The guarded / multi-label form fails for a different reason: its arm values travel on the
operand stack to a merge the flow builder never rebuilds, so they are simply not in the tree.

Only the first was fixed. The second is tracked as **BUG-2026-0123**, and the new pass deliberately
declines those shapes: rewriting them would wrap valid-looking syntax around a body that is still
missing its values, and would silence the diagnostic that reports them.

## Measured

| Gate | v1.11.0 | v1.12.0 |
|---|---|---|
| `java.base` bodies with a raw `typeSwitch` dispatch | 28 | **7** |
| of those, carrying a diagnostic | 3 | **7 of 7** |
| `qualifiedEnumCase` probe | `case  _ -> "mon"` (uncompilable) | **`case DayOfWeek.MONDAY -> "mon"`** |
| Construct matrix, default / `-g` / `-g:none` (of 55) | 55 / 53 / 54 | 55 / 53 / 54 |
| `java.base` (3,376 classes) | 0 errors | 0 errors |
| `java.base` locals re-declared in scope | 15 files / 28 sites | 14 / 25 |
| Self-decompilation | 162/162 | 163/163 |
| Automated tests | 44/45 | **46/47** |
