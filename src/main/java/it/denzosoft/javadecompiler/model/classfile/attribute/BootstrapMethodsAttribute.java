/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
