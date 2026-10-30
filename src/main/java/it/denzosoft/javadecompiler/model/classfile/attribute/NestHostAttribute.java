/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
