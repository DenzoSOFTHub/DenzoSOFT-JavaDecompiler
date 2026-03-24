/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

import java.util.List;

/**
 * Represents a single annotation from the class file.
 */
public class AnnotationInfo {
    private final String typeDescriptor;
    private final List<ElementValuePair> elementValuePairs;

    public AnnotationInfo(String typeDescriptor, List<ElementValuePair> elementValuePairs) {
        this.typeDescriptor = typeDescriptor;
        this.elementValuePairs = elementValuePairs;
    }

    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    public List<ElementValuePair> getElementValuePairs() {
        return elementValuePairs;
    }

    /**
     * A name-value pair within an annotation.
     */
    public static class ElementValuePair {
        private final String name;
        private final ElementValue value;

        public ElementValuePair(String name, ElementValue value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public ElementValue getValue() {
            return value;
        }
    }

    /**
     * Represents an element value in an annotation.
     * The tag indicates the type:
     * B,C,D,F,I,J,S,Z - primitive constant
     * s - String
     * e - enum constant (value is String[]{typeDescriptor, constName})
     * c - class (value is String descriptor)
     * @ - nested annotation (value is AnnotationInfo)
     * [ - array (value is List of ElementValue)
     */
    public static class ElementValue {
        private final char tag;
        private final Object value;

        public ElementValue(char tag, Object value) {
            this.tag = tag;
            this.value = value;
        }

        public char getTag() {
            return tag;
        }

        public Object getValue() {
            return value;
        }
    }
}
