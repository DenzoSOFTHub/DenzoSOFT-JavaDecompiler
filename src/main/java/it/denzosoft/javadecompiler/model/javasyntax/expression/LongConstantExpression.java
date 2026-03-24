/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

public class LongConstantExpression extends AbstractExpression {
    private final long value;

    public LongConstantExpression(int lineNumber, long value) {
        super(lineNumber, PrimitiveType.LONG);
        this.value = value;
    }

    public long getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return value + "L"; }
}
