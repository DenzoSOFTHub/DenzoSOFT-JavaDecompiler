/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class CastExpression extends AbstractExpression {
    private final Expression expression;

    public CastExpression(int lineNumber, Type type, Expression expression) {
        super(lineNumber, type);
        this.expression = expression;
    }

    public Expression getExpression() { return expression; }
    // START_CHANGE: BUG-2026-0142-20261017-1 - A cast the source needs even when the operand's
    // decoded type already looks like the target (int stack values typed short/char/boolean):
    // the writer must never drop it as redundant.
    private boolean explicit;
    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }
    // END_CHANGE: BUG-2026-0142-1
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
