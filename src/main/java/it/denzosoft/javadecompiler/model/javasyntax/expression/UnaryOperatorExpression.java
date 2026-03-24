/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class UnaryOperatorExpression extends AbstractExpression {
    private final String operator;
    private final Expression expression;
    private final boolean prefix;

    public UnaryOperatorExpression(int lineNumber, Type type, String operator, Expression expression, boolean prefix) {
        super(lineNumber, type);
        this.operator = operator;
        this.expression = expression;
        this.prefix = prefix;
    }

    public String getOperator() { return operator; }
    public Expression getExpression() { return expression; }
    public boolean isPrefix() { return prefix; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
