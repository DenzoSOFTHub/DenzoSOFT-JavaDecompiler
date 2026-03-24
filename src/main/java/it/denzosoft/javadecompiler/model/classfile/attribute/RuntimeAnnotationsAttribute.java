/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

/**
 * Represents RuntimeVisibleAnnotations or RuntimeInvisibleAnnotations attribute.
 */
public class RuntimeAnnotationsAttribute extends Attribute {
    private final AnnotationInfo[] annotations;
    private final boolean visible;

    public RuntimeAnnotationsAttribute(String name, int length, AnnotationInfo[] annotations, boolean visible) {
        super(name, length);
        this.annotations = annotations;
        this.visible = visible;
    }

    public AnnotationInfo[] getAnnotations() {
        return annotations;
    }

    public boolean isVisible() {
        return visible;
    }
}
