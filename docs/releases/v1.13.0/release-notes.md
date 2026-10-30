# Release Notes - v1.13.0

**Status**: RELEASED (2026-10-30)

Focus: thread safety and determinism, the pattern-switch shapes v1.12.0 deliberately left alone
(BUG-2026-0127), and a large correctness campaign on real-world `rt.jar` / `java.base` bytecode
(BUG-2026-0136 .. BUG-2026-0200).

**Documentation**: new English [User Manual](../../guides/guide-user-manual.md) and GUI screenshots in
the README.

## Summary

| ID | Severity | Summary |
|---|---|---|
| [BUG-2026-0124](BUG-2026-0124.md) | CRITICAL | `BooleanSimplifier` held per-method state in a static field, so concurrent decompilation raced on it and a boolean method's `return 0` was sometimes left as an int. Batch output differed run to run in 211 of 3,376 `java.base` classes. The flag is now per-thread, and `DenzoDecompiler` no longer shares a stateful writer — one instance is safe across threads. |
| [BUG-2026-0125](BUG-2026-0125.md) | HIGH | A statement the flow builder synthesised (a merged ternary, `pc=-1 line=0`) could not be classified and was emitted after the catch, taking the declaration it depends on with it. Unpositioned statements now inherit their predecessor's classification, region membership uses the union of a group's ranges, and try-with-resources recognises javac's suppression wrapper. Construct matrix `-g:none` 54 -> 55. The line-bearing case stays open as BUG-2026-0126, for the reason recorded there. |
| [BUG-2026-0127](BUG-2026-0127.md) | HIGH | Constant labels (`case "true"` with `case null`) and array-class labels (`case byte[] _`) were rejected by an over-broad guard, leaving the raw `typeSwitch` dispatch with every arm value dropped. Both now reconstruct. `java.base` raw dispatches 7 -> 5. |
| [IMP-2026-0070](IMP-2026-0070.md) | HIGH | The API-side view of the same defect as BUG-2026-0124: `DenzoDecompiler` held a shared stateful `JavaSourceWriter`, so one instance per thread was required while the docs promised thread safety. Closed with that fix — a fresh writer is now built per `decompile()` call. |
| [BUG-2026-0129](BUG-2026-0129.md) | CRITICAL | An inlined `finally` copy placed before a NESTED `return`/`break`/`continue` survived, so the `finally` action ran twice (a second `unlock()` throws `IllegalMonitorStateException`). Copies before nested exits are now removed. |
| [BUG-2026-0201](BUG-2026-0201.md) | HIGH | `--show-bytecode` printed no instructions at all: the new reference-collecting dry render consumed the per-method instruction map. Found and fixed during release preparation. |
| [BUG-2026-0202](BUG-2026-0202.md) | MEDIUM | A for-each over a `List<T>` PARAMETER was rewritten to `for (Object x$e : xs)` plus a cast, in every debug mode. Parameters typed by the method Signature now count as generic. Found and fixed during release preparation. |
| BUG-2026-0136 .. 0200 | UNRATED | 65 fixes from the `rt.jar` semantic campaign — see the next section. |

BUG-2026-0122 and BUG-2026-0123 were each partially resolved, so each was closed and split: the
resolved half (BUG-2026-0125, BUG-2026-0127) ships here, the remaining half (BUG-2026-0126,
BUG-2026-0128) stays open in the tracking index.

## Correctness campaign: BUG-2026-0136 .. BUG-2026-0200

Between 2026-10-15 and 2026-10-18 a campaign against `rt.jar` and `java.base` used a
bytecode-fingerprint gate (original class vs the class recompiled from the decompiled source) and the
runtime oracle to hunt silent defects. It added the flow-builder safety net (`ReachabilityChecker` +
`SafeStructurer`), a final type-repair pass (`TypeCoercer`), variable-web splitting, declaration
placement and duplicate resolution, render-driven import resolution, `$SwitchMap$` enum switches,
javac 7/8 try-with-resources normalisation, short-circuit reconstruction, NaN-correct float
comparisons, and fixes to anonymous classes, accessors and constant inlining. Nine runtime-oracle
fixtures it added (`src/test/resources/runtime-oracle/`, BUG-2026-0171..0178) pass in all three debug
modes.

**How this work is documented — read before relying on it.** The campaign was implemented without
item files. At release time each ID's item file was **reconstructed from its START_CHANGE comments**:
the description and analysis are transcribed from the code, but no severity rating and no before/after
measurement exist for any of them, and **40 of the 65 have no dedicated regression test** — they are
covered only by the release gates below. The open items filed earlier (BUG-2026-0110..0120, 0130..0135)
were NOT re-verified against this code, so some may be partly or fully resolved by it.

Two regressions this work introduced were caught while preparing this release and are fixed here
(BUG-2026-0201, BUG-2026-0202). A third behaviour change is recorded under *Still open*
(BUG-2026-0132).

| ID | Change | Dedicated test |
|---|---|---|
| [BUG-2026-0136](BUG-2026-0136.md) | The physically last goto of the true region is the if/else merge jump only when it leaves the region forward | **no** |
| [BUG-2026-0137](BUG-2026-0137.md) | Without a LineNumberTable the region has no start line, so the line-span test rejected EVERY compound body... | **no** |
| [BUG-2026-0138](BUG-2026-0138.md) | A conditional back-jump is a do-while condition only when it is the LAST back-edge into its target | **no** |
| [BUG-2026-0139](BUG-2026-0139.md) | Field post-increment/decrement used as a value | **no** |
| [BUG-2026-0140](BUG-2026-0140.md) | Remember the class-file version so that language-level-dependent rewrites (pattern instanceof, Java 16)... | **no** |
| [BUG-2026-0141](BUG-2026-0141.md) | Import resolution driven by what is actually rendered | **no** |
| [BUG-2026-0142](BUG-2026-0142.md) | A cast the source needs even when the operand's decoded type already looks like the target (int stack... | **no** |
| [BUG-2026-0143](BUG-2026-0143.md) | The hand-written walker below skipped for, for-each, labels, lambdas, array initializers and more, so a... | **no** |
| [BUG-2026-0144](BUG-2026-0144.md) | Declare locals that no enclosing declaration covers | **no** |
| [BUG-2026-0145](BUG-2026-0145.md) | A generic constructor (`<T> Foo(T t)`) declares its own type parameters too | **no** |
| [BUG-2026-0146](BUG-2026-0146.md) | Inline the accessor by substituting the call's arguments into the accessor's own decompiled body (`return... | **no** |
| [BUG-2026-0147](BUG-2026-0147.md) | The object seeded on top of this block's entry stack by its last decode (null when nothing was seeded) | **no** |
| [BUG-2026-0148](BUG-2026-0148.md) | `if (!$assertionsDisabled) throw ...` throws whenever assertions are on | **no** |
| [BUG-2026-0149](BUG-2026-0149.md) | With the synthetic outer parameter dropped from the declaration, its uses in the body must go too | **no** |
| [BUG-2026-0150](BUG-2026-0150.md) | Leftover `v = $exception;` handler stores | **no** |
| [BUG-2026-0151](BUG-2026-0151.md) | Structural defects of the flow builder are not detected (unreachable code, missing return, dangling break) | **no** |
| [BUG-2026-0152](BUG-2026-0152.md) | Explicit entry stack for the SafeStructurer's decode (bottom first) | **no** |
| [BUG-2026-0153](BUG-2026-0153.md) | var/yield/record/sealed/permits are contextual | **no** |
| [BUG-2026-0154](BUG-2026-0154.md) | `Integer.valueOf(String)` simplified away as if it were autoboxing | **no** |
| [BUG-2026-0155](BUG-2026-0155.md) | Keep the anonymous class's type arguments (`new Comparator<QName>() {`) | **no** |
| [BUG-2026-0156](BUG-2026-0156.md) | A lambda passed to a method overloaded on functional interfaces... | **no** |
| [BUG-2026-0157](BUG-2026-0157.md) | Fields whose declared type is a type variable (`T value;`) | **no** |
| [BUG-2026-0158](BUG-2026-0158.md) | One name, several variables of different types | **no** |
| [BUG-2026-0159](BUG-2026-0159.md) | A typed loop variable over a raw Iterable (no generic information survives for locals without a... | **no** |
| [BUG-2026-0160](BUG-2026-0160.md) | `new Inner(a, (Outer$1) null)` | **no** |
| [BUG-2026-0161](BUG-2026-0161.md) | Class hierarchy for structural checks | **no** |
| [BUG-2026-0162](BUG-2026-0162.md) | No local declared while one of the same name is in scope | **no** |
| [BUG-2026-0163](BUG-2026-0163.md) | The anonymous class's own fields and the rest of its constructor (field initializers, instance initializer... | **no** |
| [BUG-2026-0164](BUG-2026-0164.md) | A declaration narrower than one of the values stored into it is widened to their least common superclass | **no** |
| [BUG-2026-0165](BUG-2026-0165.md) | An argument of unknown static type (Object) for a parameter erased to Object | **no** |
| [BUG-2026-0166](BUG-2026-0166.md) | Read the $SwitchMap$ tables | **no** |
| [BUG-2026-0167](BUG-2026-0167.md) | Keep the ternary as the operand | **no** |
| [BUG-2026-0168](BUG-2026-0168.md) | The Signature attribute omits synthetic and mandated parameters (enum name/ordinal, outer instance), so it... | **no** |
| [BUG-2026-0169](BUG-2026-0169.md) | Render nested arrays through the main NewArrayExpression path | **no** |
| [BUG-2026-0170](BUG-2026-0170.md) | Match the inlined set by OWNER-qualified name | **no** |
| [BUG-2026-0171](BUG-2026-0171.md) | findBlockEnd's nested-conditional scan is not bounded by the then-region | yes |
| [BUG-2026-0172](BUG-2026-0172.md) | A conditional arm whose stack top is the value the outer condition block left pending (the `this` of... | yes |
| [BUG-2026-0173](BUG-2026-0173.md) | An Object-typed value is NOT "no information" | yes |
| [BUG-2026-0174](BUG-2026-0174.md) | The <clinit> statements actually moved into field initializers, by identity | yes |
| [BUG-2026-0175](BUG-2026-0175.md) | The BUG-2026-0122-3 rule for line-bearing classes | yes |
| [BUG-2026-0176](BUG-2026-0176.md) | Short-circuit boolean chains back from the exponentially duplicated ternary trees the flow builder emits... | yes |
| [BUG-2026-0177](BUG-2026-0177.md) | fcmpg/dcmpg push 1 for NaN, fcmpl/dcmpl push -1 | yes |
| [BUG-2026-0178](BUG-2026-0178.md) | Every decoded constant is a FRESH node | yes |
| [BUG-2026-0179](BUG-2026-0179.md) | A final instance field with a constant initializer carries a ConstantValue attribute (already rendered as... | yes |
| [BUG-2026-0180](BUG-2026-0180.md) | Bytecode pre-pass for the javac 7/8 try-with-resources desugaring | yes |
| [BUG-2026-0181](BUG-2026-0181.md) | The rebuilt statement is a new object | yes |
| [BUG-2026-0182](BUG-2026-0182.md) | A body of plain assignments (`r = Integer.parseInt(s);`, `v = "y" + in.read();`) is real code | yes |
| [BUG-2026-0183](BUG-2026-0183.md) | Print the box type as a type reference | yes |
| [BUG-2026-0184](BUG-2026-0184.md) | Catch parameters are not in scope yet when this runs | yes |
| [BUG-2026-0185](BUG-2026-0185.md) | Entry point for the writer | yes |
| [BUG-2026-0186](BUG-2026-0186.md) | getElementType() is the BASE type | yes |
| [BUG-2026-0187](BUG-2026-0187.md) | The merge point a pattern decides is handed to the caller through lastEffectiveMergePoint, but the... | yes |
| [BUG-2026-0188](BUG-2026-0188.md) | Arms that leave the switch only through CONDITIONAL jumps (`case 0 | yes |
| [BUG-2026-0189](BUG-2026-0189.md) | A compound statement's minimum pc is its first CHILD's | yes |
| [BUG-2026-0190](BUG-2026-0190.md) | In a constructor the declarations go after the explicit super(..)/this(..) call, which must stay the first... | yes |
| [BUG-2026-0191](BUG-2026-0191.md) | Slot accesses outside every LocalVariableTable scope | yes |
| [BUG-2026-0192](BUG-2026-0192.md) | Least upper bound that also considers interfaces | **no** |
| [BUG-2026-0193](BUG-2026-0193.md) | A web whose own values disagree (Format var = null | **no** |
| [BUG-2026-0194](BUG-2026-0194.md) | The catch parameter is no longer excluded from splitting | **no** |
| [BUG-2026-0195](BUG-2026-0195.md) | A copy of another local carries that local's declared type when it is a definite class type | **no** |
| [BUG-2026-0196](BUG-2026-0196.md) | A captured value is written as the enclosing method's expression (`proto`, i.e | yes |
| [BUG-2026-0197](BUG-2026-0197.md) | Both counters must be dead after the loop | yes |
| [BUG-2026-0198](BUG-2026-0198.md) | The exception table before BUG-2026-0068 drops handler-protection entries | yes |
| [BUG-2026-0199](BUG-2026-0199.md) | javac ends a protected range just BEFORE the return instruction of a `return expr;` that closes the try... | **no** |
| [BUG-2026-0200](BUG-2026-0200.md) | Labeled blocks and synchronized bodies were not visited | yes |

## Measured

Release build, 2026-10-30. Construct matrix and suite per the procedures in CLAUDE.md.

| Gate | v1.12.0 | v1.13.0 |
|---|---|---|
| `java.base` bodies with a raw `typeSwitch` dispatch | 7 | **5** (measured 2026-09-07, before the campaign) |
| Constant-label fixture: decompile → recompile → **run** | arms dropped, wrong result | **runtime-identical to the original** |
| Construct matrix, default / `-g` / `-g:none` (of 55) | 55 / 53 / 54 | 55 / 53 / **55** |
| All-class self-decompilation (176 classes) | 0 errors | 0 errors |
| Automated tests | 46/47 | **80/81** (only the known stale `BasicClass` expectation fails) |
| Runtime-oracle fixtures, 14 × 3 debug modes | — | **35 of 42 runtime-identical** (see *Still open*) |
| `java.base` batch, run-to-run differing files | 211 of 3,376 | **0** (measured 2026-09-07) |
| Shared instance across 8 threads, 400 classes | 11-21 divergences | **0** (measured 2026-09-07) |

The `-g` matrix failures are the documented baseline (`C_RecordPattern`: `variable a is already
defined`; `C_VarInference`: `package Map does not exist`). The `java.base` figures were measured on
2026-09-07 and were not re-run on the final build.

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

- **BUG-2026-0132** (CRITICAL) — `break`/`continue` out of a `synchronized` block inside a loop.
  The runtime-oracle fixture `RSyncLoopExit` changed class on this build: at `e032be5` it did not
  recompile (`missing return statement`); now it **recompiles and behaves differently** in all three
  debug modes: the statements that follow the `synchronized` block inside the loop are moved INTO
  it, so they run with the monitor held (`Thread.holdsLock` prints `true` where the original prints
  `false`; the computed values are the same). One method carries a `/* __MONITOREXIT__ */` marker
  where the structure was lost. Treat decompiled loops containing a `synchronized` block with an
  early exit as suspect.
- **BUG-2026-0130 / BUG-2026-0111** — try-with-resources: `RTwrA` and `RTwrB` do not recompile at `-g`
  and `-g:source,lines` (`auto-closeable resource ... may not be assigned`); both pass at `-g:none`.
  (`RTwrB` at `-g` moved from "compiles but the exception is no longer caught" to "does not
  recompile", the honest failure.)
- **BUG-2026-0203** (HIGH, new) — a record-pattern switch EXPRESSION loses every arm value and carries
  no diagnostic, in all three debug modes. Pre-existing (v1.12.0 also fails).
- **BUG-2026-0204** (LOW, new) — the GUI's *Compact Output* option has no effect.
- **BUG-2026-0205** (HIGH, new) — nested classes of classes under `WEB-INF/classes/` or
  `BOOT-INF/classes/` are silently missing (batch and GUI). Workaround: extract the classes directory
  and decompile it as a plain directory.
- **BUG-2026-0206..0208** (new) — an unloadable nested class is dropped without a diagnostic; `--batch`
  skips nested archives and exits 0 on failures; several small GUI defects (Find, tab close, DEX tree).

- **BUG-2026-0121** — an exception region inside an `if` branch followed by another statement loses the
  method's trailing `return`. Established today: it is not synchronized-specific (a plain `try/catch`
  reproduces it), it predates this work, and the if/else inversion comes from the flow builder, not the
  reconstructor. A reconstructor-level fix was tried and dropped the handler entirely.
- **BUG-2026-0126** (line-bearing classes; split from BUG-2026-0122) — enabling the bytecode criterion
  there removes many fabricated catch clauses but loses at least one real handler. Separating those two
  populations by measurement is the prerequisite.
- **BUG-2026-0128** (guarded form; split from BUG-2026-0123) — the 5 remaining raw dispatches are
  guarded pattern switches whose failed guard assigns the dispatch index and loops back; reconstructing
  them needs `tryBuildSwitchExpression` to model the restart loop. All 5 carry the
  `PATTERN_SWITCH_NOT_RECONSTRUCTED` diagnostic.
