/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

public class LabelStatement implements Statement {
    private final int lineNumber;
    private final String label;
    private final Statement body;

    public LabelStatement(int lineNumber, String label, Statement body) {
        this.lineNumber = lineNumber;
        this.label = label;
        this.body = body;
    }

    public String getLabel() { return label; }
    public Statement getBody() { return body; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
