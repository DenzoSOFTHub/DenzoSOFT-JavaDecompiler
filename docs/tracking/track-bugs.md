# Bug Tracking

Index shows ONLY open items.

---

## Open Bugs

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0121 | HIGH | Trailing `return` lost when a synchronized block sits inside an `if` with following statements | [BUG-2026-0121.md](items/BUG-2026-0121.md) |
| BUG-2026-0122 | HIGH | Statements displaced out of the try region when line numbers are present | [BUG-2026-0122.md](items/BUG-2026-0122.md) |

### Filed from the Java 25+ audit, not yet given dedicated files

Full evidence, root causes and file:line anchors for all of these are in
[docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md) section 3.

| ID | Severity | Summary |
|---|---|---|
| BUG-2026-0123 | HIGH | Guarded / multi-label / constant-label pattern switches lose their arm values (flow-builder work; 7 java.base bodies, all flagged) | [BUG-2026-0123.md](items/BUG-2026-0123.md) |
| BUG-2026-0110 | HIGH | Anonymous classes: fields and initializers dropped, generic supertype arguments lost, anon inside a lambda not inlined |
| BUG-2026-0111 | MEDIUM | try-with-resources combinations: multi-resource effectively-final, TWR + catch + finally, TWR body locals |
| BUG-2026-0112 | MEDIUM | `boolean ok = a > b ? 1 : 0;` instead of the boolean expression |
| BUG-2026-0113 | MEDIUM | Erased casts rendered instead of the generic target; erased-to-type-variable assignments without cast |
| BUG-2026-0114 | MEDIUM | `LambdaMetafactory.altMetafactory` rendered as chained casts `(X) (Serializable) lambda` |
| BUG-2026-0115 | MEDIUM | Switch-expression / ternary values consumed by `aastore` or a stack-carried call argument -> STACK_UNDERFLOW (8 of 16 java.base files) |
| BUG-2026-0116 | LOW | Import of the outer class lost for nested generic type arguments from LVTT (`Map.Entry`) |
| BUG-2026-0117 | MEDIUM | `ldc`/`ldc_w` of CONSTANT_Dynamic emits the literal string `"/* constant:N */"` (silent wrong value) |
| BUG-2026-0119 | MEDIUM | Unknown invokedynamic bootstraps decoded as lambdas/method references regardless of owner |
| BUG-2026-0120 | MEDIUM | StructuredFlowBuilder recursion cap truncates silently |
