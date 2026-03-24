/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

/**
 * Pattern matching expression (Java 21+ for switch patterns).
 */
public class PatternMatchExpression extends AbstractExpression {
    private final Expression expression;
    private final Type patternType;
    private final String variableName;

    public PatternMatchExpression(int lineNumber, Expression expression, Type patternType, String variableName) {
        super(lineNumber, PrimitiveType.BOOLEAN);
        this.expression = expression;
        this.patternType = patternType;
        this.variableName = variableName;
    }

    public Expression getExpression() { return expression; }
    public Type getPatternType() { return patternType; }
    public String getVariableName() { return variableName; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
