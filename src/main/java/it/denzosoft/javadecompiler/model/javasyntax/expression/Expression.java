/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
