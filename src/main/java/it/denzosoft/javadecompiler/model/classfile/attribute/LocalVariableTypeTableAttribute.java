/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class LocalVariableTypeTableAttribute extends Attribute {
    private final LocalVariableType[] localVariableTypes;

    public LocalVariableTypeTableAttribute(String name, int length, LocalVariableType[] localVariableTypes) {
        super(name, length);
        this.localVariableTypes = localVariableTypes;
    }

    public LocalVariableType[] getLocalVariableTypes() { return localVariableTypes; }

    public static class LocalVariableType {
        public final int startPc;
        public final int length;
        public final String name;
        public final String signature;
        public final int index;

        public LocalVariableType(int startPc, int length, String name, String signature, int index) {
            this.startPc = startPc;
            this.length = length;
            this.name = name;
            this.signature = signature;
            this.index = index;
        }
    }
}
