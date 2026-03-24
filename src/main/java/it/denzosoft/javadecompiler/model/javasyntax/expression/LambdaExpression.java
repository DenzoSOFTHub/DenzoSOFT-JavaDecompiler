/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class LambdaExpression extends AbstractExpression {
    private final List<String> parameterNames;
    private final List<Type> parameterTypes;
    private final Statement body;

    public LambdaExpression(int lineNumber, Type type, List<String> parameterNames,
                             List<Type> parameterTypes, Statement body) {
        super(lineNumber, type);
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.body = body;
    }

    public List<String> getParameterNames() { return parameterNames; }
    public List<Type> getParameterTypes() { return parameterTypes; }
    public Statement getBody() { return body; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
