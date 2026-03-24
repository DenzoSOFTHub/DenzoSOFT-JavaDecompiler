# Decompilation Architecture Reference

This document describes how DenzoSOFT Java Decompiler transforms compiled `.class` files back into readable Java source code.

---

## 1. Pipeline Overview

The decompiler processes each class through five sequential stages:

```
.class file (binary bytecode)
        |
        v
  [Deserializer]          Parse binary .class format into ClassFile model
        |
        v
  [CFG Builder]           Build Control Flow Graph from method bytecode
        |
        v
  [StructuredFlowBuilder] Reconstruct if/else, while, for, switch, ternary, try-catch
        |
        v
  [Transformers]          Post-processing: for-each, boolean simplify, compound assignment
        |
        v
  [Writer]                Generate readable Java source code
```

**Entry point**: `DenzoDecompiler.decompile(Loader, Printer, String)` orchestrates the pipeline. A new `ClassFileToJavaSyntaxConverter` is created per call to ensure thread safety.

**Key classes**:

| Stage | Class | Location |
|---|---|---|
| Deserializer | `ClassFileDeserializer` | `service/deserializer/` |
| CFG Builder | `ControlFlowGraph` | `service/converter/cfg/` |
| Flow Builder | `StructuredFlowBuilder` | `service/converter/cfg/` |
| Bytecode Decoder | `ClassFileToJavaSyntaxConverter` | `service/converter/` |
| Transformers | `BooleanSimplifier`, `ForEachDetector`, etc. | `service/converter/transform/` |
| Writer | `JavaSourceWriter` | `service/writer/` |

---

## 2. Class File Parsing

The `ClassFileDeserializer` reads the binary `.class` format defined by the JVM specification and produces a `ClassFile` model object.

### Binary Format Structure

A `.class` file is parsed in the following order:

1. **Magic number** (`0xCAFEBABE`) -- validates this is a valid class file.
2. **Version** -- `minor_version` (u2) and `major_version` (u2). Major version 45 = Java 1.0, up to 69 = Java 25. A minor version of `0xFFFF` indicates preview features.
3. **Constant pool** -- A 1-indexed table of tagged entries. The decompiler supports all 17 tag types:

| Tag | Name | Description |
|---|---|---|
| 1 | `Utf8` | UTF-8 encoded string |
| 3 | `Integer` | 4-byte int constant |
| 4 | `Float` | 4-byte float constant |
| 5 | `Long` | 8-byte long constant (occupies two pool slots) |
| 6 | `Double` | 8-byte double constant (occupies two pool slots) |
| 7 | `Class` | Reference to class name (Utf8 index) |
| 8 | `String` | Reference to string value (Utf8 index) |
| 9 | `Fieldref` | Class + NameAndType reference for fields |
| 10 | `Methodref` | Class + NameAndType reference for methods |
| 11 | `InterfaceMethodref` | Class + NameAndType reference for interface methods |
| 12 | `NameAndType` | Name + descriptor pair |
| 15 | `MethodHandle` | Bootstrap method handle (for lambdas, string concat) |
| 16 | `MethodType` | Method descriptor type |
| 17 | `Dynamic` | Dynamically-computed constant |
| 18 | `InvokeDynamic` | Dynamically-computed call site |
| 19 | `Module` | Module name reference |
| 20 | `Package` | Package name reference |

4. **Access flags** (`u2`) -- `ACC_PUBLIC`, `ACC_FINAL`, `ACC_SUPER`, `ACC_INTERFACE`, `ACC_ABSTRACT`, `ACC_SYNTHETIC`, `ACC_ANNOTATION`, `ACC_ENUM`, `ACC_MODULE`.
5. **This class** (`u2`) -- Constant pool index of the class name.
6. **Super class** (`u2`) -- Constant pool index of the superclass name (0 for `java.lang.Object`).
7. **Interfaces** -- Count (`u2`) followed by constant pool indices.
8. **Fields** -- Count (`u2`) followed by `FieldInfo` structures (access flags, name index, descriptor index, attributes).
9. **Methods** -- Count (`u2`) followed by `MethodInfo` structures (same layout as fields but with Code attribute).
10. **Attributes** -- Class-level attributes (SourceFile, InnerClasses, Signature, Record, etc.).

### Attribute Parsing

The decompiler parses 30+ attribute types. Key attributes include:

| Attribute | Purpose |
|---|---|
| `Code` | Method bytecode, max stack, max locals, exception table, nested attributes |
| `ConstantValue` | Compile-time constant for `static final` fields |
| `Signature` | Generic type signature (for generics reconstruction) |
| `RuntimeVisibleAnnotations` | Annotations with `RUNTIME` retention |
| `RuntimeInvisibleAnnotations` | Annotations with `CLASS` retention |
| `MethodParameters` | Formal parameter names (when compiled with `-parameters`) |
| `LocalVariableTable` | Local variable names and types (when compiled with `-g`) |
| `LocalVariableTypeTable` | Generic local variable types |
| `InnerClasses` | Inner/outer class relationships |
| `EnclosingMethod` | Enclosing method for local/anonymous classes |
| `Record` | Record component definitions (Java 16+) |
| `PermittedSubclasses` | Permitted subclass list for sealed classes (Java 17+) |
| `Module` | Module declaration (requires, exports, opens, uses, provides) |
| `BootstrapMethods` | Bootstrap methods for `invokedynamic` (lambdas, string concat) |
| `NestHost` / `NestMembers` | Nest-based access control (Java 11+) |
| `Exceptions` | Declared checked exceptions on a method |
| `StackMapTable` | Stack map frames for verification (parsed, not used in output) |
| `SourceFile` | Original source file name |

### Data Validation

The `ByteReader` utility performs bounds checking on all reads. Attribute length validation with auto-recovery prevents malformed class files from crashing the decompiler. If an attribute length is inconsistent, the reader skips to the expected end position and continues parsing.

---

## 3. Control Flow Graph

The `ControlFlowGraph` class transforms a linear sequence of bytecode instructions into a graph of basic blocks connected by edges.

### Building Basic Blocks

A **basic block** is a maximal sequence of instructions with:
- No branches except at the last instruction (exit)
- No branch targets except at the first instruction (entry)

The algorithm works in four steps:

**Step 1: Identify leaders (block boundaries)**

The following PCs become block leaders:
- PC 0 (first instruction)
- Target of any branch instruction (`if*`, `goto`, `tableswitch`, `lookupswitch`)
- Instruction after any branch instruction (fall-through)
- Exception handler start PCs from the exception table
- Exception region start/end PCs

**Step 2: Create blocks**

For each leader PC, create a `BasicBlock` with `startPc` = leader and `endPc` = next leader. The blocks are stored in a `TreeMap<Integer, BasicBlock>` for O(log n) lookup by PC.

**Step 3: Classify blocks**

The last instruction in each block determines its type:

| Block Type | Last Instruction | Successors |
|---|---|---|
| `CONDITIONAL` | `if_icmp*`, `ifne`, `ifnull`, etc. | `trueSuccessor` (branch target), `falseSuccessor` (fall-through) |
| `GOTO` | `goto`, `goto_w` | `trueSuccessor` (branch target) |
| `RETURN` | `ireturn`, `areturn`, `return`, etc. | None |
| `THROW` | `athrow` | None |
| `SWITCH` | `tableswitch`, `lookupswitch` | Multiple targets stored in `switchTargets[]` + `switchDefaultTarget` |
| `FALL_THROUGH` | Any other instruction | `trueSuccessor` (next block) |

**Step 4: Link predecessors**

Each successor relationship also creates a predecessor link, enabling backward traversal (needed for loop detection).

### Edge Types

- **Forward edge**: Points to a block with a higher PC. Used by if/else, goto-to-merge, switch cases.
- **Backward edge** (back-edge): Points to a block with a lower or equal PC. Indicates a loop. Detected via `isBackwardBranch()` when `branchTargetPc <= startPc`.

---

## 4. Structured Flow Reconstruction

The `StructuredFlowBuilder` performs pattern matching on the CFG to reconstruct high-level control flow statements. It processes blocks in PC order, visiting each block once, and uses recursive descent to handle nesting.

### Pre-scan Phase

Before main processing:
1. **Do-while headers** are identified by scanning for conditional blocks with backward branches. The target of a backward conditional branch is marked as a do-while body start.
2. **Merge points** are pre-computed for all conditional blocks to avoid repeated O(n) scans during processing.

### Pattern: If-Then

```
[conditional block] ----true----> [then-block(s)] ----> [merge point]
        |                                                    ^
        +-------------------false----------------------------+
```

Detection: A conditional block whose false-successor is the merge point (the point where the then-path and the fall-through path converge). The then-body is built by recursively processing blocks from the true-successor up to the merge point.

### Pattern: If-Then-Else

```
[conditional block] ----true----> [then-block(s)] --goto--> [merge point]
        |                                                        ^
        +---false----> [else-block(s)] --------------------------+
```

Detection: A conditional block where the true-path ends with a `goto` to a merge point, and the false-path reaches the same merge point. The merge point is computed as the target of the first `goto` encountered along the then-path, provided the else-path also reaches it.

### Pattern: While Loop

```
         +-------------------------------+
         v                               |
[loop header (conditional)] --true--> [body block(s)] --goto--+
         |
         +---false----> [exit / merge]
```

Detection: A conditional block whose true-successor (the body) eventually contains a backward branch (`goto`) to the conditional block itself. The condition is taken from the conditional block. The body is built by processing blocks from the true-successor up to the header PC.

### Pattern: Do-While Loop

```
[body start] ----> [body block(s)] ----> [conditional block]
     ^                                          |
     +----------------true (backward)-----------+

                     false ----> [exit / merge]
```

Detection: During the pre-scan, backward conditional branches identify do-while headers. When the builder reaches a do-while header block, it collects body statements up to the condition block, then emits a `DoWhileStatement`. The condition may be negated depending on which branch (true or false) is the back-edge.

### Pattern: For Loop

For loops are not directly detected by `StructuredFlowBuilder`. Instead, the `ForLoopDetector` transform (see Section 5) identifies the pattern:

```
[init statement]     // e.g., int i = 0
[while(condition)]   // e.g., i < n
    [body]
    [update]         // e.g., i++
```

The detector looks for a variable declaration immediately before a `while` statement, where the last statement in the while body is an increment/decrement or assignment to the same variable.

### Pattern: For-Each

For-each loops are detected by the `ForEachDetector` transform (see Section 5) from two bytecode patterns:

**Iterator pattern** (collections):
```java
Iterator iter = collection.iterator();
while (iter.hasNext()) {
    Type item = (Type) iter.next();
    // body
}
```

**Array pattern** (arrays):
```java
Type[] arr = arrayExpr;
int len = arr.length;
for (int i = 0; i < len; i++) {
    Type item = arr[i];
    // body
}
```

### Pattern: Switch

```
[switch block] ---case 0---> [case-0 block(s)]
       |
       +-------case 1---> [case-1 block(s)]
       |
       +-------default---> [default block(s)]
```

Detection: Blocks of type `SWITCH` have their `switchKeys[]` and `switchTargets[]` populated during CFG classification. The `StructuredFlowBuilder` creates a `SwitchStatement` by:
1. Reading the selector expression from the switch block.
2. Iterating over each (key, target-PC) pair.
3. Building case body statements from the target block up to the next case or merge point.
4. Detecting fall-through between cases.

**tableswitch** provides a contiguous range of keys (low..high) with one target per key. **lookupswitch** provides sparse (key, target) pairs.

### Pattern: Ternary Operator

```
[conditional block] --true--> [push value A] --goto--> [merge (store/return)]
        |                                                       ^
        +---false---> [push value B] --------------------------+
```

Detection: A conditional block where both the true-path and false-path each consist of a single block that pushes exactly one value onto the stack, and both paths converge at the same merge point (a store or return instruction). The `stackTopExpression` field on each basic block carries the pushed value for ternary reconstruction.

### Pattern: Try-Catch

```
[try region: startPc..endPc] ---exception--> [handler block at handlerPc]
```

Detection: The `TryCatchReconstructor` transform (see Section 5) processes the exception table attached to the `Code` attribute. Each entry specifies:
- `startPc` / `endPc`: the protected region
- `handlerPc`: where to jump on exception
- `catchType`: constant pool index of the exception class (0 = finally/catch-all)

Multiple exception table entries with the same handler PC are merged into multi-catch. Entries with `catchType == 0` become finally blocks.

### Pattern: Compound Boolean (`&&`, `||`)

```
// a && b:
[conditional: a] --false--> [merge (result=false)]
        |
        +---true---> [conditional: b] --false--> [merge]
                             |
                             +---true---> [then-body]

// a || b:
[conditional: a] --true--> [then-body]
        |
        +---false---> [conditional: b] --true--> [then-body]
                             |
                             +---false---> [merge (result=false)]
```

Detection: When processing a conditional block, if the true or false successor is another conditional block that shares a target with the current block, a compound boolean expression is built. Consecutive conditionals are chained: `a && b && c` or `a || b || c`. Mixed operators are also supported: `a != null && !a.isEmpty()`.

---

## 5. Post-processing Transforms

After `StructuredFlowBuilder` produces the initial AST, six transform passes refine the output.

### BooleanSimplifier

Simplifies boolean expressions in the AST:
- `x != 0` becomes `x` (for boolean variables)
- `x == 0` becomes `!x`
- Integer `1` / `0` becomes `true` / `false` for boolean contexts (assignments, returns, parameters)
- `return x > 0 ? 1 : 0` becomes `return x > 0`
- `!(x instanceof Foo)` gets correct precedence parentheses

### ForEachDetector

Detects and converts two patterns:

**Iterator pattern**: A variable assigned from `.iterator()`, followed by a `while(iter.hasNext())` loop whose first statement is `Type item = iter.next()`. Converted to `for (Type item : collection)`.

**Array pattern**: A temporary array variable, a length variable, and a counter variable used in a `for(int i = 0; i < len; i++)` loop whose first statement is `Type item = arr[i]`. Converted to `for (Type item : array)`.

### ForLoopDetector

Identifies `while` loops that are actually `for` loops by checking:
1. The statement immediately before the `while` is a variable declaration or assignment (the initializer).
2. The last statement in the while body is an increment, decrement, or assignment to the same variable (the update).
3. The while condition references the same variable.

Converts to `for (init; condition; update) { body }`.

### TryCatchReconstructor

Rebuilds try-catch-finally from the exception table:
1. Groups exception table entries by `(startPc, endPc)` to find try regions.
2. For each region, collects handlers by `catchType`.
3. Entries with `catchType == 0` are treated as finally blocks.
4. Multiple catch types for the same handler become multi-catch: `catch (IOException | SQLException e)`.

### StringSwitchReconstructor

Reconstructs `switch(string)` from the compiler-generated 2-phase pattern:
1. Phase 1: `switch(str.hashCode())` with cases that perform `.equals()` checks and assign an integer variable.
2. Phase 2: `switch(intVar)` with the actual case bodies.

The reconstructor detects this pattern and merges the two switches into a single `switch(string)`.

### CompoundAssignmentSimplifier

Simplifies assignment patterns:
- `x = x + 1` becomes `x += 1`
- `x = x - y` becomes `x -= y`
- Supports `+=`, `-=`, `*=`, `/=`, `%=`, `&=`, `|=`, `^=`, `<<=`, `>>=`, `>>>=`

Also handles assert reconstruction: patterns matching `if (!$assertionsDisabled && !condition) throw new AssertionError()` are converted to `assert condition` statements.

---

## 6. Source Generation

The `JavaSourceWriter` traverses the AST and generates Java source code by calling methods on the `Printer` interface.

### Output Structure

The writer processes the `JavaSyntaxResult` top-down:

1. **Package declaration**: `package com.example;`
2. **Imports**: (not generated -- types are fully qualified or simple-named)
3. **Class declaration**: Access modifiers, class/interface/enum/record keyword, name, type parameters, extends, implements, permits.
4. **Annotations**: `@Deprecated`, `@SuppressWarnings("unchecked")`, custom annotations with element-value pairs.
5. **Fields**: Modifiers, type, name, optional initializer.
6. **Constructors**: Modifiers, class name, parameters, throws, body.
7. **Methods**: Modifiers, return type, name, parameters, throws, body.
8. **Static initializers**: `static { ... }` blocks.
9. **Inner classes**: Nested class declarations.
10. **Record components**: `record Point(int x, int y)` syntax.

### Indentation

The writer uses the `Printer.indent()` / `Printer.unindent()` calls to manage nesting level. The default `StringPrinter` renders 4 spaces per indent level. Indentation increments for:
- Class body
- Method body
- Block statements (if/else/while/for/switch bodies)
- Try/catch/finally bodies
- Switch case bodies

### Type Resolution

Types are resolved from descriptors and signatures:
- Primitive descriptors (`I`, `J`, `D`, etc.) map to `int`, `long`, `double`, etc.
- Object descriptors (`Ljava/lang/String;`) map to simple names (`String`) for `java.lang` types and qualified names otherwise.
- Array descriptors (`[I`, `[[Ljava/lang/String;`) map to `int[]`, `String[][]`.
- Generic signatures (`Ljava/util/List<Ljava/lang/String;>;`) are parsed by `SignatureParser` into `List<String>`.
- Type variables (`TT;`) resolve to `T`.
- Wildcards (`+Ljava/lang/Number;`, `-Ljava/lang/Comparable;`) resolve to `? extends Number`, `? super Comparable`.
- The `LocalVariableTypeTable` attribute provides generic types for local variables, overriding the erased types from the descriptor.

### Annotation Output

Annotations are written with their element-value pairs:
- Simple: `@Deprecated`
- Single value: `@SuppressWarnings("unchecked")`
- Multiple values: `@RequestMapping(value = "/api", method = GET)`
- Array values: `@SuppressWarnings({"unchecked", "rawtypes"})`
- Nested annotations: `@Outer(@Inner("value"))`
- Enum values: `@Retention(RetentionPolicy.RUNTIME)`

### Expression Precedence

The writer handles operator precedence to minimize unnecessary parentheses while ensuring correctness. Cast expressions receive parentheses when used as the operand of a member access or when combined with arithmetic operators. Negation of `instanceof` is parenthesized: `!(x instanceof Foo)`.

### Special Cases

- **Bridge methods**: Compiler-generated bridge methods (for covariant return types and generics) are suppressed.
- **Synthetic members**: Members with the `ACC_SYNTHETIC` flag are suppressed.
- **Native methods**: Annotated with JNI comments: `// JNI: Java_pkg_Class_method | params: (JNIEnv*, jobject, jint)`.
- **Trailing return**: `return;` at the end of void methods is omitted.
- **super() calls**: Default `super()` calls with no arguments in constructors may be omitted.
- **Static field init merging**: `static final` fields initialized in `<clinit>` are merged into the field declaration when the initializer is a simple constant expression.
- **Autoboxing suppression**: `Integer.valueOf(x)` is simplified to `x` when the context expects a boxed type.
- **Redundant cast removal**: Casts to the same type or to `Object` are suppressed.
