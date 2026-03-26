# Release Notes - v1.5.0

**Date**: 2026-03-26

## Summary

CLI output options and line number alignment improvements. The decompiler now preserves original source line numbers by default, and provides flags to control output detail level.

## Changes

### New Features
- **Line-aligned output (default)**: Decompiled code preserves original source line numbers by inserting blank lines, making stack traces match the decompiled source
- **`--compact` flag**: Produces dense, readable output without line-alignment blank lines
- **`--show-bytecode` flag**: Shows bytecode metadata (size, max_stack, max_locals) as a comment at the start of each method body
- **`--show-native-info` flag**: Shows JNI function names and C parameter types on native method declarations

### Bug Fixes
- Fixed Printer `currentLine` tracking: `endLine()` now correctly advances the line position, fixing line number drift in aligned mode

### Internal
- `MethodDeclaration` extended with `bytecodeLength`, `maxStack`, `maxLocals` fields
- Output flags propagated via configuration map through the decompilation pipeline

## Items Included
- IMP-2026-0004: CLI output options and line number alignment fix

## Test Results
- Project classes: 195/195 (0 errors)
- JDK 25 classes: 27,034/27,034 (0 errors)
- All output modes verified (default, compact, show-bytecode, show-native-info)
