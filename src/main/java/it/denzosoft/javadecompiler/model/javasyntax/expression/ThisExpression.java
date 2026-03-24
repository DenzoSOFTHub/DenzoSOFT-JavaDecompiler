/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class ThisExpression extends AbstractExpression {
    public ThisExpression(int lineNumber, Type type) {
        super(lineNumber, type);
    }

    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return "this"; }
}
