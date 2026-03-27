# Release Notes - v1.7.0

**Date**: 2026-03-27

## Summary

Major quality release: 99.7% compilability on 6,372 JDK 25 classes, GUI multi-tab navigation with WAR/EAR/APK support, and advanced deobfuscation transformer.

## Compilability (99.7%)

Verified on full JDK 25 (java.base + other modules):

| Test Set | Classes | Compile OK | Rate |
|---|---|---|---|
| java.base | 3,372 | 3,355 | 99.5% |
| Other JDK modules | 3,000 | 2,996 | 99.9% |
| **Total** | **6,372** | **6,351** | **99.7%** |

## New Features

### GUI Multi-tab Navigation
- Content tab: full archive tree
- Classes tab: application classes only (strips WEB-INF/classes, BOOT-INF/classes)
- Libraries tab: JAR files from lib directories

### Archive Format Support
- WAR: WEB-INF/classes, WEB-INF/lib
- EAR: embedded modules, lib/
- Spring Boot: BOOT-INF/classes, BOOT-INF/lib
- APK: DEX class structure parser, DEX Classes tab

### Deobfuscation Transformer (--deobfuscate)
- Encrypted string detection (XOR/shift patterns) with annotation
- Opaque predicate removal (x*x>=0, x*(x+1)%2==0)
- Control flow flattening detection (while/switch state machine)
- Reflection annotation (Class.forName constant targets)
- Return-type overloading rename

## Bug Fixes (11 compilability fixes)

- Enum switch map selector simplified
- Record component fields suppressed
- MONITORENTER/MONITOREXIT as comments
- <=> operator converted to Long/Double/Float.compare()
- Trailing ; stripped from type names
- Numeric inner class names prefixed with _
- Array class literals ([S → short[])
- OuterClass.this qualified reference
- Boolean ternary simplification
- access$NNN body resolution
- access$NNN double emission fixed
