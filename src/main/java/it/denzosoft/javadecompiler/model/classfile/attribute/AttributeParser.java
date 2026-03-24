/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.util.ByteReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses class file attributes from binary data.
 */
public final class AttributeParser {

    private AttributeParser() {}

    public static List<Attribute> parseAttributes(ByteReader reader, ConstantPool pool) {
        int count = reader.readUnsignedShort();
        List<Attribute> attributes = new ArrayList<Attribute>(count);
        for (int i = 0; i < count; i++) {
            attributes.add(parseAttribute(reader, pool));
        }
        return attributes;
    }

    public static Attribute parseAttribute(ByteReader reader, ConstantPool pool) {
        int nameIndex = reader.readUnsignedShort();
        int length = reader.readInt();
        String name = pool.getUtf8(nameIndex);

        int startOffset = reader.getOffset();
        Attribute result;
        try {
            result = parseAttributeContent(name, length, reader, pool);
        } catch (Exception e) {
            // If parsing fails, skip to the end of the attribute
            reader.setOffset(startOffset + length);
            result = new Attribute(name, length);
        }

        // Verify we consumed exactly the right number of bytes
        int consumed = reader.getOffset() - startOffset;
        if (consumed != length) {
            reader.setOffset(startOffset + length);
        }

        return result;
    }

    private static Attribute parseAttributeContent(String name, int length, ByteReader reader, ConstantPool pool) {
        if ("Code".equals(name)) {
            return parseCodeAttribute(reader, pool, name, length);
        } else if ("ConstantValue".equals(name)) {
            return new ConstantValueAttribute(name, length, reader.readUnsignedShort());
        } else if ("SourceFile".equals(name)) {
            return new SourceFileAttribute(name, length, pool.getUtf8(reader.readUnsignedShort()));
        } else if ("Signature".equals(name)) {
            return new SignatureAttribute(name, length, pool.getUtf8(reader.readUnsignedShort()));
        } else if ("Exceptions".equals(name)) {
            return parseExceptionsAttribute(reader, pool, name, length);
        } else if ("InnerClasses".equals(name)) {
            return parseInnerClassesAttribute(reader, pool, name, length);
        } else if ("LineNumberTable".equals(name)) {
            return parseLineNumberTableAttribute(reader, name, length);
        } else if ("LocalVariableTable".equals(name)) {
            return parseLocalVariableTableAttribute(reader, pool, name, length);
        } else if ("Deprecated".equals(name)) {
            return new Attribute(name, length);
        } else if ("Synthetic".equals(name)) {
            return new Attribute(name, length);
        } else if ("BootstrapMethods".equals(name)) {
            return parseBootstrapMethodsAttribute(reader, name, length);
        } else if ("NestHost".equals(name)) {
            return new NestHostAttribute(name, length, pool.getClassName(reader.readUnsignedShort()));
        } else if ("NestMembers".equals(name)) {
            return parseNestMembersAttribute(reader, pool, name, length);
        } else if ("Record".equals(name)) {
            return parseRecordAttribute(reader, pool, name, length);
        } else if ("PermittedSubclasses".equals(name)) {
            return parsePermittedSubclassesAttribute(reader, pool, name, length);
        } else if ("EnclosingMethod".equals(name)) {
            return parseEnclosingMethodAttribute(reader, pool, name, length);
        } else if ("StackMapTable".equals(name)) {
            reader.skip(length);
            return new StackMapTableAttribute(name, length);
        } else if ("LocalVariableTypeTable".equals(name)) {
            return parseLocalVariableTypeTableAttribute(reader, pool, name, length);
        } else if ("Module".equals(name)) {
            return parseModuleAttribute(reader, pool, name, length);
        } else if ("ModulePackages".equals(name)) {
            return parseModulePackagesAttribute(reader, pool, name, length);
        } else if ("ModuleMainClass".equals(name)) {
            return new ModuleMainClassAttribute(name, length, pool.getClassName(reader.readUnsignedShort()));
        } else if ("SourceDebugExtension".equals(name)) {
            reader.skip(length);
            return new Attribute(name, length);
        } else if ("RuntimeVisibleAnnotations".equals(name)) {
            return parseRuntimeAnnotationsAttribute(reader, pool, name, length, true);
        } else if ("RuntimeInvisibleAnnotations".equals(name)) {
            return parseRuntimeAnnotationsAttribute(reader, pool, name, length, false);
        } else if ("RuntimeVisibleParameterAnnotations".equals(name)) {
            return parseRuntimeParameterAnnotationsAttribute(reader, pool, name, length, true);
        } else if ("RuntimeInvisibleParameterAnnotations".equals(name)) {
            return parseRuntimeParameterAnnotationsAttribute(reader, pool, name, length, false);
        } else if ("MethodParameters".equals(name)) {
            return parseMethodParametersAttribute(reader, pool, name, length);
        } else if ("AnnotationDefault".equals(name)) {
            return parseAnnotationDefaultAttribute(reader, pool, name, length);
        } else if ("RuntimeVisibleTypeAnnotations".equals(name)) {
            reader.skip(length);
            return new Attribute(name, length);
        } else if ("RuntimeInvisibleTypeAnnotations".equals(name)) {
            reader.skip(length);
            return new Attribute(name, length);
        } else {
            // Unknown attribute - skip its content
            reader.skip(length);
            return new Attribute(name, length);
        }
    }

    private static CodeAttribute parseCodeAttribute(ByteReader reader, ConstantPool pool,
                                                     String name, int length) {
        int maxStack = reader.readUnsignedShort();
        int maxLocals = reader.readUnsignedShort();
        int codeLength = reader.readInt();
        byte[] code = reader.readBytes(codeLength);

        int exceptionTableLength = reader.readUnsignedShort();
        CodeAttribute.ExceptionEntry[] exceptionTable = new CodeAttribute.ExceptionEntry[exceptionTableLength];
        for (int i = 0; i < exceptionTableLength; i++) {
            exceptionTable[i] = new CodeAttribute.ExceptionEntry(
                reader.readUnsignedShort(), reader.readUnsignedShort(),
                reader.readUnsignedShort(), reader.readUnsignedShort()
            );
        }

        List<Attribute> attrs = parseAttributes(reader, pool);
        return new CodeAttribute(name, length, maxStack, maxLocals, code, exceptionTable, attrs);
    }

    private static ExceptionsAttribute parseExceptionsAttribute(ByteReader reader, ConstantPool pool,
                                                                 String name, int length) {
        int count = reader.readUnsignedShort();
        String[] exceptions = new String[count];
        for (int i = 0; i < count; i++) {
            exceptions[i] = pool.getClassName(reader.readUnsignedShort());
        }
        return new ExceptionsAttribute(name, length, exceptions);
    }

    private static InnerClassesAttribute parseInnerClassesAttribute(ByteReader reader, ConstantPool pool,
                                                                     String name, int length) {
        int count = reader.readUnsignedShort();
        InnerClassesAttribute.InnerClass[] classes = new InnerClassesAttribute.InnerClass[count];
        for (int i = 0; i < count; i++) {
            int innerClassInfoIndex = reader.readUnsignedShort();
            int outerClassInfoIndex = reader.readUnsignedShort();
            int innerNameIndex = reader.readUnsignedShort();
            int innerAccessFlags = reader.readUnsignedShort();
            classes[i] = new InnerClassesAttribute.InnerClass(
                innerClassInfoIndex > 0 ? pool.getClassName(innerClassInfoIndex) : null,
                outerClassInfoIndex > 0 ? pool.getClassName(outerClassInfoIndex) : null,
                innerNameIndex > 0 ? pool.getUtf8(innerNameIndex) : null,
                innerAccessFlags
            );
        }
        return new InnerClassesAttribute(name, length, classes);
    }

    private static LineNumberTableAttribute parseLineNumberTableAttribute(ByteReader reader,
                                                                          String name, int length) {
        int count = reader.readUnsignedShort();
        LineNumberTableAttribute.LineNumber[] table = new LineNumberTableAttribute.LineNumber[count];
        for (int i = 0; i < count; i++) {
            table[i] = new LineNumberTableAttribute.LineNumber(reader.readUnsignedShort(), reader.readUnsignedShort());
        }
        return new LineNumberTableAttribute(name, length, table);
    }

    private static LocalVariableTableAttribute parseLocalVariableTableAttribute(ByteReader reader, ConstantPool pool,
                                                                                 String name, int length) {
        int count = reader.readUnsignedShort();
        LocalVariableTableAttribute.LocalVariable[] table = new LocalVariableTableAttribute.LocalVariable[count];
        for (int i = 0; i < count; i++) {
            table[i] = new LocalVariableTableAttribute.LocalVariable(
                reader.readUnsignedShort(), reader.readUnsignedShort(),
                pool.getUtf8(reader.readUnsignedShort()), pool.getUtf8(reader.readUnsignedShort()),
                reader.readUnsignedShort()
            );
        }
        return new LocalVariableTableAttribute(name, length, table);
    }

    private static BootstrapMethodsAttribute parseBootstrapMethodsAttribute(ByteReader reader,
                                                                             String name, int length) {
        int count = reader.readUnsignedShort();
        BootstrapMethodsAttribute.BootstrapMethod[] methods = new BootstrapMethodsAttribute.BootstrapMethod[count];
        for (int i = 0; i < count; i++) {
            int methodRef = reader.readUnsignedShort();
            int argCount = reader.readUnsignedShort();
            int[] args = new int[argCount];
            for (int j = 0; j < argCount; j++) {
                args[j] = reader.readUnsignedShort();
            }
            methods[i] = new BootstrapMethodsAttribute.BootstrapMethod(methodRef, args);
        }
        return new BootstrapMethodsAttribute(name, length, methods);
    }

    private static NestMembersAttribute parseNestMembersAttribute(ByteReader reader, ConstantPool pool,
                                                                   String name, int length) {
        int count = reader.readUnsignedShort();
        String[] members = new String[count];
        for (int i = 0; i < count; i++) {
            members[i] = pool.getClassName(reader.readUnsignedShort());
        }
        return new NestMembersAttribute(name, length, members);
    }

    private static RecordAttribute parseRecordAttribute(ByteReader reader, ConstantPool pool,
                                                         String name, int length) {
        int count = reader.readUnsignedShort();
        RecordAttribute.RecordComponent[] components = new RecordAttribute.RecordComponent[count];
        for (int i = 0; i < count; i++) {
            String compName = pool.getUtf8(reader.readUnsignedShort());
            String descriptor = pool.getUtf8(reader.readUnsignedShort());
            List<Attribute> attrs = parseAttributes(reader, pool);
            components[i] = new RecordAttribute.RecordComponent(compName, descriptor, attrs);
        }
        return new RecordAttribute(name, length, components);
    }

    private static PermittedSubclassesAttribute parsePermittedSubclassesAttribute(ByteReader reader, ConstantPool pool,
                                                                                   String name, int length) {
        int count = reader.readUnsignedShort();
        String[] permitted = new String[count];
        for (int i = 0; i < count; i++) {
            permitted[i] = pool.getClassName(reader.readUnsignedShort());
        }
        return new PermittedSubclassesAttribute(name, length, permitted);
    }

    private static EnclosingMethodAttribute parseEnclosingMethodAttribute(ByteReader reader, ConstantPool pool,
                                                                           String name, int length) {
        int classIndex = reader.readUnsignedShort();
        int methodIndex = reader.readUnsignedShort();
        String className = pool.getClassName(classIndex);
        String methodName = null;
        String methodDescriptor = null;
        if (methodIndex != 0) {
            methodName = pool.getNameFromNameAndType(methodIndex);
            methodDescriptor = pool.getDescriptorFromNameAndType(methodIndex);
        }
        return new EnclosingMethodAttribute(name, length, className, methodName, methodDescriptor);
    }

    private static LocalVariableTypeTableAttribute parseLocalVariableTypeTableAttribute(ByteReader reader, ConstantPool pool,
                                                                                         String name, int length) {
        int count = reader.readUnsignedShort();
        LocalVariableTypeTableAttribute.LocalVariableType[] table =
            new LocalVariableTypeTableAttribute.LocalVariableType[count];
        for (int i = 0; i < count; i++) {
            table[i] = new LocalVariableTypeTableAttribute.LocalVariableType(
                reader.readUnsignedShort(), reader.readUnsignedShort(),
                pool.getUtf8(reader.readUnsignedShort()), pool.getUtf8(reader.readUnsignedShort()),
                reader.readUnsignedShort()
            );
        }
        return new LocalVariableTypeTableAttribute(name, length, table);
    }

    private static ModuleAttribute parseModuleAttribute(ByteReader reader, ConstantPool pool,
                                                         String name, int length) {
        int moduleNameIndex = reader.readUnsignedShort();
        String moduleName = pool.getUtf8((Integer) pool.getValue(moduleNameIndex));
        int moduleFlags = reader.readUnsignedShort();
        int moduleVersionIndex = reader.readUnsignedShort();
        String moduleVersion = moduleVersionIndex != 0 ? pool.getUtf8(moduleVersionIndex) : null;

        // requires
        int requiresCount = reader.readUnsignedShort();
        List<ModuleAttribute.Requires> requires = new ArrayList<ModuleAttribute.Requires>(requiresCount);
        for (int i = 0; i < requiresCount; i++) {
            int reqIndex = reader.readUnsignedShort();
            String reqName = pool.getUtf8((Integer) pool.getValue(reqIndex));
            int reqFlags = reader.readUnsignedShort();
            int reqVersionIndex = reader.readUnsignedShort();
            String reqVersion = reqVersionIndex != 0 ? pool.getUtf8(reqVersionIndex) : null;
            requires.add(new ModuleAttribute.Requires(reqName, reqFlags, reqVersion));
        }

        // exports
        int exportsCount = reader.readUnsignedShort();
        List<ModuleAttribute.Exports> exports = new ArrayList<ModuleAttribute.Exports>(exportsCount);
        for (int i = 0; i < exportsCount; i++) {
            int expIndex = reader.readUnsignedShort();
            String expName = pool.getUtf8((Integer) pool.getValue(expIndex));
            int expFlags = reader.readUnsignedShort();
            int expToCount = reader.readUnsignedShort();
            String[] expTo = new String[expToCount];
            for (int j = 0; j < expToCount; j++) {
                int toIndex = reader.readUnsignedShort();
                expTo[j] = pool.getUtf8((Integer) pool.getValue(toIndex));
            }
            exports.add(new ModuleAttribute.Exports(expName, expFlags, expTo));
        }

        // opens
        int opensCount = reader.readUnsignedShort();
        List<ModuleAttribute.Opens> opens = new ArrayList<ModuleAttribute.Opens>(opensCount);
        for (int i = 0; i < opensCount; i++) {
            int openIndex = reader.readUnsignedShort();
            String openName = pool.getUtf8((Integer) pool.getValue(openIndex));
            int openFlags = reader.readUnsignedShort();
            int openToCount = reader.readUnsignedShort();
            String[] openTo = new String[openToCount];
            for (int j = 0; j < openToCount; j++) {
                int toIndex = reader.readUnsignedShort();
                openTo[j] = pool.getUtf8((Integer) pool.getValue(toIndex));
            }
            opens.add(new ModuleAttribute.Opens(openName, openFlags, openTo));
        }

        // uses
        int usesCount = reader.readUnsignedShort();
        List<String> uses = new ArrayList<String>(usesCount);
        for (int i = 0; i < usesCount; i++) {
            uses.add(pool.getClassName(reader.readUnsignedShort()));
        }

        // provides
        int providesCount = reader.readUnsignedShort();
        List<ModuleAttribute.Provides> provides = new ArrayList<ModuleAttribute.Provides>(providesCount);
        for (int i = 0; i < providesCount; i++) {
            String service = pool.getClassName(reader.readUnsignedShort());
            int withCount = reader.readUnsignedShort();
            String[] providers = new String[withCount];
            for (int j = 0; j < withCount; j++) {
                providers[j] = pool.getClassName(reader.readUnsignedShort());
            }
            provides.add(new ModuleAttribute.Provides(service, providers));
        }

        return new ModuleAttribute(name, length, moduleName, moduleFlags, moduleVersion,
            requires, exports, opens, uses, provides);
    }

    private static ModulePackagesAttribute parseModulePackagesAttribute(ByteReader reader, ConstantPool pool,
                                                                         String name, int length) {
        int count = reader.readUnsignedShort();
        String[] packages = new String[count];
        for (int i = 0; i < count; i++) {
            int pkgIndex = reader.readUnsignedShort();
            packages[i] = pool.getUtf8((Integer) pool.getValue(pkgIndex));
        }
        return new ModulePackagesAttribute(name, length, packages);
    }

    private static RuntimeAnnotationsAttribute parseRuntimeAnnotationsAttribute(ByteReader reader, ConstantPool pool,
                                                                                  String name, int length, boolean visible) {
        int count = reader.readUnsignedShort();
        AnnotationInfo[] annotations = new AnnotationInfo[count];
        for (int i = 0; i < count; i++) {
            annotations[i] = parseAnnotation(reader, pool);
        }
        return new RuntimeAnnotationsAttribute(name, length, annotations, visible);
    }

    private static RuntimeParameterAnnotationsAttribute parseRuntimeParameterAnnotationsAttribute(
            ByteReader reader, ConstantPool pool, String name, int length, boolean visible) {
        int numParams = reader.readUnsignedByte();
        AnnotationInfo[][] paramAnnotations = new AnnotationInfo[numParams][];
        for (int i = 0; i < numParams; i++) {
            int numAnnotations = reader.readUnsignedShort();
            paramAnnotations[i] = new AnnotationInfo[numAnnotations];
            for (int j = 0; j < numAnnotations; j++) {
                paramAnnotations[i][j] = parseAnnotation(reader, pool);
            }
        }
        return new RuntimeParameterAnnotationsAttribute(name, length, paramAnnotations, visible);
    }

    private static MethodParametersAttribute parseMethodParametersAttribute(ByteReader reader, ConstantPool pool,
                                                                              String name, int length) {
        int count = reader.readUnsignedByte();
        MethodParametersAttribute.Parameter[] params = new MethodParametersAttribute.Parameter[count];
        for (int i = 0; i < count; i++) {
            int nameIndex = reader.readUnsignedShort();
            int accessFlags = reader.readUnsignedShort();
            String paramName = nameIndex != 0 ? pool.getUtf8(nameIndex) : null;
            params[i] = new MethodParametersAttribute.Parameter(paramName, accessFlags);
        }
        return new MethodParametersAttribute(name, length, params);
    }

    private static AnnotationDefaultAttribute parseAnnotationDefaultAttribute(ByteReader reader, ConstantPool pool,
                                                                                String name, int length) {
        AnnotationInfo.ElementValue defaultValue = parseElementValue(reader, pool);
        return new AnnotationDefaultAttribute(name, length, defaultValue);
    }

    private static AnnotationInfo parseAnnotation(ByteReader reader, ConstantPool pool) {
        int typeIndex = reader.readUnsignedShort();
        String typeDescriptor = pool.getUtf8(typeIndex);
        int numPairs = reader.readUnsignedShort();
        List<AnnotationInfo.ElementValuePair> pairs = new ArrayList<AnnotationInfo.ElementValuePair>(numPairs);
        for (int i = 0; i < numPairs; i++) {
            int elemNameIndex = reader.readUnsignedShort();
            String elemName = pool.getUtf8(elemNameIndex);
            AnnotationInfo.ElementValue value = parseElementValue(reader, pool);
            pairs.add(new AnnotationInfo.ElementValuePair(elemName, value));
        }
        return new AnnotationInfo(typeDescriptor, pairs);
    }

    private static AnnotationInfo.ElementValue parseElementValue(ByteReader reader, ConstantPool pool) {
        char tag = (char) reader.readUnsignedByte();
        Object value;
        if (tag == 'B' || tag == 'C' || tag == 'D' || tag == 'F'
                || tag == 'I' || tag == 'J' || tag == 'S' || tag == 'Z') {
            int constIndex = reader.readUnsignedShort();
            value = pool.getValue(constIndex);
        } else if (tag == 's') {
            int constIndex = reader.readUnsignedShort();
            value = pool.getUtf8(constIndex);
        } else if (tag == 'e') {
            int typeNameIndex = reader.readUnsignedShort();
            int constNameIndex = reader.readUnsignedShort();
            value = new String[]{ pool.getUtf8(typeNameIndex), pool.getUtf8(constNameIndex) };
        } else if (tag == 'c') {
            int classInfoIndex = reader.readUnsignedShort();
            value = pool.getUtf8(classInfoIndex);
        } else if (tag == '@') {
            value = parseAnnotation(reader, pool);
        } else if (tag == '[') {
            int numValues = reader.readUnsignedShort();
            List<AnnotationInfo.ElementValue> elements = new ArrayList<AnnotationInfo.ElementValue>(numValues);
            for (int i = 0; i < numValues; i++) {
                elements.add(parseElementValue(reader, pool));
            }
            value = elements;
        } else {
            value = null;
        }
        return new AnnotationInfo.ElementValue(tag, value);
    }
}
