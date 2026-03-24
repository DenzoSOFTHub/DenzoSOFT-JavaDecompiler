/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class ClassExpression extends AbstractExpression {
    private final Type classType;

    public ClassExpression(int lineNumber, Type classType) {
        super(lineNumber, ObjectType.CLASS);
        this.classType = classType;
    }

    public Type getClassType() { return classType; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
