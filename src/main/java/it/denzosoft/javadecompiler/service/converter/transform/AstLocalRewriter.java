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
 * Immutable-AST rewriter that walks an expression/statement tree and lets a subclass replace
 * {@link LocalVariableExpression} nodes (rename a slot, or substitute the captured argument). Every other
 * node is rebuilt structurally so the replacement propagates. Unknown leaf nodes are returned unchanged.
 *
 * Used by the lambda reconstruction (BUG-2026-0065) to (a) substitute captured arguments into a synthetic
 * lambda body and (b) rename the lambda's own parameters to non-shadowing names.
 */
public abstract class AstLocalRewriter {

    /** Replacement for a local-variable reference; default keeps it unchanged. */
    protected abstract Expression onLocal(LocalVariableExpression lv);

    public Statement rewrite(Statement s) {
        if (s == null) return null;
        if (s instanceof ExpressionStatement) {
            return new ExpressionStatement(rw(((ExpressionStatement) s).getExpression()));
        }
        if (s instanceof ReturnStatement) {
            ReturnStatement r = (ReturnStatement) s;
            return r.hasExpression() ? new ReturnStatement(r.getLineNumber(), rw(r.getExpression())) : s;
        }
        if (s instanceof ThrowStatement) {
            ThrowStatement t = (ThrowStatement) s;
            return new ThrowStatement(t.getLineNumber(), rw(t.getExpression()));
        }
        if (s instanceof YieldStatement) {
            YieldStatement y = (YieldStatement) s;
            return new YieldStatement(y.getLineNumber(), rw(y.getExpression()));
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            Expression init = v.hasInitializer() ? rw(v.getInitializer()) : null;
            VariableDeclarationStatement nv = new VariableDeclarationStatement(
                v.getLineNumber(), v.getType(), v.getName(), init, v.isFinal(), v.isVar());
            if (v.getGenericSignature() != null) nv.setGenericSignature(v.getGenericSignature());
            return nv;
        }
        if (s instanceof BlockStatement) {
            return new BlockStatement(s.getLineNumber(), rwList(((BlockStatement) s).getStatements()));
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            return new IfStatement(i.getLineNumber(), rw(i.getCondition()), rewrite(i.getThenBody()));
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            return new IfElseStatement(i.getLineNumber(), rw(i.getCondition()),
                rewrite(i.getThenBody()), rewrite(i.getElseBody()));
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            return new WhileStatement(w.getLineNumber(), rw(w.getCondition()), rewrite(w.getBody()));
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            return new DoWhileStatement(d.getLineNumber(), rw(d.getCondition()), rewrite(d.getBody()));
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            return new ForStatement(f.getLineNumber(), rewrite(f.getInit()),
                rw(f.getCondition()), rewrite(f.getUpdate()), rewrite(f.getBody()));
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            return new ForEachStatement(f.getLineNumber(), f.getVariableType(), f.getVariableName(),
                rw(f.getIterable()), rewrite(f.getBody()));
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            return new SynchronizedStatement(sy.getLineNumber(), rw(sy.getMonitor()), rewrite(sy.getBody()));
        }
        if (s instanceof AssertStatement) {
            AssertStatement a = (AssertStatement) s;
            Expression msg = a.getMessage() != null ? rw(a.getMessage()) : null;
            return new AssertStatement(a.getLineNumber(), rw(a.getCondition()), msg);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            return new LabelStatement(l.getLineNumber(), l.getLabel(), rewrite(l.getBody()));
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, rewrite(cc.body)));
            }
            Statement fin = t.hasFinally() ? rewrite(t.getFinallyBody()) : null;
            return new TryCatchStatement(t.getLineNumber(), rewrite(t.getTryBody()), catches, fin, t.getResources());
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                cases.add(new SwitchStatement.SwitchCase(c.getLabels(), rwList(c.getStatements())));
            }
            return new SwitchStatement(sw.getLineNumber(), rw(sw.getSelector()), cases, sw.isArrowStyle());
        }
        return s; // Break/Continue/Comment and anything unknown
    }

    private List<Statement> rwList(List<Statement> in) {
        if (in == null) return null;
        List<Statement> out = new ArrayList<Statement>(in.size());
        for (Statement st : in) out.add(rewrite(st));
        return out;
    }

    private List<Expression> rwExprList(List<Expression> in) {
        if (in == null) return null;
        List<Expression> out = new ArrayList<Expression>(in.size());
        for (Expression e : in) out.add(rw(e));
        return out;
    }

    protected Expression rw(Expression e) {
        if (e == null) return null;
        if (e instanceof LocalVariableExpression) {
            return onLocal((LocalVariableExpression) e);
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            return new BinaryOperatorExpression(b.getLineNumber(), b.getType(),
                rw(b.getLeft()), b.getOperator(), rw(b.getRight()));
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            return new UnaryOperatorExpression(u.getLineNumber(), u.getType(),
                u.getOperator(), rw(u.getExpression()), u.isPrefix());
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e;
            return new CastExpression(c.getLineNumber(), c.getType(), rw(c.getExpression()));
        }
        if (e instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) e;
            return new AssignmentExpression(a.getLineNumber(), a.getType(),
                rw(a.getLeft()), a.getOperator(), rw(a.getRight()));
        }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression f = (FieldAccessExpression) e;
            return new FieldAccessExpression(f.getLineNumber(), f.getType(),
                f.getObject() != null ? rw(f.getObject()) : null,
                f.getOwnerInternalName(), f.getName(), f.getDescriptor());
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression a = (ArrayAccessExpression) e;
            return new ArrayAccessExpression(a.getLineNumber(), a.getType(), rw(a.getArray()), rw(a.getIndex()));
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            return new TernaryExpression(t.getLineNumber(), t.getType(),
                rw(t.getCondition()), rw(t.getTrueExpression()), rw(t.getFalseExpression()));
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression i = (InstanceOfExpression) e;
            return new InstanceOfExpression(i.getLineNumber(), rw(i.getExpression()),
                i.getCheckType(), i.getPatternVariableName());
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            return new MethodInvocationExpression(m.getLineNumber(), m.getType(),
                m.getObject() != null ? rw(m.getObject()) : null,
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor(), rwExprList(m.getArguments()));
        }
        if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) e;
            return new StaticMethodInvocationExpression(m.getLineNumber(), m.getType(),
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor(), rwExprList(m.getArguments()));
        }
        if (e instanceof MethodReferenceExpression) {
            MethodReferenceExpression m = (MethodReferenceExpression) e;
            return new MethodReferenceExpression(m.getLineNumber(), m.getType(),
                m.getObject() != null ? rw(m.getObject()) : null,
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor());
        }
        if (e instanceof NewExpression) {
            NewExpression n = (NewExpression) e;
            return new NewExpression(n.getLineNumber(), n.getType(),
                n.getInternalTypeName(), n.getDescriptor(), rwExprList(n.getArguments()));
        }
        if (e instanceof NewArrayExpression) {
            NewArrayExpression n = (NewArrayExpression) e;
            NewArrayExpression nn = new NewArrayExpression(n.getLineNumber(), n.getType(),
                rwExprList(n.getDimensionExpressions()));
            if (n.getInitValues() != null) {
                for (Expression iv : n.getInitValues()) nn.addInitValue(rw(iv));
            }
            return nn;
        }
        if (e instanceof LambdaExpression) {
            LambdaExpression l = (LambdaExpression) e;
            return new LambdaExpression(l.getLineNumber(), l.getType(),
                l.getParameterNames(), l.getParameterTypes(), rewrite(l.getBody()));
        }
        if (e instanceof SwitchExpression) {
            SwitchExpression sw = (SwitchExpression) e;
            List<SwitchExpression.SwitchCase> cases = new ArrayList<SwitchExpression.SwitchCase>();
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                cases.add(new SwitchExpression.SwitchCase(c.getLabels(), rw(c.getValue())));
            }
            return new SwitchExpression(sw.getLineNumber(), sw.getType(), rw(sw.getSelector()), cases);
        }
        return e; // constants, ThisExpression, ClassExpression, TextBlock, etc.
    }
}
