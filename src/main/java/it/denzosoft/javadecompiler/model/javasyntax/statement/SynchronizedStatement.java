/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

public class SynchronizedStatement implements Statement {
    private final int lineNumber;
    private final Expression monitor;
    private final Statement body;

    public SynchronizedStatement(int lineNumber, Expression monitor, Statement body) {
        this.lineNumber = lineNumber;
        this.monitor = monitor;
        this.body = body;
    }

    public Expression getMonitor() { return monitor; }
    public Statement getBody() { return body; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
