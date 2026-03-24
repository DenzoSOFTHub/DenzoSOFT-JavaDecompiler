/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

public interface StatementVisitor {
    void visit(ExpressionStatement statement);
    void visit(ReturnStatement statement);
    void visit(IfStatement statement);
    void visit(IfElseStatement statement);
    void visit(WhileStatement statement);
    void visit(DoWhileStatement statement);
    void visit(ForStatement statement);
    void visit(ForEachStatement statement);
    void visit(SwitchStatement statement);
    void visit(TryCatchStatement statement);
    void visit(ThrowStatement statement);
    void visit(BlockStatement statement);
    void visit(VariableDeclarationStatement statement);
    void visit(BreakStatement statement);
    void visit(ContinueStatement statement);
    void visit(LabelStatement statement);
    void visit(SynchronizedStatement statement);
    void visit(AssertStatement statement);
    void visit(YieldStatement statement);
}
