/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class AssertStatement implements Statement {
    private final int lineNumber;
    private final Expression condition;
    private final Expression message;

    public AssertStatement(int lineNumber, Expression condition, Expression message) {
        this.lineNumber = lineNumber;
        this.condition = condition;
        this.message = message;
    }

    public Expression getCondition() { return condition; }
    public Expression getMessage() { return message; }
    public boolean hasMessage() { return message != null; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
