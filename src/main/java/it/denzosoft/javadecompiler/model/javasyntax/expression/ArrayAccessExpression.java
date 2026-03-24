/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class ArrayAccessExpression extends AbstractExpression {
    private final Expression array;
    private final Expression index;

    public ArrayAccessExpression(int lineNumber, Type type, Expression array, Expression index) {
        super(lineNumber, type);
        this.array = array;
        this.index = index;
    }

    public Expression getArray() { return array; }
    public Expression getIndex() { return index; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
