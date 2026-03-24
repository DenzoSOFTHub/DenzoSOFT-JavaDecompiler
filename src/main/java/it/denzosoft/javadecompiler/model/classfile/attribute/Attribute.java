/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

/**
 * Base class for class file attributes.
 */
public class Attribute {
    private final String name;
    private final int length;

    public Attribute(String name, int length) {
        this.name = name;
        this.length = length;
    }

    public String getName() { return name; }
    public int getLength() { return length; }
}
