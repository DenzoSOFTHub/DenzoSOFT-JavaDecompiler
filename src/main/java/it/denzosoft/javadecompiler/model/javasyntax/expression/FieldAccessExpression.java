/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class FieldAccessExpression extends AbstractExpression {
    private final Expression object;
    private final String ownerInternalName;
    private final String name;
    private final String descriptor;

    public FieldAccessExpression(int lineNumber, Type type, Expression object,
                                  String ownerInternalName, String name, String descriptor) {
        super(lineNumber, type);
        this.object = object;
        this.ownerInternalName = ownerInternalName;
        this.name = name;
        this.descriptor = descriptor;
    }

    public Expression getObject() { return object; }
    public String getOwnerInternalName() { return ownerInternalName; }
    public String getName() { return name; }
    public String getDescriptor() { return descriptor; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return object + "." + name; }
}
