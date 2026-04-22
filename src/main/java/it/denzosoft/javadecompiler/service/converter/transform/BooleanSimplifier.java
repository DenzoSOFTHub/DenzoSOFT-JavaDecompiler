/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplifies boolean comparison artifacts from bytecode decompilation.
 * <ul>
 *   <li>{@code expr != 0} &rarr; {@code expr} (when expr looks boolean)</li>
 *   <li>{@code expr == 0} &rarr; {@code !expr}</li>
 *   <li>{@code return 1} &rarr; {@code return true}</li>
 *   <li>{@code return 0} &rarr; {@code return false}</li>
 * </ul>
 */
public final class BooleanSimplifier {

    private BooleanSimplifier() {}

    /** Whether the current method has a boolean return type. */
    private static boolean methodReturnIsBoolean = false;

    public static List<Statement> simplify(List<Statement> statements) {
        return simplifyBooleanComparisons(statements);
    }

    /**
     * Simplify with knowledge of the method's return type.
     * When returnIsBoolean is true, {@code return 1} becomes {@code return true}.
     * When false, {@code return 1} stays as-is to avoid incorrect type conversion.
     */
    public static List<Statement> simplify(List<Statement> statements, boolean returnIsBoolean) {
        methodReturnIsBoolean = returnIsBoolean;
        List<Statement> result = simplifyBooleanComparisons(statements);
        methodReturnIsBoolean = false;
        return result;
    }

    public static List<Statement> simplifyBooleanComparisons(List<Statement> statements) {
        if (statements == null) return statements;
        boolean changed = false;
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            Statement simplified = simplifyBooleanInStatement(stmt);
            if (simplified != stmt) {
                changed = true;
            }
            result.add(simplified);
        }
        return changed ? result : statements;
    }

    public static Statement simplifyBooleanInStatement(Statement stmt) {
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Expression simplifiedCond = simplifyBooleanExpr(is.getCondition());
            Statement simplifiedBody = simplifyBooleanInStatement(is.getThenBody());
            if (simplifiedCond == is.getCondition() && simplifiedBody == is.getThenBody()) {
                return stmt;
            }
            return new IfStatement(is.getLineNumber(), simplifiedCond, simplifiedBody);
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            Expression simplifiedCond = simplifyBooleanExpr(ies.getCondition());
            Statement simplifiedThen = simplifyBooleanInStatement(ies.getThenBody());
            Statement simplifiedElse = simplifyBooleanInStatement(ies.getElseBody());
            if (simplifiedCond == ies.getCondition() && simplifiedThen == ies.getThenBody() && simplifiedElse == ies.getElseBody()) {
                return stmt;
            }
            return new IfElseStatement(ies.getLineNumber(), simplifiedCond, simplifiedThen, simplifiedElse);
        }
        if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            Expression simplifiedCond = simplifyBooleanExpr(ws.getCondition());
            Statement simplifiedBody = simplifyBooleanInStatement(ws.getBody());
            if (simplifiedCond == ws.getCondition() && simplifiedBody == ws.getBody()) {
                return stmt;
            }
            return new WhileStatement(ws.getLineNumber(), simplifiedCond, simplifiedBody);
        }
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> simplified = simplifyBooleanComparisons(bs.getStatements());
            if (simplified == bs.getStatements()) {
                return stmt;
            }
            return new BlockStatement(bs.getLineNumber(), simplified);
        }
        if (stmt instanceof ForEachStatement) {
            ForEachStatement fes = (ForEachStatement) stmt;
            Statement simplifiedBody = simplifyBooleanInStatement(fes.getBody());
            if (simplifiedBody == fes.getBody()) {
                return stmt;
            }
            return new ForEachStatement(fes.getLineNumber(), fes.getVariableType(), fes.getVariableName(),
                fes.getIterable(), simplifiedBody);
        }
        if (stmt instanceof ForStatement) {
            ForStatement fs = (ForStatement) stmt;
            Expression cond = fs.getCondition() != null ? simplifyBooleanExpr(fs.getCondition()) : null;
            Statement simplifiedBody = simplifyBooleanInStatement(fs.getBody());
            if (cond == fs.getCondition() && simplifiedBody == fs.getBody()) {
                return stmt;
            }
            return new ForStatement(fs.getLineNumber(), fs.getInit(), cond, fs.getUpdate(),
                simplifiedBody);
        }
        if (stmt instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) stmt;
            Expression simplifiedCond = simplifyBooleanExpr(dws.getCondition());
            Statement simplifiedBody = simplifyBooleanInStatement(dws.getBody());
            if (simplifiedCond == dws.getCondition() && simplifiedBody == dws.getBody()) {
                return stmt;
            }
            return new DoWhileStatement(dws.getLineNumber(), simplifiedCond, simplifiedBody);
        }
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt;
            Statement tryBody = simplifyBooleanInStatement(tcs.getTryBody());
            boolean catchesChanged = false;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                Statement simplifiedCatchBody = simplifyBooleanInStatement(cc.body);
                if (simplifiedCatchBody != cc.body) {
                    catchesChanged = true;
                }
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName,
                    simplifiedCatchBody));
            }
            Statement fin = tcs.hasFinally() ? simplifyBooleanInStatement(tcs.getFinallyBody()) : null;
            if (tryBody == tcs.getTryBody() && !catchesChanged && fin == tcs.getFinallyBody()) {
                return stmt;
            }
            return new TryCatchStatement(tcs.getLineNumber(), tryBody, catches, fin, tcs.getResources());
        }
        if (stmt instanceof SwitchStatement) {
            SwitchStatement ss = (SwitchStatement) stmt;
            boolean casesChanged = false;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase sc : ss.getCases()) {
                List<Statement> simplifiedStmts = simplifyBooleanComparisons(sc.getStatements());
                if (simplifiedStmts != sc.getStatements()) {
                    casesChanged = true;
                }
                cases.add(new SwitchStatement.SwitchCase(sc.getLabels(), simplifiedStmts));
            }
            if (!casesChanged) {
                return stmt;
            }
            return new SwitchStatement(ss.getLineNumber(), ss.getSelector(), cases, ss.isArrowStyle());
        }
        if (stmt instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) stmt;
            if (rs.hasExpression()) {
                Expression expr = rs.getExpression();
                // Only convert return 1/0 to true/false when method returns boolean
                if (methodReturnIsBoolean && expr instanceof IntegerConstantExpression) {
                    int val = ((IntegerConstantExpression) expr).getValue();
                    if (val == 1) return new ReturnStatement(rs.getLineNumber(), BooleanExpression.TRUE);
                    if (val == 0) return new ReturnStatement(rs.getLineNumber(), BooleanExpression.FALSE);
                }
                if (methodReturnIsBoolean && expr instanceof TernaryExpression) {
                    TernaryExpression te = (TernaryExpression) expr;
                    // condition ? 1 : 0 -> condition (boolean return)
                    if (isIntConstant(te.getTrueExpression(), 1) && isIntConstant(te.getFalseExpression(), 0)) {
                        return new ReturnStatement(rs.getLineNumber(), simplifyBooleanExpr(te.getCondition()));
                    }
                    // condition ? 0 : 1 -> !condition
                    if (isIntConstant(te.getTrueExpression(), 0) && isIntConstant(te.getFalseExpression(), 1)) {
                        Expression negated = new UnaryOperatorExpression(te.getLineNumber(), PrimitiveType.BOOLEAN, "!",
                            simplifyBooleanExpr(te.getCondition()), true);
                        return new ReturnStatement(rs.getLineNumber(), negated);
                    }
                    Expression simplifiedCond = simplifyBooleanExpr(te.getCondition());
                    if (simplifiedCond != te.getCondition()) {
                        return new ReturnStatement(rs.getLineNumber(),
                            new TernaryExpression(te.getLineNumber(), te.getType(),
                                simplifiedCond, te.getTrueExpression(), te.getFalseExpression()));
                    }
                }
                Expression simplified = simplifyBooleanExpr(expr);
                if (simplified != expr) {
                    return new ReturnStatement(rs.getLineNumber(), simplified);
                }
            }
        }
        if (stmt instanceof ExpressionStatement) {
            ExpressionStatement es = (ExpressionStatement) stmt;
            Expression expr = es.getExpression();
            // START_CHANGE: BUG-2026-0057-20260421-1 - Simplify the condition of an orphan
            // ternary-as-statement. The writer later wraps such a statement in
            // `Object _orphan = ...`; if we leave the condition as `x != 0` it renders as an
            // illegal boolean-vs-int comparison in cases where x is already boolean.
            if (expr instanceof TernaryExpression) {
                Expression simplifiedExpr = simplifyBooleanExpr(expr);
                if (simplifiedExpr != expr) {
                    return new ExpressionStatement(simplifiedExpr);
                }
            }
            // END_CHANGE: BUG-2026-0057-1
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getRight() instanceof TernaryExpression) {
                    TernaryExpression te = (TernaryExpression) ae.getRight();
                    // x = cond ? 1 : 0 -> x = cond
                    if (isIntConstant(te.getTrueExpression(), 1) && isIntConstant(te.getFalseExpression(), 0)) {
                        return new ExpressionStatement(new AssignmentExpression(ae.getLineNumber(),
                            ae.getType(), ae.getLeft(), ae.getOperator(), simplifyBooleanExpr(te.getCondition())));
                    }
                    Expression simplifiedCond = simplifyBooleanExpr(te.getCondition());
                    if (simplifiedCond != te.getCondition()) {
                        return new ExpressionStatement(new AssignmentExpression(ae.getLineNumber(),
                            ae.getType(), ae.getLeft(), ae.getOperator(),
                            new TernaryExpression(te.getLineNumber(), te.getType(),
                                simplifiedCond, te.getTrueExpression(), te.getFalseExpression())));
                    }
                }
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    Type varType = ((LocalVariableExpression) ae.getLeft()).getType();
                    if (varType == PrimitiveType.BOOLEAN) {
                        if (ae.getRight() instanceof IntegerConstantExpression) {
                            int val = ((IntegerConstantExpression) ae.getRight()).getValue();
                            if (val == 1) {
                                return new ExpressionStatement(new AssignmentExpression(ae.getLineNumber(),
                                    ae.getType(), ae.getLeft(), ae.getOperator(), BooleanExpression.TRUE));
                            }
                            if (val == 0) {
                                return new ExpressionStatement(new AssignmentExpression(ae.getLineNumber(),
                                    ae.getType(), ae.getLeft(), ae.getOperator(), BooleanExpression.FALSE));
                            }
                        }
                    }
                }
                // START_CHANGE: BUG-2026-0043-20260327-1 - Convert int 0/1 to boolean for field assignments
                if (ae.getLeft() instanceof FieldAccessExpression) {
                    String fDesc = ((FieldAccessExpression) ae.getLeft()).getDescriptor();
                    if ("Z".equals(fDesc) && ae.getRight() instanceof IntegerConstantExpression) {
                        int val = ((IntegerConstantExpression) ae.getRight()).getValue();
                        Expression boolVal = val != 0 ? BooleanExpression.TRUE : BooleanExpression.FALSE;
                        return new ExpressionStatement(new AssignmentExpression(ae.getLineNumber(),
                            ae.getType(), ae.getLeft(), ae.getOperator(), boolVal));
                    }
                }
                // END_CHANGE: BUG-2026-0043-1
            }
        }
        if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            if (vds.hasInitializer() && vds.getType() == PrimitiveType.BOOLEAN) {
                if (vds.getInitializer() instanceof IntegerConstantExpression) {
                    int val = ((IntegerConstantExpression) vds.getInitializer()).getValue();
                    Expression boolVal = val != 0 ? BooleanExpression.TRUE : BooleanExpression.FALSE;
                    return new VariableDeclarationStatement(vds.getLineNumber(), vds.getType(),
                        vds.getName(), boolVal, vds.isFinal(), vds.isVar());
                }
            }
        }
        return stmt;
    }

    public static Expression simplifyBooleanExpr(Expression expr) {
        // START_CHANGE: BUG-2026-0047-20260327-1 - Simplify ternary boolean patterns
        if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            Expression cond = simplifyBooleanExpr(te.getCondition());
            // cond ? 1 : 0 → cond
            if (isIntConstant(te.getTrueExpression(), 1) && isIntConstant(te.getFalseExpression(), 0)) {
                return cond;
            }
            // cond ? 0 : 1 → !cond
            if (isIntConstant(te.getTrueExpression(), 0) && isIntConstant(te.getFalseExpression(), 1)) {
                return new UnaryOperatorExpression(te.getLineNumber(), PrimitiveType.BOOLEAN, "!", cond, true);
            }
            // cond ? true : false → cond (when true/false are BooleanExpression)
            if (te.getTrueExpression() instanceof BooleanExpression && te.getFalseExpression() instanceof BooleanExpression) {
                if (((BooleanExpression) te.getTrueExpression()).getValue() && !((BooleanExpression) te.getFalseExpression()).getValue()) {
                    return cond;
                }
            }
            if (cond != te.getCondition()) {
                return new TernaryExpression(te.getLineNumber(), te.getType(), cond, te.getTrueExpression(), te.getFalseExpression());
            }
            return expr;
        }
        // END_CHANGE: BUG-2026-0047-1
        // START_CHANGE: BUG-2026-0060-20260421-1 - Recurse into unary negation so that
        // `!(a == 0 && b == 0 && ...)` simplifies its operand too; without this, compound
        // boolean expressions like the Spring `isRestTemplateMethod` pattern keep the raw
        // `x.equals(y) == 0` comparisons and fail to compile against boolean method returns.
        if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            Expression simplified = simplifyBooleanExpr(uoe.getExpression());
            if (simplified != uoe.getExpression()) {
                return new UnaryOperatorExpression(uoe.getLineNumber(), uoe.getType(),
                    uoe.getOperator(), simplified, uoe.isPrefix());
            }
            return expr;
        }
        // END_CHANGE: BUG-2026-0060-1
        if (!(expr instanceof BinaryOperatorExpression)) return expr;
        BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
        Expression left = boe.getLeft();
        Expression right = boe.getRight();
        String op = boe.getOperator();

        // START_CHANGE: ISS-2026-0009-20260323-1 - Simplify lcmp/fcmp/dcmp patterns: (a <=> b) OP 0 -> a OP b
        if (left instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression inner = (BinaryOperatorExpression) left;
            if ("<=>".equals(inner.getOperator()) && isZeroConstant(right)) {
                return new BinaryOperatorExpression(boe.getLineNumber(), PrimitiveType.BOOLEAN,
                    inner.getLeft(), op, inner.getRight());
            }
        }
        if (right instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression inner = (BinaryOperatorExpression) right;
            if ("<=>".equals(inner.getOperator()) && isZeroConstant(left)) {
                // 0 OP (a <=> b) -> flip: a FLIPPED_OP b
                String flipped = flipOperator(op);
                if (flipped != null) {
                    return new BinaryOperatorExpression(boe.getLineNumber(), PrimitiveType.BOOLEAN,
                        inner.getLeft(), flipped, inner.getRight());
                }
            }
        }
        // END_CHANGE: ISS-2026-0009-1

        if ("!=".equals(op) && isZeroConstant(right) && looksBoolean(left)) {
            return left;
        }
        if ("!=".equals(op) && isZeroConstant(left) && looksBoolean(right)) {
            return right;
        }
        if ("==".equals(op) && isZeroConstant(right) && looksBoolean(left)) {
            return new UnaryOperatorExpression(boe.getLineNumber(), PrimitiveType.BOOLEAN, "!", left, true);
        }
        if ("==".equals(op) && isZeroConstant(left) && looksBoolean(right)) {
            return new UnaryOperatorExpression(boe.getLineNumber(), PrimitiveType.BOOLEAN, "!", right, true);
        }
        // Recurse into && and || sub-expressions
        if ("&&".equals(op) || "||".equals(op)) {
            Expression simplifiedLeft = simplifyBooleanExpr(left);
            Expression simplifiedRight = simplifyBooleanExpr(right);
            if (simplifiedLeft != left || simplifiedRight != right) {
                return new BinaryOperatorExpression(boe.getLineNumber(), PrimitiveType.BOOLEAN,
                    simplifiedLeft, op, simplifiedRight);
            }
        }
        return expr;
    }

    private static boolean isIntConstant(Expression expr, int value) {
        return expr instanceof IntegerConstantExpression && ((IntegerConstantExpression) expr).getValue() == value;
    }

    public static boolean isZeroConstant(Expression expr) {
        return expr instanceof IntegerConstantExpression && ((IntegerConstantExpression) expr).getValue() == 0;
    }

    public static boolean looksBoolean(Expression expr) {
        // START_CHANGE: BUG-2026-0027-20260325-1 - Only treat method calls as boolean if return type is boolean or unknown (not int)
        if (expr instanceof MethodInvocationExpression) {
            Type mt = expr.getType();
            // If we know the return type is int, don't treat as boolean
            if (mt == PrimitiveType.INT) return false;
            return true;
        }
        if (expr instanceof StaticMethodInvocationExpression) {
            Type mt = expr.getType();
            if (mt == PrimitiveType.INT) return false;
            return true;
        }
        // END_CHANGE: BUG-2026-0027-1
        if (expr instanceof InstanceOfExpression) return true;
        if (expr instanceof FieldAccessExpression) return true;
        if (expr instanceof LocalVariableExpression) {
            Type t = ((LocalVariableExpression) expr).getType();
            if (t == PrimitiveType.BOOLEAN) return true;
        }
        // START_CHANGE: BUG-2026-0067-20260421-1 - ArrayAccessExpression on a boolean[] array
        // is boolean-typed. Previously `reported[0] != 0` (where `reported` is boolean[])
        // wasn't simplified, producing `incomparable types: boolean and int`.
        if (expr instanceof ArrayAccessExpression) {
            Type t = expr.getType();
            if (t == PrimitiveType.BOOLEAN) return true;
        }
        // END_CHANGE: BUG-2026-0067-1
        if (expr instanceof UnaryOperatorExpression) {
            String op = ((UnaryOperatorExpression) expr).getOperator();
            return "!".equals(op);
        }
        if (expr instanceof BinaryOperatorExpression) {
            String op = ((BinaryOperatorExpression) expr).getOperator();
            return "==".equals(op) || "!=".equals(op) || "<".equals(op) ||
                   "<=".equals(op) || ">".equals(op) || ">=".equals(op) ||
                   "&&".equals(op) || "||".equals(op);
        }
        return false;
    }

    // START_CHANGE: ISS-2026-0009-20260323-2 - Flip comparison operator for reversed operands
    private static String flipOperator(String op) {
        if ("<".equals(op)) return ">";
        if (">".equals(op)) return "<";
        if ("<=".equals(op)) return ">=";
        if (">=".equals(op)) return "<=";
        if ("==".equals(op)) return "==";
        if ("!=".equals(op)) return "!=";
        return null;
    }
    // END_CHANGE: ISS-2026-0009-2
}
