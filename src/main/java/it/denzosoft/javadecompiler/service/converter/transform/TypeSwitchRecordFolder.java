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
                // START_CHANGE: BUG-2026-0067-20260610-30 - absorbedNext is now a count: the fold may
                // reclaim the whole post-switch tail (the exhaustive switch's fall-out arm), not just
                // a single default `return X;`.
                for (int k = 0; k < fr.absorbedNext && i + 1 < statements.size(); k++) statements.remove(i + 1);
                // END_CHANGE: BUG-2026-0067-30
                return statements;
            }
        }
        return statements;
    }

    // START_CHANGE: BUG-2026-0067-20260610-31 - absorbedNext as a count (see reconstruct()).
    private static final class FoldResult { final Statement folded; final int absorbedNext; FoldResult(Statement f, int a) { folded = f; absorbedNext = a; } }
    // END_CHANGE: BUG-2026-0067-31

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
        // START_CHANGE: BUG-2026-0067-20260610-32 - Sealed-exhaustive shape: the synthetic
        // `default: throw new MatchException(...)` arm is skipped (exhaustive marker), and ONE empty
        // labeled case is allowed — its arm fell out of the switch and is reclaimed from the
        // post-switch statements after the loop.
        boolean exhaustive = false;
        int tailPos = -1;
        for (SwitchStatement.SwitchCase sc : sw.getCases()) {
            List<Statement> body = sc.getStatements();
            if (body == null || body.isEmpty()) {
                if (sc.isDefault()) continue; // spurious `default:`/empty fall-through label
                if (tailPos >= 0) return null; // at most one fall-out arm
                tailPos = exprCases.size();
                exprCases.add(null); // placeholder, filled after the loop
                continue;
            }
            if (sc.isDefault() && body.size() == 1 && isMatchExceptionThrow(body.get(0))) {
                exhaustive = true; // synthetic exhaustive default -> no default arm in the source
                continue;
            }
            // END_CHANGE: BUG-2026-0067-32
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

        // START_CHANGE: BUG-2026-0067-20260610-33 - Reclaim the fall-out arm: the empty tail case's
        // body is the post-switch statements `Type v = (Type) subject; <deconstruction>; return <value>;`.
        // Only the sealed-exhaustive shape (MatchException default) produces it, and the fold must
        // consume the whole tail (no trailing live code may be absorbed).
        int absorbedNext = 0;
        if (tailPos >= 0) {
            if (!exhaustive || idx + 2 > outer.size()) return null;
            CastBind tcb = matchCastBind(outer.get(idx + 1), subject);
            if (tcb == null) return null;
            List<Statement> tailRest = new ArrayList<Statement>(outer.subList(idx + 2, outer.size()));
            RecordDeconstructionFolder.ArmFold taf = RecordDeconstructionFolder.foldArm(tcb.binding, tailRest);
            if (taf == null || taf.remainingBody.size() != 1
                    || !(taf.remainingBody.get(0) instanceof ReturnStatement)
                    || !((ReturnStatement) taf.remainingBody.get(0)).hasExpression()) return null;
            boolean coversTail = taf.flatWalk
                ? taf.consumed + taf.remainingBody.size() == tailRest.size()
                : taf.consumed == tailRest.size();
            if (!coversTail) return null;
            Expression tailValue = ((ReturnStatement) taf.remainingBody.get(0)).getExpression();
            SwitchExpression.SwitchCase tec = new SwitchExpression.SwitchCase(null, tailValue);
            tec.setPatternType(tcb.type);
            tec.setRecordPattern(new RecordPattern(taf.components));
            exprCases.set(tailPos, tec);
            if (valueType == null && tailValue != null) valueType = tailValue.getType();
            absorbedNext = outer.size() - idx - 1;
        }

        // Default value: the `return X;` immediately after the switch (only when the switch is NOT
        // exhaustive — for the exhaustive shape any post-switch return is unrelated code).
        Expression defaultValue = null;
        if (tailPos < 0 && !exhaustive
                && idx + 1 < outer.size() && outer.get(idx + 1) instanceof ReturnStatement
                && ((ReturnStatement) outer.get(idx + 1)).hasExpression()) {
            defaultValue = ((ReturnStatement) outer.get(idx + 1)).getExpression();
            absorbedNext = 1;
        }
        // END_CHANGE: BUG-2026-0067-33
        if (defaultValue != null) {
            exprCases.add(new SwitchExpression.SwitchCase(null, defaultValue));
        }

        SwitchExpression swExpr = new SwitchExpression(sw.getLineNumber(),
            valueType != null ? valueType : (defaultValue != null ? defaultValue.getType() : null),
            subject, exprCases);
        return new FoldResult(new ReturnStatement(sw.getLineNumber(), swExpr), absorbedNext);
    }

    // START_CHANGE: BUG-2026-0067-20260610-34 - Recognize the synthetic `throw new MatchException(...)`
    // default arm of a sealed-exhaustive pattern switch.
    private static boolean isMatchExceptionThrow(Statement s) {
        if (!(s instanceof ThrowStatement)) return false;
        Expression e = ((ThrowStatement) s).getExpression();
        if (!(e instanceof NewExpression)) return false;
        NewExpression ne = (NewExpression) e;
        if (ne.getInternalTypeName() != null && ne.getInternalTypeName().endsWith("MatchException")) return true;
        return ne.getType() != null && "MatchException".equals(ne.getType().getName());
    }
    // END_CHANGE: BUG-2026-0067-34

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
