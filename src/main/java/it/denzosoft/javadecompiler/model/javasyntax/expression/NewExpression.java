/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class NewExpression extends AbstractExpression {
    private final String internalTypeName;
    private final String descriptor;
    private final List<Expression> arguments;

    public NewExpression(int lineNumber, Type type, String internalTypeName,
                          String descriptor, List<Expression> arguments) {
        super(lineNumber, type);
        this.internalTypeName = internalTypeName;
        this.descriptor = descriptor;
        this.arguments = arguments;
    }

    public String getInternalTypeName() { return internalTypeName; }
    public String getDescriptor() { return descriptor; }
    public List<Expression> getArguments() { return arguments; }
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
