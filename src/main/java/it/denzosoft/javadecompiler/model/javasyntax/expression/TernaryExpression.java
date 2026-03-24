/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class TernaryExpression extends AbstractExpression {
    private final Expression condition;
    private final Expression trueExpression;
    private final Expression falseExpression;

    public TernaryExpression(int lineNumber, Type type, Expression condition,
                              Expression trueExpression, Expression falseExpression) {
        super(lineNumber, type);
        this.condition = condition;
        this.trueExpression = trueExpression;
        this.falseExpression = falseExpression;
    }

    public Expression getCondition() { return condition; }
    public Expression getTrueExpression() { return trueExpression; }
    public Expression getFalseExpression() { return falseExpression; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
