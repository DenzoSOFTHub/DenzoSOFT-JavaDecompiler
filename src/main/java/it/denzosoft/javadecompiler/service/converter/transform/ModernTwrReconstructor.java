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
 * Collapses the Java 9+ try-with-resources desugar back into a {@code try (res) { ... }} statement
 * (BUG-2026-0068).
 *
 * The compiler (without a synthetic {@code finally}) lowers
 * <pre>try (R r = init; ...) { body }</pre>
 * to roughly
 * <pre>
 *   R r = init; ...
 *   try { body } catch (Throwable t) { r.close(); throw t; }
 *   r.close(); ...                       // normal path
 * </pre>
 * This pass recognises that shape (a single {@code catch (Throwable)} whose body is just resource
 * {@code close()} calls plus a rethrow, immediately followed by the same {@code close()} calls and
 * preceded by the resource declarations) and rewrites it to a real try-with-resources.
 */
public final class ModernTwrReconstructor {

    private ModernTwrReconstructor() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null || statements.isEmpty()) return statements;
        // Recurse into nested bodies first.
        for (int i = 0; i < statements.size(); i++) {
            recurse(statements.get(i));
        }
        // Scan for the desugar shape.
        for (int i = 0; i < statements.size(); i++) {
            if (!(statements.get(i) instanceof TryCatchStatement)) continue;
            TryCatchStatement tcs = (TryCatchStatement) statements.get(i);
            if (tcs.hasFinally() || tcs.getResources() != null) continue;
            if (tcs.getCatchClauses() == null || tcs.getCatchClauses().size() != 1) continue;
            TryCatchStatement.CatchClause cc = tcs.getCatchClauses().get(0);
            if (!catchesThrowable(cc) || !isCloseAndRethrow(cc.body)) continue;

            // Collect the normal-path close() calls immediately after the try (close order).
            List<String> closeVars = new ArrayList<String>();
            int j = i + 1;
            while (j < statements.size() && closeCallVar(statements.get(j)) != null) {
                closeVars.add(closeCallVar(statements.get(j)));
                j++;
            }
            // Single-resource desugar may have no separate normal-path close in the decompiled output;
            // fall back to the resources closed in the catch body (the try-with-resources re-adds the
            // normal close implicitly).
            if (closeVars.isEmpty()) {
                closeVars = closeVarsOf(cc.body);
            }
            if (closeVars.isEmpty()) continue;

            // Resources, in declaration order, are the reverse of the close order.
            List<String> resourceNames = new ArrayList<String>();
            for (int k = closeVars.size() - 1; k >= 0; k--) resourceNames.add(closeVars.get(k));

            // The declarations immediately before the try must declare exactly those resources.
            int declStart = i - resourceNames.size();
            if (declStart < 0) continue;
            List<Statement> resourceDecls = new ArrayList<Statement>();
            boolean ok = true;
            for (int r = 0; r < resourceNames.size(); r++) {
                Statement s = statements.get(declStart + r);
                VariableDeclarationStatement vds = (s instanceof VariableDeclarationStatement)
                    ? (VariableDeclarationStatement) s : null;
                // A genuine resource is initialised with a real expression (`new ...`), not a bare
                // copy of another local (`Object var4 = var2`) — the latter is the synthetic copy of
                // the mangled effectively-final desugar, which must NOT be collapsed.
                if (vds != null && resourceNames.get(r).equals(vds.getName()) && vds.hasInitializer()
                        && !(vds.getInitializer() instanceof LocalVariableExpression)) {
                    resourceDecls.add(s);
                } else {
                    ok = false;
                    break;
                }
            }
            int dropFrom = declStart;
            // START_CHANGE: BUG-2026-0068-20260610-4 - Effectively-final single-resource form:
            //   T orig = init;              // user declaration
            //   Object copy = orig;         // synthetic copy used only by the closes
            //   try { body } catch (Throwable t) { copy.close(); throw t; }
            //   if (copy != null) copy.close();   // normal path (guarded)
            // collapses to `try (T orig = init) { body }` - runtime-identical to the original
            // `try (orig)` (same init, body, close and suppression order). Only fires when the
            // synthetic copy is provably dead outside the desugar (body and trailing code never
            // mention it) and `orig` is not used after the try (it moves into the header scope).
            if (!ok && resourceNames.size() == 1 && declStart >= 1) {
                String copyName = resourceNames.get(0);
                Statement copyS = statements.get(declStart);
                Statement origS = statements.get(declStart - 1);
                VariableDeclarationStatement copyVds = (copyS instanceof VariableDeclarationStatement)
                    ? (VariableDeclarationStatement) copyS : null;
                VariableDeclarationStatement origVds = (origS instanceof VariableDeclarationStatement)
                    ? (VariableDeclarationStatement) origS : null;
                if (copyVds != null && copyName.equals(copyVds.getName()) && copyVds.hasInitializer()
                        && copyVds.getInitializer() instanceof LocalVariableExpression) {
                    String origName =
                        ((LocalVariableExpression) copyVds.getInitializer()).getName();
                    if (!origName.equals(copyName)
                            && origVds != null && origName.equals(origVds.getName())
                            && origVds.hasInitializer()
                            && !(origVds.getInitializer() instanceof LocalVariableExpression)
                            && !refersTo(tcs.getTryBody(), copyName)) {
                        // Consume the normal-path guarded close `if (copy != null) copy.close();`.
                        int j2 = j;
                        if (j2 < statements.size()
                                && copyName.equals(guardedCloseVar(statements.get(j2)))) {
                            j2++;
                        }
                        // Neither variable may be referenced after the collapsed try.
                        boolean usedAfter = false;
                        for (int k = j2; k < statements.size(); k++) {
                            if (refersTo(statements.get(k), copyName)
                                    || refersTo(statements.get(k), origName)) {
                                usedAfter = true;
                                break;
                            }
                        }
                        if (!usedAfter) {
                            resourceDecls = new ArrayList<Statement>();
                            resourceDecls.add(origS);
                            dropFrom = declStart - 1;
                            j = j2;
                            ok = true;
                        }
                    }
                }
            }
            // END_CHANGE: BUG-2026-0068-4
            if (!ok) continue;

            // Rewrite: try (resourceDecls) { body }, dropping the Throwable catch + normal closes + decls.
            TryCatchStatement twr = new TryCatchStatement(tcs.getLineNumber(), tcs.getTryBody(),
                new ArrayList<TryCatchStatement.CatchClause>(), null, resourceDecls);
            List<Statement> rebuilt = new ArrayList<Statement>();
            for (int k = 0; k < dropFrom; k++) rebuilt.add(statements.get(k));
            rebuilt.add(twr);
            for (int k = j; k < statements.size(); k++) rebuilt.add(statements.get(k));
            return reconstruct(rebuilt); // re-scan (handles nested resource groups)
        }
        return statements;
    }

    private static void recurse(Statement s) {
        // START_CHANGE: BUG-2026-0068-20260610-3 - reconstruct() returns a NEW list; the result
        // was previously discarded, so nested TWR shapes (e.g. the inner resource of a
        // multi-resource try, which sits inside the outer collapsed try body) never collapsed.
        // Mutate the live block list in place. All BlockStatement bodies (including
        // TryCatchStatement try/catch/finally bodies, reached below) are built on ArrayLists.
        if (s instanceof BlockStatement) {
            List<Statement> live = ((BlockStatement) s).getStatements();
            List<Statement> nb = reconstruct(live);
            if (nb != live) {
                live.clear();
                live.addAll(nb);
            }
        }
        // END_CHANGE: BUG-2026-0068-3
        else if (s instanceof IfStatement) recurse(((IfStatement) s).getThenBody());
        else if (s instanceof IfElseStatement) {
            recurse(((IfElseStatement) s).getThenBody());
            recurse(((IfElseStatement) s).getElseBody());
        } else if (s instanceof WhileStatement) recurse(((WhileStatement) s).getBody());
        else if (s instanceof DoWhileStatement) recurse(((DoWhileStatement) s).getBody());
        else if (s instanceof ForStatement) recurse(((ForStatement) s).getBody());
        else if (s instanceof ForEachStatement) recurse(((ForEachStatement) s).getBody());
        else if (s instanceof LabelStatement) recurse(((LabelStatement) s).getBody());
        else if (s instanceof SynchronizedStatement) recurse(((SynchronizedStatement) s).getBody());
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            recurse(t.getTryBody());
            if (t.getCatchClauses() != null) {
                for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) recurse(cc.body);
            }
            if (t.getFinallyBody() != null) recurse(t.getFinallyBody());
        }
    }

    private static boolean catchesThrowable(TryCatchStatement.CatchClause cc) {
        if (cc.exceptionTypes == null || cc.exceptionTypes.size() != 1) return false;
        String d = cc.exceptionTypes.get(0).getDescriptor();
        return d != null && (d.contains("Throwable"));
    }

    private static boolean isCloseAndRethrow(Statement body) {
        List<Statement> stmts = body instanceof BlockStatement
            ? ((BlockStatement) body).getStatements() : null;
        if (stmts == null || stmts.isEmpty()) return false;
        boolean sawClose = false;
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (i == stmts.size() - 1) {
                if (!(s instanceof ThrowStatement)) return false;
            } else if (closeCallVar(s) != null) {
                sawClose = true;
            } else if (s instanceof IfStatement) {
                // `if (r != null) r.close();` form — accept as a close.
                sawClose = true;
            } else {
                return false;
            }
        }
        return sawClose;
    }

    private static List<String> closeVarsOf(Statement body) {
        List<String> out = new ArrayList<String>();
        if (!(body instanceof BlockStatement)) return out;
        for (Statement s : ((BlockStatement) body).getStatements()) {
            String v = closeCallVar(s);
            if (v != null) out.add(v);
        }
        return out;
    }

    // START_CHANGE: BUG-2026-0068-20260610-5 - Helpers for the effectively-final resource form:
    // guarded normal-path close recognition and a conservative variable-reference scanner
    // (unknown statement shapes count as "references it", so the collapse is simply skipped).
    /** Matches {@code if (v != null) v.close();} (block or bare) and returns {@code v}. */
    private static String guardedCloseVar(Statement s) {
        if (!(s instanceof IfStatement)) return null;
        IfStatement is = (IfStatement) s;
        Statement then = is.getThenBody();
        Statement single = then;
        if (then instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) then).getStatements();
            if (stmts.size() != 1) return null;
            single = stmts.get(0);
        }
        String v = closeCallVar(single);
        if (v == null) return null;
        // The condition must test the same variable against null.
        Expression cond = is.getCondition();
        if (cond instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) cond;
            boolean nullCheck =
                (boe.getLeft() instanceof LocalVariableExpression
                    && v.equals(((LocalVariableExpression) boe.getLeft()).getName())
                    && boe.getRight() instanceof NullExpression)
                || (boe.getRight() instanceof LocalVariableExpression
                    && v.equals(((LocalVariableExpression) boe.getRight()).getName())
                    && boe.getLeft() instanceof NullExpression);
            if (nullCheck) return v;
        }
        return null;
    }

    private static boolean refersTo(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof BlockStatement) {
            for (Statement c : ((BlockStatement) s).getStatements()) {
                if (refersTo(c, name)) return true;
            }
            return false;
        }
        if (s instanceof ExpressionStatement) {
            return exprRefersTo(((ExpressionStatement) s).getExpression(), name);
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            return vds.hasInitializer() && exprRefersTo(vds.getInitializer(), name);
        }
        if (s instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) s;
            return rs.hasExpression() && exprRefersTo(rs.getExpression(), name);
        }
        if (s instanceof ThrowStatement) {
            return exprRefersTo(((ThrowStatement) s).getExpression(), name);
        }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            return exprRefersTo(is.getCondition(), name) || refersTo(is.getThenBody(), name);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ie = (IfElseStatement) s;
            return exprRefersTo(ie.getCondition(), name)
                || refersTo(ie.getThenBody(), name) || refersTo(ie.getElseBody(), name);
        }
        if (s instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) s;
            return exprRefersTo(ws.getCondition(), name) || refersTo(ws.getBody(), name);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement ds = (DoWhileStatement) s;
            return exprRefersTo(ds.getCondition(), name) || refersTo(ds.getBody(), name);
        }
        if (s instanceof ForStatement) {
            ForStatement fs = (ForStatement) s;
            return refersTo(fs.getInit(), name) || exprRefersTo(fs.getCondition(), name)
                || refersTo(fs.getUpdate(), name) || refersTo(fs.getBody(), name);
        }
        if (s instanceof LabelStatement) {
            return refersTo(((LabelStatement) s).getBody(), name);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (t.getResources() != null) {
                for (Statement r : t.getResources()) {
                    if (refersTo(r, name)) return true;
                }
            }
            if (refersTo(t.getTryBody(), name)) return true;
            if (t.getCatchClauses() != null) {
                for (TryCatchStatement.CatchClause cc2 : t.getCatchClauses()) {
                    if (refersTo(cc2.body, name)) return true;
                }
            }
            return t.getFinallyBody() != null && refersTo(t.getFinallyBody(), name);
        }
        if (s instanceof BreakStatement || s instanceof ContinueStatement
                || s instanceof CommentStatement) {
            return false;
        }
        return true; // unknown statement shape: assume it references the variable
    }

    private static boolean exprRefersTo(Expression e, String name) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) {
            return name.equals(((LocalVariableExpression) e).getName());
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) e;
            if (exprRefersTo(mie.getObject(), name)) return true;
            return anyExprRefersTo(mie.getArguments(), name);
        }
        if (e instanceof StaticMethodInvocationExpression) {
            return anyExprRefersTo(((StaticMethodInvocationExpression) e).getArguments(), name);
        }
        if (e instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) e;
            return exprRefersTo(ae.getLeft(), name) || exprRefersTo(ae.getRight(), name);
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) e;
            return exprRefersTo(boe.getLeft(), name) || exprRefersTo(boe.getRight(), name);
        }
        if (e instanceof UnaryOperatorExpression) {
            return exprRefersTo(((UnaryOperatorExpression) e).getExpression(), name);
        }
        if (e instanceof CastExpression) {
            return exprRefersTo(((CastExpression) e).getExpression(), name);
        }
        if (e instanceof NewExpression) {
            return anyExprRefersTo(((NewExpression) e).getArguments(), name);
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) e;
            return exprRefersTo(te.getCondition(), name)
                || exprRefersTo(te.getTrueExpression(), name)
                || exprRefersTo(te.getFalseExpression(), name);
        }
        if (e instanceof FieldAccessExpression) {
            return exprRefersTo(((FieldAccessExpression) e).getObject(), name);
        }
        // Literals render to their value; other shapes fall back to a word-boundary
        // text match (false positives only block the collapse, which is safe).
        return containsWord(String.valueOf(e), name);
    }

    private static boolean anyExprRefersTo(List<Expression> exprs, String name) {
        if (exprs == null) return false;
        for (Expression e : exprs) {
            if (exprRefersTo(e, name)) return true;
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.length() == 0) return false;
        int idx = text.indexOf(word);
        while (idx >= 0) {
            boolean beforeOk = idx == 0 || !isIdentChar(text.charAt(idx - 1));
            int end = idx + word.length();
            boolean afterOk = end >= text.length() || !isIdentChar(text.charAt(end));
            if (beforeOk && afterOk) return true;
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
    // END_CHANGE: BUG-2026-0068-5

    private static String closeCallVar(Statement s) {
        if (!(s instanceof ExpressionStatement)) return null;
        Expression e = ((ExpressionStatement) s).getExpression();
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) e;
            if ("close".equals(mie.getMethodName())
                    && (mie.getArguments() == null || mie.getArguments().isEmpty())
                    && mie.getObject() instanceof LocalVariableExpression) {
                return ((LocalVariableExpression) mie.getObject()).getName();
            }
        }
        return null;
    }
}
