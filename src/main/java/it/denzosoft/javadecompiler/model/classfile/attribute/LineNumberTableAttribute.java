/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

public class LineNumberTableAttribute extends Attribute {
    private final LineNumber[] lineNumbers;

    public LineNumberTableAttribute(String name, int length, LineNumber[] lineNumbers) {
        super(name, length);
        this.lineNumbers = lineNumbers;
    }

    public LineNumber[] getLineNumbers() { return lineNumbers; }

    public int getMaxLineNumber() {
        int max = 0;
        for (LineNumber ln : lineNumbers) {
            if (ln.lineNumber > max) max = ln.lineNumber;
        }
        return max;
    }

    public static class LineNumber {
        public final int startPc;
        public final int lineNumber;

        public LineNumber(int startPc, int lineNumber) {
            this.startPc = startPc;
            this.lineNumber = lineNumber;
        }
    }
}
