/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class ReturnStatement implements Statement {
    private final int lineNumber;
    private final Expression expression;

    public ReturnStatement(int lineNumber) { this(lineNumber, null); }

    public ReturnStatement(int lineNumber, Expression expression) {
        this.lineNumber = lineNumber;
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    public boolean hasExpression() { return expression != null; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
