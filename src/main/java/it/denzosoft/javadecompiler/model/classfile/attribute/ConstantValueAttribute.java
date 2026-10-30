/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
