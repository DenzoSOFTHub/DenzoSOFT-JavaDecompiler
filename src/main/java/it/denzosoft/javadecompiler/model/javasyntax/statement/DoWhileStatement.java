/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class DoWhileStatement implements Statement {
    private final int lineNumber;
    private final Expression condition;
    private final Statement body;

    public DoWhileStatement(int lineNumber, Expression condition, Statement body) {
        this.lineNumber = lineNumber;
        this.condition = condition;
        this.body = body;
    }

    public Expression getCondition() { return condition; }
    public Statement getBody() { return body; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
