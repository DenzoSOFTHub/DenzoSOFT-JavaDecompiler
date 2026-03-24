# API Reference

This document covers the Javassist-like class inspection API provided by DenzoSOFT Java Decompiler. All classes are in the `it.denzosoft.javadecompiler.api.classmodel` package.

---

## ClassPool

Entry point for loading and navigating class files. Caches loaded classes for reuse.

### Construction

```java
ClassPool pool = new ClassPool();
```

Creates an empty pool with no class paths configured.

### Methods

| Method | Description |
|---|---|
| `void appendClassPath(String dirPath)` | Add a directory to the class search path. The directory should contain `.class` files in package-matching subdirectories (e.g., `com/example/MyClass.class`). |
| `void appendJarPath(String jarPath) throws IOException` | Add a JAR file to the class search path. |
| `void insertClass(String internalName, byte[] bytecode)` | Insert a class from raw bytes into the byte cache. Accepts internal names (`com/example/MyClass`). |
| `CtClass get(String className) throws NotFoundException` | Load a class by name. Accepts both qualified names (`com.example.MyClass`) and internal names (`com/example/MyClass`). Results are cached. |
| `CtClass makeClass(byte[] bytecode)` | Create a `CtClass` from raw class file bytes. The class is added to the cache. |
| `CtClass makeClass(File classFile) throws IOException` | Create a `CtClass` from a `.class` file on disk. The class is added to the cache. |
| `Set<String> getLoadedClassNames()` | Return the qualified names of all classes currently loaded in the cache. |

### Class Resolution Order

When `get()` is called:
1. Check the in-memory cache.
2. Check the byte cache (classes added via `insertClass()`).
3. Search class paths in the order they were added (directories first, then JARs, based on insertion order).
4. Throw `NotFoundException` if not found.

---

## CtClass

Represents a loaded Java class. Provides read-only access to class metadata, members, annotations, generics, and more.

### Identity

| Method | Return Type | Description |
|---|---|---|
| `getName()` | `String` | Qualified class name (e.g., `com.example.MyClass`) |
| `getInternalName()` | `String` | Internal class name (e.g., `com/example/MyClass`) |
| `getSimpleName()` | `String` | Simple class name (e.g., `MyClass`) |
| `getPackageName()` | `String` | Package name (e.g., `com.example`) |

### Version

| Method | Return Type | Description |
|---|---|---|
| `getMajorVersion()` | `int` | Class file major version (e.g., 65 for Java 21) |
| `getMinorVersion()` | `int` | Class file minor version |
| `getJavaVersion()` | `String` | Human-readable Java version (e.g., `"21"`) |
| `isPreviewFeatures()` | `boolean` | `true` if compiled with `--enable-preview` (minor version = 0xFFFF) |

### Access Flags

| Method | Return Type | Description |
|---|---|---|
| `getModifiers()` | `int` | Raw access flags bitmask |
| `isPublic()` | `boolean` | `ACC_PUBLIC` flag set |
| `isAbstract()` | `boolean` | `ACC_ABSTRACT` flag set |
| `isFinal()` | `boolean` | `ACC_FINAL` flag set |
| `isInterface()` | `boolean` | `ACC_INTERFACE` flag set |
| `isEnum()` | `boolean` | `ACC_ENUM` flag set |
| `isAnnotation()` | `boolean` | `ACC_ANNOTATION` flag set |
| `isRecord()` | `boolean` | Has Record attribute |
| `isSealed()` | `boolean` | Has PermittedSubclasses attribute |
| `isModule()` | `boolean` | `ACC_MODULE` flag set |
| `isSynthetic()` | `boolean` | `ACC_SYNTHETIC` flag set |

### Hierarchy

| Method | Return Type | Description |
|---|---|---|
| `getSuperclassName()` | `String` | Qualified superclass name, or `null` for `Object` |
| `getSuperclass()` | `CtClass` | Superclass loaded from pool. Throws `NotFoundException`. |
| `getInterfaceNames()` | `String[]` | Qualified names of implemented interfaces |
| `getInterfaces()` | `CtClass[]` | Interface classes loaded from pool. Throws `NotFoundException`. |

### Members

| Method | Return Type | Description |
|---|---|---|
| `getDeclaredFields()` | `CtField[]` | All fields declared in this class |
| `getDeclaredField(String name)` | `CtField` | Field by name. Throws `NotFoundException`. |
| `getDeclaredMethods()` | `CtMethod[]` | All methods (excludes `<init>` and `<clinit>`) |
| `getDeclaredMethod(String name)` | `CtMethod` | First method matching name. Throws `NotFoundException`. |
| `getDeclaredMethod(String name, String descriptor)` | `CtMethod` | Method by name + descriptor. Throws `NotFoundException`. |
| `getDeclaredConstructors()` | `CtConstructor[]` | All constructors (includes `<init>` and `<clinit>`) |

### Annotations

| Method | Return Type | Description |
|---|---|---|
| `getAnnotations()` | `AnnotationInfo[]` | Runtime-visible annotations on this class |
| `hasAnnotation(String annotationType)` | `boolean` | Check for annotation by qualified type name |

### Generics

| Method | Return Type | Description |
|---|---|---|
| `getGenericSignature()` | `String` | Raw generic signature from Signature attribute, or `null` |
| `getTypeParameters()` | `String[]` | Parsed type parameter names (e.g., `["T extends Comparable<T>"]`) |

### Inner Classes

| Method | Return Type | Description |
|---|---|---|
| `getInnerClassNames()` | `String[]` | Qualified names of inner classes declared in this class |
| `getOuterClassName()` | `String` | Qualified name of the outer class, or `null` |
| `getEnclosingMethodName()` | `String` | Name of the enclosing method (for local/anonymous classes), or `null` |

### Records (Java 16+)

| Method | Return Type | Description |
|---|---|---|
| `getRecordComponents()` | `CtRecordComponent[]` | Record components, or `null` if not a record |

### Sealed Classes (Java 17+)

| Method | Return Type | Description |
|---|---|---|
| `getPermittedSubclasses()` | `String[]` | Qualified names of permitted subclasses |

### Source

| Method | Return Type | Description |
|---|---|---|
| `getSourceFileName()` | `String` | Original source file name from SourceFile attribute, or `null` |

### Decompilation

| Method | Return Type | Description |
|---|---|---|
| `decompile()` | `String` | Decompile this class to Java source code. Throws `Exception`. |

### Raw Access

| Method | Return Type | Description |
|---|---|---|
| `getConstantPool()` | `ConstantPool` | Direct access to the constant pool |
| `getClassFile()` | `ClassFile` | Direct access to the parsed class file model |

---

## CtMethod

Represents a method in a class. Provides access to name, descriptor, parameters, bytecode, annotations, and more.

### Identity

| Method | Return Type | Description |
|---|---|---|
| `getName()` | `String` | Method name (e.g., `"calculateTotal"`) |
| `getDescriptor()` | `String` | Method descriptor (e.g., `"(ILjava/lang/String;)V"`) |
| `getReturnTypeName()` | `String` | Human-readable return type (e.g., `"void"`, `"java.lang.String"`) |
| `getDeclaringClass()` | `CtClass` | The class that declares this method |

### Parameters

| Method | Return Type | Description |
|---|---|---|
| `getParameterTypeNames()` | `String[]` | Human-readable parameter types (e.g., `["int", "java.lang.String"]`) |
| `getParameterNames()` | `String[]` | Parameter names from MethodParameters or LocalVariableTable. Returns `null` if unavailable. |
| `getParameterTypes()` | `CtClass[]` | Parameter types as `CtClass` instances (loaded from pool). Throws `NotFoundException`. |
| `getExceptionTypeNames()` | `String[]` | Declared exception types from the Exceptions attribute |

### Bytecode

| Method | Return Type | Description |
|---|---|---|
| `getBytecodeLength()` | `int` | Length of bytecode in Code attribute (0 for abstract/native) |
| `getBytecode()` | `byte[]` | Raw bytecode bytes, or `null` for abstract/native methods |
| `getMaxStack()` | `int` | Maximum operand stack depth |
| `getMaxLocals()` | `int` | Maximum number of local variable slots |

### Access Flags

| Method | Return Type | Description |
|---|---|---|
| `getModifiers()` | `int` | Raw access flags bitmask |
| `isPublic()` | `boolean` | `ACC_PUBLIC` |
| `isPrivate()` | `boolean` | `ACC_PRIVATE` |
| `isProtected()` | `boolean` | `ACC_PROTECTED` |
| `isStatic()` | `boolean` | `ACC_STATIC` |
| `isFinal()` | `boolean` | `ACC_FINAL` |
| `isAbstract()` | `boolean` | `ACC_ABSTRACT` |
| `isNative()` | `boolean` | `ACC_NATIVE` |
| `isSynchronized()` | `boolean` | `ACC_SYNCHRONIZED` |
| `isBridge()` | `boolean` | `ACC_BRIDGE` |
| `isVarargs()` | `boolean` | `ACC_VARARGS` |
| `isSynthetic()` | `boolean` | `ACC_SYNTHETIC` |

### Annotations

| Method | Return Type | Description |
|---|---|---|
| `getAnnotations()` | `AnnotationInfo[]` | Runtime-visible and invisible annotations on this method |
| `getParameterAnnotations()` | `AnnotationInfo[][]` | Per-parameter annotations. Outer array indexed by parameter position. |

### Generics

| Method | Return Type | Description |
|---|---|---|
| `getGenericSignature()` | `String` | Raw generic signature, or `null` |

### Type Resolution

| Method | Return Type | Description |
|---|---|---|
| `getReturnType()` | `CtClass` | Return type as `CtClass` (loaded from pool). Throws `NotFoundException`. Only works for object types. |

### Decompilation

| Method | Return Type | Description |
|---|---|---|
| `decompile()` | `String` | Decompile the declaring class to source. Throws `Exception`. |

---

## CtField

Represents a field in a class. Provides access to name, type, modifiers, constant value, and annotations.

### Identity

| Method | Return Type | Description |
|---|---|---|
| `getName()` | `String` | Field name |
| `getDescriptor()` | `String` | Field descriptor (e.g., `"I"`, `"Ljava/lang/String;"`) |
| `getTypeName()` | `String` | Human-readable type name (e.g., `"int"`, `"java.lang.String"`) |
| `getDeclaringClass()` | `CtClass` | The class that declares this field |

### Access Flags

| Method | Return Type | Description |
|---|---|---|
| `getModifiers()` | `int` | Raw access flags bitmask |
| `isPublic()` | `boolean` | `ACC_PUBLIC` |
| `isPrivate()` | `boolean` | `ACC_PRIVATE` |
| `isProtected()` | `boolean` | `ACC_PROTECTED` |
| `isStatic()` | `boolean` | `ACC_STATIC` |
| `isFinal()` | `boolean` | `ACC_FINAL` |
| `isVolatile()` | `boolean` | `ACC_VOLATILE` |
| `isTransient()` | `boolean` | `ACC_TRANSIENT` |
| `isSynthetic()` | `boolean` | `ACC_SYNTHETIC` |
| `isEnum()` | `boolean` | `ACC_ENUM` |

### Value

| Method | Return Type | Description |
|---|---|---|
| `getConstantValue()` | `Object` | Compile-time constant for `static final` fields. Returns `Integer`, `Long`, `Float`, `Double`, or `String`. Returns `null` if no constant. |

### Annotations

| Method | Return Type | Description |
|---|---|---|
| `getAnnotations()` | `AnnotationInfo[]` | Runtime-visible and invisible annotations on this field |

### Generics

| Method | Return Type | Description |
|---|---|---|
| `getGenericSignature()` | `String` | Raw generic signature, or `null` |

### Type Resolution

| Method | Return Type | Description |
|---|---|---|
| `getType()` | `CtClass` | Field type as `CtClass` (loaded from pool). Throws `NotFoundException`. Only works for object types. |

---

## CtConstructor

Represents a constructor (`<init>`) or static initializer (`<clinit>`). Extends `CtMethod` with constructor-specific behavior.

### Additional Methods

| Method | Return Type | Description |
|---|---|---|
| `isClassInitializer()` | `boolean` | `true` if this is a `<clinit>` (static initializer) |
| `getName()` | `String` | Returns the declaring class simple name (for `<init>`) or `"<clinit>"` |

All methods from `CtMethod` are inherited (descriptor, parameters, bytecode, annotations, etc.).

---

## CtRecordComponent

Represents a component of a record class (Java 16+).

### Methods

| Method | Return Type | Description |
|---|---|---|
| `getName()` | `String` | Component name (e.g., `"x"`) |
| `getDescriptor()` | `String` | Field descriptor (e.g., `"I"`) |
| `getTypeName()` | `String` | Human-readable type name (e.g., `"int"`) |
| `getGenericSignature()` | `String` | Generic signature, or `null` |

---

## NotFoundException

Thrown when a class, field, or method cannot be found in the `ClassPool`.

```java
public class NotFoundException extends Exception {
    public NotFoundException(String message);
}
```

---

## Usage Examples

### Load and inspect a class from a directory

```java
ClassPool pool = new ClassPool();
pool.appendClassPath("target/classes");

CtClass cls = pool.get("com.example.MyClass");
System.out.println("Class: " + cls.getName());
System.out.println("Java version: " + cls.getJavaVersion());
System.out.println("Is public: " + cls.isPublic());
System.out.println("Superclass: " + cls.getSuperclassName());
```

### Load a class from a JAR

```java
ClassPool pool = new ClassPool();
pool.appendJarPath("lib/mylib.jar");

CtClass cls = pool.get("com.example.Library");
```

### List all methods with their signatures

```java
CtClass cls = pool.get("com.example.MyClass");
for (CtMethod method : cls.getDeclaredMethods()) {
    System.out.println(method.getName() + method.getDescriptor());
    System.out.println("  Return type: " + method.getReturnTypeName());
    System.out.println("  Parameters: " + Arrays.toString(method.getParameterTypeNames()));
    System.out.println("  Bytecode: " + method.getBytecodeLength() + " bytes");
}
```

### Check annotations

```java
CtClass cls = pool.get("com.example.MyService");
if (cls.hasAnnotation("java.lang.Deprecated")) {
    System.out.println("Class is deprecated");
}

for (CtMethod method : cls.getDeclaredMethods()) {
    for (AnnotationInfo ann : method.getAnnotations()) {
        System.out.println("  @" + ann.getTypeDescriptor());
    }
}
```

### Inspect record components

```java
CtClass cls = pool.get("com.example.Point");
if (cls.isRecord()) {
    CtRecordComponent[] comps = cls.getRecordComponents();
    for (CtRecordComponent rc : comps) {
        System.out.println("Component: " + rc.getTypeName() + " " + rc.getName());
    }
}
```

### Navigate class hierarchy

```java
CtClass cls = pool.get("com.example.MyList");
CtClass superClass = cls.getSuperclass();  // loads from pool
CtClass[] interfaces = cls.getInterfaces(); // loads from pool

String sig = cls.getGenericSignature();
// e.g., "Ljava/util/AbstractList<Ljava/lang/String;>;Ljava/io/Serializable;"
```

### Decompile a class to source

```java
ClassPool pool = new ClassPool();
pool.appendClassPath("target/classes");

CtClass cls = pool.get("com.example.MyClass");
String source = cls.decompile();
System.out.println(source);
```

### Create a class from raw bytes

```java
byte[] bytecode = Files.readAllBytes(Paths.get("MyClass.class"));
ClassPool pool = new ClassPool();
CtClass cls = pool.makeClass(bytecode);
System.out.println("Loaded: " + cls.getName());
```
