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

            // BUG-2026-0080: if the loop counter is referenced AFTER the loop, it cannot be declared in the
            // for-init (that would scope it to the loop). Keep the declaration before the loop and use an
            // empty for-init.
            boolean keepDeclOutside = false;
            if (init == current && current instanceof VariableDeclarationStatement) {
                String cv = ((VariableDeclarationStatement) current).getName();
                for (int k = i + 2; k < statements.size(); k++) {
                    if (nameUsedInStatement(statements.get(k), cv)) { keepDeclOutside = true; break; }
                }
            }
            ForStatement forStmt = new ForStatement(ws.getLineNumber(),
                keepDeclOutside ? null : init, condition, update,
                new BlockStatement(ws.getLineNumber(), forBody));

            // START_CHANGE: ISS-2026-0007-20260324-9 - Preserve label on converted for-loop
            Statement finalStmt;
            if (loopLabel != null) {
                finalStmt = new LabelStatement(ws.getLineNumber(), loopLabel, forStmt);
            } else {
                finalStmt = forStmt;
            }
            // END_CHANGE: ISS-2026-0007-9
            if (keepDeclOutside) {
                statements.set(i + 1, finalStmt); // while -> for; the declaration stays at index i
            } else {
                statements.set(i, finalStmt);
                statements.remove(i + 1);
                i--;
            }
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

    // BUG-2026-0080: does statement `s` reference local `v` (so it can't be scoped to a for-init)?
    static boolean nameUsedInStatement(Statement s, String v) {
        if (s == null) return false;
        if (s instanceof ExpressionStatement) return exprUses(((ExpressionStatement) s).getExpression(), v);
        if (s instanceof ReturnStatement) return ((ReturnStatement) s).hasExpression() && exprUses(((ReturnStatement) s).getExpression(), v);
        if (s instanceof ThrowStatement) return exprUses(((ThrowStatement) s).getExpression(), v);
        if (s instanceof VariableDeclarationStatement) { VariableDeclarationStatement d = (VariableDeclarationStatement) s; return d.hasInitializer() && exprUses(d.getInitializer(), v); }
        if (s instanceof IfStatement) return exprUses(((IfStatement) s).getCondition(), v) || nameUsedInStatement(((IfStatement) s).getThenBody(), v);
        if (s instanceof IfElseStatement) { IfElseStatement x = (IfElseStatement) s; return exprUses(x.getCondition(), v) || nameUsedInStatement(x.getThenBody(), v) || nameUsedInStatement(x.getElseBody(), v); }
        if (s instanceof BlockStatement) { for (Statement c : ((BlockStatement) s).getStatements()) if (nameUsedInStatement(c, v)) return true; return false; }
        if (s instanceof WhileStatement) return exprUses(((WhileStatement) s).getCondition(), v) || nameUsedInStatement(((WhileStatement) s).getBody(), v);
        if (s instanceof DoWhileStatement) return exprUses(((DoWhileStatement) s).getCondition(), v) || nameUsedInStatement(((DoWhileStatement) s).getBody(), v);
        if (s instanceof ForStatement) { ForStatement f = (ForStatement) s; return nameUsedInStatement(f.getInit(), v) || exprUses(f.getCondition(), v) || nameUsedInStatement(f.getUpdate(), v) || nameUsedInStatement(f.getBody(), v); }
        if (s instanceof ForEachStatement) return exprUses(((ForEachStatement) s).getIterable(), v) || nameUsedInStatement(((ForEachStatement) s).getBody(), v);
        if (s instanceof LabelStatement) return nameUsedInStatement(((LabelStatement) s).getBody(), v);
        if (s instanceof SynchronizedStatement) return exprUses(((SynchronizedStatement) s).getMonitor(), v) || nameUsedInStatement(((SynchronizedStatement) s).getBody(), v);
        if (s instanceof SwitchStatement) { for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) for (Statement cs : c.getStatements()) if (nameUsedInStatement(cs, v)) return true; return exprUses(((SwitchStatement) s).getSelector(), v); }
        if (s instanceof TryCatchStatement) { TryCatchStatement t = (TryCatchStatement) s; if (nameUsedInStatement(t.getTryBody(), v)) return true; for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) if (nameUsedInStatement(cc.body, v)) return true; return t.getFinallyBody() != null && nameUsedInStatement(t.getFinallyBody(), v); }
        return false;
    }
    private static boolean exprUses(Expression e, String v) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) return v.equals(((LocalVariableExpression) e).getName());
        if (e instanceof BinaryOperatorExpression) return exprUses(((BinaryOperatorExpression) e).getLeft(), v) || exprUses(((BinaryOperatorExpression) e).getRight(), v);
        if (e instanceof UnaryOperatorExpression) return exprUses(((UnaryOperatorExpression) e).getExpression(), v);
        if (e instanceof CastExpression) return exprUses(((CastExpression) e).getExpression(), v);
        if (e instanceof AssignmentExpression) return exprUses(((AssignmentExpression) e).getLeft(), v) || exprUses(((AssignmentExpression) e).getRight(), v);
        if (e instanceof TernaryExpression) { TernaryExpression t = (TernaryExpression) e; return exprUses(t.getCondition(), v) || exprUses(t.getTrueExpression(), v) || exprUses(t.getFalseExpression(), v); }
        if (e instanceof FieldAccessExpression) return exprUses(((FieldAccessExpression) e).getObject(), v);
        if (e instanceof ArrayAccessExpression) return exprUses(((ArrayAccessExpression) e).getArray(), v) || exprUses(((ArrayAccessExpression) e).getIndex(), v);
        if (e instanceof InstanceOfExpression) return exprUses(((InstanceOfExpression) e).getExpression(), v);
        if (e instanceof MethodInvocationExpression) { MethodInvocationExpression m = (MethodInvocationExpression) e; if (exprUses(m.getObject(), v)) return true; if (m.getArguments() != null) for (Expression a : m.getArguments()) if (exprUses(a, v)) return true; return false; }
        if (e instanceof StaticMethodInvocationExpression) { if (((StaticMethodInvocationExpression) e).getArguments() != null) for (Expression a : ((StaticMethodInvocationExpression) e).getArguments()) if (exprUses(a, v)) return true; return false; }
        if (e instanceof NewExpression) { if (((NewExpression) e).getArguments() != null) for (Expression a : ((NewExpression) e).getArguments()) if (exprUses(a, v)) return true; return false; }
        if (e instanceof NewArrayExpression) { NewArrayExpression n = (NewArrayExpression) e; if (n.getDimensionExpressions() != null) for (Expression a : n.getDimensionExpressions()) if (exprUses(a, v)) return true; if (n.getInitValues() != null) for (Expression a : n.getInitValues()) if (exprUses(a, v)) return true; return false; }
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
