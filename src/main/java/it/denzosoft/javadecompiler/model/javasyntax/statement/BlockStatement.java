/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import java.util.List;

public class BlockStatement implements Statement {
    private final int lineNumber;
    private final List<Statement> statements;

    public BlockStatement(int lineNumber, List<Statement> statements) {
        this.lineNumber = lineNumber;
        this.statements = statements;
    }

    public List<Statement> getStatements() { return statements; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
