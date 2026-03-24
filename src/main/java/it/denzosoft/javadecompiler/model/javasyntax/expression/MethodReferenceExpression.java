/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class MethodReferenceExpression extends AbstractExpression {
    private final Expression object; // null for static/type references
    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;

    public MethodReferenceExpression(int lineNumber, Type type, Expression object,
                                      String ownerInternalName, String methodName, String descriptor) {
        super(lineNumber, type);
        this.object = object;
        this.ownerInternalName = ownerInternalName;
        this.methodName = methodName;
        this.descriptor = descriptor;
    }

    public Expression getObject() { return object; }
    public String getOwnerInternalName() { return ownerInternalName; }
    public String getMethodName() { return methodName; }
    public String getDescriptor() { return descriptor; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
