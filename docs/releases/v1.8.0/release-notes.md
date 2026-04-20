# Release Notes - v1.8.0

**Date**: 2026-04-20

## Summary

Quality + observability release. Two substantial stack-simulation fixes eliminate virtually all spurious stack-underflow placeholders on real-world Spring Boot bytecode, and a new decompilation-diagnostics system surfaces every silent fallback as an in-source comment so readers can immediately see where decompilation was incomplete and why.

## Headline Results

### Real-world Spring Boot (contrp.be-springboot 22.2.66 + 22.2.65)

| Metric | v1.7.0 | v1.8.0 |
|---|---|---|
| Classes decompiled | 2,803 / 2,803 | 2,803 / 2,803 |
| STACK_UNDERFLOW incidents | 2,824 | **0** |
| Files flagged with decompilation notes | 458 | **0** |

### JDK 25 java.base (3,372 classes)

| Metric | v1.7.0 | v1.8.0 |
|---|---|---|
| Decompilation errors | 0 | 0 |
| Compile errors (non cross-module permits) | 25 files | **4 files** |
| Total compile errors | 100 | 100 (95 cross-module permits — permanent) |

## New Feature: Decompilation Diagnostics (IMP-2026-0002)

Every silent recovery path now records a diagnostic visible in the generated source:

- **Class-level banner** at top of file when any class-scoped issue occurred (skipped inner class, pipeline fallback):
  ```
  // =========================================================
  // WARNING: This class was NOT fully decompiled.
  //   - INNER_CLASS_SKIPPED com/foo/Bar$1 IOException: ...
  // =========================================================
  ```

- **Per-method notes block** immediately before the method body:
  ```java
  void doWork() {
      // === DECOMPILATION NOTES (body may be inaccurate) ===
      //   - STACK_UNDERFLOW pc=172 opcode=0xB6 (ref placeholder used)
      //   - CFG_BUILD_FAILED IllegalStateException -- using linear-scan fallback
      ...
  }
  ```

Recorded event types:
- `STACK_UNDERFLOW pc=N opcode=0xXX` — empty stack forced a placeholder
- `DECODE_ERROR pc=N opcode=0xXX ...` — decoder exception caught
- `CFG_BUILD_FAILED ...` — CFG construction failed, linear-scan fallback used
- `STRUCTURED_FLOW_FAILED ...` — structured-flow builder failed
- `INNER_CLASS_SKIPPED ...` — outer-class-level: nested class could not be loaded

## Bug Fixes

### Stack Simulation (BUG-2026-0050, BUG-2026-0051)

- **Exception handler operand-stack seed**: handler blocks are now pre-seeded with the caught exception reference. Previously the `astore` at handler entry saw an empty stack and substituted `null`, producing `catch (… e) { null = e; ... }`-style bodies.
- **Multi-value block exit stack**: each basic block now snapshots its full exit stack and successors restore it. Previously only the single top-of-stack expression carried across block boundaries, which wrecked compound arithmetic around ternaries. The Lombok `hashCode` pattern `result = result * PRIME + (x == null ? 43 : x.hashCode())` is now reconstructed cleanly.

### Signature / Source-level Output

- **BUG-2026-0043** — malformed `import ::Ljava.lang.foreign.MemoryLayout;`. Root cause: the signature scanner treated a type-parameter name "L" as a class descriptor. Rewrote the scanner to be structural (type-parameter block, bounds, type arguments).
- **BUG-2026-0046** — `<L extends A extends B>` (illegal). The "extends already emitted" flag was never set when a class bound existed, so the first interface bound re-emitted `extends`. Now correctly renders `<L extends A & B>`.
- **BUG-2026-0044** — text-block emission produced `illegal text block open delimiter` / `illegal escape` errors. Added `isTextBlockSafe` guard: only emit text block when content has no trailing `"`, no lone `\r`, no `"""`, no backslashes, no source-level line terminators other than `\n`.
- **BUG-2026-0045** — non-printable and U+0085/U+2028/U+2029 line terminators inside string literals broke compilation. All are now rendered as `\uXXXX` escapes.
- **BUG-2026-0047** — interfaces with static-final fields initialized in `<clinit>` emitted an illegal `static { ... }` block (both at top level and as nested inner classes). Clinit assignments now inline into field initializers; clinit is suppressed entirely in interfaces.
- **BUG-2026-0048** — `package-info` classes rendered as `interface package-info {}` (illegal identifier). Now emits a proper `package X;` declaration with any package annotations.
- **BUG-2026-0049** — class/field names that collide with Java reserved words (e.g., inner class `SystemModules$default`) produced `new SystemModules.default()` (illegal). Reserved-word identifiers are now prefixed with `_` even when `--deobfuscate` is not requested.

## Internal Changes

- `BasicBlock`: added `isExceptionHandler`, `exceptionHandlerType`, `exitStack` fields.
- `ControlFlowGraph`: marks handler-entry blocks during build.
- `ClassFileToJavaSyntaxConverter`: new `popOrUnderflowInt` / `popOrUnderflowRef` helpers replace ~56 inline `stack.isEmpty() ? default : stack.pop()` call sites and centralize diagnostic recording.
- `JavaSyntaxResult.MethodDeclaration`: new `decompilationNotes` field.
- `JavaSyntaxResult`: new class-level `decompilationNotes` field.
- `JavaSourceWriter`: renders banner + per-method notes; `collectSignatureImports` rewritten; reserved-word sanitization applied by `sn` helper.

## Migration Notes

- Output now contains `// WARNING` / `// === DECOMPILATION NOTES ===` comment blocks when the decompiler wasn't able to fully reconstruct a class or method. These are comments only and do not affect compilation of correctly-decompiled code.
- No CLI flags changed.
- No API changes.

## Testing

- Built and tested on JDK 25 (`--release 25 --enable-preview`).
- 15 / 16 tests in `DecompilerTest` pass (the one failure is a test-expectation bug: the test expects `super()` on an implicit call; the decompiler correctly omits it).
- Self-decompile 139 / 139 classes OK.
- `contrp.be-springboot-22.2.66.jar` (1,402 classes): 0 decompilation diagnostics.
- `contrp.be-springboot.22.2.65.jar` (1,401 classes): 0 decompilation diagnostics.

## Known Limitations

See `docs/tracking/track-limitations.md`. Permanent items: cross-module sealed `permits` references (not a decompiler defect — valid Java that requires additional modules to compile), `@Override` (SOURCE retention).
