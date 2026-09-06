# DenzoSOFT Java Decompiler - Roadmap

Planning document for optimizations, structural improvements, stability, compatibility and maintainability.

Current state: **v1.12.0** — construct matrix (Java 1.0–25, 55 top-level
classes) **55/55** at javac-default debug, **53/55** at `-g`, **54/55** at `-g:none`; JDK 25
`java.base` 3,376 classes with 0 errors; ~900 classes/sec single-thread (2,300/s at 4 threads);
47 automated tests. Java 26+ class files decompile on a best-effort basis rather than being refused.

The v1.10.0 claim of "57/57, 0 known silent miscompilations" was measured only at javac's default
debug settings and only with a recompile oracle. The 2026-09-05 audit
(`docs/reports/report-java25-plus-audit.md`) measured the other two debug shapes and a structural
census, and found three silent-semantics defects — all fixed in v1.11.0 — plus a backlog of 30 items.
**Always quote which debug mode a number refers to.**

---

## 1. Completed milestones

- **v1.1–v1.7**: string switch, enum bodies, lambda captures, try-with-resources, pattern switch
  foundations, type annotations, line-number alignment, GUI, batch mode, deobfuscation.
- **v1.8.0**: stack-simulation hardening on real-world uber-jars (Spring Boot 100% decompile-clean),
  decompilation diagnostics blocks.
- **v1.9.0**: post-Java-8 construct reconstruction on the default path (records, instanceof patterns,
  switch expressions value-arm subset, sealed, modern TWR subset), 22 defects closed.
- **v1.10.0**: construct-matrix campaign to 57/57 (silent-miscompilation class eliminated: super-call
  virtualization, iinc value semantics, ternary value-merge, switch break loss, dup-store aliasing,
  record canonical-ctor loss, annotation metadata loss); guarded pattern switch, record deconstruction,
  unnamed `_`, switch expressions (yield/throw/nested), nested synchronized, structural finally dedup,
  erasure-generics recovery (factory table + indy instantiatedMethodType), local/anonymous class
  emission with capture substitution, Java 25 (JEP 512/513), module requires flags, catch bodies with
  control flow, branch declaration hoisting. Verified dominance over official JD-Core 1.3.0
  (57/57 vs 44/57; corpus 7 vs 39+4-no-output errors).

## 1b. v1.12.0

Pattern switch (Java 21+). Statement-form pattern switches kept the raw `SwitchBootstraps.typeSwitch`
dispatch instead of a real `switch` (BUG-2026-0109, `java.base` 28 raw bodies -> 7, all 7 remaining
now flagged with `PATTERN_SWITCH_NOT_RECONSTRUCTED`); qualified enum constants used as case labels
were lost and rendered as the uncompilable `case  _` (BUG-2026-0108).

The audit had filed 0109 as one defect; it was two. The expression form already worked, the statement
form failed only for want of a value merge (its arm bodies were intact, so an AST pass rebuilds it),
and the guarded / multi-label form loses its arm values on the operand stack — that half is
BUG-2026-0123 and needs flow-builder work.

## 1c. v1.11.0

Fixed: handlers deleted without a LineNumberTable (BUG-2026-0100, `catch` 0 -> 15/15 at `-g:none`);
`synchronized` nested in a compound statement dropped (BUG-2026-0101, `java.base` leaked markers
98 -> 0 files, locks 720 -> 932); duplicate declarations with a LocalVariableTable (BUG-2026-0106,
matrix `-g` 44/55 -> 53/55, `java.base` locals re-declared in scope 1,120 -> 15 files, 8,055 -> 28 sites, with BUG-2026-0107); LocalVariableTable scope ranges ignored
so slot-sharing variables collapsed (BUG-2026-0103); the category-2 `dup` family
(BUG-2026-0104, `java.base` STACK_UNDERFLOW 16 -> 7 files); lambda bodies truncated to
`/* inline stmt */` (BUG-2026-0102, `java.base` 36 files -> 0); Java 26+ class files refused outright
(BUG-2026-0118); unconditional per-method disassembly (OPT-0007, batch ~25% faster).

## 2. Known residual imperfections (candidate next items)

Highest value first, from the audit backlog (`docs/tracking/track-bugs.md`):

- **BUG-2026-0123** guarded / multi-label / constant-label pattern switches lose their arm values
  (7 java.base bodies, all flagged). Needs the flow builder to rebuild the stack-carried merge.
- **BUG-2026-0122** try-region membership still uses source lines when they exist.

- **J21SwitchPattern statement-form fallback** (4 corpus errors): statement-form guarded pattern
  switches with stack-carried arm values fall back to raw typeSwitch in rare shapes.
- **J9Interfaces** (3 corpus errors): private interface method invocation shapes.
- **Type annotations on generic type arguments** (`List<@NonNull String>`): parsed, not rendered.
- **Multi-resource effectively-final TWR** (`try (a; b)` without local copies): statement-order
  constraints currently block reconstruction; single-resource form works.
- **Enum-constant labels for ordinal-lowered switch expressions**: int labels + MatchException default
  are emitted (compilable, semantically correct); mapping back to `case CONSTANT ->` needs an
  enum-ordinal resolution pass.
- **Local classes**: emitted as nested members (`_1Local`) instead of inside the enclosing method
  (EnclosingMethod-faithful placement).
- **Cross-unit member-inner constructor call sites**: synthetic outer argument stripped only within
  the same compilation unit.

## 3. Structural / maintainability

- **JD pipeline (cfg/jd/, flag `-Ddenzo.jd.pipeline`)**: now strictly behind the legacy path on every
  corpus class (30 vs 8 errors). Decide: invest to parity or retire to reduce maintenance surface.
- **ClassFileToJavaSyntaxConverter size** (~4k lines): extract decode helpers (iinc/indy/concat,
  synchronized reconstruction) into focused collaborators.
- **Automated semantic-diff harness**: promote the runtime original-vs-recompiled comparison used in
  v1.10.0 verification into a standing test stage (currently matrix classes have only one run() harness).

## 4. Performance / tooling opportunities

- Parallel batch decompilation tuning (thread-pool sizing per class size distribution).
- GUI: search across decompiled JAR, export-all, bytecode side-by-side view.
- CLI: --semantic-check flag (decompile -> recompile -> compare) for CI pipelines.

---

*Last updated: 2026-09-06 (v1.12.0)*
