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
    // START_CHANGE: BUG-2026-0069-20260610-14 - Erasure-generics Stage C: parameterized field
    // signature of the reference's functional interface, unified from the invokedynamic
    // bootstrap's instantiatedMethodType (same channel as LambdaExpression). A method reference
    // against a RAW target often does not even compile (`Consumer c = list::add` — accept(Object)
    // cannot dispatch to add(String)), so the declaration must carry the type arguments.
    private String interfaceGenericSignature;
    // END_CHANGE: BUG-2026-0069-14

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
    // START_CHANGE: BUG-2026-0069-20260610-14 - Stage C accessor pair
    public String getInterfaceGenericSignature() { return interfaceGenericSignature; }
    public void setInterfaceGenericSignature(String sig) { this.interfaceGenericSignature = sig; }
    // END_CHANGE: BUG-2026-0069-14
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
