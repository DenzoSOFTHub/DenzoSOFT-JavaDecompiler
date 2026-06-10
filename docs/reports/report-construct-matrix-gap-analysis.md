# Report: Construct-Matrix Gap Analysis (Java 1.0-25)

**Date**: 2026-06-10
**Method**: 63-agent verified analysis — per-class root-causing with adversarial verification of every claim,
semantic audit of all recompile-clean classes, re-verification of all open tracked bugs.
**Baseline**: construct matrix **37/57** recompile-clean, 0 crashes (extended with Java 25 classes
C_FlexibleCtor [JEP 513 flexible constructor bodies] and C_ImplicitMain [JEP 512 compact source files]).
Corpus 21 recompile errors; DecompilerTest 32/33; regression 160 classes 0 crashes.

**Headline result**: the matrix number UNDERSTATES the defect surface — the semantic audit found
**11 verified silent miscompilations** in classes that recompile cleanly, 5 of them CRITICAL
(super→this infinite recursion, post-increment wrong value x3, ternary branch deletion).
Recompile-clean does not mean correct; the fix plan below prioritizes silent-wrong-code first.

## Verified silent miscompilations (recompile-clean classes)

| Class | Severity | Defect |
|---|---|---|
| C_Circle | CRITICAL | Super call silently converted to virtual self-call, producing infinite recursion. Bytecode is 'invokespecial C_Shape.describe' (a super.describe() call), but the decompiler emitted |
| C_AnnotationDecl | HIGH | @Retention(RetentionPolicy.RUNTIME) dropped from all three nested annotation types (Marker, Name, Config). The bytecode for each annotation class carries RuntimeVisibleAnnotations  |
| C_AnnotationDecl | HIGH | All AnnotationDefault values on Config dropped: author() default "unknown", version() default 1, tags() default {}, targetKind() default ElementType.METHOD. The class file contains |
| C_AnnotationDecl | MEDIUM | @Target meta-annotations dropped from all three nested annotation types (Marker: TYPE; Name: METHOD/FIELD/PARAMETER; Config: TYPE/METHOD/PARAMETER) despite being present in the byt |
| C_FlexibleCtor$Sub | CRITICAL | Ternary condition and else-branch silently dropped in the Sub(int, long) constructor. Original computes the absolute value of 'stamp' (bytecode confirmed: lcmp/ifge selecting betwe |
| C_Java7StringSwitch | HIGH | All `break` statements dropped from the reconstructed string switch in category(String). Every case arm now falls through to the next, so the assignments cascade: "add"/"sub" assig |
| C_Operators | CRITICAL | Post-increment in return position returns the wrong value. Original 'return a++;' must return the OLD value (bytecode: iload_1; iinc 1,1; ireturn), but the decompiled code incremen |
| C_Operators | CRITICAL | Post-decrement in return position returns the wrong value, same defect as postIncrement. Original 'return a--;' returns the OLD value; decompiled output decrements first and return |
| C_Operators | CRITICAL | Mixed pre/post increment inside an expression is hoisted incorrectly: 'int r = a++ + ++a;' evaluates to a + (a+2) = 2a+2 (the left operand is the pre-increment value, per bytecode  |
| C_Sealed | MEDIUM | All sealed-hierarchy modifiers are silently dropped: 'sealed interface Expr permits Lit, Add, Neg, Wild', 'sealed static abstract class Shape permits Square, Triangle', and 'non-se |
| C_TextBlocks | HIGH | Silent loss of trailing whitespace in a text-block string constant. The original method escapes() uses '\s\s' to keep two trailing spaces, so the compiled string constant contains  |

## Open-bug re-verification (current build)

| ID | Still reproduces | Notes |
|---|---|---|
| BUG-2026-0052 | NO — closed | The item file is no longer in docs/tracking/items/; it was moved to docs/releases/v1.9.0/BUG-2026-0052.md (released in v1.9.0), although its Status header still reads TO_ANALYZE — header was apparentl |
| BUG-2026-0053 | YES | Item file is at docs/releases/v1.9.0/BUG-2026-0053.md (not docs/tracking/items/) but is still marked Status: TO_ANALYZE — it appears to have been swept into the v1.9.0 release directory without ever b |
| BUG-2026-0054 | NO — closed | Item file now lives at /workspace/DenzoSOFT-JavaDecompiler/docs/releases/v1.9.0/BUG-2026-0054.md (moved into the v1.9.0 release dir, though its Status header still says TO_ANALYZE). Verdict basis: the |
| BUG-2026-0055 | NO — closed | The original symptom (mid-method `new X(array)` split into an illegal `super(array)` plus a type-mismatched array assignment) does not reproduce on the current build; the fix appears released in v1.9. |
| BUG-2026-0056 | YES | Partially fixed — symptom changed shape. Of the two original symptoms: (2) "post-try code dropped / method ends without return" is FIXED (trailing println + return now emitted). (1) "catch body trunca |
| BUG-2026-0066 | YES | Item is marked IN_PROGRESS (partial fix in v1.9.0) and the verification matches that status, but the symptom has partially changed shape. (1) Simple value-arm return-merge switches (the resolved subse |
| BUG-2026-0067 | YES | Partially fixed; symptom narrowed but the original headline leak persists. The item file (now at docs/releases/v1.9.0/BUG-2026-0067.md, self-declared "switch form open") matches reality: type patterns |
| BUG-2026-0068 | YES | Partially fixed, matching the item's own IN_PROGRESS status. Fixed: single typed resource (try (R r = init) {...}) collapses correctly (readFirst, useCustom). Still broken: (1) multi-resource typed fo |
| BUG-2026-0069 | YES | Verdict: still reproduces, partially reshaped. Of the 4 symptoms: (c) ternary-store-to-declaration -> constant reproduces EXACTLY (worst: silently wrong semantics, also with -g); (a) reused-slot assig |
| BUG-2026-0071 | YES | Symptom is unchanged in shape: exactly as described (sealed/permits/non-sealed silently dropped on nested types; output still recompiles, so fidelity-only). Bookkeeping anomaly: the item file is not i |

## New tracked items created from this analysis

BUG-2026-0081..0098 (one per root-cause cluster, full analysis + fix approach in each item file)
plus BUG-2026-0099 (module-info requires flags, found in the same sweep). Index: docs/tracking/track-bugs.md.

---


# Decompiler Fix Plan — Construct Matrix v1.10 Campaign

Baseline: construct matrix **37/57** recompile-clean (20 failing), regression suite 160 classes 0 crashes, corpus 21, DecompilerTest 32/33. All findings below were adversarially verified (rejected claims excluded; corrections incorporated). Guiding principle: **silent miscompilation outranks recompile failures** — a class that recompiles but computes wrong values is worse than one javac rejects.

## 1. Cluster table

| # | Cluster | Classes / repros | Severity | Effort | Tracked as |
|---|---------|------------------|----------|--------|------------|
| 1 | super-call-virtualized | C_Circle | CRITICAL | LOW | NEW |
| 2 | iinc-postfix-expression | C_Operators (x3) | CRITICAL | MEDIUM | NEW |
| 3 | ternary-value-merge | C_FlexibleCtor, C_Records; BUG-2026-0053/0055/0066(5)/0069(c) shapes | CRITICAL | MEDIUM | consolidate NEW (refs 0053/0069) |
| 4 | wide-opcode-decode | C_Exceptions (+any iinc_w code) | HIGH | LOW | NEW |
| 5 | switch-break-emission | C_Java7StringSwitch (silent), C_ControlFlow | HIGH | MEDIUM | NEW under BUG-2026-0080 |
| 6 | loop-exit-branch | C_ControlFlow (silent body loss); helps BUG-2026-0056 | HIGH | MEDIUM | NEW |
| 7 | dup-store-alias | C_Java7TryResources copy() (silent double-read) | HIGH | LOW | NEW |
| 8 | textblock-trailing-ws | C_TextBlocks (silent value change) | HIGH | LOW | NEW |
| 9 | record-canonical-ctor | C_Records (silent clamp loss) | HIGH | MEDIUM | NEW (0080 umbrella) |
| 10 | annotation-metadata | C_AnnotationDecl/C_AnnotationUse (silent reflection loss) | HIGH | MEDIUM | NEW |
| 11 | finally-dedup | C_Concurrency, C_Exceptions | HIGH | LOW | NEW (0080 umbrella; rel. 0056) |
| 12 | synchronized-reconstruction | C_Concurrency | HIGH | MEDIUM | NEW (0080 umbrella) |
| 13 | cast-precedence | C_Generics, C_RecursiveGenerics | HIGH | LOW | NEW (sibling of released 0052) |
| 14 | signature-unread | C_RecursiveGenerics, C_RecordPattern (Box<T>) | MEDIUM | LOW | NEW |
| 15 | sealed-permits | C_Sealed (silent), C_PatternSwitch prereq | MEDIUM | MEDIUM | BUG-2026-0071 |
| 16 | pattern-switch-folding | C_PatternSwitch, C_RecordPattern, C_Unnamed | HIGH | MEDIUM | BUG-2026-0067 |
| 17 | compound-cond-stmt-drop | C_InstanceofPattern, C_RecordPattern (isDiagonal) | HIGH | MEDIUM | NEW |
| 18 | switch-expression-reconstruction | C_EnumSwitch, C_SwitchExpr | HIGH | HIGH | BUG-2026-0066 |
| 19 | twr-reconstruction | C_Java7TryResources | HIGH | MEDIUM | BUG-2026-0068 (absorbs 0054 residue) |
| 20 | erasure-generics (staged) | C_VarInference, C_Optional, C_StreamsAdvanced, C_FunctionalInterfaces, C_VarLambdaParams, C_Unnamed | HIGH | HIGH | BUG-2026-0069(d)/LIM-0002/0080 |
| 21 | local-slot-typing | C_Unnamed; BUG-2026-0069(a) | MEDIUM | MEDIUM | BUG-2026-0069 partial |
| 22 | inner-classes-captures | C_InnerClasses, C_InterfaceMethods | HIGH | HIGH | NEW (0080 umbrella) |
| 23 | requirenonnull-leak | C_FunctionalInterfaces, C_Optional (cosmetic) | COSMETIC | LOW | NEW |

Cross-cutting dedup notes: the Objects.requireNonNull leak appears in 3 classes (one mechanism, cluster 23); the cast-precedence bug appears in 2 classes (one writer site); the compound-&&-merge statement drop appears in C_InstanceofPattern AND C_RecordPattern.isDiagonal (one StructuredFlowBuilder site, 3 merge branches); the missing-break defect is identical in C_ControlFlow and the silent C_Java7StringSwitch corruption; the raw-generic-local family spans 6 classes and 3 distinct repair points (for-each back-prop, factory table, indy instantiatedMethodType).

## 2. Fix order and rationale

### Phase A — silent-miscompilation batch (steps 1–10, no matrix movement except C_Records)
These ship first because the classes **already pass** the matrix while producing wrong code — every day they stay broken, the matrix number overstates quality.

1. **super-call-virtualized** (LOW): `this.describe()` instead of `super.describe()` = StackOverflowError. One flag + writer branch.
2. **iinc-postfix-expression** (MEDIUM): `return a++` returns the wrong value; three runtime-proven divergences. Stack-alias replacement + pending-prefix fuse in `case 0x84`. Must not disturb for-loop increment shape (stack is empty there) — guard verified by design.
3. **ternary-value-merge** (MEDIUM): the single most dangerous cluster — conditions are silently deleted (C_FlexibleCtor abs(), BUG-2026-0053 'All' branch, BUG-2026-0055 JComboBox variant) or consumers dropped (C_Records `new Slope(...)`). Generalize `replaceTernaryInMergeStatements` into a recursive consumer rewriter. **Matrix +1 (C_Records) → 38**.
4. **wide-opcode-decode** (LOW): `i += 1000` vanishes from ANY method today. Decode-level, near-zero risk.
5. **switch-break-emission** (MEDIUM): C_Java7StringSwitch silently returns "unknown" for all inputs. PC-order case grouping + next-target bounding + break-on-goto-to-merge.
6. **loop-exit-branch** (MEDIUM): labeledContinue's whole inner body lost but compiles (infinite loop). Also hardens the silent both-successors-dropped path that underlies BUG-2026-0056's catch-conditional loss. With step 5: **matrix +1 (C_ControlFlow) → 39**.
7. **dup-store-alias** (LOW): `while ((b=in.read())!=-1)` reads the stream twice, writes stale bytes. Identity-checked, self-contained.
8. **textblock-trailing-ws** (LOW): different string constant after round-trip.
9. **record-canonical-ctor** (MEDIUM): user validation/clamping code in canonical ctors silently deleted.
10. **annotation-metadata** (MEDIUM): RUNTIME retention/targets/defaults dropped → reflection silently breaks, annotation uses uncompilable.

### Phase B — cheap recompile unblocks (steps 11–14)
Ranked by classes-unblocked-per-effort.

11. **finally-dedup** (LOW): structural matching instead of count truncation; afterTry leading-dup strip; filterFinallyBody double-strip fix. With step 4: **matrix +1 (C_Exceptions) → 40**.
12. **synchronized-reconstruction** (MEDIUM): balanced monitor pairing + the 1-line static-synchronized fix. With step 11: **matrix +1 (C_Concurrency) → 41**.
13. **cast-precedence** (LOW): one writer parenthesization fix clears C_Generics entirely. **+1 → 42**.
14. **signature-unread** (LOW): call the already-written SignatureParser class-header functions; record-component Signature. With step 13: **matrix +1 (C_RecursiveGenerics) → 43**. Also fixes the silent `Box<T>(Object value)` divergence.

### Phase C — pattern/switch machinery (steps 15–18)
15. **sealed-permits** (MEDIUM, BUG-2026-0071): fixes the C_Sealed silent contract loss and is a hard prerequisite for 16 (no-default exhaustive switches need a sealed hierarchy to recompile — demonstrated on BUG-2026-0067's repro where perfectly reconstructed switches still failed).
16. **pattern-switch-folding** (MEDIUM, BUG-2026-0067): TypeSwitchRecordFolder default-skip + tail reclaim (patch already validated end-to-end), RecordDeconstructionFolder accessorType + dead-binding `_` components, bare-value `case Type _` arms, SwitchVarHoister type guard. **Matrix +1 (C_PatternSwitch) → 44**.
17. **compound-cond-stmt-drop** (MEDIUM): empty-statements guard on all three merge branches + pattern-cast rebind + constant-return re-materialization. **Matrix +2 (C_InstanceofPattern; C_RecordPattern with step 16) → 46**.
18. **switch-expression-reconstruction** (HIGH, BUG-2026-0066): non-pattern MatchException acceptance + ordinal→constant mapping (needs a loader callback into StructuredFlowBuilder), yield-block arms (SwitchCase.bodyStatements model extension), per-switch merge voting + StringSwitchReconstructor extension. Both EnumSwitch sub-fixes must land together (verified dependency). Also covers the 0066(5) store-merge regression. **Matrix +2 (C_EnumSwitch, C_SwitchExpr) → 48**.

### Phase D — TWR and type inference (steps 19–21)
19. **twr-reconstruction** (MEDIUM, BUG-2026-0068): exception-entry coalescing + in-place nested reconstruct. **Matrix +1 (C_Java7TryResources) → 49** (step 7 already fixed its silent loop defect).
20. **erasure-generics** (staged): Stage A ForEachDetector back-prop → **+1 (C_VarInference) → 50**; Stage B factory table → **+2 (C_Optional, C_StreamsAdvanced) → 52**; Stage C indy instantiatedMethodType → **+2 (C_FunctionalInterfaces, C_VarLambdaParams) → 54**.
21. **local-slot-typing** (MEDIUM): boolean istore inference + slot-reuse fresh names. With step 16: **matrix +1 (C_Unnamed) → 55**.

### Phase E — structural heavy lifting (steps 22–23)
22. **inner-classes-captures** (HIGH): local-class loading, val$ capture substitution, member-inner outer-param stripping. **Matrix +2 (C_InnerClasses, C_InterfaceMethods) → 57/57**.
23. **requirenonnull-leak** (LOW, cosmetic): ride-along cleanup; also de-noises the step-22 ctor output.

## 3. Parameterized-type AST extension dependency

Only these need new type-model machinery (a `ParameterizedObjectType extends ObjectType` carrying a field-signature string, or systematic reuse of the existing `VariableDeclarationStatement.setGenericSignature` channel from BUG-2026-0065, rendered via `SignatureParser.parseFieldSignature` at JavaSourceWriter:2206-2213):
- **Cluster 20 Stage C** (indy instantiatedMethodType → `Function<Integer,Integer>` locals) — hard requirement.
- **Cluster 20 Stages A/B** — NO new AST needed; the setGenericSignature channel suffices.
- **Cluster 18** needs a different, smaller model extension: `SwitchExpression.SwitchCase` gains `bodyStatements`, a throw-arm flag, and (with cluster 16) a pattern-binding label kind.
- Clusters 13/14 need no AST change (pure emission).

## 4. Risks and regression strategy

- **Highest blast radius: StructuredFlowBuilder switch rework (steps 5, 18)** — touches every tableswitch/lookupswitch. Precedent: the batch-5 SwitchVarHoister change dropped the corpus 25→21. Mitigation: land step 5 (statement-switch ordering/breaks) and step 18 (expression reconstruction) as separate commits, each gated on full matrix + 160-class regression + corpus + DecompilerTest; the new break emission must only fire on verified goto-to-merge terminals.
- **finally-dedup (step 11)**: removeDuplicatedFinally carries the BUG-2026-0021 guard history. Structural equality matching is strictly more conservative than count truncation, but run the corpus to confirm no try/finally regressions; keep the old behavior behind the match-failure path.
- **iinc fusion (step 2)**: risk of breaking ForLoopDetector/CompoundAssignmentSimplifier shapes that expect trailing `var++` statements — fusion only triggers when a stack alias exists, which is never true at loop-tail iinc; add explicit for-loop regression tests.
- **Exception-entry coalescing (step 19)**: could mis-merge genuine nested user try regions; key the filter strictly on `entry.startPc == otherEntry.handlerPc` + identical (handlerPc, catchType).
- **sealed modifier inference (step 15)**: emitting `non-sealed` requires sibling-hierarchy analysis; restrict to nested results where the full hierarchy is visible in the same outer result, else omit permits entirely (raw but compilable) rather than emit a wrong modifier.
- **Capture substitution (step 22)**: writer-state scoping for nested anonymous classes — follow the BUG-2026-0044-2 save/restore pattern.
- **Process (per CLAUDE.md)**: each cluster gets a dedicated item file in `docs/tracking/items/` before implementation, START_CHANGE/END_CHANGE tags, and a regression test in DecompilerTest; every step runs `mvn clean compile`, DecompilerTest, the 57-class matrix, the 160-class regression sweep, and the corpus round-trip before merge.

## 5. Tracking housekeeping (do first, zero code)

- **Close as stale**: BUG-2026-0052 (fixed in v1.9.0, verified no-repro), BUG-2026-0054 (original symptom fixed; residual scoping shapes are BUG-2026-0068 territory — reference them there), BUG-2026-0055 (fixed; the ternary-ctor-arg stress variant belongs to cluster 3's new item). Update their Status headers (currently still TO_ANALYZE despite shipped fixes) and remove them from `track-bugs.md`.
- **Repair the v1.9.0 sweep anomaly**: BUG-2026-0053, 0056, 0066, 0067, 0068, 0069, 0071 sit in `docs/releases/v1.9.0/` while still open in the index. Move the still-open items back to `docs/tracking/items/` (or create explicit follow-up items referencing the partial v1.9.0 fixes) so the index and items directory are consistent again.
- **BUG-2026-0080** stays the matrix umbrella; link each new cluster item to it and update its remaining-clusters section as steps land.

## 6. Expected end state

| Milestone | Matrix | Silent-divergence fixes |
|---|---|---|
| Phase A done | 39/57 | 11 verified divergences eliminated (C_Circle, C_Operators x3, C_FlexibleCtor, C_Java7StringSwitch, C_TextBlocks, C_AnnotationDecl x3, C_Records ctor) |
| Phase B done | 43/57 | + C_Sealed pending |
| Phase C done | 48/57 | + C_Sealed, Box<T> |
| Phase D done | 55/57 | — |
| Phase E done | 57/57 | all known divergences closed |
