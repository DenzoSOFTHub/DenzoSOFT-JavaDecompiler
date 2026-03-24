/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class ModulePackagesAttribute extends Attribute {
    private final String[] packages;

    public ModulePackagesAttribute(String name, int length, String[] packages) {
        super(name, length);
        this.packages = packages;
    }

    public String[] getPackages() { return packages; }
}
