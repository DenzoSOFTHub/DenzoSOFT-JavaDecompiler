/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
