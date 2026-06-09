/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds the JD-pipeline reconstruction of a Java 21 record-pattern SWITCH expression
 * (BUG-2026-0079). After the return tail-duplicator + dead-guard pass, the JD emitter produces:
 * <pre>
 *   switch (SwitchBootstraps.typeSwitch(subject, idx)) {
 *       case 0: Type0 v0 = (Type0) subject; &lt;deconstruction&gt;; return &lt;value0&gt;; idx = 1;
 *       case 1: Type1 v1 = (Type1) subject; &lt;deconstruction&gt;; return &lt;value1&gt;;
 *   }
 *   return &lt;defaultValue&gt;;
 * </pre>
 * which this transform folds into {@code return switch (subject) { case Type0(...) -> value0; ...;
 * default -> defaultValue; };}. Every shape it does not recognize is left untouched (safe no-op), so it
 * cannot miscompile even if the upstream pieces only partially de-share the value merge.
 */
public final class TypeSwitchRecordFolder {

    private TypeSwitchRecordFolder() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        // Recurse into nested bodies first.
        for (int i = 0; i < statements.size(); i++) recurse(statements.get(i));
        // Then try to fold a top-level typeSwitch (+ its trailing default return).
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (!(s instanceof SwitchStatement)) continue;
            StaticMethodInvocationExpression ts = typeSwitchSelector((SwitchStatement) s);
            if (ts == null) continue;
            FoldResult fr = tryFold((SwitchStatement) s, statements, i);
            if (fr != null) {
                statements.set(i, fr.folded);
                if (fr.absorbedNext && i + 1 < statements.size()) statements.remove(i + 1);
                return statements;
            }
        }
        return statements;
    }

    private static final class FoldResult { final Statement folded; final boolean absorbedNext; FoldResult(Statement f, boolean a) { folded = f; absorbedNext = a; } }

    private static void recurse(Statement s) {
        if (s instanceof BlockStatement) reconstruct(((BlockStatement) s).getStatements());
        else if (s instanceof IfStatement) recurse(((IfStatement) s).getThenBody());
        else if (s instanceof IfElseStatement) { recurse(((IfElseStatement) s).getThenBody()); recurse(((IfElseStatement) s).getElseBody()); }
        else if (s instanceof WhileStatement) recurse(((WhileStatement) s).getBody());
        else if (s instanceof DoWhileStatement) recurse(((DoWhileStatement) s).getBody());
        else if (s instanceof ForStatement) recurse(((ForStatement) s).getBody());
        else if (s instanceof ForEachStatement) recurse(((ForEachStatement) s).getBody());
        else if (s instanceof LabelStatement) recurse(((LabelStatement) s).getBody());
        else if (s instanceof SynchronizedStatement) recurse(((SynchronizedStatement) s).getBody());
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            recurse(t.getTryBody());
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) recurse(cc.body);
            if (t.getFinallyBody() != null) recurse(t.getFinallyBody());
        } else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) reconstruct(c.getStatements());
        }
    }

    private static StaticMethodInvocationExpression typeSwitchSelector(SwitchStatement sw) {
        Expression sel = sw.getSelector();
        if (sel instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) sel;
            if (("typeSwitch".equals(m.getMethodName()) || "enumSwitch".equals(m.getMethodName()))
                    && m.getArguments() != null && !m.getArguments().isEmpty()) {
                return m;
            }
        }
        return null;
    }

    private static FoldResult tryFold(SwitchStatement sw, List<Statement> outer, int idx) {
        Expression subject = typeSwitchSelector(sw).getArguments().get(0);

        List<SwitchExpression.SwitchCase> exprCases = new ArrayList<SwitchExpression.SwitchCase>();
        Type valueType = null;
        for (SwitchStatement.SwitchCase sc : sw.getCases()) {
            List<Statement> body = sc.getStatements();
            if (body == null || body.isEmpty()) continue; // spurious `default:`/empty fall-through label
            // The arm must start with `Type v = (Type) subject;` (decl or assignment).
            CastBind cb = matchCastBind(body.get(0), subject);
            if (cb == null) return null; // not a pattern arm -> bail (safe)
            List<Statement> rest = body.subList(1, body.size());
            RecordDeconstructionFolder.ArmFold af = RecordDeconstructionFolder.foldArm(cb.binding, new ArrayList<Statement>(rest));
            SwitchExpression.SwitchCase ec = new SwitchExpression.SwitchCase(null, null);
            Expression value;
            if (af != null && af.remainingBody.size() == 1 && af.remainingBody.get(0) instanceof ReturnStatement
                    && ((ReturnStatement) af.remainingBody.get(0)).hasExpression()) {
                value = ((ReturnStatement) af.remainingBody.get(0)).getExpression();
                ec = new SwitchExpression.SwitchCase(null, value);
                ec.setPatternType(cb.type);
                ec.setRecordPattern(new RecordPattern(af.components));
            } else {
                // Deconstruction didn't fold (or has a guard/extra statements) -> bail the whole switch.
                return null;
            }
            if (valueType == null && value != null) valueType = value.getType();
            exprCases.add(ec);
        }
        if (exprCases.isEmpty()) return null;

        // Default value: the `return X;` immediately after the switch.
        Expression defaultValue = null;
        boolean absorbedNext = false;
        if (idx + 1 < outer.size() && outer.get(idx + 1) instanceof ReturnStatement
                && ((ReturnStatement) outer.get(idx + 1)).hasExpression()) {
            defaultValue = ((ReturnStatement) outer.get(idx + 1)).getExpression();
            absorbedNext = true;
        }
        if (defaultValue != null) {
            exprCases.add(new SwitchExpression.SwitchCase(null, defaultValue));
        }

        SwitchExpression swExpr = new SwitchExpression(sw.getLineNumber(),
            valueType != null ? valueType : (defaultValue != null ? defaultValue.getType() : null),
            subject, exprCases);
        return new FoldResult(new ReturnStatement(sw.getLineNumber(), swExpr), absorbedNext);
    }

    private static final class CastBind { final String binding; final Type type; CastBind(String b, Type t) { binding = b; type = t; } }

    /** `Type v = (Type) subject;` (decl) or `v = (Type) subject;` (assignment). */
    private static CastBind matchCastBind(Statement s, Expression subject) {
        String bind = null; Expression init = null;
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            if (v.hasInitializer()) { bind = v.getName(); init = v.getInitializer(); }
        } else if (s instanceof ExpressionStatement && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            if (ae.getLeft() instanceof LocalVariableExpression) { bind = ((LocalVariableExpression) ae.getLeft()).getName(); init = ae.getRight(); }
        }
        if (bind == null || !(init instanceof CastExpression)) return null;
        CastExpression cast = (CastExpression) init;
        if (!sameLocal(cast.getExpression(), subject)) return null;
        return new CastBind(bind, cast.getType());
    }

    private static boolean sameLocal(Expression a, Expression b) {
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            return ((LocalVariableExpression) a).getName().equals(((LocalVariableExpression) b).getName());
        }
        return false;
    }
}
