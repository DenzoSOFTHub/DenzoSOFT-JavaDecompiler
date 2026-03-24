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

        public SwitchCase(List<Expression> labels, Expression value) {
            this.labels = labels;
            this.value = value;
        }

        public List<Expression> getLabels() { return labels; }
        public Expression getValue() { return value; }
        public boolean isDefault() { return labels == null || labels.isEmpty(); }
    }
}
