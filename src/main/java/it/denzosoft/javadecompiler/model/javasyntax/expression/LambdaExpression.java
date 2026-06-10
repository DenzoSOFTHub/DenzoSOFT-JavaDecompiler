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
    // START_CHANGE: BUG-2026-0069-20260610-8 - Erasure-generics Stage C: parameterized field
    // signature of the lambda's functional interface (e.g.
    // "Ljava/util/function/Function<Ljava/lang/Integer;Ljava/lang/Integer;>;"), unified from the
    // invokedynamic bootstrap's instantiatedMethodType. Consumed by storeLocal to type the
    // declaration of a local initialized with this lambda; null when not derivable.
    private String interfaceGenericSignature;
    // END_CHANGE: BUG-2026-0069-8

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
    // START_CHANGE: BUG-2026-0069-20260610-8 - Stage C accessor pair
    public String getInterfaceGenericSignature() { return interfaceGenericSignature; }
    public void setInterfaceGenericSignature(String sig) { this.interfaceGenericSignature = sig; }
    // END_CHANGE: BUG-2026-0069-8
    @Override public void accept(ExpressionVisitor visitor) { visitor.visit(this); }
}
