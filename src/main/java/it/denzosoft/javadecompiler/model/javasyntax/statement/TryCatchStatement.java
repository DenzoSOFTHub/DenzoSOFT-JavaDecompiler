/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

public class TryCatchStatement implements Statement {
    private final int lineNumber;
    private final Statement tryBody;
    private final List<CatchClause> catchClauses;
    private final Statement finallyBody;
    private final List<Statement> resources; // try-with-resources

    public TryCatchStatement(int lineNumber, Statement tryBody, List<CatchClause> catchClauses,
                              Statement finallyBody, List<Statement> resources) {
        this.lineNumber = lineNumber;
        this.tryBody = tryBody;
        this.catchClauses = catchClauses;
        this.finallyBody = finallyBody;
        this.resources = resources;
    }

    public Statement getTryBody() { return tryBody; }
    public List<CatchClause> getCatchClauses() { return catchClauses; }
    public Statement getFinallyBody() { return finallyBody; }
    public List<Statement> getResources() { return resources; }
    public boolean hasFinally() { return finallyBody != null; }
    public boolean hasResources() { return resources != null && !resources.isEmpty(); }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { visitor.visit(this); }

    public static class CatchClause {
        public final List<Type> exceptionTypes;
        public final String variableName;
        public final Statement body;

        public CatchClause(List<Type> exceptionTypes, String variableName, Statement body) {
            this.exceptionTypes = exceptionTypes;
            this.variableName = variableName;
            this.body = body;
        }
    }
}
