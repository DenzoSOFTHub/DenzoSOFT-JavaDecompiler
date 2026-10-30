/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete, identity-preserving AST rewriter (BUG-2026-0143).
 *
 * <p>Unlike {@link AstLocalRewriter}, which always rebuilds, this mapper returns the very same
 * node instance when nothing below it changed. Callers can therefore detect "did anything
 * change / is the name referenced" with an identity test, and untouched subtrees keep any
 * identity-keyed side tables (statement pc maps) valid. It visits every statement and
 * expression kind of the model, so a subclass never silently misses a nested reference.</p>
 */
public abstract class AstMapper {

    /** Hook for local variable references. Default: unchanged. */
    protected Expression mapLocal(LocalVariableExpression lv) { return lv; }

    /** Hook for declared local names (declarations, for-each variables). Default: unchanged. */
    protected String mapDeclaredName(String name) { return name; }

    /** Whether to descend into a catch clause (a rename stops where the name is shadowed). */
    protected boolean enterCatch(TryCatchStatement.CatchClause cc) { return true; }

    /** Whether to descend into a lambda body. */
    protected boolean enterLambda(LambdaExpression l) { return true; }

    public List<Statement> mapList(List<Statement> in) {
        if (in == null) return null;
        List<Statement> out = null;
        for (int i = 0; i < in.size(); i++) {
            Statement s = in.get(i);
            Statement n = map(s);
            if (n != s && out == null) out = new ArrayList<Statement>(in);
            if (out != null) out.set(i, n);
        }
        return out != null ? out : in;
    }

    public Statement map(Statement s) {
        if (s == null) return null;
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            Expression n = map(e);
            return n == e ? s : new ExpressionStatement(n);
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            Expression init = v.hasInitializer() ? map(v.getInitializer()) : null;
            String name = mapDeclaredName(v.getName());
            if (init == v.getInitializer() && name.equals(v.getName()) && name == v.getName()) return s;
            VariableDeclarationStatement nv = new VariableDeclarationStatement(v.getLineNumber(), v.getType(),
                name, init, v.isFinal(), v.isVar());
            if (v.getGenericSignature() != null) nv.setGenericSignature(v.getGenericSignature());
            return nv;
        }
        if (s instanceof ReturnStatement) {
            ReturnStatement r = (ReturnStatement) s;
            if (!r.hasExpression()) return s;
            Expression n = map(r.getExpression());
            return n == r.getExpression() ? s : new ReturnStatement(r.getLineNumber(), n);
        }
        if (s instanceof ThrowStatement) {
            ThrowStatement t = (ThrowStatement) s;
            Expression n = map(t.getExpression());
            return n == t.getExpression() ? s : new ThrowStatement(t.getLineNumber(), n);
        }
        if (s instanceof YieldStatement) {
            YieldStatement y = (YieldStatement) s;
            Expression n = map(y.getExpression());
            return n == y.getExpression() ? s : new YieldStatement(y.getLineNumber(), n);
        }
        if (s instanceof BlockStatement) {
            List<Statement> st = ((BlockStatement) s).getStatements();
            List<Statement> n = mapList(st);
            return n == st ? s : new BlockStatement(s.getLineNumber(), n);
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            Expression c = map(i.getCondition());
            Statement t = map(i.getThenBody());
            if (c == i.getCondition() && t == i.getThenBody()) return s;
            return new IfStatement(i.getLineNumber(), c, t);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            Expression c = map(i.getCondition());
            Statement t = map(i.getThenBody());
            Statement e = map(i.getElseBody());
            if (c == i.getCondition() && t == i.getThenBody() && e == i.getElseBody()) return s;
            return new IfElseStatement(i.getLineNumber(), c, t, e);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            Expression c = map(w.getCondition());
            Statement b = map(w.getBody());
            if (c == w.getCondition() && b == w.getBody()) return s;
            return new WhileStatement(w.getLineNumber(), c, b);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            Expression c = map(d.getCondition());
            Statement b = map(d.getBody());
            if (c == d.getCondition() && b == d.getBody()) return s;
            return new DoWhileStatement(d.getLineNumber(), c, b);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            Statement init = map(f.getInit());
            Expression c = map(f.getCondition());
            Statement upd = map(f.getUpdate());
            Statement b = map(f.getBody());
            if (init == f.getInit() && c == f.getCondition() && upd == f.getUpdate() && b == f.getBody()) return s;
            return new ForStatement(f.getLineNumber(), init, c, upd, b);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            Expression it = map(f.getIterable());
            String name = mapDeclaredName(f.getVariableName());
            Statement b = map(f.getBody());
            if (it == f.getIterable() && b == f.getBody() && name == f.getVariableName()) return s;
            return new ForEachStatement(f.getLineNumber(), f.getVariableType(), name, it, b);
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            Expression m = map(sy.getMonitor());
            Statement b = map(sy.getBody());
            if (m == sy.getMonitor() && b == sy.getBody()) return s;
            return new SynchronizedStatement(sy.getLineNumber(), m, b);
        }
        if (s instanceof AssertStatement) {
            AssertStatement a = (AssertStatement) s;
            Expression c = map(a.getCondition());
            Expression m = map(a.getMessage());
            if (c == a.getCondition() && m == a.getMessage()) return s;
            return new AssertStatement(a.getLineNumber(), c, m);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement b = map(l.getBody());
            return b == l.getBody() ? s : new LabelStatement(l.getLineNumber(), l.getLabel(), b);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<Statement> res = t.getResources() != null ? mapList(t.getResources()) : null;
            Statement tryBody = map(t.getTryBody());
            boolean changed = tryBody != t.getTryBody() || res != t.getResources();
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                if (!enterCatch(cc)) {
                    catches.add(cc);
                    continue;
                }
                Statement b = map(cc.body);
                if (b != cc.body) {
                    changed = true;
                    catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, b));
                } else {
                    catches.add(cc);
                }
            }
            Statement fin = t.hasFinally() ? map(t.getFinallyBody()) : null;
            if (fin != t.getFinallyBody()) changed = true;
            if (!changed) return s;
            return new TryCatchStatement(t.getLineNumber(), tryBody, catches, fin, res);
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            Expression sel = map(sw.getSelector());
            boolean changed = sel != sw.getSelector();
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                List<Statement> st = mapList(c.getStatements());
                if (st != c.getStatements()) {
                    changed = true;
                    SwitchStatement.SwitchCase nc = new SwitchStatement.SwitchCase(c.getLabels(), st);
                    if (c.getPatternTypes() != null) nc.setPatterns(c.getPatternTypes(), c.getPatternBindings());
                    nc.setNullLabel(c.isNullLabel());
                    cases.add(nc);
                } else {
                    cases.add(c);
                }
            }
            if (!changed) return s;
            return new SwitchStatement(sw.getLineNumber(), sel, cases, sw.isArrowStyle());
        }
        return s; // break, continue, comment
    }

    private List<Expression> mapExprs(List<Expression> in) {
        if (in == null) return null;
        List<Expression> out = null;
        for (int i = 0; i < in.size(); i++) {
            Expression e = in.get(i);
            Expression n = map(e);
            if (n != e && out == null) out = new ArrayList<Expression>(in);
            if (out != null) out.set(i, n);
        }
        return out != null ? out : in;
    }

    public Expression map(Expression e) {
        if (e == null) return null;
        if (e instanceof LocalVariableExpression) return mapLocal((LocalVariableExpression) e);
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            Expression l = map(b.getLeft());
            Expression r = map(b.getRight());
            if (l == b.getLeft() && r == b.getRight()) return e;
            return new BinaryOperatorExpression(b.getLineNumber(), b.getType(), l, b.getOperator(), r);
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            Expression x = map(u.getExpression());
            if (x == u.getExpression()) return e;
            return new UnaryOperatorExpression(u.getLineNumber(), u.getType(), u.getOperator(), x, u.isPrefix());
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e;
            Expression x = map(c.getExpression());
            if (x == c.getExpression()) return e;
            CastExpression nc = new CastExpression(c.getLineNumber(), c.getType(), x);
            nc.setExplicit(c.isExplicit());
            return nc;
        }
        if (e instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) e;
            Expression l = map(a.getLeft());
            Expression r = map(a.getRight());
            if (l == a.getLeft() && r == a.getRight()) return e;
            return new AssignmentExpression(a.getLineNumber(), a.getType(), l, a.getOperator(), r);
        }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression f = (FieldAccessExpression) e;
            Expression o = map(f.getObject());
            if (o == f.getObject()) return e;
            return new FieldAccessExpression(f.getLineNumber(), f.getType(), o,
                f.getOwnerInternalName(), f.getName(), f.getDescriptor());
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression a = (ArrayAccessExpression) e;
            Expression arr = map(a.getArray());
            Expression idx = map(a.getIndex());
            if (arr == a.getArray() && idx == a.getIndex()) return e;
            return new ArrayAccessExpression(a.getLineNumber(), a.getType(), arr, idx);
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            Expression c = map(t.getCondition());
            Expression a = map(t.getTrueExpression());
            Expression b = map(t.getFalseExpression());
            if (c == t.getCondition() && a == t.getTrueExpression() && b == t.getFalseExpression()) return e;
            return new TernaryExpression(t.getLineNumber(), t.getType(), c, a, b);
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression i = (InstanceOfExpression) e;
            Expression x = map(i.getExpression());
            if (x == i.getExpression()) return e;
            return new InstanceOfExpression(i.getLineNumber(), x, i.getCheckType(),
                i.getPatternVariableName(), i.getRecordPattern());
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            Expression o = map(m.getObject());
            List<Expression> as = mapExprs(m.getArguments());
            if (o == m.getObject() && as == m.getArguments()) return e;
            return new MethodInvocationExpression(m.getLineNumber(), m.getType(), o,
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor(), as);
        }
        if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) e;
            List<Expression> as = mapExprs(m.getArguments());
            if (as == m.getArguments()) return e;
            return new StaticMethodInvocationExpression(m.getLineNumber(), m.getType(),
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor(), as);
        }
        if (e instanceof MethodReferenceExpression) {
            MethodReferenceExpression m = (MethodReferenceExpression) e;
            Expression o = map(m.getObject());
            if (o == m.getObject()) return e;
            MethodReferenceExpression nm = new MethodReferenceExpression(m.getLineNumber(), m.getType(), o,
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor());
            nm.setInterfaceGenericSignature(m.getInterfaceGenericSignature());
            return nm;
        }
        if (e instanceof NewExpression) {
            NewExpression n = (NewExpression) e;
            List<Expression> as = mapExprs(n.getArguments());
            if (as == n.getArguments()) return e;
            return new NewExpression(n.getLineNumber(), n.getType(), n.getInternalTypeName(), n.getDescriptor(), as);
        }
        if (e instanceof NewArrayExpression) {
            NewArrayExpression n = (NewArrayExpression) e;
            List<Expression> dims = mapExprs(n.getDimensionExpressions());
            List<Expression> iv = mapExprs(n.getInitValues());
            if (dims == n.getDimensionExpressions() && iv == n.getInitValues()) return e;
            NewArrayExpression nn = new NewArrayExpression(n.getLineNumber(), n.getType(), dims);
            if (iv != null) {
                for (Expression v : iv) nn.addInitValue(v);
            }
            return nn;
        }
        if (e instanceof LambdaExpression) {
            LambdaExpression l = (LambdaExpression) e;
            if (!enterLambda(l)) return e;
            Statement b = map(l.getBody());
            if (b == l.getBody()) return e;
            LambdaExpression nl = new LambdaExpression(l.getLineNumber(), l.getType(),
                l.getParameterNames(), l.getParameterTypes(), b);
            nl.setInterfaceGenericSignature(l.getInterfaceGenericSignature());
            return nl;
        }
        if (e instanceof SwitchExpression) {
            SwitchExpression sw = (SwitchExpression) e;
            Expression sel = map(sw.getSelector());
            boolean changed = sel != sw.getSelector();
            List<SwitchExpression.SwitchCase> cases = new ArrayList<SwitchExpression.SwitchCase>();
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                Expression v = map(c.getValue());
                Expression g = map(c.getGuard());
                List<Statement> body = c.getBodyStatements() != null ? mapList(c.getBodyStatements()) : null;
                if (v != c.getValue() || g != c.getGuard() || body != c.getBodyStatements()) {
                    changed = true;
                    SwitchExpression.SwitchCase nc = new SwitchExpression.SwitchCase(c.getLabels(), v);
                    nc.setPatternType(c.getPatternType());
                    nc.setPatternBinding(c.getPatternBinding());
                    nc.setGuard(g);
                    nc.setRecordPattern(c.getRecordPattern());
                    nc.setBodyStatements(body);
                    cases.add(nc);
                } else {
                    cases.add(c);
                }
            }
            if (!changed) return e;
            return new SwitchExpression(sw.getLineNumber(), sw.getType(), sel, cases);
        }
        if (e instanceof PatternMatchExpression) {
            PatternMatchExpression p = (PatternMatchExpression) e;
            Expression x = map(p.getExpression());
            if (x == p.getExpression()) return e;
            return new PatternMatchExpression(p.getLineNumber(), x, p.getPatternType(), p.getVariableName());
        }
        if (e instanceof ReturnExpression) {
            ReturnExpression r = (ReturnExpression) e;
            if (!r.hasExpression()) return e;
            Expression x = map(r.getExpression());
            if (x == r.getExpression()) return e;
            return new ReturnExpression(r.getLineNumber(), x);
        }
        return e; // constants, this, class literals, text blocks
    }

    // ------------------------------------------------------------------ ready-made mappers

    /** Renames every reference to (and declaration of) local {@code oldName}. */
    public static final class Renamer extends AstMapper {
        private final String oldName;
        private final String newName;

        public Renamer(String oldName, String newName) {
            this.oldName = oldName;
            this.newName = newName;
        }

        @Override
        protected Expression mapLocal(LocalVariableExpression lv) {
            if (!oldName.equals(lv.getName())) return lv;
            return new LocalVariableExpression(lv.getLineNumber(), lv.getType(), newName, lv.getIndex());
        }

        @Override
        protected String mapDeclaredName(String name) {
            // a fresh String instance signals the change even for a same-name rename
            return oldName.equals(name) ? new String(newName) : name;
        }

        @Override
        protected boolean enterCatch(TryCatchStatement.CatchClause cc) {
            return !oldName.equals(cc.variableName); // shadowed inside this clause
        }
    }
}
