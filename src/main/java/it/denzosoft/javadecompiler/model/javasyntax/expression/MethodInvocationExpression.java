/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class MethodInvocationExpression extends AbstractExpression {
    private final Expression object;
    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final List<Expression> arguments;

    public MethodInvocationExpression(int lineNumber, Type type, Expression object,
                                       String ownerInternalName, String methodName,
                                       String descriptor, List<Expression> arguments) {
        super(lineNumber, type);
        this.object = object;
        this.ownerInternalName = ownerInternalName;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.arguments = arguments;
    }

    public Expression getObject() { return object; }
    public String getOwnerInternalName() { return ownerInternalName; }
    public String getMethodName() { return methodName; }
    public String getDescriptor() { return descriptor; }
    public List<Expression> getArguments() { return arguments; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return object + "." + methodName + "(...)"; }
}
