/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;

public class StringConstantExpression extends AbstractExpression {
    private final String value;

    public StringConstantExpression(int lineNumber, String value) {
        super(lineNumber, ObjectType.STRING);
        this.value = value;
    }

    public String getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return "\"" + value + "\""; }
}
