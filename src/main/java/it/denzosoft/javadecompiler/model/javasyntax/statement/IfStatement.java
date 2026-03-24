/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class IfStatement implements Statement {
    private final int lineNumber;
    private final Expression condition;
    private final Statement thenBody;

    public IfStatement(int lineNumber, Expression condition, Statement thenBody) {
        this.lineNumber = lineNumber;
        this.condition = condition;
        this.thenBody = thenBody;
    }

    public Expression getCondition() { return condition; }
    public Statement getThenBody() { return thenBody; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
