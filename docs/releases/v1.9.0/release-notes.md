# v1.9.0 — Post-Java-8 constructs + Java 1.0–25 coverage assurance

Major decompilation-quality release. An empirical Java 8→21 construct corpus that round-tripped **0 of 13**
classes now round-trips **5 of 13** with the modern constructs reconstructed on the default path, and a new
Java 1.0–25 construct matrix recompiles **35 of 55** classes. On 1,674 real JDK 25 classes the decompiler is
**0 crashes, 93.3% marker-clean**.

## Highlights

### Post-Java-8 constructs reconstructed on the default path (BUG-2026-0057 … 0078)
- **Records** — implicit members suppressed, compact/validating canonical constructors.
- **Pattern `instanceof`** — bindings, `&&`-tail binding recovery, and **record deconstruction**
  (flat `instanceof Point(int x, int y)`, nested `Line(Point(..), Point(..))`, generic `Box(String v)`).
- **Switch expressions** and **pattern switch** (sealed, `when` guards, `case null`).
- **Modern try-with-resources**, interface `default`, `sealed`, enum constant bodies, lambdas / method
  references (including nested and bound).
- The highest-leverage fix: `StructuredFlowBuilder.canFormTernary` made non-mutating (it was poisoning the
  shared `visited` set and silently truncating control flow) — recovered ~6,300 lines on real code.

### SWITCH-form record patterns on the JD-Core pipeline (BUG-2026-0079, IMP-2026-0063)
`switch (o) { case Line(Point(int x, int y), ...) -> ... }` now reconstructs exactly. Solved the JD pipeline's
decode-once value-merge limitation (return tail-duplication + dead-guard elimination + a typeSwitch arm folder),
with **selective activation** that routes only `typeSwitch`+`MatchException` methods to JD and keeps the legacy
path (byte-identical) for everything else.

### Java 1.0–25 construct-matrix gap fixes (BUG-2026-0080)
Annotations (`@interface`, nested annotation types), multidimensional arrays, array-foreach element types,
`Double.NaN`/`Infinity` literals, record canonical-constructor access, for-loop variable scope, catch-variable
rename recursion, switch-case variable hoisting, and boolean-comparison simplification inside call arguments.

### Coverage assurance
New `docs/reports/report-coverage-assurance.md` + a standing `src/test/resources/construct-matrix/` suite
(Java 1.0–25). Documents the debug-info nuance: generic types on locals are erased without `-g` — a bytecode
limitation shared with JD-Core, not a decompiler defect (with `-g` those classes recompile cleanly).

## Quality
- DecompilerTest 32/33 (the one failure is a pre-existing `BasicClass` expectation).
- Regression suite: the decompiler's own 160 classes, **0 decompile crashes**.
- Zero net regressions across the release; every fix gated against the corpus + regression.

## Tracking
BUG-2026-0057 … 0080, IMP-2026-0063. Full analysis in
`docs/reports/report-decompilation-defect-analysis.md`.
