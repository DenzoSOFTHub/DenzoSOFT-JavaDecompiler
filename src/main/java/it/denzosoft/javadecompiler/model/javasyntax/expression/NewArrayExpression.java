/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class NewArrayExpression extends AbstractExpression {
    private final List<Expression> dimensionExpressions;
    // START_CHANGE: ISS-2026-0002-20260323-1 - Support array initializer values for inline init pattern
    private List<Expression> initValues;
    // END_CHANGE: ISS-2026-0002-1

    public NewArrayExpression(int lineNumber, Type type, List<Expression> dimensionExpressions) {
        super(lineNumber, type);
        this.dimensionExpressions = dimensionExpressions;
    }

    public List<Expression> getDimensionExpressions() { return dimensionExpressions; }

    // START_CHANGE: ISS-2026-0002-20260323-2 - Getter/setter for array init values
    public List<Expression> getInitValues() { return initValues; }
    public void addInitValue(Expression value) {
        if (initValues == null) {
            initValues = new java.util.ArrayList<Expression>();
        }
        initValues.add(value);
    }
    public boolean hasInitValues() { return initValues != null && !initValues.isEmpty(); }
    // END_CHANGE: ISS-2026-0002-2

    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
