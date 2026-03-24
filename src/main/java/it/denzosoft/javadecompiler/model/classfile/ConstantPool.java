/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile;

import it.denzosoft.javadecompiler.util.ByteReader;

/**
 * Represents the constant pool of a Java class file.
 * Supports all constant pool tag types through Java 25.
 */
public class ConstantPool {

    // Constant pool tags
    public static final int CONSTANT_Utf8 = 1;
    public static final int CONSTANT_Integer = 3;
    public static final int CONSTANT_Float = 4;
    public static final int CONSTANT_Long = 5;
    public static final int CONSTANT_Double = 6;
    public static final int CONSTANT_Class = 7;
    public static final int CONSTANT_String = 8;
    public static final int CONSTANT_Fieldref = 9;
    public static final int CONSTANT_Methodref = 10;
    public static final int CONSTANT_InterfaceMethodref = 11;
    public static final int CONSTANT_NameAndType = 12;
    public static final int CONSTANT_MethodHandle = 15;
    public static final int CONSTANT_MethodType = 16;
    public static final int CONSTANT_Dynamic = 17;
    public static final int CONSTANT_InvokeDynamic = 18;
    public static final int CONSTANT_Module = 19;
    public static final int CONSTANT_Package = 20;

    private final int[] tags;
    private final Object[] values;
    private final int size;

    public ConstantPool(int size) {
        this.size = size;
        this.tags = new int[size];
        this.values = new Object[size];
    }

    public int getSize() {
        return size;
    }

    public int getTag(int index) {
        return tags[index];
    }

    public void setEntry(int index, int tag, Object value) {
        tags[index] = tag;
        values[index] = value;
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue(int index) {
        return (T) values[index];
    }

    /**
     * Get UTF-8 string at the given constant pool index.
     */
    public String getUtf8(int index) {
        if (index <= 0 || index >= size) return null;
        return (String) values[index];
    }

    /**
     * Get class name (internal form) at the given constant pool index.
     * The constant pool entry at `index` must be CONSTANT_Class, which
     * contains a reference to a CONSTANT_Utf8 entry.
     */
    public String getClassName(int index) {
        if (index <= 0 || index >= size) return null;
        if (tags[index] != CONSTANT_Class) return null;
        int nameIndex = ((Integer) values[index]).intValue();
        return getUtf8(nameIndex);
    }

    /**
     * Get string constant at the given constant pool index.
     */
    public String getStringConstant(int index) {
        if (index <= 0 || index >= size) return null;
        if (tags[index] != CONSTANT_String) return null;
        int utf8Index = ((Integer) values[index]).intValue();
        return getUtf8(utf8Index);
    }

    /**
     * Get the name from a NameAndType entry.
     */
    public String getNameFromNameAndType(int index) {
        if (tags[index] != CONSTANT_NameAndType) return null;
        int[] nat = (int[]) values[index];
        return getUtf8(nat[0]);
    }

    /**
     * Get the descriptor from a NameAndType entry.
     */
    public String getDescriptorFromNameAndType(int index) {
        if (tags[index] != CONSTANT_NameAndType) return null;
        int[] nat = (int[]) values[index];
        return getUtf8(nat[1]);
    }

    /**
     * Get the class name from a Fieldref/Methodref/InterfaceMethodref entry.
     */
    public String getMemberClassName(int index) {
        int tag = tags[index];
        if (tag != CONSTANT_Fieldref && tag != CONSTANT_Methodref && tag != CONSTANT_InterfaceMethodref) return null;
        int[] ref = (int[]) values[index];
        return getClassName(ref[0]);
    }

    /**
     * Get the member name from a Fieldref/Methodref/InterfaceMethodref entry.
     */
    public String getMemberName(int index) {
        int tag = tags[index];
        if (tag != CONSTANT_Fieldref && tag != CONSTANT_Methodref && tag != CONSTANT_InterfaceMethodref) return null;
        int[] ref = (int[]) values[index];
        return getNameFromNameAndType(ref[1]);
    }

    /**
     * Get the member descriptor from a Fieldref/Methodref/InterfaceMethodref entry.
     */
    public String getMemberDescriptor(int index) {
        int tag = tags[index];
        if (tag != CONSTANT_Fieldref && tag != CONSTANT_Methodref && tag != CONSTANT_InterfaceMethodref) return null;
        int[] ref = (int[]) values[index];
        return getDescriptorFromNameAndType(ref[1]);
    }

    /**
     * Parse the constant pool from a ByteReader.
     */
    public static ConstantPool parse(ByteReader reader) {
        int count = reader.readUnsignedShort();
        ConstantPool pool = new ConstantPool(count);

        for (int i = 1; i < count; i++) {
            int tag = reader.readUnsignedByte();
            switch (tag) {
                case CONSTANT_Utf8: {
                    int length = reader.readUnsignedShort();
                    pool.setEntry(i, tag, reader.readUTF8(length));
                    break;
                }
                case CONSTANT_Integer:
                    pool.setEntry(i, tag, reader.readInt());
                    break;
                case CONSTANT_Float:
                    pool.setEntry(i, tag, reader.readFloat());
                    break;
                case CONSTANT_Long: {
                    pool.setEntry(i, tag, reader.readLong());
                    i++; // longs take 2 slots
                    break;
                }
                case CONSTANT_Double: {
                    pool.setEntry(i, tag, reader.readDouble());
                    i++; // doubles take 2 slots
                    break;
                }
                case CONSTANT_Class:
                case CONSTANT_String:
                case CONSTANT_MethodType:
                case CONSTANT_Module:
                case CONSTANT_Package:
                    pool.setEntry(i, tag, reader.readUnsignedShort());
                    break;
                case CONSTANT_Fieldref:
                case CONSTANT_Methodref:
                case CONSTANT_InterfaceMethodref:
                case CONSTANT_NameAndType:
                case CONSTANT_InvokeDynamic:
                case CONSTANT_Dynamic:
                    pool.setEntry(i, tag, new int[]{reader.readUnsignedShort(), reader.readUnsignedShort()});
                    break;
                case CONSTANT_MethodHandle:
                    pool.setEntry(i, tag, new int[]{reader.readUnsignedByte(), reader.readUnsignedShort()});
                    break;
                default:
                    throw new IllegalArgumentException("Unknown constant pool tag: " + tag + " at index " + i);
            }
        }
        return pool;
    }
}
