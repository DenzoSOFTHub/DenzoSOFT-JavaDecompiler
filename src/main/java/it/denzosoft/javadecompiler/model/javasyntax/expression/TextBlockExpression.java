/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;

/**
 * Text block expression (Java 15+).
 */
public class TextBlockExpression extends AbstractExpression {
    private final String value;

    public TextBlockExpression(int lineNumber, String value) {
        super(lineNumber, ObjectType.STRING);
        this.value = value;
    }

    public String getValue() { return value; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
