/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplifies {@code x = x + y} into {@code x += y} and similar compound assignments.
 */
public final class CompoundAssignmentSimplifier {

    private CompoundAssignmentSimplifier() {}

    public static List<Statement> simplify(List<Statement> statements) {
        return simplifyCompoundAssignments(statements);
    }

    private static List<Statement> simplifyCompoundAssignments(List<Statement> statements) {
        if (statements == null) return statements;
        boolean changed = false;
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            Statement simplified = simplifyCompoundAssignment(stmt);
            if (simplified != stmt) {
                changed = true;
            }
            result.add(simplified);
        }
        return changed ? result : statements;
    }

    private static Statement simplifyCompoundAssignment(Statement stmt) {
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if ("=".equals(ae.getOperator()) && ae.getRight() instanceof BinaryOperatorExpression) {
                    BinaryOperatorExpression boe = (BinaryOperatorExpression) ae.getRight();
                    // Check if left of binary matches assignment target
                    if (expressionsMatch(ae.getLeft(), boe.getLeft())) {
                        String binOp = boe.getOperator();
                        // Only simplify for standard compound-assignable operators
                        if ("+".equals(binOp) || "-".equals(binOp) || "*".equals(binOp) ||
                            "/".equals(binOp) || "%".equals(binOp) || "&".equals(binOp) ||
                            "|".equals(binOp) || "^".equals(binOp) || "<<".equals(binOp) ||
                            ">>".equals(binOp) || ">>>".equals(binOp)) {
                            String compoundOp = binOp + "=";
                            return new ExpressionStatement(new AssignmentExpression(
                                ae.getLineNumber(), ae.getType(), ae.getLeft(), compoundOp, boe.getRight()));
                        }
                    }
                }
            }
        }
        // Recurse into blocks
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> simplified = simplifyCompoundAssignments(bs.getStatements());
            if (simplified != bs.getStatements()) {
                return new BlockStatement(bs.getLineNumber(), simplified);
            }
            return stmt;
        }
        if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            Statement simplifiedBody = simplifyCompoundAssignment(ws.getBody());
            if (simplifiedBody != ws.getBody()) {
                return new WhileStatement(ws.getLineNumber(), ws.getCondition(), simplifiedBody);
            }
            return stmt;
        }
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Statement simplifiedBody = simplifyCompoundAssignment(is.getThenBody());
            if (simplifiedBody != is.getThenBody()) {
                return new IfStatement(is.getLineNumber(), is.getCondition(), simplifiedBody);
            }
            return stmt;
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            Statement simplifiedThen = simplifyCompoundAssignment(ies.getThenBody());
            Statement simplifiedElse = simplifyCompoundAssignment(ies.getElseBody());
            if (simplifiedThen != ies.getThenBody() || simplifiedElse != ies.getElseBody()) {
                return new IfElseStatement(ies.getLineNumber(), ies.getCondition(), simplifiedThen, simplifiedElse);
            }
            return stmt;
        }
        if (stmt instanceof ForStatement) {
            ForStatement fs = (ForStatement) stmt;
            Statement simplifiedBody = simplifyCompoundAssignment(fs.getBody());
            if (simplifiedBody != fs.getBody()) {
                return new ForStatement(fs.getLineNumber(), fs.getInit(), fs.getCondition(), fs.getUpdate(), simplifiedBody);
            }
            return stmt;
        }
        if (stmt instanceof ForEachStatement) {
            ForEachStatement fes = (ForEachStatement) stmt;
            Statement simplifiedBody = simplifyCompoundAssignment(fes.getBody());
            if (simplifiedBody != fes.getBody()) {
                return new ForEachStatement(fes.getLineNumber(), fes.getVariableType(), fes.getVariableName(),
                    fes.getIterable(), simplifiedBody);
            }
            return stmt;
        }
        if (stmt instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) stmt;
            Statement simplifiedBody = simplifyCompoundAssignment(dws.getBody());
            if (simplifiedBody != dws.getBody()) {
                return new DoWhileStatement(dws.getLineNumber(), dws.getCondition(), simplifiedBody);
            }
            return stmt;
        }
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt;
            Statement tryBody = simplifyCompoundAssignment(tcs.getTryBody());
            boolean catchesChanged = false;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                Statement simplifiedCatchBody = simplifyCompoundAssignment(cc.body);
                if (simplifiedCatchBody != cc.body) {
                    catchesChanged = true;
                }
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName,
                    simplifiedCatchBody));
            }
            Statement fin = tcs.hasFinally() ? simplifyCompoundAssignment(tcs.getFinallyBody()) : null;
            if (tryBody != tcs.getTryBody() || catchesChanged || fin != tcs.getFinallyBody()) {
                return new TryCatchStatement(tcs.getLineNumber(), tryBody, catches, fin, tcs.getResources());
            }
            return stmt;
        }
        return stmt;
    }

    private static boolean expressionsMatch(Expression a, Expression b) {
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            return ((LocalVariableExpression) a).getName().equals(((LocalVariableExpression) b).getName());
        }
        if (a instanceof FieldAccessExpression && b instanceof FieldAccessExpression) {
            return ((FieldAccessExpression) a).getName().equals(((FieldAccessExpression) b).getName());
        }
        return false;
    }
}
