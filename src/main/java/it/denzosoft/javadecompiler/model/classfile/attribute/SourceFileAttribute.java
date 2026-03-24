/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class SourceFileAttribute extends Attribute {
    private final String sourceFile;

    public SourceFileAttribute(String name, int length, String sourceFile) {
        super(name, length);
        this.sourceFile = sourceFile;
    }

    public String getSourceFile() { return sourceFile; }
}
