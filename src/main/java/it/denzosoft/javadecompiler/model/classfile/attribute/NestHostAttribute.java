/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class NestHostAttribute extends Attribute {
    private final String hostClassName;

    public NestHostAttribute(String name, int length, String hostClassName) {
        super(name, length);
        this.hostClassName = hostClassName;
    }

    public String getHostClassName() { return hostClassName; }
}
