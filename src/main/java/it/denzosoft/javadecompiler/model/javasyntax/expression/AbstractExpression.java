/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
