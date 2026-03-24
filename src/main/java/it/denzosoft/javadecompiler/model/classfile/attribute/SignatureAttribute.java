/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class SignatureAttribute extends Attribute {
    private final String signature;

    public SignatureAttribute(String name, int length, String signature) {
        super(name, length);
        this.signature = signature;
    }

    public String getSignature() { return signature; }
}
