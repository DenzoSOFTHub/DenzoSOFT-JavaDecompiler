/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
// START_CHANGE: ISS-2026-0007-20260324-6 - Import LabelStatement for labeled loop support
// END_CHANGE: ISS-2026-0007-6

import java.util.ArrayList;
import java.util.List;

/**
 * Detects the pattern: init-statement followed by while(cond) { ... update; }
 * and converts it to a for(init; cond; update) { ... } statement.
 * Applied recursively to nested structures.
 */
public final class ForLoopDetector {

    private ForLoopDetector() {}

    public static List<Statement> convert(List<Statement> statements) {
        return convertWhileToFor(statements);
    }

    public static List<Statement> convertWhileToFor(List<Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            return statements;
        }

        // First, recursively apply to nested structures
        boolean changed = false;
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            Statement converted = convertWhileToForInStatement(stmt);
            if (converted != stmt) {
                changed = true;
            }
            result.add(converted);
        }
        if (changed) {
            statements = result;
        } else {
            statements = new ArrayList<Statement>(statements);
        }

        // Now detect init + while pattern in this list
        for (int i = 0; i < statements.size() - 1; i++) {
            Statement current = statements.get(i);
            Statement next = statements.get(i + 1);

            // START_CHANGE: ISS-2026-0007-20260324-8 - Handle LabelStatement wrapping a WhileStatement
            String loopLabel = null;
            Statement unwrappedNext = next;
            if (next instanceof LabelStatement) {
                LabelStatement ls = (LabelStatement) next;
                loopLabel = ls.getLabel();
                unwrappedNext = ls.getBody();
            }
            if (!(unwrappedNext instanceof WhileStatement)) continue;
            WhileStatement ws = (WhileStatement) unwrappedNext;
            // END_CHANGE: ISS-2026-0007-8

            String initVarName = getInitVarName(current);
            if (initVarName == null) continue;

            if (!conditionUsesVar(ws.getCondition(), initVarName)) continue;

            List<Statement> bodyStmts = getBodyStatements(ws.getBody());
            if (bodyStmts.isEmpty()) continue;

            Statement lastInBody = bodyStmts.get(bodyStmts.size() - 1);
            if (!isUpdateOf(lastInBody, initVarName)) continue;

            Statement init = current;
            Expression condition = ws.getCondition();
            Statement update = lastInBody;
            List<Statement> forBody = new ArrayList<Statement>(bodyStmts.subList(0, bodyStmts.size() - 1));

            // START_CHANGE: ISS-2026-0006-20260324-1 - Multi-init and multi-update for loops
            // Check if there's a second init variable before the current one, with same type
            if (i >= 1) {
                Statement prevStmt = statements.get(i - 1);
                String prevVarName = getInitVarName(prevStmt);
                if (prevVarName != null && conditionUsesVar(ws.getCondition(), prevVarName)) {
                    // Check same type for multi-init declaration
                    if (prevStmt instanceof VariableDeclarationStatement
                        && current instanceof VariableDeclarationStatement) {
                        VariableDeclarationStatement prevDecl = (VariableDeclarationStatement) prevStmt;
                        VariableDeclarationStatement currDecl = (VariableDeclarationStatement) current;
                        if (prevDecl.getType() != null && currDecl.getType() != null
                            && prevDecl.getType().getName().equals(currDecl.getType().getName())) {
                            // Check for second update at end of body
                            if (forBody.size() >= 1) {
                                Statement secondLastInBody = forBody.get(forBody.size() - 1);
                                if (isUpdateOf(secondLastInBody, prevVarName)) {
                                    // Multi-init: combine two inits into a BlockStatement
                                    List<Statement> multiInits = new ArrayList<Statement>();
                                    multiInits.add(prevStmt);
                                    multiInits.add(current);
                                    init = new BlockStatement(ws.getLineNumber(), multiInits);
                                    // Multi-update: combine two updates into a BlockStatement
                                    List<Statement> multiUpdates = new ArrayList<Statement>();
                                    multiUpdates.add(secondLastInBody);
                                    multiUpdates.add(update);
                                    update = new BlockStatement(ws.getLineNumber(), multiUpdates);
                                    forBody = new ArrayList<Statement>(forBody.subList(0, forBody.size() - 1));
                                    // Remove the prev statement
                                    i--;
                                    statements.remove(i);
                                }
                            }
                        }
                    }
                }
            }
            // END_CHANGE: ISS-2026-0006-1

            ForStatement forStmt = new ForStatement(ws.getLineNumber(), init, condition, update,
                new BlockStatement(ws.getLineNumber(), forBody));

            // START_CHANGE: ISS-2026-0007-20260324-9 - Preserve label on converted for-loop
            Statement finalStmt;
            if (loopLabel != null) {
                finalStmt = new LabelStatement(ws.getLineNumber(), loopLabel, forStmt);
            } else {
                finalStmt = forStmt;
            }
            // END_CHANGE: ISS-2026-0007-9
            statements.set(i, finalStmt);
            statements.remove(i + 1);
            i--;
        }

        return statements;
    }

    public static Statement convertWhileToForInStatement(Statement stmt) {
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Statement convertedBody = convertWhileToForInStatement(is.getThenBody());
            if (convertedBody != is.getThenBody()) {
                return new IfStatement(is.getLineNumber(), is.getCondition(), convertedBody);
            }
            return stmt;
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            Statement convertedThen = convertWhileToForInStatement(ies.getThenBody());
            Statement convertedElse = convertWhileToForInStatement(ies.getElseBody());
            if (convertedThen != ies.getThenBody() || convertedElse != ies.getElseBody()) {
                return new IfElseStatement(ies.getLineNumber(), ies.getCondition(), convertedThen, convertedElse);
            }
            return stmt;
        }
        if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            Statement convertedBody = convertWhileToForInStatement(ws.getBody());
            if (convertedBody != ws.getBody()) {
                return new WhileStatement(ws.getLineNumber(), ws.getCondition(), convertedBody);
            }
            return stmt;
        }
        if (stmt instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) stmt;
            Statement convertedBody = convertWhileToForInStatement(dws.getBody());
            if (convertedBody != dws.getBody()) {
                return new DoWhileStatement(dws.getLineNumber(), dws.getCondition(), convertedBody);
            }
            return stmt;
        }
        if (stmt instanceof ForStatement) {
            ForStatement fs = (ForStatement) stmt;
            Statement convertedBody = convertWhileToForInStatement(fs.getBody());
            if (convertedBody != fs.getBody()) {
                return new ForStatement(fs.getLineNumber(), fs.getInit(), fs.getCondition(), fs.getUpdate(), convertedBody);
            }
            return stmt;
        }
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> converted = convertWhileToFor(bs.getStatements());
            if (converted != bs.getStatements()) {
                return new BlockStatement(bs.getLineNumber(), converted);
            }
            return stmt;
        }
        // START_CHANGE: ISS-2026-0007-20260324-7 - Handle LabelStatement wrapping a loop
        if (stmt instanceof LabelStatement) {
            LabelStatement ls = (LabelStatement) stmt;
            Statement convertedBody = convertWhileToForInStatement(ls.getBody());
            if (convertedBody != ls.getBody()) {
                return new LabelStatement(ls.getLineNumber(), ls.getLabel(), convertedBody);
            }
            return stmt;
        }
        // END_CHANGE: ISS-2026-0007-7
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt;
            Statement tryBody = convertWhileToForInStatement(tcs.getTryBody());
            boolean catchesChanged = false;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                Statement convertedCatchBody = convertWhileToForInStatement(cc.body);
                if (convertedCatchBody != cc.body) {
                    catchesChanged = true;
                }
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, convertedCatchBody));
            }
            Statement fin = tcs.hasFinally() ? convertWhileToForInStatement(tcs.getFinallyBody()) : null;
            if (tryBody != tcs.getTryBody() || catchesChanged || fin != tcs.getFinallyBody()) {
                return new TryCatchStatement(tcs.getLineNumber(), tryBody, catches, fin, tcs.getResources());
            }
            return stmt;
        }
        return stmt;
    }

    public static String getInitVarName(Statement stmt) {
        if (stmt instanceof VariableDeclarationStatement) {
            return ((VariableDeclarationStatement) stmt).getName();
        }
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                Expression left = ((AssignmentExpression) expr).getLeft();
                if (left instanceof LocalVariableExpression) {
                    return ((LocalVariableExpression) left).getName();
                }
            }
        }
        return null;
    }

    public static boolean conditionUsesVar(Expression expr, String varName) {
        if (expr == null || varName == null) return false;
        if (expr instanceof LocalVariableExpression) {
            return varName.equals(((LocalVariableExpression) expr).getName());
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            return conditionUsesVar(boe.getLeft(), varName) || conditionUsesVar(boe.getRight(), varName);
        }
        if (expr instanceof UnaryOperatorExpression) {
            return conditionUsesVar(((UnaryOperatorExpression) expr).getExpression(), varName);
        }
        return false;
    }

    public static List<Statement> getBodyStatements(Statement body) {
        if (body instanceof BlockStatement) {
            return ((BlockStatement) body).getStatements();
        }
        List<Statement> list = new ArrayList<Statement>();
        if (body != null) {
            list.add(body);
        }
        return list;
    }

    public static boolean isUpdateOf(Statement stmt, String varName) {
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof UnaryOperatorExpression) {
                UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
                String op = uoe.getOperator();
                if ("++".equals(op) || "--".equals(op)) {
                    if (uoe.getExpression() instanceof LocalVariableExpression) {
                        return varName.equals(((LocalVariableExpression) uoe.getExpression()).getName());
                    }
                }
            }
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    String leftName = ((LocalVariableExpression) ae.getLeft()).getName();
                    if (varName.equals(leftName)) {
                        String op = ae.getOperator();
                        if ("+=".equals(op) || "-=".equals(op)) {
                            return true;
                        }
                        if ("=".equals(op)) {
                            if (ae.getRight() instanceof BinaryOperatorExpression) {
                                BinaryOperatorExpression boe = (BinaryOperatorExpression) ae.getRight();
                                if (boe.getLeft() instanceof LocalVariableExpression) {
                                    return varName.equals(((LocalVariableExpression) boe.getLeft()).getName());
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
