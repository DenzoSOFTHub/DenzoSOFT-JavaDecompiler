/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class ForStatement implements Statement {
    private final int lineNumber;
    private final Statement init;
    private final Expression condition;
    private final Statement update;
    private final Statement body;

    public ForStatement(int lineNumber, Statement init, Expression condition, Statement update, Statement body) {
        this.lineNumber = lineNumber;
        this.init = init;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }

    public Statement getInit() { return init; }
    public Expression getCondition() { return condition; }
    public Statement getUpdate() { return update; }
    public Statement getBody() { return body; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
