/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;

import java.util.List;

public class SwitchStatement implements Statement {
    private final int lineNumber;
    private final Expression selector;
    private final List<SwitchCase> cases;
    private final boolean arrowStyle; // Java 14+

    public SwitchStatement(int lineNumber, Expression selector, List<SwitchCase> cases, boolean arrowStyle) {
        this.lineNumber = lineNumber;
        this.selector = selector;
        this.cases = cases;
        this.arrowStyle = arrowStyle;
    }

    public Expression getSelector() { return selector; }
    public List<SwitchCase> getCases() { return cases; }
    public boolean isArrowStyle() { return arrowStyle; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }

    public static class SwitchCase {
        private final List<Expression> labels;
        private final List<Statement> statements;

        public SwitchCase(List<Expression> labels, List<Statement> statements) {
            this.labels = labels;
            this.statements = statements;
        }

        public List<Expression> getLabels() { return labels; }
        public List<Statement> getStatements() { return statements; }
        public boolean isDefault() { return labels == null || labels.isEmpty(); }
    }
}
