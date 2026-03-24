/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class ExpressionStatement implements Statement {
    private final Expression expression;

    public ExpressionStatement(Expression expression) {
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    @Override public int getLineNumber() { return expression.getLineNumber(); }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
