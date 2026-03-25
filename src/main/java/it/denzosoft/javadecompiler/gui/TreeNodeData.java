/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.gui;

/**
 * Data model for JAR tree nodes.
 */
public class TreeNodeData {

    public final String name;
    public final String fullPath;
    public final boolean isPackage;
    public final boolean isClass;
    public final boolean isJar;

    public TreeNodeData(String name, String fullPath, boolean isPackage, boolean isClass, boolean isJar) {
        this.name = name;
        this.fullPath = fullPath;
        this.isPackage = isPackage;
        this.isClass = isClass;
        this.isJar = isJar;
    }

    public String toString() {
        return name;
    }
}
