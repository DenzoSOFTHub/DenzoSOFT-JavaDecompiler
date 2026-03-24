/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class EnclosingMethodAttribute extends Attribute {
    private final String className;
    private final String methodName;
    private final String methodDescriptor;

    public EnclosingMethodAttribute(String name, int length, String className,
                                     String methodName, String methodDescriptor) {
        super(name, length);
        this.className = className;
        this.methodName = methodName;
        this.methodDescriptor = methodDescriptor;
    }

    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getMethodDescriptor() { return methodDescriptor; }
}
