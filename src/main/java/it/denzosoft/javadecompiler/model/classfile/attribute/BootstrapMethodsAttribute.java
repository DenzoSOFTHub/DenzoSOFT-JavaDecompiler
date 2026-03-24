/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class BootstrapMethodsAttribute extends Attribute {
    private final BootstrapMethod[] bootstrapMethods;

    public BootstrapMethodsAttribute(String name, int length, BootstrapMethod[] bootstrapMethods) {
        super(name, length);
        this.bootstrapMethods = bootstrapMethods;
    }

    public BootstrapMethod[] getBootstrapMethods() { return bootstrapMethods; }

    public static class BootstrapMethod {
        public final int bootstrapMethodRef;
        public final int[] bootstrapArguments;

        public BootstrapMethod(int bootstrapMethodRef, int[] bootstrapArguments) {
            this.bootstrapMethodRef = bootstrapMethodRef;
            this.bootstrapArguments = bootstrapArguments;
        }
    }
}
