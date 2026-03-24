/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class IfElseStatement implements Statement {
    private final int lineNumber;
    private final Expression condition;
    private final Statement thenBody;
    private final Statement elseBody;

    public IfElseStatement(int lineNumber, Expression condition, Statement thenBody, Statement elseBody) {
        this.lineNumber = lineNumber;
        this.condition = condition;
        this.thenBody = thenBody;
        this.elseBody = elseBody;
    }

    public Expression getCondition() { return condition; }
    public Statement getThenBody() { return thenBody; }
    public Statement getElseBody() { return elseBody; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
