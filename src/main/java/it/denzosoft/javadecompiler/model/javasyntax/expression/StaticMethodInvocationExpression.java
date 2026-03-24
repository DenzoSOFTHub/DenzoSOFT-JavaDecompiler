/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class StaticMethodInvocationExpression extends AbstractExpression {
    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final List<Expression> arguments;

    public StaticMethodInvocationExpression(int lineNumber, Type type,
                                             String ownerInternalName, String methodName,
                                             String descriptor, List<Expression> arguments) {
        super(lineNumber, type);
        this.ownerInternalName = ownerInternalName;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.arguments = arguments;
    }

    public String getOwnerInternalName() { return ownerInternalName; }
    public String getMethodName() { return methodName; }
    public String getDescriptor() { return descriptor; }
    public List<Expression> getArguments() { return arguments; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
