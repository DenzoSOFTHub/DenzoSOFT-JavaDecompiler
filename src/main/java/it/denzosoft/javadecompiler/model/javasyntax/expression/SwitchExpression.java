/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

/**
 * Switch expression (Java 14+).
 */
public class SwitchExpression extends AbstractExpression {
    private final Expression selector;
    private final List<SwitchCase> cases;

    public SwitchExpression(int lineNumber, Type type, Expression selector, List<SwitchCase> cases) {
        super(lineNumber, type);
        this.selector = selector;
        this.cases = cases;
    }

    public Expression getSelector() { return selector; }
    public List<SwitchCase> getCases() { return cases; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }

    public static class SwitchCase {
        private final List<Expression> labels; // null/empty for default
        private final Expression value;
        // BUG-2026-0067: type-pattern case (`case Circle c -> ...`). When patternType != null the case
        // is a pattern label; labels (int) are ignored by the writer.
        private Type patternType;
        private String patternBinding;
        private Expression guard; // optional `when` guard
        private RecordPattern recordPattern; // BUG-2026-0067: record deconstruction case

        public SwitchCase(List<Expression> labels, Expression value) {
            this.labels = labels;
            this.value = value;
        }

        public List<Expression> getLabels() { return labels; }
        public Expression getValue() { return value; }
        public boolean isDefault() { return labels == null || labels.isEmpty(); }

        public Type getPatternType() { return patternType; }
        public void setPatternType(Type t) { this.patternType = t; }
        public String getPatternBinding() { return patternBinding; }
        public void setPatternBinding(String b) { this.patternBinding = b; }
        public boolean isPattern() { return patternType != null; }
        public Expression getGuard() { return guard; }
        public void setGuard(Expression g) { this.guard = g; }
        public RecordPattern getRecordPattern() { return recordPattern; }
        public void setRecordPattern(RecordPattern p) { this.recordPattern = p; }
        public boolean isRecordPattern() { return recordPattern != null; }
    }
}
