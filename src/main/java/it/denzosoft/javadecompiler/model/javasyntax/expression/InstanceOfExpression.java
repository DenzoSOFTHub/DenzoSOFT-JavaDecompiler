/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class InstanceOfExpression extends AbstractExpression {
    private final Expression expression;
    private final Type checkType;
    private final String patternVariableName; // Java 16+ pattern matching
    private final RecordPattern recordPattern; // Java 21 deconstruction; null => plain/binding instanceof

    public InstanceOfExpression(int lineNumber, Expression expression, Type checkType) {
        this(lineNumber, expression, checkType, null, null);
    }

    public InstanceOfExpression(int lineNumber, Expression expression, Type checkType, String patternVariableName) {
        this(lineNumber, expression, checkType, patternVariableName, null);
    }

    public InstanceOfExpression(int lineNumber, Expression expression, Type checkType,
                                String patternVariableName, RecordPattern recordPattern) {
        super(lineNumber, PrimitiveType.BOOLEAN);
        this.expression = expression;
        this.checkType = checkType;
        this.patternVariableName = patternVariableName;
        this.recordPattern = recordPattern;
    }

    public Expression getExpression() { return expression; }
    public Type getCheckType() { return checkType; }
    public String getPatternVariableName() { return patternVariableName; }
    public boolean hasPatternVariable() { return patternVariableName != null; }
    public RecordPattern getRecordPattern() { return recordPattern; }
    public boolean hasRecordPattern() { return recordPattern != null; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
