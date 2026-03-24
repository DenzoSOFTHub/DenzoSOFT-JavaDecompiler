/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.deserializer;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.model.classfile.ClassFile;
import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.FieldInfo;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.AttributeParser;
import it.denzosoft.javadecompiler.model.message.Message;
import it.denzosoft.javadecompiler.model.processor.Processor;
import it.denzosoft.javadecompiler.util.ByteReader;
import it.denzosoft.javadecompiler.util.StringConstants;

import java.util.List;

/**
 * Deserializes binary .class file data into the ClassFile model.
 * Supports class file format versions 45.0 (Java 1.0) through 69.0 (Java 25).
 */
public class ClassFileDeserializer implements Processor {

    @Override
    public void process(Message message) throws Exception {
        Loader loader = message.getHeader("loader");
        String internalName = message.getHeader("mainInternalTypeName");

        byte[] data = loader.load(internalName);
        if (data == null) {
            throw new IllegalArgumentException("Cannot load class: " + internalName);
        }

        ClassFile classFile = deserialize(data);
        message.setHeader("classFile", classFile);
        message.setBody(classFile);
    }

    public ClassFile deserialize(byte[] data) {
        ByteReader reader = new ByteReader(data);

        // Magic number
        int magic = reader.readInt();
        if (magic != StringConstants.MAGIC) {
            throw new IllegalArgumentException(
                String.format("Invalid class file: bad magic number 0x%08X (expected 0xCAFEBABE)", magic));
        }

        // Version
        int minorVersion = reader.readUnsignedShort();
        int majorVersion = reader.readUnsignedShort();

        if (majorVersion > StringConstants.MAX_SUPPORTED_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported class file version: " + majorVersion + "." + minorVersion +
                " (max supported: " + StringConstants.MAX_SUPPORTED_MAJOR_VERSION + ".0 / Java " +
                StringConstants.javaVersionFromMajor(StringConstants.MAX_SUPPORTED_MAJOR_VERSION) + ")");
        }

        // Constant pool
        ConstantPool constantPool = ConstantPool.parse(reader);

        // Access flags
        int accessFlags = reader.readUnsignedShort();

        // This class
        int thisClassIndex = reader.readUnsignedShort();
        String thisClassName = constantPool.getClassName(thisClassIndex);

        // Super class
        int superClassIndex = reader.readUnsignedShort();
        String superClassName = superClassIndex > 0 ? constantPool.getClassName(superClassIndex) : null;

        // Interfaces
        int interfacesCount = reader.readUnsignedShort();
        String[] interfaces = new String[interfacesCount];
        for (int i = 0; i < interfacesCount; i++) {
            interfaces[i] = constantPool.getClassName(reader.readUnsignedShort());
        }

        // Fields
        int fieldsCount = reader.readUnsignedShort();
        FieldInfo[] fields = new FieldInfo[fieldsCount];
        for (int i = 0; i < fieldsCount; i++) {
            int fieldAccessFlags = reader.readUnsignedShort();
            String fieldName = constantPool.getUtf8(reader.readUnsignedShort());
            String fieldDescriptor = constantPool.getUtf8(reader.readUnsignedShort());
            List<Attribute> fieldAttributes = AttributeParser.parseAttributes(reader, constantPool);
            fields[i] = new FieldInfo(fieldAccessFlags, fieldName, fieldDescriptor, fieldAttributes);
        }

        // Methods
        int methodsCount = reader.readUnsignedShort();
        MethodInfo[] methods = new MethodInfo[methodsCount];
        for (int i = 0; i < methodsCount; i++) {
            int methodAccessFlags = reader.readUnsignedShort();
            String methodName = constantPool.getUtf8(reader.readUnsignedShort());
            String methodDescriptor = constantPool.getUtf8(reader.readUnsignedShort());
            List<Attribute> methodAttributes = AttributeParser.parseAttributes(reader, constantPool);
            methods[i] = new MethodInfo(methodAccessFlags, methodName, methodDescriptor, methodAttributes);
        }

        // Class attributes
        List<Attribute> classAttributes = AttributeParser.parseAttributes(reader, constantPool);

        return new ClassFile(minorVersion, majorVersion, constantPool, accessFlags,
                             thisClassName, superClassName, interfaces, fields, methods, classAttributes);
    }
}
