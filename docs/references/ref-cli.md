# CLI Reference

This document describes the command-line interface for DenzoSOFT Java Decompiler.

---

## Synopsis

```
java -jar denzosoft-decompiler.jar [options] <command>
```

---

## Global Options

| Option | Description |
|---|---|
| `--compact` | Compact output without line number alignment. By default, the decompiler preserves original source line numbers by inserting blank lines. |
| `--show-bytecode` | Show JVM bytecode instructions as comments before each decompiled line, with Java-level explanations (variable names, field accesses, method calls). |
| `--show-native-info` | Show JNI function names and parameter types as comments on native method declarations. |
| `--deobfuscate` | Sanitize obfuscated identifiers to produce compilable output. Java keywords (`do`, `if`, `int`, etc.) get a `_` prefix; illegal characters are replaced with `_`. Applies to variables, fields, methods, and class names. |

Options can appear anywhere on the command line, before or after the command. Multiple options can be combined.

---

## Commands

### Decompile a .class file

```
java -jar denzosoft-decompiler.jar [options] <file.class>
```

Reads the specified `.class` file, decompiles it, and prints Java source code to standard output. Inner classes in the same directory are automatically discovered and loaded.

**Arguments:**
- `<file.class>` -- Path to a compiled `.class` file.

**Example:**

```bash
java -jar denzosoft-decompiler.jar MyClass.class
java -jar denzosoft-decompiler.jar target/classes/com/example/MyClass.class
```

---

### Decompile a class from a JAR

```
java -jar denzosoft-decompiler.jar <file.jar> <class-name>
```

Opens the JAR file and decompiles the specified class. Other classes in the JAR are available for resolution (inner classes, superclasses).

**Arguments:**
- `<file.jar>` -- Path to a `.jar` file.
- `<class-name>` -- Fully qualified class name using `/` separators (no `.class` suffix).

**Example:**

```bash
java -jar denzosoft-decompiler.jar myapp.jar com/example/MyClass
java -jar denzosoft-decompiler.jar lib/gson-2.10.jar com/google/gson/Gson
```

---

### Batch mode

```
java -jar denzosoft-decompiler.jar --batch <file.jar|class-dir> <output-dir>
```

Decompiles all classes in a JAR file or directory tree, writing each decompiled class to a `.java` file in the output directory. Uses a parallel thread pool with one thread per available CPU core.

**Arguments:**
- `<file.jar|class-dir>` -- A `.jar` file or a directory containing `.class` files.
- `<output-dir>` -- Directory where `.java` files will be written. Created if it does not exist.

**Output:**

After completion, prints a summary:

```
Batch decompilation complete:
  Total classes: 150
  Succeeded:     148
  Errors:        2
  Time:          5230 ms
  Failed classes:
    - com/example/Problematic
    - com/example/BrokenClass
```

**Example:**

```bash
java -jar denzosoft-decompiler.jar --batch myapp.jar output/
java -jar denzosoft-decompiler.jar --batch target/classes/ decompiled/
```

---

### Trace mode

```
java -jar denzosoft-decompiler.jar --trace <trace-dir> <file.class>
java -jar denzosoft-decompiler.jar --trace <trace-dir> <file.jar> <class-name>
```

Decompiles a single class with diagnostic tracing enabled. Writes `.trace` files to the specified directory containing detailed information about each decompilation stage. Useful for troubleshooting decompilation issues.

**Arguments:**
- `<trace-dir>` -- Directory where trace files will be written.
- Remaining arguments are the same as single-class or JAR decompilation.

The decompiled source is printed to standard output. A message on standard error confirms the trace file location.

**Example:**

```bash
java -jar denzosoft-decompiler.jar --trace traces/ MyClass.class
java -jar denzosoft-decompiler.jar --trace traces/ myapp.jar com/example/MyClass
```

---

### Version

```
java -jar denzosoft-decompiler.jar --version
java -jar denzosoft-decompiler.jar -v
```

Prints the decompiler version and the maximum supported Java version, then exits.

**Output:**

```
DenzoSOFT Java Decompiler v1.8.0
Supports Java 1.0 through Java 25
```

---

## Exit Codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Error: missing arguments, unsupported file type, decompilation failure, or batch error |

When an error occurs, a diagnostic message is printed to standard error. For `DecompilationException` errors, the diagnostic info includes the pipeline stage where the failure occurred and contextual details.

---

## Error Handling

### DecompilationException

If the decompiler encounters an error during processing, it throws a `DecompilationException` with diagnostic information including:
- The pipeline stage where the error occurred
- The class name being processed
- A description of the issue

This information is printed to standard error.

### General Errors

File not found, I/O errors, and other exceptions print the error message and stack trace to standard error.

---

## Examples

```bash
# Decompile a single class (compact output, default)
java -jar denzosoft-decompiler.jar HelloWorld.class

# Decompile with compact output (no line number alignment)
java -jar denzosoft-decompiler.jar --compact HelloWorld.class

# Decompile with bytecode metadata
java -jar denzosoft-decompiler.jar --show-bytecode HelloWorld.class

# Decompile with JNI info for native methods
java -jar denzosoft-decompiler.jar --show-native-info NativeLib.class

# Decompile a class from a JAR and save to file
java -jar denzosoft-decompiler.jar myapp.jar com/example/Main > Main.java

# Batch decompile a JAR
java -jar denzosoft-decompiler.jar --batch myapp.jar src-decompiled/

# Batch decompile with all options
java -jar denzosoft-decompiler.jar --show-bytecode --show-native-info --batch myapp.jar src-decompiled/

# Batch decompile compiled classes
java -jar denzosoft-decompiler.jar --batch target/classes/ decompiled-src/

# Trace a problematic class
java -jar denzosoft-decompiler.jar --trace /tmp/traces ProblematicClass.class

# Check version
java -jar denzosoft-decompiler.jar --version
```
