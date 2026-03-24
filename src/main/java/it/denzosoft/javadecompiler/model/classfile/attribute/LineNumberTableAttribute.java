/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
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
