/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class LocalVariableTableAttribute extends Attribute {
    private final LocalVariable[] localVariables;

    public LocalVariableTableAttribute(String name, int length, LocalVariable[] localVariables) {
        super(name, length);
        this.localVariables = localVariables;
    }

    public LocalVariable[] getLocalVariables() { return localVariables; }

    public static class LocalVariable {
        public final int startPc;
        public final int length;
        public final String name;
        public final String descriptor;
        public final int index;

        public LocalVariable(int startPc, int length, String name, String descriptor, int index) {
            this.startPc = startPc;
            this.length = length;
            this.name = name;
            this.descriptor = descriptor;
            this.index = index;
        }
    }
}
