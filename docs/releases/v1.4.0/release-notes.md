# Release v1.4.0

## Summary

Resolve all fixable known limitations from v1.3.0.

## Resolved Items

### Limitations Resolved

- **LIM-0004**: Type annotations (Java 8+) — Parsed from RuntimeVisibleTypeAnnotations/RuntimeInvisibleTypeAnnotations attributes and rendered on field types and method return types.
- **LIM-0005**: Pattern matching for switch (Java 21+) — SwitchBootstraps.typeSwitch/enumSwitch detected at invokedynamic level; case type patterns extracted from bootstrap arguments and used to replace integer labels in switch statements.
- **LIM-0008**: Try-with-resources (Java 7+) — Resource variables extracted by matching finally close() targets with preceding variable declarations. Resources rendered in `try(...)` header; compiler-generated Throwable catches filtered out.

### Improvements

- **IMP-2026-0002**: If-else-if chains now render as `else if` instead of nested `else { if ... }`.

### Bug Fixes

- **BUG-2026-0016**: While loops with assignment in condition now reconstruct as `while((line = readLine()) != null)` instead of separate assignment + while.

## Technical Changes

- New classes: `TypeAnnotationInfo`, `RuntimeTypeAnnotationsAttribute`, `PatternSwitchReconstructor`
- Modified: `AttributeParser` (type annotation parsing), `ClassFileToJavaSyntaxConverter` (type annotation extraction, pattern switch detection), `TryCatchReconstructor` (resource extraction), `StructuredFlowBuilder` (assignment-in-condition merging), `JavaSourceWriter` (else-if chains, type annotation rendering, assignment parenthesization)
