/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
