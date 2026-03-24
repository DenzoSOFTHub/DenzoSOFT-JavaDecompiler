/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class LocalVariableExpression extends AbstractExpression {
    private final String name;
    private final int index;

    public LocalVariableExpression(int lineNumber, Type type, String name, int index) {
        super(lineNumber, type);
        this.name = name;
        this.index = index;
    }

    public String getName() { return name; }
    public int getIndex() { return index; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return name; }
}
