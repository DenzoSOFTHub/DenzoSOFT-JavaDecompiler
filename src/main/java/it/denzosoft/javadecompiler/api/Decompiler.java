/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.api;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import java.util.Map;

/**
 * Main decompiler interface. Converts compiled .class files back to Java source code.
 */
public interface Decompiler {

    /**
     * Decompile a class file to Java source code.
     *
     * @param loader       provides access to class file bytes
     * @param printer      receives the decompiled output
     * @param internalName the internal name of the class (e.g., "com/example/MyClass")
     */
    void decompile(Loader loader, Printer printer, String internalName) throws Exception;

    /**
     * Decompile a class file with additional configuration options.
     */
    void decompile(Loader loader, Printer printer, String internalName, Map<String, Object> configuration) throws Exception;
}
