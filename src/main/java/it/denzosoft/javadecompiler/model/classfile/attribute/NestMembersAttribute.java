/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
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
