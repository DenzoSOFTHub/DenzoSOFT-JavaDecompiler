/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public abstract class AbstractExpression implements Expression {
    protected final int lineNumber;
    protected final Type type;

    protected AbstractExpression(int lineNumber, Type type) {
        this.lineNumber = lineNumber;
        this.type = type;
    }

    @Override public Type getType() { return type; }
    @Override public int getLineNumber() { return lineNumber; }
}
