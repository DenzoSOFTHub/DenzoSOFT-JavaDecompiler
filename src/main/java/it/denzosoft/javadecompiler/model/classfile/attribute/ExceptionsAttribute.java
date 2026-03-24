/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class ExceptionsAttribute extends Attribute {
    private final String[] exceptions;

    public ExceptionsAttribute(String name, int length, String[] exceptions) {
        super(name, length);
        this.exceptions = exceptions;
    }

    public String[] getExceptions() { return exceptions; }
}
