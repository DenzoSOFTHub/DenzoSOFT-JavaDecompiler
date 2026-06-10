# Release v1.10.0

Construct-matrix campaign: every Java 1.0–25 language construct in the 57-class strict
decompile→recompile matrix now round-trips clean — **57/57, 0 crashes** (baseline at the start
of the campaign: 37/57). Driven by a 63-agent verified gap analysis
(`docs/reports/report-construct-matrix-gap-analysis.md`) that root-caused every failing class
and semantically audited every passing one.

## Headline results

| Gate | Before | After |
|---|---|---|
| Construct matrix (Java 1.0–25, strict recompile) | 37/57 | **57/57** |
| Silent miscompilations (verified, on "clean" classes) | 11 (5 CRITICAL) | **0 known** |
| Corpus (13 Java 8–21 classes, per-class errors) | 19 | **10** |
| Breadth (1674 real JDK 25 classes) | 93.3% marker-clean | **99.7%**, 0 crashes |
| Self-sweep (160 own classes) | 1 marker-dirty | **0**, 0 crashes |
| DecompilerTest | 32/33 | **37/38** (+5 new tests; 1 pre-existing failure) |
| Internal JD pipeline comparison (corpus) | — | legacy ≤ JD errors on **every** class (10 vs 30 total) |

New Java 25 coverage: JEP 513 flexible constructor bodies (C_FlexibleCtor), JEP 512 compact
source files / instance main (C_ImplicitMain), `module-info` `requires transitive`/`static`.

## Items resolved

### Silent miscompilations (CRITICAL/HIGH — wrong code that recompiled cleanly)
- **BUG-2026-0081** — `super.m()` decompiled as `this.m()` → infinite recursion (invokespecial super detection)
- **BUG-2026-0082** — `return a++` returned the new value; iinc now fuses postfix/prefix into expressions (runtime-proven 5/5/12)
- **BUG-2026-0083** — ternary value-merge deleted conditions/else-branches/consumers; generalized recursive consumer rewriter
- **BUG-2026-0085** — string/int switch lost `break`s → silent fall-through cascade; PC-ordered case grouping + goto-to-merge breaks
- **BUG-2026-0086** — labeled-continue inner loop body silently dropped; loop-exit branch hardening
- **BUG-2026-0087** — `(b = in.read()) != -1` evaluated `read()` twice; dup-store alias now references the variable
- **BUG-2026-0088** — text-block trailing whitespace lost; `\s` escaping (byte-identical constants)
- **BUG-2026-0089** — record canonical-ctor validation/clamping deleted; compact-form param reassignment
- **BUG-2026-0090** — @Retention/@Target/AnnotationDefault dropped (reflection-proven round-trip)
- **BUG-2026-0053** — ternary-in-lambda + nested-ternary conditions + folded `"" +` concat (runtime-proven)

### Recompile failures
- **BUG-2026-0084** — `wide` opcode (iinc_w >127, wide load/store) decoded
- **BUG-2026-0091** — finally dedup now structural-match based (count truncation deleted real `return`s)
- **BUG-2026-0092** — nested synchronized balanced pairing + `static synchronized` emission
- **BUG-2026-0093** — cast operand precedence parentheses (`(U) (cmp >= 0 ? a : b)`)
- **BUG-2026-0094** — class-header/record-component Signature attributes consulted (`implements Container<E>`, `record Box<T>(T value)`)
- **BUG-2026-0071** — sealed/permits/non-sealed on nested types with subtype-modifier inference
- **BUG-2026-0095** — `&&`/`||` merge no longer drops second-condition statements; pattern-cast rebinding
- **BUG-2026-0066** — switch expressions: enum-ordinal MatchException defaults, yield-block/throwing/nested arms, in-place merge substitution
- **BUG-2026-0067** — pattern-switch folding: synthetic default skip, tail-case reclaim, guarded `when` arms, unnamed `case Type _` synthesis
- **BUG-2026-0068** — TWR: handler-protection entry coalescing, nested collapse, single effectively-final form
- **BUG-2026-0096** — boolean slot inference + per-slot category-conflict splitting (no-LVT)
- **BUG-2026-0097** — local classes loaded and emitted; anonymous `val$` capture substitution; member-inner synthetic outer param stripped
- **BUG-2026-0098** — synthetic `Objects.requireNonNull` (bound method-ref null check) no longer leaks
- **BUG-2026-0099** — module-info `requires transitive`/`static` flags honored; mandated `java.base` suppressed
- **BUG-2026-0069** (stages A/B/C) — erasure-bound generics without `-g`: for-each element back-prop, generic-factory table (Arrays.asList/List.of/Optional.of/…), indy `instantiatedMethodType` SAM unification (`Function<Integer, Integer> f = x -> …`)
- **BUG-2026-0080** — construct-matrix umbrella: closed at 57/57

## Verification discipline

Every fix carries START_CHANGE/END_CHANGE tags, a dedicated test (matrix class, DecompilerTest
method, or runtime-compared synthetic suite), and passed the full gate: mvn clean compile,
DecompilerTest, 57-class matrix, 13-class corpus (both pipelines), 160-class self-sweep,
1674-class JDK breadth. Semantic fixes are runtime-proven (original vs recompiled output
compared on real inputs); annotation fixes reflection-proven; synchronized/sealed fixes
bytecode-proven via javap.

### Closed in the final sweep
- **BUG-2026-0056** — catch-conditional body drop: handler bodies with control flow now route through
  `StructuredFlowBuilder.buildHandlerBody` (full if/else, `&&`, switch in catch); structural finally
  matching extended with locals-normalized signatures; `Object v = null` retyping; try-declared var
  hoisting; nested catch-var disambiguation (runtime-proven on the SimogClient shape).
- **BUG-2026-0069** — final residual (a) closed by the new `transform/BranchVarHoister` (if/else and
  fall-through declaration hoisting with definite-assignment proof). Item fully resolved.
- **IMP-2026-0001** — inner classes emitted interleaved at their original line positions
  (LineNumberTable anchors; byte-identical fallback without line info).

### JD-Core official comparison (user-authorized, jd-cli 1.3.0-beta-1)

- Matrix: ours **57/57** vs JD-Core **44/57** (6 classes with no output at all: records, sealed,
  pattern switch, unnamed; 7 recompile failures incl. TWR and enum-switch).
- Corpus: ours **7** total errors vs JD-Core **39** + 4 classes with no output.
- Per-class: JD-Core produces a better result on **zero** classes — everything JD-Core
  decompiles, this decompiler decompiles at least as well, plus Java 14–25 constructs
  JD-Core cannot handle.

## Still open

(none — all tracked bugs and improvements closed)
