/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

public class FloatConstantExpression extends AbstractExpression {
    private final float value;

    public FloatConstantExpression(int lineNumber, float value) {
        super(lineNumber, PrimitiveType.FLOAT);
        this.value = value;
    }

    public float getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return value + "F"; }
}
