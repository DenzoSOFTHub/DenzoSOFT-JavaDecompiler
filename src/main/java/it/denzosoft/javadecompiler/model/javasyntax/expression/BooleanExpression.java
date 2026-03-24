/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

public class BooleanExpression extends AbstractExpression {
    public static final BooleanExpression TRUE = new BooleanExpression(0, true);
    public static final BooleanExpression FALSE = new BooleanExpression(0, false);

    private final boolean value;

    public BooleanExpression(int lineNumber, boolean value) {
        super(lineNumber, PrimitiveType.BOOLEAN);
        this.value = value;
    }

    public boolean getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return String.valueOf(value); }
}
