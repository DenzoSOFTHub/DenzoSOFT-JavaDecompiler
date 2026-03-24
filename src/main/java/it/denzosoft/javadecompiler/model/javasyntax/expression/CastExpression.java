/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class CastExpression extends AbstractExpression {
    private final Expression expression;

    public CastExpression(int lineNumber, Type type, Expression expression) {
        super(lineNumber, type);
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
