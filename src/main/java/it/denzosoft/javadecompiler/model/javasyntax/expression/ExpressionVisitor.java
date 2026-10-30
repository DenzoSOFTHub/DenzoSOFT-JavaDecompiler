/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

public interface ExpressionVisitor {
    void visit(IntegerConstantExpression expression);
    void visit(LongConstantExpression expression);
    void visit(FloatConstantExpression expression);
    void visit(DoubleConstantExpression expression);
    void visit(StringConstantExpression expression);
    void visit(NullExpression expression);
    void visit(BooleanExpression expression);
    void visit(ThisExpression expression);
    void visit(LocalVariableExpression expression);
    void visit(FieldAccessExpression expression);
    void visit(MethodInvocationExpression expression);
    void visit(StaticMethodInvocationExpression expression);
    void visit(NewExpression expression);
    void visit(NewArrayExpression expression);
    void visit(ArrayAccessExpression expression);
    void visit(CastExpression expression);
    void visit(InstanceOfExpression expression);
    void visit(BinaryOperatorExpression expression);
    void visit(UnaryOperatorExpression expression);
    void visit(TernaryExpression expression);
    void visit(AssignmentExpression expression);
    void visit(ReturnExpression expression);
    void visit(LambdaExpression expression);
    void visit(MethodReferenceExpression expression);
    void visit(ClassExpression expression);
    void visit(SwitchExpression expression);
    void visit(TextBlockExpression expression);
    void visit(PatternMatchExpression expression);
}
