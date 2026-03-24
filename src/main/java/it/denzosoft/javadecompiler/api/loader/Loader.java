/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.api.loader;

/**
 * Interface for loading compiled class file data.
 */
public interface Loader {

    /**
     * Check if a class with the given internal name can be loaded.
     *
     * @param internalName internal class name (e.g., "java/lang/String")
     * @return true if the class data is available
     */
    boolean canLoad(String internalName);

    /**
     * Load the raw bytes of a class file.
     *
     * @param internalName internal class name
     * @return the class file bytes
     */
    byte[] load(String internalName) throws Exception;
}
