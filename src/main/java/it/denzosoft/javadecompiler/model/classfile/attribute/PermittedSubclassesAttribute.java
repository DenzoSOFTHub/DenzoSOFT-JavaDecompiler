/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class PermittedSubclassesAttribute extends Attribute {
    private final String[] permittedSubclasses;

    public PermittedSubclassesAttribute(String name, int length, String[] permittedSubclasses) {
        super(name, length);
        this.permittedSubclasses = permittedSubclasses;
    }

    public String[] getPermittedSubclasses() { return permittedSubclasses; }
}
