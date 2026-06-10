# DenzoSOFT Java Decompiler - Roadmap

Planning document for optimizations, structural improvements, stability, compatibility and maintainability.

Current state: **v1.10.0** — construct matrix (Java 1.0–25) **57/57** recompile-clean, 0 known silent
miscompilations, JDK 25 breadth 1,674 classes 99.7% marker-clean with 0 crashes, java.base 99.88%
compilable, ~4,000 classes/sec, 38 automated tests.

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

## 2. Known residual imperfections (candidate next items)

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

*Last updated: 2026-06-10 (v1.10.0)*
