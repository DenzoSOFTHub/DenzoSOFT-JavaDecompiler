/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * JLS 11.2 check of a structured body: every checked exception a call can throw must be caught
 * by an enclosing {@code catch} or declared by the method (BUG-2026-0161).
 *
 * <p>An invocation that leaves its {@code try} is the visible trace of a lost or misplaced
 * exception region (a {@code finally} body copied after the statement, a {@code close()} moved
 * out of its {@code catch}). Unknown classes count as "covered": the check only reports what
 * the class files prove.</p>
 */
public final class ExceptionChecker {

    private final ClassHierarchy hierarchy;
    private final String[] declared;
    private final LinkedList<List<String>> catches = new LinkedList<List<String>>();
    private final List<String> problems = new ArrayList<String>();

    private ExceptionChecker(ClassHierarchy h, String[] declared) {
        this.hierarchy = h;
        this.declared = declared != null ? declared : new String[0];
    }

    /** Uncaught checked exceptions (empty when the body is consistent or nothing is known). */
    public static List<String> check(List<Statement> body, ClassHierarchy h, String[] declaredThrows, String superName,
                                     String thisName) {
        ExceptionChecker c = new ExceptionChecker(h, declaredThrows);
        c.superName = superName;
        c.thisName = thisName;
        if (body != null) for (Statement s : body) c.stmt(s);
        return c.problems;
    }

    private String superName;
    private String thisName;

    private void stmt(Statement s) {
        if (s == null || problems.size() > 0) return;
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<String> types = new ArrayList<String>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                if (cc.exceptionTypes == null) continue;
                for (Type ct : cc.exceptionTypes) {
                    if (ct instanceof ObjectType) types.add(((ObjectType) ct).getInternalName());
                }
            }
            catches.push(types);
            try {
                if (t.getResources() != null) for (Statement r : t.getResources()) stmt(r);
                stmt(t.getTryBody());
            } finally {
                catches.pop();
            }
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) stmt(cc.body);
            if (t.hasFinally()) stmt(t.getFinallyBody());
            return;
        }
        if (s instanceof BlockStatement) { for (Statement x : ((BlockStatement) s).getStatements()) stmt(x); return; }
        if (s instanceof IfStatement) { expr(((IfStatement) s).getCondition()); stmt(((IfStatement) s).getThenBody()); return; }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            expr(i.getCondition()); stmt(i.getThenBody()); stmt(i.getElseBody()); return;
        }
        if (s instanceof WhileStatement) { expr(((WhileStatement) s).getCondition()); stmt(((WhileStatement) s).getBody()); return; }
        if (s instanceof DoWhileStatement) { stmt(((DoWhileStatement) s).getBody()); expr(((DoWhileStatement) s).getCondition()); return; }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            stmt(f.getInit()); expr(f.getCondition()); stmt(f.getUpdate()); stmt(f.getBody()); return;
        }
        if (s instanceof ForEachStatement) { expr(((ForEachStatement) s).getIterable()); stmt(((ForEachStatement) s).getBody()); return; }
        if (s instanceof LabelStatement) { stmt(((LabelStatement) s).getBody()); return; }
        if (s instanceof SynchronizedStatement) { expr(((SynchronizedStatement) s).getMonitor()); stmt(((SynchronizedStatement) s).getBody()); return; }
        if (s instanceof SwitchStatement) {
            expr(((SwitchStatement) s).getSelector());
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                if (c.getStatements() != null) for (Statement x : c.getStatements()) stmt(x);
            }
            return;
        }
        if (s instanceof ExpressionStatement) { expr(((ExpressionStatement) s).getExpression()); return; }
        if (s instanceof VariableDeclarationStatement) { expr(((VariableDeclarationStatement) s).getInitializer()); return; }
        if (s instanceof ReturnStatement) { expr(((ReturnStatement) s).getExpression()); return; }
        if (s instanceof ThrowStatement) { expr(((ThrowStatement) s).getExpression()); return; }
        if (s instanceof AssertStatement) { expr(((AssertStatement) s).getCondition()); expr(((AssertStatement) s).getMessage()); }
    }

    private void expr(Expression e) {
        if (e == null || problems.size() > 0) return;
        if (e instanceof LambdaExpression) return; // its own functional interface decides
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            String name = m.getMethodName();
            String owner = m.getOwnerInternalName();
            if ("super".equals(name)) { name = "<init>"; if (owner == null || owner.equals(thisName)) owner = superName; }
            else if ("this".equals(name)) { name = "<init>"; owner = thisName; }
            call(owner, name, m.getDescriptor());
        } else if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) e;
            if (m.getMethodName() != null && !m.getMethodName().startsWith("access$")) {
                call(m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor());
            }
        } else if (e instanceof NewExpression) {
            NewExpression n = (NewExpression) e;
            call(n.getInternalTypeName(), "<init>", n.getDescriptor());
        }
        if (e instanceof SwitchExpression) return;
        for (Expression c : it.denzosoft.javadecompiler.service.converter.transform.LocalDeclarationFixer.children(e)) expr(c);
    }

    private void call(String owner, String name, String desc) {
        if (owner == null || desc == null || owner.indexOf('$') >= 0 && name.equals("<init>")) return;
        String[] thr = hierarchy.throwsOf(owner, name, desc);
        if (thr == null) return;
        for (String ex : thr) {
            if (!Boolean.TRUE.equals(hierarchy.isChecked(ex))) continue;
            if (covered(ex)) continue;
            problems.add("unreported " + ex + " from " + owner + "." + name);
            return;
        }
    }

    private boolean covered(String ex) {
        for (List<String> l : catches) {
            for (String c : l) {
                Boolean b = hierarchy.isSubtype(ex, c);
                if (b == null || b.booleanValue()) return true;
            }
        }
        for (String d : declared) {
            Boolean b = hierarchy.isSubtype(ex, d);
            if (b == null || b.booleanValue()) return true;
        }
        return false;
    }
}
