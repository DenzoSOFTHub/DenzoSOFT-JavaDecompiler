/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.ThrowStatement;
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
        // START_CHANGE: BUG-2026-0066-20260610-6 - Non-final: the flow builder coerces leaked
        // int-constant arm values (iconst_0/iconst_1) to boolean literals when the merge's
        // consumer is boolean-typed (`return switch(d.ordinal()) { case 5, 6 -> true; ... }`).
        private Expression value;
        // END_CHANGE: BUG-2026-0066-6
        // BUG-2026-0067: type-pattern case (`case Circle c -> ...`). When patternType != null the case
        // is a pattern label; labels (int) are ignored by the writer.
        private Type patternType;
        private String patternBinding;
        private Expression guard; // optional `when` guard
        private RecordPattern recordPattern; // BUG-2026-0067: record deconstruction case
        // START_CHANGE: BUG-2026-0066-20260610-1 - Block-bodied and throwing arms.
        // bodyStatements are the straight-line statements executed before the arm's value is
        // yielded (`case X -> { stmts; yield value; }`). When value == null the arm completes
        // abruptly: bodyStatements end with a ThrowStatement
        // (`default -> throw new MatchException(null, null);`).
        private List<Statement> bodyStatements;
        // END_CHANGE: BUG-2026-0066-1

        public SwitchCase(List<Expression> labels, Expression value) {
            this.labels = labels;
            this.value = value;
        }

        public List<Expression> getLabels() { return labels; }
        public Expression getValue() { return value; }
        // START_CHANGE: BUG-2026-0066-20260610-7 - See change -6 (boolean arm coercion).
        public void setValue(Expression v) { this.value = v; }
        // END_CHANGE: BUG-2026-0066-7
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

        // START_CHANGE: BUG-2026-0066-20260610-2 - Accessors for block/throwing arms.
        public List<Statement> getBodyStatements() { return bodyStatements; }
        public void setBodyStatements(List<Statement> stmts) { this.bodyStatements = stmts; }
        public boolean hasBodyStatements() { return bodyStatements != null && !bodyStatements.isEmpty(); }
        public boolean isThrowing() {
            return value == null && bodyStatements != null && !bodyStatements.isEmpty()
                && bodyStatements.get(bodyStatements.size() - 1) instanceof ThrowStatement;
        }
        // END_CHANGE: BUG-2026-0066-2
    }
}
