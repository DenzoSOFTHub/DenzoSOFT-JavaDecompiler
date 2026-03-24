/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

public class BreakStatement implements Statement {
    private final int lineNumber;
    private final String label;

    public BreakStatement(int lineNumber) { this(lineNumber, null); }
    public BreakStatement(int lineNumber, String label) {
        this.lineNumber = lineNumber;
        this.label = label;
    }

    public String getLabel() { return label; }
    public boolean hasLabel() { return label != null; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
