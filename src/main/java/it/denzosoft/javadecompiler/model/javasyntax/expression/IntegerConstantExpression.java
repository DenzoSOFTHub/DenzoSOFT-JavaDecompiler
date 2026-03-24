/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

public class IntegerConstantExpression extends AbstractExpression {
    private static final IntegerConstantExpression[] CACHE = new IntegerConstantExpression[7];
    static {
        for (int i = 0; i < 7; i++) {
            CACHE[i] = new IntegerConstantExpression(0, i - 1); // -1 through 5
        }
    }

    public static IntegerConstantExpression valueOf(int lineNumber, int value) {
        if (lineNumber == 0 && value >= -1 && value <= 5) {
            return CACHE[value + 1];
        }
        return new IntegerConstantExpression(lineNumber, value);
    }

    private final int value;

    public IntegerConstantExpression(int lineNumber, int value) {
        super(lineNumber, PrimitiveType.INT);
        this.value = value;
    }

    public int getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return String.valueOf(value); }
}
