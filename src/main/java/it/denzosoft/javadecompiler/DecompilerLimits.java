/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler;

/**
 * Security limits to prevent excessive resource consumption during decompilation.
 */
public class DecompilerLimits {
    public static final int MAX_METHOD_BYTECODE_SIZE = 65535;
    public static final int MAX_RECURSION_DEPTH = 200;
    public static final int MAX_AST_NODES = 100000;
    public static final int MAX_CONSTANT_POOL_SIZE = 65535;
    public static final long MAX_DECOMPILE_TIME_MS = 30000;

    private DecompilerLimits() {}
}
