/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

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
        // START_CHANGE: BUG-2026-0109-20260906-4 - Pattern labels on a STATEMENT switch.
        // SwitchExpression.SwitchCase already carried these for `return switch (...)`; the
        // statement form needs them too so `switch (o) { case Integer i -> ...; }` can be
        // reconstructed instead of leaving the raw SwitchBootstraps.typeSwitch dispatch.
        // A case may carry SEVERAL pattern labels (`case Integer _, Long _ ->`).
        private List<Type> patternTypes;
        private List<String> patternBindings;
        private boolean nullLabel;

        public List<Type> getPatternTypes() { return patternTypes; }
        public List<String> getPatternBindings() { return patternBindings; }
        public boolean isNullLabel() { return nullLabel; }
        public boolean isPattern() { return patternTypes != null && !patternTypes.isEmpty(); }
        public void setPatterns(List<Type> types, List<String> bindings) {
            this.patternTypes = types;
            this.patternBindings = bindings;
        }
        public void setNullLabel(boolean nullLabel) { this.nullLabel = nullLabel; }
        // END_CHANGE: BUG-2026-0109-4

        public SwitchCase(List<Expression> labels, List<Statement> statements) {
            this.labels = labels;
            this.statements = statements;
        }

        public List<Expression> getLabels() { return labels; }
        public List<Statement> getStatements() { return statements; }
        // A pattern case is never the `default` case even though it carries no expression labels.
        public boolean isDefault() {
            return !isPattern() && !nullLabel && (labels == null || labels.isEmpty());
        }
    }
}
