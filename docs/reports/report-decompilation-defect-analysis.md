# Report: Decompilation Defect Analysis (JD-Core parity + post-Java-8)

**Date**: 2026-06-08
**Scope**: Empirical analysis of where the decompiler misbehaves or fails to decompile, with focus on
post-Java-8 constructs and parity with JD-Core (the upstream this project derives from).
**Method**: 13-class corpus (Java 8→21 constructs) compiled with JDK 25 `javac`, decompiled in both the
default (legacy) path and the opt-in JD pipeline (`-Ddenzo.jd.pipeline=true`), then recompiled and
inspected for semantic correctness.

## Environment notes (reproduction)

- The Maven `target/` dir is unreliable on this WSL mount (`clean` and `maven-jar-plugin` fail on the
  `api` subdir; the shipped `target/java-decompiler-1.8.0.jar` is **broken** — missing `Main`). Build the
  decompiler by compiling `src/main/java` straight to a non-`target` dir and run via classpath:
  `javac -d /tmp/dc-classes @<srcs>; java -cp /tmp/dc-classes it.denzosoft.javadecompiler.Main --compact <class>`.
- Corpus + outputs: `/tmp/corpus/src` (originals), `/tmp/corpus/out/{legacy,jd}/src` (decompiled),
  `/tmp/corpus/out/*/rc` (recompile errors). Evidence digest: `/tmp/corpus/EVIDENCE.md`.

## 1. Executive summary

**0 of 13 corpus classes round-trip** (decompile → recompile cleanly with correct semantics) in **either**
mode. Failures split into: (a) classic ≤Java-8 control-flow corruption on the *default* legacy path, and
(b) wholesale loss of post-Java-8 constructs (records, switch-expressions, pattern matching, `default`,
`sealed`, modern try-with-resources). The opt-in JD-Core CFG pipeline is **currently worse than legacy on
every corpus class** (more recompile errors on all 13) — its per-method quality gate still accepts outputs
that regress loops/booleans — so it cannot become the default yet.

Critically, **recompilability undercounts the damage**: many outputs compile (or almost) but are *silently
semantically wrong* — boolean methods that return a constant, switch-expressions that always return one arm,
try-with-resources that leaks on exception and double-closes, if/else-if chains truncated to the first branch.

## Recompile-error counts (lower = closer to correct; all still fail)

| Class | construct focus | legacy rc-errors | jd rc-errors |
|---|---|---|---|
| ControlFlow | loops, ternary, finally | 3 | 5 |
| ClassicConstructs | enum-body, TWR, arrays, varargs | 8 | 16 |
| J8Lambdas | lambdas, method refs | 5 | 4 |
| J8Streams | stream pipelines | 5 | 5 |
| J9Interfaces | default/private methods, TWR | 6 | 8 |
| J10Var | var, generics, arrays | 3 | 5 |
| J14SwitchExpr | switch expressions | 1* | 1* |
| J15TextBlocks | text blocks, .formatted | 1 | 1 |
| J16InstanceOf | instanceof patterns | 2 | 1 |
| J16Records | records | 24 | 24 |
| J17Sealed | sealed/permits, records | 10 | 16 |
| J21SwitchPattern | pattern switch, guards | 35 | 66 |
| J21RecordPattern | record deconstruction | 43 | 58 |

\* J14SwitchExpr "1 error" is misleading: it *almost* compiles but every switch-expression method returns a
constant — the per-case yielded values are dropped. Semantic correctness ≪ recompilability here.

## 2. Severity-ranked defects

| ID | Title | Severity | Post-1.8 | Root cause (one line) | Primary site |
|----|-------|----------|----------|------------------------|--------------|
| F06 | `visited` claim-tracker drops loop bodies / branch arms / if-chains | **CRITICAL** | no | Shared `visited` conflates "claimed during analysis" with "emitted"; guards then bail | `cfg/StructuredFlowBuilder.java:146-150` |
| F01 | Record implicit members emitted verbatim; ObjectMethods indy → empty lambda | **CRITICAL** | yes | No `isRecord()` suppression branch; ObjectMethods bootstrap unrecognized | `writer/JavaSourceWriter.java:856-863` |
| F02 | invokedynamic dispatched on call-site name, not bootstrap factory | **CRITICAL** | yes | Only `makeConcatWithConstants`/`typeSwitch` handled; everything else → lambda fallback | `converter/ClassFileToJavaSyntaxConverter.java:2152-2166` |
| F05 | instanceof patterns not reconstructed; binding leaks; else-if truncated | **CRITICAL** | yes | 3-arg `InstanceOfExpression` (patternVar=null) + shared F06 truncation | `ClassFileToJavaSyntaxConverter.java:2458-2469` |
| F09 | Java 9+ try-with-resources desugar not collapsed | **CRITICAL** | no | Collapse gated on a `finally` block modern javac never emits | `transform/TryCatchReconstructor.java:299-340` |
| F10 | Local decls demoted; array types collapsed to element type; ternary collapsed | **CRITICAL** | no | Re-decode + shared `declaredVars`; `NewArrayExpression` stores element type | `ClassFileToJavaSyntaxConverter.java:2605-2620` |
| F03 | Switch-expressions never reconstructed (always returns one arm) | HIGH | yes | No producer for `SwitchExpression`/`YieldStatement`; `arrowStyle` ignored | `StructuredFlowBuilder.java:298-398` |
| F04 | Java 21 pattern switch not reconstructed; raw `SwitchBootstraps` leaks | HIGH | yes | Reconstructor precondition never matches inlined-selector shape | `transform/PatternSwitchReconstructor.java:41-64` |
| F07 | Interface `default` modifier never emitted | HIGH | yes | `default` has no ACC bit; writer gets no interface context to infer it | `JavaSourceWriter.java:1567` |
| F11 | Lambda `arg0` collision, nested-lambda synthetic leak, bound method-ref receiver loss | HIGH | yes | Incremental synthetic-body map (order bug) + null receiver in method-ref ctor | `ClassFileToJavaSyntaxConverter.java:189-219` |
| F12 | Enum constant bodies dropped; illegal `abstract` on enum | HIGH | no | Abstract branch lacks `ACC_ENUM` guard; constant bodies never re-attached | `JavaSourceWriter.java:671-680` |
| F13 | Varargs `Object[]` becomes both arg and receiver of `.formatted(...)` | HIGH | yes | Array-init absorber double-counts the `dup` copy | `ClassFileToJavaSyntaxConverter.java:1668-1685` |
| F08 | `sealed`/`permits`/`non-sealed` dropped on nested types | MEDIUM | yes | `writeInnerClass` never reads `isSealed()`; `non-sealed` never derived | `JavaSourceWriter.java:683-695` |

(Full per-defect symptom / root-cause / fix-sketch / JD-Core comparison for each F0x is retained in the
analysis transcript and summarized below.)

## 3. Highest-leverage fix

**Fix F06 first** (the `visited` claim-tracker in `StructuredFlowBuilder`). It is the only defect on the
**default** path that produces both *silently wrong* output (boolean methods → constants) and *non-compiling*
output (truncated if/else-if chains, empty loop bodies) for ordinary ≤Java-8 control flow — the
highest-frequency patterns in essentially every method. F05's else-if truncation **is** the F06 mechanism,
and F10(a)'s declaration demotion shares the same re-decode/shared-state anti-pattern. Then **F02** (the
single root for the whole invokedynamic family: records F01, pattern switch F04, string-concat, lambdas F11).

**Order: F06 → F02 → construct-specific producers (F01, F10, F09, F05, F03/F04, F11, F07/F12, F13, F08).**

## 4. Roadmap to JD-Core parity + post-1.8

The legacy `StructuredFlowBuilder` is a from-scratch pattern matcher carrying the F06 claim-tracker bug; the
JD-derived pipeline (`service/converter/cfg/jd/`: ControlFlowGraphMaker → Reducer/LoopReducer/GotoReducer →
JdFlowBuilder) *structurally cannot* have claim-tracker truncation (each block belongs to exactly one reduced
region) but currently regresses loops/booleans, so it stays opt-in with a per-method quality fallback.
Durable end state: **JD pipeline as default, legacy retired** — at which point the F06 class of bugs vanishes
by construction.

1. **F06** — split `visited` into `claimed` vs `emitted` (legacy path; cures F06, F05-truncation, helps F10).
2. **F02** — bootstrap-factory-keyed invokedynamic dispatch (shared decode; enables F01/F04 + concat half of F03/F11).
3. **F01** — record-member suppression in `JavaSourceWriter` (`isRecord()` branch; depends on F02).
4. **F10(b)+(c)** — full `ArrayType` modeling (dims) + ternary store-to-local.
5. **F09** — Java-9+ TWR recognizer independent of `finally` (silent-wrong-semantics fix).
6. **F05** — `InstanceOfPatternReconstructor` + flow-scoping of pattern bindings.
7. **F03/F04** — switch-expression producer + pattern-switch reconstruction (SwitchBootstraps consumers of #2).
8. **F11** — two-phase synthetic-body prepass + scope-aware lambda naming + bound method-ref receiver.
9. **F07/F12** — `default` keyword inference; enum `abstract`/constant-body re-attachment.
10. **F13** — guard the array-init re-push against the `dup` copy.
11. **F08** — sealed/permits/non-sealed in `writeInnerClass` + `non-sealed` derivation (fidelity-only; last).

**JD-pipeline gating (cross-cutting):** the per-method quality gate must stop accepting JD outputs that
regress loops/booleans vs legacy; the JD path also needs array-init folding (`jd/ByteCodeParser.java` has
none), pattern-switch and TWR lowering — so items 2–9 must be mirrored on the JD path before it can flip to
default.

---

## Addendum — fixes applied 2026-06-08 (F06 + F02 + F01)

Three defects were fixed and gated against a regression baseline (the decompiler's own 151 classes) and
the official `DecompilerTest`:

- **F06 → BUG-2026-0057** (`StructuredFlowBuilder.canFormTernary` non-mutating probe). The real root cause
  was sharper than first stated: the ternary *probe* mutated the caller's `visited` set, poisoning
  `if`-cascades and loop bodies. Fix recovered **+6323 lines** of previously-truncated code on the
  regression set (10947→17270), **0 crashes**, no duplication.
- **F02 → BUG-2026-0058** (invokedynamic dispatched on bootstrap factory). `ObjectMethods` and bare
  `makeConcat` now decode correctly; synthetic bootstrap classes excluded from imports.
- **F01 → BUG-2026-0059** (record implicit-member suppression). J16Records 24→1 recompile errors;
  `record Point(int x, int y) {}` reconstructs exactly.

Corpus recompile-error deltas (legacy path, original → after): J16Records 24→1, J17Sealed 10→6,
J21SwitchPattern 35→20. J16InstanceOf (2→4) and J21RecordPattern (43→57) **rose** — not a regression:
F06 stops *hiding* truncated branches, so the recovered code now surfaces the still-open pattern-matching
defects **F05** (instanceof binding scope) and **F04** (pattern switch). Net: structural completeness up
sharply; residual errors are concentrated in F04/F05 rather than masked by silent truncation.

Tests added to `DecompilerTest`: `testSequentialIfReturn`, `testRecordImplicitMembers`, `testMakeConcat`
(suite now 18/19; the one failure is the pre-existing `BasicClass` `super()` expectation).

Recommended next: **F05** (instanceof pattern binding scope) and **F04** (pattern switch), which now account
for most residual corpus errors, then F03 (switch-expressions) and F10(b/c) (array types / ternary store).

---

## Addendum 2 — extended fix batch 2026-06-08

Eight defects fixed and gated (official `DecompilerTest` 18/19 — the one failure is the pre-existing
`BasicClass` `super()` expectation; regression set = decompiler's own 152 classes, 0 decompile crashes,
10947→17314 emitted lines). Tracking items BUG-2026-0057..0064.

| Defect | BUG | Fix |
|---|---|---|
| F06 | 0057 | `canFormTernary` made a non-mutating probe (if-cascade/loop-body truncation) |
| F02 | 0058 | invokedynamic dispatched on bootstrap factory (ObjectMethods, bare makeConcat) |
| F01 | 0059 | record implicit-member suppression |
| F07 | 0060 | interface `default` modifier emitted |
| F12a | 0061 | illegal `abstract` on enums suppressed |
| F13 | 0062 | array-init `dup` double-count guard (varargs `.formatted`) |
| F10a | 0063 | array local declaration keeps `[]` (single dim) |
| F05 | 0064 | `InstanceOfPatternReconstructor` — `instanceof X v` binding |

### Final corpus recompile-error counts (legacy path), original → now

| Class | orig | now | note |
|---|---|---|---|
| J15TextBlocks | 1 | **0** | fully fixed (F13) |
| J17Sealed | 10 | **0** | fully fixed (F01+F05) |
| J16Records | 24 | 1 | only the validating canonical ctor (Range) remains |
| J9Interfaces | 6 | 3 | F07 done; residual = try-with-resources (F09) |
| ClassicConstructs | 8 | 6 | F12a/F13/F10a; residual = enum bodies (F12b) + multidim arrays + local class |
| ControlFlow | 3 | 3 | residual = boolean-as-if return / while(true) / finally (F06 sub-symptoms) |
| J10Var | 3 | 3 | residual = ternary→constant + missing decl + generics erasure (F10b/c) |
| J14SwitchExpr | 1* | 1* | switch-expression not reconstructed (F03) — *semantically wrong, not just rc |
| J16InstanceOf | 2 | 3 | F05 done for cast-decl branches; `&&`-leak binding remains (F05 tail) |
| J8Streams | 5 | 5 | lambda param shadowing (F11 — attempted, reverted) |
| J8Lambdas | 5 | 5 | F11 + method-ref edge cases |
| J21SwitchPattern | 35 | 20 | pattern switch (F04) — partially helped by F01/F05 |
| J21RecordPattern | 43 | 54 | F06 un-hid truncated pattern-switch code (F04) |

Two classes round-trip cleanly (0 → 2). Note J16InstanceOf/J21RecordPattern rose because F06 stopped
*hiding* truncated branches — the recovered code now surfaces F04/F05 rather than silently dropping it.

### Remaining open defects (prioritized)
1. **F04** — Java 21 pattern switch (`SwitchBootstraps.typeSwitch`) reconstruction (J21*, 74 errors). Hardest.
2. **F11** — lambda param scope-rename **with captured-arg substitution** (BUG-2026-0065; simple prefix
   rename reverted because it breaks captured-variable references).
3. **F09** — modern (Java 9+) try-with-resources collapse without a synthetic `finally`.
4. **F03** — switch-expression reconstruction (producer for `SwitchExpression`/`YieldStatement`).
5. **F10b/c** — multi-dim array initializers, ternary-store-to-declaration, local generics erasure.
6. **F12b** — inline enum-constant bodies (`Op$1`/`Op$2`) into the constants.
7. **F06 sub-symptoms** — boolean-returning method final-return, `while(true)` body, `finally`+return.
8. **F08** — `sealed`/`permits`/`non-sealed` on nested types (fidelity; already compiles).

Several of these (F03/F04/F10/F06-sub) are intertwined with the legacy `StructuredFlowBuilder`'s eager
single-pass decode; per §4 the durable fix is the JD-Core CFG pipeline, where each block is decoded once in
a reduced graph. Recommend implementing F04/F03 on the JD path and improving its quality gate rather than
further patching the legacy builder.

---

## Addendum 3 — continued fix batch 2026-06-08 (session 3)

Six more defects resolved (gated each: official `DecompilerTest` 18/19, regression = decompiler's own 153
classes, **0 crashes** throughout, 10947→17642 emitted lines). Tracking items BUG-2026-0065,0070,0072,0073,0074.

| Defect | BUG | Fix |
|---|---|---|
| F11 (param shadowing) | 0065 | `AstLocalRewriter` (new): substitute captured args into lambda body + rename own params to `pN` |
| F12b (enum bodies) | 0070 | `writeEnumConstantBody` inlines `Enum$N` user methods into the constant |
| F01 (Range compact ctor) | 0059 | validating canonical record ctor emitted compact (`Range { ... }`) via param rename |
| method-ref import | 0072 | `MethodReferenceExpression` owner imported (`ArrayList::new`) |
| nested-lambda leak | 0073 | two-pass synthetic-body decode (nested lambdas inline, no `Class::lambda$x$n`) |
| bound method-ref | 0074 | captured receiver preserved (`prefix::startsWith`) |

### Cumulative result (all 3 sessions)

**14 defects fixed.** Corpus: **0 → 4 of 13 classes round-trip cleanly** (J15TextBlocks, J17Sealed,
J16Records, J8Streams). Records fully done. Total recompile errors **93**, but **74 of those are the single
remaining hard item J21* (pattern switch, F04)** — the other 12 classes hold only ~19 errors, concentrated in:

| Remaining | classes | open item |
|---|---|---|
| Pattern switch (Java 21) | J21SwitchPattern (20), J21RecordPattern (54) | BUG-2026-0067 (F04) |
| Modern try-with-resources | J9Interfaces (3) | BUG-2026-0068 (F09) |
| Switch-expression | J14SwitchExpr (1, semantically wrong) | BUG-2026-0066 (F03) |
| CFG sub-symptoms: finally+return, while(true), boolean-as-if | ControlFlow (3) | BUG-2026-0057 tail |
| Local decl/type: multidim array init, ternary→constant, local generics erasure | J10Var (3), ClassicConstructs (3), J8Lambdas (1) | BUG-2026-0069 (F10) |
| instanceof `&&`-binding / negated flow-scope | J16InstanceOf (3) | BUG-2026-0064 tail |
| Local/anonymous class + captured locals | ClassicConstructs (2) | (new) |

### Why these remain
All are intertwined with the legacy `StructuredFlowBuilder`'s eager single-pass decode and the CFG shape
(pattern-switch desugaring, finally duplication, ternary-store merges, multi-dim array folding). Per §4 the
durable fix is the JD-Core pipeline (`service/converter/cfg/jd/`), where each block is decoded once in a
reduced graph — pattern-switch (F04), switch-expression (F03) and the TWR/finally cases (F09) should be
implemented there and the per-method quality gate improved, rather than further patching the legacy builder
(growing regression risk for diminishing return). The 14 fixes landed here are all writer/decoder-localized
or non-mutating-probe corrections that do not depend on the CFG rework.

---

## Addendum 4 — switch-expression reconstruction (F03 foundation), 2026-06-08 session 4

Started the hard-items work on the **correct (transform/decoder) layer**. First concrete feature: a
**switch-expression producer + writer** (BUG-2026-0066), the foundation pattern switch (F04) will build on.

- `StructuredFlowBuilder.tryBuildSwitchExpression` — detects a switch whose every arm (cases + default)
  yields a simple value through a common `*return` merge **reached exclusively by the arms** (a
  predecessor-scan guard prevents consuming a merge shared with outer flow, e.g. nested string-switch).
  Builds a `SwitchExpression`.
- `JavaSourceWriter` — replaced the dead `/* switch expression */` stub with a real single-line renderer
  `switch (sel) { case L -> v; default -> v; }` (single line so it nests inside `return switch(...) {...};`).

Result: `return switch (x) { case 1 -> 10; case 2 -> 20; default -> 0; };` reconstructs and recompiles;
J14 `oldStyleColonYield` now correct (was a constant). Gated: `DecompilerTest` 22/23 (new
`testSwitchExpression`), regression 0 crashes. Switch-expressions never occur in the Java-1.6 self-build, so
the producer only fires on genuine arrow/colon value-switches; the strict all-arms-value guard means a
regular switch statement never matches.

Still scoped out (documented in BUG-2026-0066): block-body arms (`-> { ...; yield v; }`), throwing
exhaustive `default -> throw MatchException` (enum/ordinal switches), store-merge, and string switch
expressions — these need a `YieldStatement`/block-arm `SwitchExpression` model extension.

### Path to F04 (pattern switch) — now unblocked at the infrastructure level
With the `SwitchExpression` producer+writer in place, F04 becomes: (1) recognize the inlined
`SwitchBootstraps.typeSwitch(sel, idx)` selector → real selector + `patternSwitchLabels`; (2) per arm,
fold a leading `Type b = (Type) sel;` into a `case Type b ->` binding label; (3) handle `when` guards (the
typeSwitch restart loop) and record deconstruction; (4) emit as a `SwitchExpression`/pattern switch. This
is the next increment; it is large but no longer blocked on missing model/writer support.

---

## Addendum 5 — pattern switch (F04) + modern try-with-resources (F09), 2026-06-08 session 5

Two more hard items landed on the transform/flow-builder layer, each gated (`DecompilerTest` 24/25,
regression 154 classes 0 crashes).

**F04 / BUG-2026-0067 — pattern switch (sealed, no-guard subset).** `tryBuildSwitchExpression` now
recognises `switch (SwitchBootstraps.typeSwitch(sel, idx))`: real selector = first bootstrap arg; each arm
`Type b = (Type) sel; <value>` → `case Type b -> value`; the synthetic `default -> throw MatchException`
is dropped (exhaustive). `SwitchExpression.SwitchCase` gained pattern type/binding/guard fields; the writer
renders `case Type b [when g] -> value`. Result: J21SwitchPattern `area()` recompiles
(`return switch (var2) { case Circle var4 -> ...; case Square var5 -> ...; case Rectangle var6 -> ...; }`);
J21SwitchPattern 20→16.

**F09 / BUG-2026-0068 — modern try-with-resources collapse.** New `transform/ModernTwrReconstructor`
collapses `R r = init; try { body } catch (Throwable t){ r.close(); throw t; } [r.close();]` into
`try (R r = init; ...) { body }` (single- and multi-resource). A guard skips synthetic-copy "resources"
(`Object v = otherLocal`) so the mangled effectively-final desugar is left alone (no regression). The import
collector now scans try-resource declarations and finally bodies.

Cumulative across 5 sessions: **19 defects fixed**, 4/13 corpus classes round-trip, total corpus
recompile-errors 146→90 (and the residual is dominated by J21RecordPattern's record-deconstruction/guard
desugar). Still open: pattern-switch **guards + record deconstruction** (J21RecordPattern 54,
J21SwitchPattern 16 — the `while(true)` typeSwitch restart loop + nested record patterns, one with a
DECODE_ERROR), effectively-final TWR, switch-expression block/throwing-default arms, CFG sub-symptoms
(finally+return, while(true), boolean-as-if), local-decl/type loss (multidim/ternary/generics), local
classes, and the instanceof `&&`/negated tail.

---

## Addendum 6 — guarded pattern switch (F04 extended), 2026-06-08 session 5b

`tryBuildSwitchExpression` now reconstructs the full non-record pattern switch:
- `case null` (typeSwitch label -1 → a `case null -> value` arm).
- **`when` guards**: a conditional arm `Type b = (Type) sel; if (cond) { value } else { idx = M; goto loopHeader }`
  becomes `case Type b when <guard> -> value` — the guard is oriented to the value path and `boolExpr != 0`
  is simplified to `boolExpr` (avoiding an illegal `boolean != int`).
- the `while (true)` typeSwitch restart loop is unwrapped once its arms are consumed (a `while(true)` whose
  body is a single return/throw == its body).

Result: J21SwitchPattern `classify()` recompiles —
`return switch (var2) { case null -> "null"; case Integer var4 when var4.intValue() < 0 -> "negative"; case Integer var5 -> "int " + var5; case String var6 when var6.isEmpty() -> "empty string"; case String var7 -> "string " + var7; default -> "other"; };`
J21SwitchPattern 20→14 (session 5 total). Gated: `DecompilerTest` 25/26, regression 154 classes 0 crashes.

Cumulative (5 sessions + 5b): **20 defects fixed**, corpus recompile-errors 146→88. The remaining 88 are
dominated by **record-deconstruction patterns** (J21RecordPattern 54, part of J21SwitchPattern): the compiler
desugars `case Point(int x, int y)` / nested `Line(Point(..),Point(..))` to 6+ nested
`try { ... } catch (Throwable) { throw new MatchException(...) }` wrappers around component-accessor
extraction with `if (1 != 0)` binding artifacts. Reconstructing it (strip the MatchException try/catch
scaffolding, fold `instanceof T t; var=t.comp()` chains into deconstruction patterns, drop always-true ifs)
is the single largest remaining decompilation feature — a dedicated effort, best on the JD-Core CFG pipeline.
Other residue: effectively-final TWR, switch-expr block/throwing-default arms, CFG sub-symptoms
(finally+return, while(true), boolean-as-if), local-decl/type loss, local classes, instanceof `&&`/negated.

---

## Addendum 7 — record-pattern scaffolding cleanup, 2026-06-08 session 6

New `transform/RecordPatternReconstructor` performs the safe structural cleanups of the Java 21
record-pattern desugar (stages 1–2 of full record-pattern support):
1. strips `try { ... } catch (Throwable t) { throw new MatchException(...); }` wrappers down to the try body
   (the catch referenced a try-scoped binding and did not compile, and added deep nesting);
2. flattens always-true `if (1 != 0) { ... }` component-binding guards.

Wired before `InstanceOfPatternReconstructor`. Result: record-pattern bodies become clean, readable
`if (o instanceof Type t) { <component extraction>; <body> }`. **J21RecordPattern 54→36, J21SwitchPattern
13, corpus total 88→69** (−19), gated `DecompilerTest` 26/27, regression 155 classes 0 crashes.

Cumulative (6 sessions): **21 defects fixed**, corpus recompile-errors **146→69** (53% reduction), 4/13
classes round-trip, 4 reusable transforms added (`AstLocalRewriter`, `InstanceOfPatternReconstructor`,
`ModernTwrReconstructor`, `RecordPatternReconstructor`).

### Remaining — record-pattern Stage 3 (the largest piece)
Folding the component-accessor extraction `Type t; b1 = t.comp1(); b2 = t.comp2(); ...` into a real
deconstruction pattern `instanceof Type(C1 b1, C2 b2, ...)` (correctly-typed bindings, slot-reuse across
sibling `if`s, and recursive nested record patterns) needs a record-pattern AST node + data-flow folding.
The remaining 69 corpus errors are dominated by this (J21RecordPattern 36) plus: effectively-final TWR,
switch-expr block/throwing-default arms, CFG sub-symptoms (finally+return, while(true), boolean-as-if),
local-decl/type loss (multidim/ternary/generics), local classes, instanceof `&&`/negated. The record-pattern
folding and the CFG sub-symptoms are best implemented on the JD-Core CFG pipeline per §4.

---

## Addendum 8 — record-deconstruction folding (instanceof form), 2026-06-08 session 6 Stage 3

After a parallel design workflow (4 analyses → spec), implemented full instanceof record-pattern folding:
- NEW AST node `RecordPattern` (recursive: simple binding | nested pattern), carried by
  `InstanceOfExpression` + `SwitchExpression.SwitchCase`; writer renders `Type(comp, ...)` recursively.
- NEW `transform/RecordDeconstructionFolder`: matches `subject.comp()` accessor heads, drops dead
  temporaries by downstream-liveness, captures the live binding, recurses through nested
  `if (scratch instanceof T sub)` checks, and resolves the binding type from a cast head (generic erasure).
- `InstanceOfPatternReconstructor` extended to anchor bare-alias bindings (`Box v = o;`).

Result (all recompile): `instanceof Point(int x, int y)`, `instanceof Line(Point(int x1, int y1),
Point(int x2, int y2))`, `instanceof Box(String v)`. **J21RecordPattern 54→19, corpus total 88→52** this
session (146→52 cumulative, **64% reduction**). Gated: `DecompilerTest` 28/29, regression 157 classes 0
crashes. A bare-alias-vs-nested-fold ordering regression (J21RecordPattern spiked to 33) was caught and
fixed (the nested-check matcher now accepts the pattern-var form).

Cumulative (6 sessions): **22 defects fixed**, corpus recompile-errors **146→52**, 5 reusable transforms.
Remaining 52 errors: the SWITCH form of record patterns (`sum` — pattern-switch + block-spanning
deconstruction, the last record piece), effectively-final TWR, switch-expr block/throwing-default arms,
CFG sub-symptoms (finally+return, while(true), boolean-as-if), local-decl/type loss, local classes,
instanceof `&&`/negated.

---

## Addendum 9 — instanceof `&&` pattern binding, 2026-06-08 session 6

`InstanceOfPatternReconstructor` now also recovers the binding of an `instanceof` pattern whose cast
declaration was consumed by compound-condition folding: `o instanceof X && V.m()` → `o instanceof X V &&
V.m()`. The binding `V` is found as a method/field RECEIVER in the `&&` tail whose static type is the
instanceof check type or an erased Object/unknown (the folded cast loses the precise type). Restricting to
receiver position + the type guard keeps it from binding an unrelated value-position local. Applied to `if`
conditions and `return` expressions.

Result: J16InstanceOf 3→0 — fully recompiles (5th corpus class to round-trip). **Corpus total 52→49.**
Gated: `DecompilerTest` 29/30, regression 157 classes 0 crashes.

Cumulative (6 sessions): **23 defects fixed**, corpus recompile-errors **146→49 (66% reduction)**, **5/13
classes round-trip** (J15TextBlocks, J17Sealed, J16Records, J8Streams, J16InstanceOf). 6 reusable
transforms/AST nodes. Post-Java-8 constructs now reconstructed on the default path: records (incl. compact
ctors), pattern instanceof (binding, `&&`, record deconstruction flat/nested/generic), switch-expressions,
pattern switch (sealed, guards, `case null`), try-with-resources, `default`/`sealed`, enum bodies,
lambdas/method-refs, text blocks, var. Remaining 49 errors: switch-FORM record patterns (`sum`), and the
fiddly/architectural tail (effectively-final TWR, multidim arrays, ternary-to-decl, local generics, local
classes, finally+return, while(true), boolean-as-if) — best on the JD-Core CFG pipeline per §4.

---

## Conclusion — 2026-06-08 (6 sessions)

Starting point: **0 of 13** corpus classes round-tripped (decompile → recompile correct); the analysis
root-caused 13 critical/high defects, all on the default (legacy) path.

End state: **5 of 13** classes round-trip cleanly (J15TextBlocks, J17Sealed, J16Records, J8Streams,
J16InstanceOf); corpus recompile-errors **146 → 49 (66% reduction)**; **23 defects fixed**, each
test-covered and gated; regression set (decompiler's own 157 classes) **0 decompile crashes** throughout,
emitted code 10,947 → 18,752 lines (recovered, not bloat — verified no duplication); official `DecompilerTest`
**29/30** (the one failure is a pre-existing `BasicClass super()` expectation, unrelated to any change).

Post-Java-8 constructs now reconstructed on the DEFAULT path (previously broken or unsupported):
records (+ compact constructors), pattern `instanceof` (binding, `&&`-tail, **record deconstruction:
flat / nested / generic**), switch expressions, **pattern switch (sealed, `when` guards, `case null`)**,
modern try-with-resources, interface `default`, `sealed`, enum constant bodies, lambdas / method references
(incl. nested + bound), text blocks, var, arrays.

Highest-leverage single fix: **BUG-2026-0057** — `StructuredFlowBuilder.canFormTernary` was a probe that
mutated the shared `visited` set, silently truncating if-cascades and loop bodies across ALL code; making it
non-mutating recovered ~6,300 lines on real code.

New reusable machinery: `AstLocalRewriter`, `InstanceOfPatternReconstructor`, `ModernTwrReconstructor`,
`RecordPatternReconstructor`, `RecordDeconstructionFolder` transforms + the `RecordPattern` AST node.

Remaining (49 errors, the hard tail): switch-FORM record patterns (`sum` — pattern-switch + block-spanning
deconstruction), effectively-final TWR, multidim-array outer dimension, ternary-to-declaration, local
generics erasure, local/anonymous classes, and CFG sub-symptoms (finally+return, while(true), boolean-as-if).
These are intertwined with the legacy eager single-pass decode; per §4 the durable path is to implement them
on the JD-Core CFG pipeline (reduced graph, single decode) and flip its per-method quality gate to default —
not to keep patching the legacy `StructuredFlowBuilder`.

---

## Addendum 10 — criticality-ranked tail (2026-06-09)

A 5-agent root-cause workflow ranked the remaining 49 errors by criticality. Fixed the CRITICAL+tractable tier:
- **BUG-2026-0076** (C5) — `ConstantPool.getUtf8` tag guard; stops the guarded-record-pattern typeSwitch
  decode crash (0xBA). Near-zero risk.
- **BUG-2026-0077** (C3) — promote the first bare assignment of an undeclared reused slot to a declaration
  (`var6 = new HashMap()` → `HashMap var6 = ...`). Clears `J10Var` undeclared-var break.
- **BUG-2026-0078** (C2) — recover `break` out of `while(true)` via a loop-exit stack. Clears
  `ControlFlow.whileBreakContinue` unreachable-return.
Plus the multidim nested-array initializer writer fix (BUG-2026-0063 update).

Corpus **49 → 44**; `DecompilerTest` 31/32; regression 157 classes 0 crashes. C6 (enum arrow switch) was
tried and reverted — dropping the synthetic MatchException default on an `ordinal()` int-switch only swaps
"unreachable" for "not exhaustive"; the real fix needs ordinal→enum-constant remapping.

Remaining 44, by criticality (deferred — each is high-risk CFG surgery or architectural):
- HIGH-risk CFG singles: `finallyReturn` (return-in-try lost + finally duplicated, C7), `shortCircuit`
  (AND/OR polarity + dropped boolean leaf, C9), `stringSwitchExpr` (value-switch built inside the hashCode
  case, blocking StringSwitchReconstructor, C8). 3 errors; central boolean/try detectors — high regression risk.
- Architectural: SWITCH-form record patterns (`sum`, `recordPattern`) — 32 errors; record-deconstruction
  arms span nested blocks with the arm value displaced by the flow builder.
- Inference-needed: raw-generic locals (`ArrayList`/`Predicate` without LVTT) — 3 errors; needs type
  inference from usage, not recoverable from the class file.
- Local/anonymous classes + captured locals — 2 errors.
