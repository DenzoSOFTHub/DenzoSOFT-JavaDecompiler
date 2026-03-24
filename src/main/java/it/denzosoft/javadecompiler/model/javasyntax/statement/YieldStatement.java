/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

/**
 * Yield statement (Java 14+ switch expressions).
 */
public class YieldStatement implements Statement {
    private final int lineNumber;
    private final Expression expression;

    public YieldStatement(int lineNumber, Expression expression) {
        this.lineNumber = lineNumber;
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
