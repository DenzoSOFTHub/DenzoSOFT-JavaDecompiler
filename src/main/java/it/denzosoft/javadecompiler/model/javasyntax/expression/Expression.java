/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

/**
 * Base interface for all expressions in the Java AST.
 */
public interface Expression {
    Type getType();
    int getLineNumber();
    void accept(ExpressionVisitor visitor);
}
