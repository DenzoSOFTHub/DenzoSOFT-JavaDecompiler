/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class SourceFileAttribute extends Attribute {
    private final String sourceFile;

    public SourceFileAttribute(String name, int length, String sourceFile) {
        super(name, length);
        this.sourceFile = sourceFile;
    }

    public String getSourceFile() { return sourceFile; }
}
