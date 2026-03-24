# Changelog

All notable changes to DenzoSOFT Java Decompiler.

## [1.1.0] - 2026-03-24

### Added
- Java bytecode decompiler supporting Java 1.0 through Java 25 (class file v45-v69)
- CFG-based control flow reconstruction (if/else, while, do-while, for, for-each, switch, ternary, try-catch)
- Compound boolean operators (`&&`, `||`)
- Annotations, generics, records, sealed classes, enums, lambda, module declarations
- String switch reconstruction, string concatenation templates
- Inner class inlining
- Javassist-like API (ClassPool, CtClass, CtMethod, CtField)
- Batch parallel decompilation (`--batch`)
- Trace mode for troubleshooting (`--trace`)
- Performance benchmarking tool
- Security limits and bounds checking
