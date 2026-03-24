/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class ForEachStatement implements Statement {
    private final int lineNumber;
    private final Type variableType;
    private final String variableName;
    private final Expression iterable;
    private final Statement body;

    public ForEachStatement(int lineNumber, Type variableType, String variableName,
                             Expression iterable, Statement body) {
        this.lineNumber = lineNumber;
        this.variableType = variableType;
        this.variableName = variableName;
        this.iterable = iterable;
        this.body = body;
    }

    public Type getVariableType() { return variableType; }
    public String getVariableName() { return variableName; }
    public Expression getIterable() { return iterable; }
    public Statement getBody() { return body; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
