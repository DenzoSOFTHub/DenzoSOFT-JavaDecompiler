/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.api.printer;

/**
 * Interface for receiving decompiled Java source output.
 */
public interface Printer {

    void start(int maxLineNumber, int majorVersion, int minorVersion);
    void end();

    void printText(String text);
    void printNumericConstant(String constant);
    void printStringConstant(String constant, String ownerInternalName);
    void printKeyword(String keyword);

    void printDeclaration(int type, String internalTypeName, String name, String descriptor);
    void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName);

    void indent();
    void unindent();

    void startLine(int lineNumber);
    void endLine();
    void extraLine(int count);

    void startMarker(int type);
    void endMarker(int type);

    // Declaration/reference type constants
    int TYPE = 1;
    int FIELD = 2;
    int METHOD = 3;
    int CONSTRUCTOR = 4;
    int PACKAGE = 5;
    int MODULE = 6;
}
