/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class ConstantValueAttribute extends Attribute {
    private final int constantValueIndex;

    public ConstantValueAttribute(String name, int length, int constantValueIndex) {
        super(name, length);
        this.constantValueIndex = constantValueIndex;
    }

    public int getConstantValueIndex() { return constantValueIndex; }
}
