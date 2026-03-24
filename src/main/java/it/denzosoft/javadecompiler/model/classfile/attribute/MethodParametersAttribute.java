/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

/**
 * Represents the MethodParameters attribute.
 */
public class MethodParametersAttribute extends Attribute {
    private final Parameter[] parameters;

    public MethodParametersAttribute(String name, int length, Parameter[] parameters) {
        super(name, length);
        this.parameters = parameters;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public static class Parameter {
        public final String name;
        public final int accessFlags;

        public Parameter(String name, int accessFlags) {
            this.name = name;
            this.accessFlags = accessFlags;
        }
    }
}
