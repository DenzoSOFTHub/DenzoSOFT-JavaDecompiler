# Release Notes - v1.13.0

**Status**: READY (not yet tagged)

Focus: BUG-2026-0123 — the pattern-switch shapes v1.12.0 deliberately left alone.

## Summary

| ID | Severity | Summary |
|---|---|---|
| [BUG-2026-0124](BUG-2026-0124.md) | CRITICAL | `BooleanSimplifier` held per-method state in a static field, so concurrent decompilation raced on it and a boolean method's `return 0` was sometimes left as an int. Batch output differed run to run in 211 of 3,376 `java.base` classes. The flag is now per-thread, and `DenzoDecompiler` no longer shares a stateful writer — one instance is safe across threads. |
| [BUG-2026-0122](BUG-2026-0122.md) | HIGH | A statement the flow builder synthesised (a merged ternary, `pc=-1 line=0`) could not be classified and was emitted after the catch, taking the declaration it depends on with it. Unpositioned statements now inherit their predecessor's classification, region membership uses the union of a group's ranges, and try-with-resources recognises javac's suppression wrapper. Construct matrix `-g:none` 54 -> 55. The line-bearing case stays open, for a reason recorded in the item. |
| [BUG-2026-0123](BUG-2026-0123.md) | HIGH | Constant labels (`case "true"` with `case null`) and array-class labels (`case byte[] _`) were rejected by an over-broad guard, leaving the raw `typeSwitch` dispatch with every arm value dropped. Both now reconstruct. `java.base` raw dispatches 7 -> 5. |

## Measured

| Gate | v1.12.0 | v1.13.0 |
|---|---|---|
| `java.base` bodies with a raw `typeSwitch` dispatch | 7 | **5** |
| Constant-label fixture: decompile → recompile → **run** | arms dropped, wrong result | **runtime-identical to the original** |
| Construct matrix, default / `-g` / `-g:none` (of 55) | 55 / 53 / 54 | 55 / 53 / **55** |
| `java.base` (3,376 classes) | 0 errors | 0 errors |
| Automated tests | 46/47 | **50/51** |
| `java.base` batch, run-to-run differing files | 211 of 3,376 | **0** |
| Shared instance across 8 threads, 400 classes | 11-21 divergences | **0** |

## BUG-2026-0124: the library is now thread-safe and deterministic (CRITICAL, fixed)

Measuring a candidate fix against the baseline exposed something unrelated and more serious:
`--batch` was **non-deterministic**. The same build over the same 3,376 `java.base` classes produced
211 differing files run to run, and the variants were not equivalent — a boolean-returning method came
out as `return false;` (correct) or `return 0;` (does not compile).

The cause was a single static field: `BooleanSimplifier` kept "does this method return boolean" in
per-process storage while it is per-method state, so concurrent tasks raced on it. It now lives in a
`ThreadLocal` with save/restore. `DenzoDecompiler` also shared one stateful `JavaSourceWriter` across
calls; it now builds one per call, so **a single instance is safe to use from several threads** —
something the documentation previously warned against.

After the fix, three multi-threaded batches over `java.base` are byte-identical to each other and to
the single-threaded reference, and a shared instance driven by 8 threads over 400 classes matches the
sequential output exactly (the pre-fix build diverged in 11-21 classes per run).

## Still open

- **BUG-2026-0121** — an exception region inside an `if` branch followed by another statement loses the
  method's trailing `return`. Established today: it is not synchronized-specific (a plain `try/catch`
  reproduces it), it predates this work, and the if/else inversion comes from the flow builder, not the
  reconstructor. A reconstructor-level fix was tried and dropped the handler entirely.
- **BUG-2026-0122 (line-bearing classes)** — see the item: enabling the criterion there removes many
  fabricated catch clauses but loses at least one real handler.
- **BUG-2026-0123 (guarded form)** — the 5 remaining raw dispatches are guarded pattern switches whose
  failed guard assigns the dispatch index and loops back; reconstructing them needs
  `tryBuildSwitchExpression` to model the restart loop. All 5 carry the
  `PATTERN_SWITCH_NOT_RECONSTRUCTED` diagnostic.
