/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class BinaryOperatorExpression extends AbstractExpression {
    private final Expression left;
    private final String operator;
    private final Expression right;

    public BinaryOperatorExpression(int lineNumber, Type type, Expression left, String operator, Expression right) {
        super(lineNumber, type);
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public Expression getLeft() { return left; }
    public String getOperator() { return operator; }
    public Expression getRight() { return right; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return left + " " + operator + " " + right; }
}
