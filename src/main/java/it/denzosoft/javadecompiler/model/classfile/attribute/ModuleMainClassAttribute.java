/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class ModuleMainClassAttribute extends Attribute {
    private final String mainClass;

    public ModuleMainClassAttribute(String name, int length, String mainClass) {
        super(name, length);
        this.mainClass = mainClass;
    }

    public String getMainClass() { return mainClass; }
}
