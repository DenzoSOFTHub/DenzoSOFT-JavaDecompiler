# Bug Tracking

Index shows ONLY open items.

---

## Open Bugs

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0121 | HIGH | Trailing `return` lost when a synchronized block sits inside an `if` with following statements | [BUG-2026-0121.md](items/BUG-2026-0121.md) |
| BUG-2026-0126 | HIGH | Statements displaced out of the try region in classes WITH line information (split from BUG-2026-0122; output depends on source formatting) | [BUG-2026-0126.md](items/BUG-2026-0126.md) |
| BUG-2026-0128 | HIGH | Guarded pattern switches with an index restart lose their arm values (split from BUG-2026-0123; flow-builder work; 5 java.base bodies, all flagged) | [BUG-2026-0128.md](items/BUG-2026-0128.md) |
| BUG-2026-0130 | CRITICAL | try-with-resources flattened to open/use/close with no `try` at all — the resource is never closed on exception (runtime-proven) | [BUG-2026-0130.md](items/BUG-2026-0130.md) |
| BUG-2026-0131 | HIGH | Flow-builder-synthesised statements (`line=0`) pushed out of the try region — exception coverage lost, method tail lost; 59 sites / 41 java.base files | [BUG-2026-0131.md](items/BUG-2026-0131.md) |
| BUG-2026-0132 | CRITICAL | `break`/`continue` out of a `synchronized` block inside a loop deletes the block body (`Phaser.doRegister`, `TimerThread.mainLoop`) | [BUG-2026-0132.md](items/BUG-2026-0132.md) |
| BUG-2026-0133 | CRITICAL | `synchronized` dropped when its monitor temp is consumed as an instanceof binding (`String.contentEquals`); 16 java.base classes | [BUG-2026-0133.md](items/BUG-2026-0133.md) |
| BUG-2026-0135 | HIGH | One `try/finally` split into several, each re-running the `finally` (javac emits several protected ranges per handler); runtime-proven `u=1` -> `u=2` | [BUG-2026-0135.md](items/BUG-2026-0135.md) |
| BUG-2026-0203 | HIGH | Record-pattern switch EXPRESSION loses every arm value and carries no diagnostic (all three debug modes; pre-existing in v1.12.0) | [BUG-2026-0203.md](items/BUG-2026-0203.md) |
| BUG-2026-0204 | LOW | GUI *Compact Output* option has no effect (flag never read; GUI printer ignores line numbers) | [BUG-2026-0204.md](items/BUG-2026-0204.md) |
| BUG-2026-0205 | HIGH | Nested classes silently lost for classes under `WEB-INF/classes/` / `BOOT-INF/classes/` (batch and GUI) | [BUG-2026-0205.md](items/BUG-2026-0205.md) |
| BUG-2026-0206 | MEDIUM | A nested class the loader cannot supply is dropped without a diagnostic | [BUG-2026-0206.md](items/BUG-2026-0206.md) |
| BUG-2026-0207 | MEDIUM | `--batch` skips nested archives, reports 0 classes for an APK silently, exits 0 on failures | [BUG-2026-0207.md](items/BUG-2026-0207.md) |
| BUG-2026-0208 | LOW | GUI: Find never advances, archive tab "x" leaks the file, DEX tree inert, nested WAR shown as text | [BUG-2026-0208.md](items/BUG-2026-0208.md) |

### Filed from the Java 25+ audit

Each now has a dedicated item file. The original evidence, root causes and file:line anchors are in
[docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md) section 3; the item
files transcribe it and record which findings were re-verified against the current tree on 2026-09-07.

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0110 | HIGH | Anonymous classes: fields and initializers dropped, generic supertype arguments lost, anon inside a lambda not inlined | [BUG-2026-0110.md](items/BUG-2026-0110.md) |
| BUG-2026-0111 | HIGH | try-with-resources combinations: multi-resource effectively-final, TWR + catch + finally, TWR body locals | [BUG-2026-0111.md](items/BUG-2026-0111.md) |
| BUG-2026-0112 | MEDIUM | `boolean ok = a > b ? 1 : 0;` instead of the boolean expression | [BUG-2026-0112.md](items/BUG-2026-0112.md) |
| BUG-2026-0113 | MEDIUM | Erased casts rendered instead of the generic target; erased-to-type-variable assignments without cast | [BUG-2026-0113.md](items/BUG-2026-0113.md) |
| BUG-2026-0114 | MEDIUM | `LambdaMetafactory.altMetafactory` rendered as chained casts `(X) (Serializable) lambda` | [BUG-2026-0114.md](items/BUG-2026-0114.md) |
| BUG-2026-0115 | MEDIUM | Switch-expression / ternary values consumed by `aastore` or a stack-carried call argument -> STACK_UNDERFLOW (8 of 16 java.base files) | [BUG-2026-0115.md](items/BUG-2026-0115.md) |
| BUG-2026-0116 | MEDIUM | Import of the outer class lost for nested generic type arguments from LVTT (`Map.Entry`) | [BUG-2026-0116.md](items/BUG-2026-0116.md) |
| BUG-2026-0117 | MEDIUM | `ldc`/`ldc_w` of CONSTANT_Dynamic emits the literal string `"/* constant:N */"` (silent wrong value) | [BUG-2026-0117.md](items/BUG-2026-0117.md) |
| BUG-2026-0119 | MEDIUM | Unknown invokedynamic bootstraps decoded as lambdas/method references regardless of owner | [BUG-2026-0119.md](items/BUG-2026-0119.md) |
| BUG-2026-0120 | MEDIUM | StructuredFlowBuilder recursion cap truncates silently | [BUG-2026-0120.md](items/BUG-2026-0120.md) |
