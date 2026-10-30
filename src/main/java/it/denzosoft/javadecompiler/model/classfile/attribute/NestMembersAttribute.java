/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class NestMembersAttribute extends Attribute {
    private final String[] members;

    public NestMembersAttribute(String name, int length, String[] members) {
        super(name, length);
        this.members = members;
    }

    public String[] getMembers() { return members; }
}
