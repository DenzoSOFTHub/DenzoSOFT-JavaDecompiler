/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

public class VariableDeclarationStatement implements Statement {
    private final int lineNumber;
    private final Type type;
    private final String name;
    private final Expression initializer;
    private final boolean isFinal;
    private final boolean isVar; // Java 10+ local variable type inference
    // START_CHANGE: BUG-2026-0065-20260421-1 - Raw JVM generic signature for this local, if
    // available from LocalVariableTypeTable (e.g. `Ljava/util/List<Lcom/foo/Bar;>;`). When set,
    // the writer renders the declared type using this signature so generic type arguments are
    // preserved -- critical for lambda inference (e.g. `(a, b) -> a.compareTo(b)` needs the
    // element type of `results` to resolve `a`).
    private String genericSignature;
    // END_CHANGE: BUG-2026-0065-1

    public VariableDeclarationStatement(int lineNumber, Type type, String name, Expression initializer,
                                         boolean isFinal, boolean isVar) {
        this.lineNumber = lineNumber;
        this.type = type;
        this.name = name;
        this.initializer = initializer;
        this.isFinal = isFinal;
        this.isVar = isVar;
    }

    public Type getType() { return type; }
    public String getName() { return name; }
    public Expression getInitializer() { return initializer; }
    public boolean hasInitializer() { return initializer != null; }
    public boolean isFinal() { return isFinal; }
    public boolean isVar() { return isVar; }
    // START_CHANGE: BUG-2026-0065-20260421-2 - Access to the generic signature
    public String getGenericSignature() { return genericSignature; }
    public void setGenericSignature(String sig) { this.genericSignature = sig; }
    // END_CHANGE: BUG-2026-0065-2
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }
}
