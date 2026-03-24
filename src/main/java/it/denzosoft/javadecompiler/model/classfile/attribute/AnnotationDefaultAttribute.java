/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

/**
 * Represents the AnnotationDefault attribute for annotation type elements.
 */
public class AnnotationDefaultAttribute extends Attribute {
    private final AnnotationInfo.ElementValue defaultValue;

    public AnnotationDefaultAttribute(String name, int length, AnnotationInfo.ElementValue defaultValue) {
        super(name, length);
        this.defaultValue = defaultValue;
    }

    public AnnotationInfo.ElementValue getDefaultValue() {
        return defaultValue;
    }
}
