/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

/**
 * Represents RuntimeVisibleParameterAnnotations or RuntimeInvisibleParameterAnnotations attribute.
 */
public class RuntimeParameterAnnotationsAttribute extends Attribute {
    private final AnnotationInfo[][] parameterAnnotations;
    private final boolean visible;

    public RuntimeParameterAnnotationsAttribute(String name, int length,
                                                 AnnotationInfo[][] parameterAnnotations, boolean visible) {
        super(name, length);
        this.parameterAnnotations = parameterAnnotations;
        this.visible = visible;
    }

    public AnnotationInfo[][] getParameterAnnotations() {
        return parameterAnnotations;
    }

    public boolean isVisible() {
        return visible;
    }
}
