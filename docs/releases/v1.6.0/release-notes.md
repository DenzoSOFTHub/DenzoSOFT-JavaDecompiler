# Release Notes - v1.6.0

**Date**: 2026-03-27

## Summary

Major release focused on obfuscated class decompilation, compilability of decompiled output, and inline bytecode disassembly. The decompiler now produces compilable Java code from obfuscated classes and shows JVM bytecode instructions alongside decompiled source.

## New Features

### Deobfuscation (`--deobfuscate`)
- Java keywords used as identifiers (e.g., `do`, `if`, `int`) are sanitized with `_` prefix
- Illegal characters in identifiers replaced with `_`
- Consistent variable naming when LocalVariableTable is stripped (obfuscated classes)
- Applied to variables, fields, methods, and class names

### Inline Bytecode Disassembly (`--show-bytecode`)
- Each decompiled line is preceded by its corresponding JVM bytecode instructions as comments
- Instructions include Java-level explanations: variable names, field accesses, method calls, arithmetic operations
- Example: `// 0: aload_0  // push ref this`

### GUI Options Menu
- New "Options" menu with checkboxes: Compact, Show Bytecode, Show Native Info, Deobfuscate
- Options take effect on next class decompilation

### Default GUI Launch
- Running the JAR without arguments now launches the GUI instead of showing usage
- CLI mode available by passing file arguments

## Bug Fixes

### Compilability (20+ fixes)
- Variable naming: `arg0` in signature now matches `arg0` in body when LocalVariableTable is absent
- Import collector: traverses all types in fields, methods, body expressions, generic signatures, inner classes
- Multi-dimensional arrays: `new T[n][]` instead of `new T[][n]`
- Array initializers: `new int[]{1,2}` instead of `new int{1,2}`
- Inner class types: use `Outer.Inner` dot notation, import outer class only
- Generic signature parser: preserves inner class qualifiers
- Boolean/int: `false`/`true` for boolean fields and method parameters
- Char/int: cast to `(char)` for char method parameters
- Batch mode: inner classes resolved via shared Loader, not decompiled separately
- Anonymous classes: body methods inlined, `this$0`/`val$xxx` synthetic fields resolved
- Synthetic `access$NNN` methods suppressed in instance calls

## Test Results
- JDK 25: 15,071 top-level classes, 0 decompilation errors
- Obfuscated classes (no debug info, single-letter names, keyword identifiers): 0 compilation errors
- Project self-decompilation: 130/135 top-level files compile (96%)
