/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
