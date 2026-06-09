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
            if (!ok) continue;

            // Rewrite: try (resourceDecls) { body }, dropping the Throwable catch + normal closes + decls.
            TryCatchStatement twr = new TryCatchStatement(tcs.getLineNumber(), tcs.getTryBody(),
                new ArrayList<TryCatchStatement.CatchClause>(), null, resourceDecls);
            List<Statement> rebuilt = new ArrayList<Statement>();
            for (int k = 0; k < declStart; k++) rebuilt.add(statements.get(k));
            rebuilt.add(twr);
            for (int k = j; k < statements.size(); k++) rebuilt.add(statements.get(k));
            return reconstruct(rebuilt); // re-scan (handles nested resource groups)
        }
        return statements;
    }

    private static void recurse(Statement s) {
        if (s instanceof BlockStatement) reconstruct(((BlockStatement) s).getStatements());
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
