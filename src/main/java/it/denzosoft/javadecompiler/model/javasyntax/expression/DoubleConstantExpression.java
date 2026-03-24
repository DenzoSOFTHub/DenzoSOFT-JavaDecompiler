/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

public class DoubleConstantExpression extends AbstractExpression {
    private final double value;

    public DoubleConstantExpression(int lineNumber, double value) {
        super(lineNumber, PrimitiveType.DOUBLE);
        this.value = value;
    }

    public double getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return String.valueOf(value); }
}
