/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.VoidType;

public class ReturnExpression extends AbstractExpression {
    private final Expression expression; // null for void return

    public ReturnExpression(int lineNumber) {
        super(lineNumber, VoidType.INSTANCE);
        this.expression = null;
    }

    public ReturnExpression(int lineNumber, Expression expression) {
        super(lineNumber, expression != null ? expression.getType() : VoidType.INSTANCE);
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    public boolean hasExpression() { return expression != null; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
