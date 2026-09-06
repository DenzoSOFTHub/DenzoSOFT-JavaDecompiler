# Release Notes - v1.12.0

**Status**: IN DEVELOPMENT

Focus: pattern switch (Java 21+) on real-world bytecode. The v1.11.0 audit measured that 27 of the
31 JDK 25 `java.base` classes using `SwitchBootstraps.typeSwitch` still emitted the raw bootstrap
call instead of a reconstructed `switch`, making this the largest remaining modern-Java gap.

## Summary

| ID | Severity | Summary |
|---|---|---|
| [BUG-2026-0108](BUG-2026-0108.md) | HIGH | Pattern-switch labels stored as `CONSTANT_Dynamic` were lost, rendering the arm as the uncompilable `case  _`. Qualified enum constants (`case DayOfWeek.MONDAY`, Java 21 GA) and the JEP 507 boolean previews now resolve, and constant labels are emitted as real constant cases instead of type patterns with a binding. |

## In progress

**BUG-2026-0109** — statement-form pattern switches still fall back to a raw `typeSwitch` dispatch and
their arm VALUES are missing, because those values are carried on the operand stack to a merge the
flow builder does not rebuild. No post-hoc transform can recover them; this needs flow-builder work
and is not done.

What v1.12.0 does deliver for it is the honesty half: such a body used to be **silently** wrong (only
3 of the 29 affected `java.base` files carried any diagnostic). A surviving raw dispatch is now
detected on both flow paths and recorded as `PATTERN_SWITCH_NOT_RECONSTRUCTED`, so the degraded body
announces itself. Coverage is 27 of 28 real cases; the miss is a switch nested inside an inline lambda
body, which the statement walker does not reach.

## Measured

| Gate | v1.11.0 | v1.12.0 |
|---|---|---|
| `qualifiedEnumCase` probe | `case  _ -> "mon"` (uncompilable) | **`case DayOfWeek.MONDAY -> "mon"`** |
| `java.base` raw `typeSwitch` bodies carrying a diagnostic | 3 of 29 | **27 of 28 real cases** |
| Construct matrix, default / `-g` / `-g:none` (of 55) | 55 / 53 / 54 | 55 / 53 / 54 |
| `java.base` (3,376 classes) | 0 errors | 0 errors |
| Automated tests | 44/45 | **45/46** |
