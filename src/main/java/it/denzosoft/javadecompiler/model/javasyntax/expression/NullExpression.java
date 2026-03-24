/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class NullExpression extends AbstractExpression {
    public static final NullExpression INSTANCE = new NullExpression();

    public NullExpression() { super(0, ObjectType.OBJECT); }
    public NullExpression(Type type) { super(0, type); }

    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return "null"; }
}
