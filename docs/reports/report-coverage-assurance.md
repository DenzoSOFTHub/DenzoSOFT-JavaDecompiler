# Coverage Assurance Report — JD-Core parity + Java 1.0–25

**Date**: 2026-06-09
**Decompiler build**: corpus 44→25 (post BUG-2026-0079 switch-form record patterns)

## Purpose
Empirically establish how much the decompiler reproduces — both real-world code (JD-Core parity proxy) and a
comprehensive Java 1.0–25 language-construct matrix.

## Methodology
Two independent test populations, both compiled/decompiled with JDK 25 (`/usr/lib/jvm/jdk-25.0.2+10`):

1. **Breadth (real-world)** — 1,674 top-level classes sampled (every 9th) across the JDK 25 `java.base` image
   (27,034 classes total). Metric: decompile crash rate + degraded-output marker rate (DECODE_ERROR,
   STACK_UNDERFLOW, placeholder comments). Recompilation of JDK classes is not feasible (module/package-access
   deps), so the marker rate is the proxy for output quality.
2. **Construct matrix (strict)** — 55 self-contained top-level classes (48 source files) hand-built to densely
   cover Java 1.0–25 constructs. Metric: decompile → **recompile with JDK 25** → 0 errors (exact round-trip).

## Results

### Breadth — real JDK 25 code (1,674 classes)
| Metric | Count | % |
|---|---|---|
| Clean output (no diagnostic markers) | 1,562 | **93.3%** |
| Degraded markers (placeholder/decode) | 112 | 6.7% |
| Empty output | 0 | 0% |
| **Crashes** | **0** | **0%** |

**Strong real-world robustness**: zero crashes, zero empty output, 93% marker-clean on a diverse real sample.

### Construct matrix — strict recompile (55 classes)
| Metric | Count | % |
|---|---|---|
| Recompiles cleanly (round-trip) | 28 | **51%** |
| Recompile errors | 27 | 49% |
| Crashes | 0 | 0% |

The matrix is deliberately harsher than the breadth test (exact recompile vs marker check) and surfaces the
construct-level gaps that real-world sampling averages out.

### Matrix gaps (27 failing classes, by cluster)
- **Lambda / generics**: C_VarLambdaParams(12), C_FunctionalInterfaces(9), C_RecursiveGenerics(4),
  C_StreamsAdvanced(3), C_Generics(1), C_Optional(1), C_GenericRecord(1) — generic-signature erasure +
  lambda-parameter reconstruction.
- **Modern patterns**: C_PatternSwitch(10), C_Unnamed(8), C_Records(5), C_RecordPattern(3), C_SwitchExpr(2),
  C_EnumSwitch(2), C_InstanceofPattern(1) — guarded pattern switch, unnamed `_`, record/enum/switch edges.
- **Annotations**: C_AnnotationUse(6), C_AnnotationDecl(3) — `@interface` elements + annotation use sites.
- **Classic (Java 1–11)**: C_Arrays(5), C_InnerClasses(4), C_Java7TryResources(3), C_Concurrency(2),
  C_ControlFlow(2), C_Exceptions(1), C_EnhancedFor(1), C_Java7StringSwitch(1), C_VarInference(1),
  C_InterfaceMethods(1).
- **Text blocks**: C_TextBlocks(1).

### Debug-info sensitivity (critical nuance)
The matrix was first compiled WITHOUT full debug info (javac default = `-g:source,lines`, no
LocalVariableTable / LocalVariableTypeTable). Re-compiling the same corpus WITH `-g` (full debug, as most
production builds and JD-Core's own test fixtures use) changes the result:

| Compile mode | Matrix recompile |
|---|---|
| default (no LVT/LVTT) | 32/55 (post batch-1) |
| `-g` (full debug) | 33/55 |

Crucially the **failure set shifts**: with `-g` the lambda / functional-interface / generic-local classes
(`C_FunctionalInterfaces`, `C_VarLambdaParams`, `C_Optional`, `C_StreamsAdvanced`) become **clean** — the
decompiler reads the `LocalVariableTypeTable` and recovers `Function<Integer,Integer>` etc. Without `-g`,
generic type arguments on locals are **erased from the class file and unrecoverable by any decompiler**,
JD-Core included — this is a bytecode limitation, not a decompiler gap. (`-g` also surfaces a few separate
LVTT-handling bugs, e.g. `C_ControlFlow`, tracked separately.)

So the construct-level gaps split into two kinds:
1. **Erasure-bound** (generics on locals without `-g`) — parity with JD-Core; not fixable from the class file.
2. **Real construct bugs** (records, try-with-resources, synchronized-return, inner classes, guarded pattern
   switch, unnamed `_`) — fixable, the actual improvement surface.

## Interpretation
- Real-world **parity is strong** (0 crashes / 93% clean on JDK code) — the decompiler robustly handles the
  shapes that dominate production bytecode.
- The construct matrix shows **systematic gaps** under strict recompile, concentrated in generics/lambda
  signature fidelity, modern pattern constructs, annotations, and a few classic edges. Many failing classes
  share a small number of root causes (e.g. generic-signature propagation affects several at once).

## Follow-up
A criticality-ranked fix plan for the 27 matrix gaps is tracked under the decompilation-defect analysis; the
matrix corpus (`/tmp/matrix/src` generator) is the standing assurance suite to re-run after each fix.
