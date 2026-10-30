/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
