/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

import java.util.List;

/**
 * Represents the Code attribute of a method.
 */
public class CodeAttribute extends Attribute {
    private final int maxStack;
    private final int maxLocals;
    private final byte[] code;
    private final ExceptionEntry[] exceptionTable;
    private final List<Attribute> attributes;

    public CodeAttribute(String name, int length, int maxStack, int maxLocals,
                         byte[] code, ExceptionEntry[] exceptionTable, List<Attribute> attributes) {
        super(name, length);
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        this.code = code;
        this.exceptionTable = exceptionTable;
        this.attributes = attributes;
    }

    public int getMaxStack() { return maxStack; }
    public int getMaxLocals() { return maxLocals; }
    public byte[] getCode() { return code; }
    public ExceptionEntry[] getExceptionTable() { return exceptionTable; }
    public List<Attribute> getAttributes() { return attributes; }

    public static class ExceptionEntry {
        public final int startPc;
        public final int endPc;
        public final int handlerPc;
        public final int catchType;

        public ExceptionEntry(int startPc, int endPc, int handlerPc, int catchType) {
            this.startPc = startPc;
            this.endPc = endPc;
            this.handlerPc = handlerPc;
            this.catchType = catchType;
        }
    }
}
