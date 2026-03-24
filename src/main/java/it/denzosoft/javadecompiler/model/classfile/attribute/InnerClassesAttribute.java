/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class InnerClassesAttribute extends Attribute {
    private final InnerClass[] classes;

    public InnerClassesAttribute(String name, int length, InnerClass[] classes) {
        super(name, length);
        this.classes = classes;
    }

    public InnerClass[] getClasses() { return classes; }

    public static class InnerClass {
        public final String innerClassName;
        public final String outerClassName;
        public final String innerName;
        public final int accessFlags;

        public InnerClass(String innerClassName, String outerClassName, String innerName, int accessFlags) {
            this.innerClassName = innerClassName;
            this.outerClassName = outerClassName;
            this.innerName = innerName;
            this.accessFlags = accessFlags;
        }
    }
}
