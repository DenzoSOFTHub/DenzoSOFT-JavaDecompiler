# DenzoSOFT Java Decompiler

A Java bytecode decompiler supporting **Java 1.0 through Java 25**, with zero external dependencies.

## Overview

DenzoSOFT Java Decompiler is an open-source Java decompiler that reconstructs Java source code from compiled `.class` files. It is designed to handle all Java versions from 1.0 up to Java 25 (class file format version 69).

## Acknowledgements

This project is based on and extends the work of **[JD-Core](https://github.com/java-decompiler/jd-core)**, the Java Decompiler core library originally created by **Emmanuel Dupuy**.

JD-Core is a standalone Java library that reconstructs Java source code from compiled bytecode. It is the engine behind the popular [JD-GUI](https://github.com/java-decompiler/jd-gui) graphical decompiler. The original project is distributed under the **GPLv3 license**.

DenzoSOFT Java Decompiler takes the architectural concepts of JD-Core (pipeline-based decompilation with deserializer, converter, and writer stages) and extends them with a clean-room implementation under `it.denzosoft.javadecompiler`.

We gratefully acknowledge the contributions of Emmanuel Dupuy and all JD-Core contributors for laying the groundwork that made this project possible.

## License

This project is distributed under the **GPLv3 license**.
This is a Copyleft license that gives the user the right to use, copy and modify the code freely for non-commercial purposes.

## Features

### Class File Support
- **Java 1.0 - 25**: Full class file format support (versions 45.0 - 69.0)
- **Constant pool**: All 17 tag types including CONSTANT_Dynamic, CONSTANT_Module, CONSTANT_Package
- **30+ attributes**: Code, Signature, Record, PermittedSubclasses, Module, BootstrapMethods, RuntimeVisibleAnnotations, MethodParameters, NestHost/NestMembers, LocalVariableTable/TypeTable, EnclosingMethod, etc.
- **Preview features detection**: Classes compiled with preview features are flagged in the output

### Control Flow Reconstruction
- **If/else**: Single and chained if-then-else from bytecode branches, including deeply nested if-else chains
- **While loops**: Reconstructed from backward branch + condition header
- **Do-while loops**: Detected from body + backward conditional branch
- **For loops**: Reconstructed from `init + while(cond) { body; update }` pattern
- **For-each (collections)**: Iterator pattern (`iterator()/hasNext()/next()`) detected and converted
- **For-each (arrays)**: Array index pattern (`length + counter + array[i]`) detected and converted
- **Switch statements**: Reconstructed from `tableswitch`/`lookupswitch` bytecode
- **Ternary operator**: `a ? b : c` reconstructed from conditional value-push pattern
- **Try-catch-finally**: Structural reconstruction from exception table entries
- **Multi-catch**: Multiple catch clauses per try region
- **Boolean operators**: `&&` and `||` reconstructed from compound branch patterns

### Type System
- **Generics**: Full reconstruction from Signature attribute (`List<String>`, `Map<K, V>`, `<T extends Comparable<T>>`, wildcards `? extends`, `? super`)
- **Primitive types**: All 8 primitives + void
- **Array types**: Single and multi-dimensional (`int[]`, `String[][]`)
- **Object types**: With simple name resolution

### Java Language Features
- **Records** (Java 16+): `record Point(int x, int y)` with components
- **Sealed classes** (Java 17+): `sealed class Shape permits Circle, Rectangle`
- **Enums**: Constants, methods, fields
- **Annotations**: `@Deprecated`, `@SuppressWarnings`, custom annotations with values (primitives, strings, enums, arrays, nested annotations)
- **Lambda expressions**: Body reconstructed from synthetic methods via BootstrapMethods
- **Method references**: Displayed as `Class::method`
- **String concatenation**: Template-based reconstruction from `makeConcatWithConstants` bootstrap
- **Varargs**: Displayed as `Type...`
- **Static initializers**: `static { }` blocks decompiled
- **Module declarations**: `module-info.java` with requires/exports/opens/uses/provides

### Output Quality
- **Variable declarations**: `int total = 0` merged from separate declaration and assignment
- **Compound assignments**: `total = total + i` simplified to `total += i`
- **Boolean simplification**: `containsKey(key)` instead of `containsKey(key) != 0`
- **Boolean literals**: `true`/`false` instead of `1`/`0` for boolean variables, returns, and assignments
- **Boolean ternary**: `return x > 0 ? 1 : 0` simplified to `return x > 0`
- **Compound `&&`/`||`**: Multiple chained boolean operators: `a != null && !a.isEmpty() && a.length() >= min`
- **Nested ternary**: `val < min ? min : val > max ? max : val`
- **Negation parentheses**: `!(x instanceof Foo)` with correct precedence
- **Redundant cast removal**: Same-type and Object casts suppressed
- **Autoboxing suppression**: `Integer.valueOf(1)` simplified to `1`
- **super() calls**: Displayed as `super()` without `this.` prefix
- **Return suppression**: Trailing `return;` in void methods omitted
- **Static field init merging**: `static final X = value` inlined from `static { }` when possible
- **Generic type variables**: `T item` from LocalVariableTypeTable instead of erased `Comparable item`
- **Proper indentation**: Consistent 4-space indentation at all nesting levels
- **Bridge method suppression**: Compiler-generated bridge methods hidden
- **Native method JNI info**: `// JNI: Java_pkg_Class_method | params: (JNIEnv*, jobject, jint)`
- **Diagnostic comments**: Unhandled opcodes and control flow annotated
- **Assert statements**: `assert condition` and `assert condition : message` reconstructed from `$assertionsDisabled` pattern
- **Constructor delegation**: `this(args)` delegation calls correctly reconstructed
- **Long/double comparisons**: `lcmp`, `dcmpg`, `dcmpl`, `fcmpg`, `fcmpl` properly handled in conditions
- **Cast precedence**: Correct parenthesization of cast expressions in complex expressions
- **Array initializers**: `new int[] {1, 2, 3}` reconstructed from `newarray` + `aastore`/`iastore` sequences

### Performance
- **~4,000+ classes/sec** throughput on typical code
- **Thread-safe**: Converter creates fresh state per decompile call
- **Memory-efficient**: Pre-sized collections, expression caching, short-circuit AST transforms
- **Security limits**: Max bytecode size, max recursion depth, max AST nodes

### Tools
- **Swing GUI**: JD-GUI style graphical interface with tabbed JAR browsing, package tree, syntax-highlighted source viewer, drag-and-drop, find (Ctrl+F)
- **CLI**: Single-class and JAR decompilation
- **Batch mode**: Parallel decompilation of entire JARs/directories with thread pool
- **Trace mode**: Per-class diagnostic trace file for troubleshooting (see below)
- **Library API**: Simple `Loader`/`Printer` interfaces for embedding
- **Class inspection API**: Javassist-like ClassPool/CtClass/CtMethod/CtField for programmatic navigation
- **Benchmark tool**: Built-in performance measurement

## Usage

### Command Line

```bash
# Decompile a .class file
java -jar denzosoft-decompiler.jar MyClass.class

# Decompile a class from a .jar
java -jar denzosoft-decompiler.jar myapp.jar com/example/MyClass

# Launch graphical interface (JD-GUI style)
java -jar denzosoft-decompiler.jar --gui
java -jar denzosoft-decompiler.jar --gui myapp.jar

# Batch decompile an entire JAR (parallel)
java -jar denzosoft-decompiler.jar --batch myapp.jar output/

# Batch decompile a directory of .class files
java -jar denzosoft-decompiler.jar --batch classes/ output/

# Enable trace mode (writes .trace files for troubleshooting)
java -jar denzosoft-decompiler.jar --trace trace-output/ MyClass.class

# Show version
java -jar denzosoft-decompiler.jar --version
```

### Library API

```java
import it.denzosoft.javadecompiler.DenzoDecompiler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

DenzoDecompiler decompiler = new DenzoDecompiler();
decompiler.decompile(loader, printer, "com/example/MyClass");
```

### Batch API

```java
import it.denzosoft.javadecompiler.BatchDecompiler;

BatchDecompiler batch = new BatchDecompiler(outputDir, 4); // 4 threads
BatchDecompiler.BatchResult result = batch.decompileJar(jarFile);
System.out.println("Decompiled " + result.successCount + "/" + result.totalClasses);
```

### Class Inspection API (Javassist-like)

A read-only API for programmatic inspection of `.class` files, similar to Javassist's `CtClass`/`CtMethod`/`CtField`. No bytecode modification, only navigation and analysis.

```java
import it.denzosoft.javadecompiler.api.classmodel.*;

// Create a pool and add class search paths
ClassPool pool = new ClassPool();
pool.appendClassPath("target/classes");     // directory
pool.appendJarPath("lib/mylib.jar");        // JAR file

// Load a class by name (qualified or internal)
CtClass cls = pool.get("com.example.MyClass");

// Class metadata
System.out.println("Name: " + cls.getName());             // "com.example.MyClass"
System.out.println("Super: " + cls.getSuperclassName());   // "java.lang.Object"
System.out.println("Java: " + cls.getJavaVersion());       // "25"
System.out.println("Public: " + cls.isPublic());           // true
System.out.println("Record: " + cls.isRecord());           // false
System.out.println("Sealed: " + cls.isSealed());           // false

// Navigate fields
for (CtField field : cls.getDeclaredFields()) {
    System.out.println(field.getTypeName() + " " + field.getName());
    // "java.lang.String name"
    // "int count"
}

// Navigate methods
for (CtMethod method : cls.getDeclaredMethods()) {
    System.out.println(method.getName() + " -> " + method.getReturnTypeName());
    System.out.println("  params: " + Arrays.toString(method.getParameterTypeNames()));
    System.out.println("  bytecode: " + method.getBytecodeLength() + " bytes");

    // Generic signature
    String sig = method.getGenericSignature();
    if (sig != null) System.out.println("  signature: " + sig);

    // Annotations
    for (AnnotationInfo ann : method.getAnnotations()) {
        System.out.println("  @" + ann.getTypeDescriptor());
    }
}

// Navigate constructors
for (CtConstructor ctor : cls.getDeclaredConstructors()) {
    System.out.println("Constructor: " + ctor.getDescriptor());
}

// Hierarchy navigation (lazy loading from pool)
CtClass superClass = cls.getSuperclass();        // loads superclass .class
CtClass[] interfaces = cls.getInterfaces();      // loads interface .class files

// Inner classes
String[] innerNames = cls.getInnerClassNames();

// Records
CtRecordComponent[] components = cls.getRecordComponents();
if (components != null) {
    for (CtRecordComponent rc : components) {
        System.out.println("Component: " + rc.getTypeName() + " " + rc.getName());
    }
}

// Decompile to source (convenience method)
String source = cls.decompile();

// Raw access to constant pool and class file model
ConstantPool cp = cls.getConstantPool();
ClassFile cf = cls.getClassFile();
```

**Available classes:**

| Class | Description |
|---|---|
| `ClassPool` | Entry point. Loads and caches class files from directories and JARs |
| `CtClass` | Class metadata: name, version, access flags, hierarchy, members, annotations, generics |
| `CtField` | Field metadata: name, type, modifiers, constant value, annotations |
| `CtMethod` | Method metadata: name, descriptor, parameters, bytecode, annotations, exceptions |
| `CtConstructor` | Constructor metadata (extends CtMethod) |
| `CtRecordComponent` | Record component: name, type, generic signature |
| `NotFoundException` | Thrown when a class, method, or field cannot be found |

## Architecture

### Decompilation Pipeline

```
.class file (binary bytecode)
        |
        v
  [Deserializer] ── Parse class file format into ClassFile model
        |
        v
   [CFG Builder] ── Build Control Flow Graph from bytecode
        |
        v
  [Flow Builder] ── Reconstruct if/else, while, for, do-while, switch, try-catch
        |
        v
  [Transformers] ── Post-processing: for-each, boolean simplify, for-loop, variable merge
        |
        v
    [Writer]     ── Generate readable Java source code
```

### Package Structure

```
it.denzosoft.javadecompiler
├── api/
│   ├── classmodel/                # Javassist-like class inspection API
│   │   ├── ClassPool              #   Class loader and cache
│   │   ├── CtClass                #   Class metadata and navigation
│   │   ├── CtField                #   Field metadata
│   │   ├── CtMethod               #   Method metadata (bytecode, params, annotations)
│   │   ├── CtConstructor          #   Constructor metadata
│   │   ├── CtRecordComponent      #   Record component metadata
│   │   └── NotFoundException      #   Lookup failure exception
│   ├── loader/Loader              #   Class data provider
│   └── printer/Printer            #   Output consumer
├── model/
│   ├── classfile/                 # Class file model
│   │   ├── ClassFile              #   Parsed class file
│   │   ├── ConstantPool           #   Constant pool (17 tag types)
│   │   ├── FieldInfo / MethodInfo #   Fields and methods
│   │   └── attribute/             #   30+ attribute parsers
│   ├── javasyntax/                # Java syntax AST
│   │   ├── type/                  #   Type system (Primitive, Object, Array, Generic, Void)
│   │   ├── expression/            #   26 expression types
│   │   └── statement/             #   20 statement types
│   ├── message/Message            # Pipeline context
│   ├── processor/Processor        # Pipeline stage interface
│   └── token/Token                # Output token model
├── service/
│   ├── deserializer/              # Binary class file parser
│   ├── converter/                 # Bytecode to Java syntax converter
│   │   ├── ClassFileToJavaSyntaxConverter  # Orchestrator + bytecode decoder
│   │   ├── JavaSyntaxResult       # Conversion result model
│   │   ├── cfg/                   # Control Flow Graph
│   │   │   ├── BasicBlock         #   Basic block model
│   │   │   ├── ControlFlowGraph   #   CFG builder
│   │   │   └── StructuredFlowBuilder  # Pattern matching (if/while/for/switch/ternary)
│   │   └── transform/             # AST post-processors
│   │       ├── BooleanSimplifier  #   x!=0 → x, 1 → true
│   │       ├── ForEachDetector    #   Iterator/array pattern → for-each
│   │       ├── ForLoopDetector    #   init+while+update → for loop
│   │       └── TryCatchReconstructor  # Exception table → try-catch-finally
│   └── writer/JavaSourceWriter    # Java source code generator
├── util/
│   ├── ByteReader                 # Binary data reader with bounds checking
│   ├── OpcodeInfo                 # Shared JVM opcode metadata
│   ├── SignatureParser            # Generic signature parser
│   ├── StringConstants            # JVM constants and access flags
│   └── TypeNameUtil               # Type name conversion utilities
├── BatchDecompiler                # Parallel JAR/directory decompilation
├── DecompilationException         # Diagnostic error with pipeline stage info
├── DecompilerLimits               # Security limits (max bytecode, recursion, etc.)
├── DenzoDecompiler                # Main orchestrator (thread-safe)
└── Main                           # CLI entry point
```

## Building

```bash
mvn clean package
```

The resulting JAR will be in `target/java-decompiler-1.0.0-SNAPSHOT.jar`.

Source compatibility: **Java 1.6** (runs on any JVM 1.6+).
Class file support: **Java 1.0 through Java 25** (versions 45.0 - 69.0).

## Supported Java Features by Version

| Java Version | Class File | Supported Features |
|---|---|---|
| 1.0 - 1.4 | 45 - 48 | Classes, interfaces, fields, methods, basic control flow |
| 5 | 49 | Generics, enums, annotations, varargs, for-each |
| 6 | 50 | StackMapTable (parsed, not used in output) |
| 7 | 51 | Diamond operator, try-with-resources, multi-catch |
| 8 | 52 | Lambda expressions, method references, default methods |
| 9 | 53 | Modules (`module-info`), private interface methods |
| 10 | 54 | Local variable type inference (`var`) |
| 11 | 55 | Nest-based access control |
| 14 | 58 | Switch expressions, records (preview) |
| 15 | 59 | Text blocks, sealed classes (preview) |
| 16 | 60 | Records (final), pattern matching for `instanceof` |
| 17 | 61 | Sealed classes (final), `strictfp` default |
| 21 | 65 | Pattern matching for switch, record patterns |
| 25 | 69 | Latest features |

## Known Limitations

### Compilability Exceptions
- **Try-with-resources** (Java 7+): The compiler generates complex `addSuppressed` + nested try-catch patterns. The decompiler falls back to linear code to ensure compilability, losing the resource management structure. Model and writer support exist; resource extraction logic is pending.

### Type Information
- **Generic type erasure**: Some generic type parameters are lost at bytecode level; raw types may appear where generics were used. `LocalVariableTypeTable` is used when available for better results.
- **`@Override`**: Not reconstructable (has `RetentionPolicy.SOURCE`, not present in class files)
- **Type annotations** (Java 8+): Not yet rendered in output (`@NonNull List<@NonNull String>`). Attributes are recognized but skipped during parsing.

### Modern Java Features
- **Pattern matching for switch** (Java 21+): Model exists but bytecode pattern detection not fully implemented
- **Text blocks** (Java 15+): Heuristic detection based on newline count (2+ newlines → text block) for Java 15+ class files. Exact distinction is impossible since text blocks and regular strings compile to identical bytecode.
- **String templates** (Java 21+ preview): Not supported

### Output Quality
- **Inner/anonymous classes**: Named inner classes are fully inlined. Anonymous classes have display name mapping but body inlining into `new` expressions is in progress.

### Resolved in Previous Versions
- **String switch** (Java 7+): Fully reconstructed from hashCode/equals pattern since v1.1.0
- **Enum constant initialization**: Synthetic members suppressed, constructor arguments extracted since v1.1.0/v1.2.0
- **Lambda captures**: Captured variables properly resolved via bootstrap method analysis since v1.1.0

## Performance

Benchmark results (measured on project's own 180 class files):

| Metric | Value |
|---|---|
| Throughput | ~4,000 classes/sec |
| Avg per class | ~0.25 ms |
| Heap usage | ~1.7 MB for 200 classes |
| Batch (parallel) | 150 classes in ~5 sec |
| Thread safety | Full (one converter per call) |
| Security limits | Max 64KB bytecode, 200 recursion depth |

## Test Suite

16 automated tests covering:
- Basic classes, constructors, field initialization
- Annotations (`@Deprecated`, custom)
- Generics (`<T extends Comparable<T>>`, `List<String>`)
- Records, sealed classes, enums, interfaces, abstract classes
- Static initializers, string concatenation
- Field types (public, private, protected, static, final, volatile, transient)
- Arithmetic operations (int, double, float, modulo)
- Try-catch, try-catch-finally, multi-catch
- Inheritance with generics

Run tests:
```bash
java -cp target/classes it.denzosoft.javadecompiler.DecompilerTest /path/to/javac
```
