/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
