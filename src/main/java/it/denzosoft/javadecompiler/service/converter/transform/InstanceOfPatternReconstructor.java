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
 * Reconstructs Java 16+ pattern-matching {@code instanceof}.
 *
 * The compiler lowers {@code if (o instanceof X x) { ... x ... }} to a plain
 * {@code instanceof} test whose then-branch starts with a synthetic
 * {@code X x = (X) o;} cast. The legacy decoder emits exactly that shape, which
 * is verbose and (for the binding's scope) fragile. This pass rewrites the
 * common case back into a binding pattern:
 *
 * <pre>
 *   if (o instanceof X) { X x = (X) o; ... }   ==&gt;   if (o instanceof X x) { ... }
 * </pre>
 *
 * Only the trivial leading-cast shape is rewritten; nothing else is touched, so
 * the pass is safe to run unconditionally (BUG-2026-0064).
 */
public final class InstanceOfPatternReconstructor {

    private InstanceOfPatternReconstructor() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        for (int i = 0; i < statements.size(); i++) {
            statements.set(i, visit(statements.get(i)));
        }
        return statements;
    }

    private static Statement visit(Statement stmt) {
        if (stmt == null) return null;

        if (stmt instanceof BlockStatement) {
            reconstruct(((BlockStatement) stmt).getStatements());
            return stmt;
        }
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Statement then = visit(is.getThenBody());
            Expression cond = bindAmpersand(is.getCondition());
            Bound b = tryBind(cond, then);
            if (b != null) {
                return new IfStatement(is.getLineNumber(), b.condition, b.body);
            }
            return new IfStatement(is.getLineNumber(), cond, then);
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement is = (IfElseStatement) stmt;
            Statement then = visit(is.getThenBody());
            Statement els = visit(is.getElseBody());
            Expression cond = bindAmpersand(is.getCondition());
            Bound b = tryBind(cond, then);
            if (b != null) {
                return new IfElseStatement(is.getLineNumber(), b.condition, b.body, els);
            }
            return new IfElseStatement(is.getLineNumber(), cond, then, els);
        }
        if (stmt instanceof ReturnStatement && ((ReturnStatement) stmt).hasExpression()) {
            ReturnStatement rs = (ReturnStatement) stmt;
            Expression e = bindAmpersand(rs.getExpression());
            return e == rs.getExpression() ? stmt : new ReturnStatement(rs.getLineNumber(), e);
        }
        if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            return new WhileStatement(ws.getLineNumber(), ws.getCondition(), visit(ws.getBody()));
        }
        if (stmt instanceof DoWhileStatement) {
            DoWhileStatement ds = (DoWhileStatement) stmt;
            return new DoWhileStatement(ds.getLineNumber(), ds.getCondition(), visit(ds.getBody()));
        }
        if (stmt instanceof ForStatement) {
            ForStatement fs = (ForStatement) stmt;
            return new ForStatement(fs.getLineNumber(), fs.getInit(), fs.getCondition(),
                fs.getUpdate(), visit(fs.getBody()));
        }
        if (stmt instanceof ForEachStatement) {
            ForEachStatement fe = (ForEachStatement) stmt;
            return new ForEachStatement(fe.getLineNumber(), fe.getVariableType(), fe.getVariableName(),
                fe.getIterable(), visit(fe.getBody()));
        }
        if (stmt instanceof LabelStatement) {
            LabelStatement ls = (LabelStatement) stmt;
            return new LabelStatement(ls.getLineNumber(), ls.getLabel(), visit(ls.getBody()));
        }
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement ts = (TryCatchStatement) stmt;
            Statement tryBody = visit(ts.getTryBody());
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : ts.getCatchClauses()) {
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, visit(cc.body)));
            }
            Statement fin = ts.hasFinally() ? visit(ts.getFinallyBody()) : null;
            return new TryCatchStatement(ts.getLineNumber(), tryBody, catches, fin, ts.getResources());
        }
        if (stmt instanceof SwitchStatement) {
            SwitchStatement ss = (SwitchStatement) stmt;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase sc : ss.getCases()) {
                cases.add(new SwitchStatement.SwitchCase(sc.getLabels(), reconstruct(sc.getStatements())));
            }
            return new SwitchStatement(ss.getLineNumber(), ss.getSelector(), cases, ss.isArrowStyle());
        }
        return stmt;
    }

    private static final class Bound {
        final Expression condition;
        final Statement body;
        Bound(Expression condition, Statement body) { this.condition = condition; this.body = body; }
    }

    // BUG-2026-0067: `o instanceof X && <expr using V>` where V is the (folded-away) cast binding.
    // Bind it: `o instanceof X V && <expr using V>`. Guard tightly — V must be a local whose static type
    // equals the instanceof check type, so this never fires on an unrelated `&&`.
    private static Expression bindAmpersand(Expression e) {
        if (!(e instanceof BinaryOperatorExpression)) return e;
        BinaryOperatorExpression b = (BinaryOperatorExpression) e;
        Expression l = bindAmpersand(b.getLeft());
        Expression r = bindAmpersand(b.getRight());
        if ("&&".equals(b.getOperator()) && l instanceof InstanceOfExpression) {
            InstanceOfExpression io = (InstanceOfExpression) l;
            if (!io.hasPatternVariable() && !io.hasRecordPattern()) {
                String v = findBindingVar(r, io.getCheckType());
                if (v != null) {
                    InstanceOfExpression bound = new InstanceOfExpression(io.getLineNumber(),
                        io.getExpression(), io.getCheckType(), v);
                    return new BinaryOperatorExpression(b.getLineNumber(), b.getType(), bound, "&&", r);
                }
            }
        }
        if (l == b.getLeft() && r == b.getRight()) return e;
        return new BinaryOperatorExpression(b.getLineNumber(), b.getType(), l, b.getOperator(), r);
    }

    /**
     * The cast binding referenced in {@code e}: a local used as a METHOD RECEIVER whose static type is
     * the instanceof check type or an erased Object/unknown (the folded cast loses the precise type).
     * Restricting to receiver position keeps this from binding an unrelated value-position local.
     */
    private static String findBindingVar(Expression e, Type checkType) {
        if (e == null || checkType == null) return null;
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            if (m.getObject() instanceof LocalVariableExpression && isBindingType(((LocalVariableExpression) m.getObject()).getType(), checkType)) {
                return ((LocalVariableExpression) m.getObject()).getName();
            }
            String x = findBindingVar(m.getObject(), checkType);
            if (x != null) return x;
            if (m.getArguments() != null) for (Expression a : m.getArguments()) { String y = findBindingVar(a, checkType); if (y != null) return y; }
            return null;
        }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression f = (FieldAccessExpression) e;
            if (f.getObject() instanceof LocalVariableExpression && isBindingType(((LocalVariableExpression) f.getObject()).getType(), checkType)) {
                return ((LocalVariableExpression) f.getObject()).getName();
            }
            return findBindingVar(f.getObject(), checkType);
        }
        if (e instanceof BinaryOperatorExpression) {
            String x = findBindingVar(((BinaryOperatorExpression) e).getLeft(), checkType);
            return x != null ? x : findBindingVar(((BinaryOperatorExpression) e).getRight(), checkType);
        }
        if (e instanceof UnaryOperatorExpression) return findBindingVar(((UnaryOperatorExpression) e).getExpression(), checkType);
        if (e instanceof CastExpression) return findBindingVar(((CastExpression) e).getExpression(), checkType);
        if (e instanceof InstanceOfExpression) return findBindingVar(((InstanceOfExpression) e).getExpression(), checkType);
        return null;
    }

    private static boolean isBindingType(Type t, Type checkType) {
        if (t == null) return true; // unknown -> assume the erased binding
        String d = t.getDescriptor();
        if (d == null) return true;
        return d.equals(checkType.getDescriptor()) || "Ljava/lang/Object;".equals(d);
    }

    /**
     * If {@code condition} contains a pattern-less {@code instanceof X} whose operand matches the
     * leading {@code X v = (X) operand;} of the then-body, bind the variable into the instanceof and
     * drop the cast declaration.
     */
    private static Bound tryBind(Expression condition, Statement thenBody) {
        InstanceOfExpression io = findInstanceOf(condition);
        if (io == null || io.hasPatternVariable()) return null;
        if (!(thenBody instanceof BlockStatement)) return null;
        List<Statement> body = ((BlockStatement) thenBody).getStatements();
        if (body.isEmpty()) return null;

        // The leading cast is either `X v = (X) operand;` (first use of the slot) or `v = (X) operand;`
        // (a reused slot across sibling instanceof branches). Both bind a fresh pattern variable in the
        // then-branch scope.
        String varName = null;
        CastExpression cast = null;
        Expression bareAlias = null; // BUG-2026-0067: `X v = operand;` (no cast — record/generic anchor)
        Statement first = body.get(0);
        if (first instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) first;
            if (vds.hasInitializer() && vds.getInitializer() instanceof CastExpression) {
                varName = vds.getName();
                cast = (CastExpression) vds.getInitializer();
            } else if (vds.hasInitializer() && vds.getInitializer() instanceof LocalVariableExpression) {
                varName = vds.getName();
                bareAlias = vds.getInitializer();
            }
        } else if (first instanceof ExpressionStatement
                && ((ExpressionStatement) first).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) first).getExpression();
            if (ae.getLeft() instanceof LocalVariableExpression && ae.getRight() instanceof CastExpression) {
                varName = ((LocalVariableExpression) ae.getLeft()).getName();
                cast = (CastExpression) ae.getRight();
            } else if (ae.getLeft() instanceof LocalVariableExpression && ae.getRight() instanceof LocalVariableExpression) {
                varName = ((LocalVariableExpression) ae.getLeft()).getName();
                bareAlias = ae.getRight();
            }
        }
        if (varName == null) return null;

        if (cast != null) {
            if (!sameType(cast.getType(), io.getCheckType())) return null;
            if (!sameOperand(cast.getExpression(), io.getExpression())) return null;
        } else if (bareAlias != null) {
            // The local must alias the EXACT instanceof operand (so binding it is sound).
            if (!sameOperand(bareAlias, io.getExpression())) return null;
        } else {
            return null;
        }

        InstanceOfExpression bound = new InstanceOfExpression(
            io.getLineNumber(), io.getExpression(), io.getCheckType(), varName);
        Expression newCond = replaceInstanceOf(condition, io, bound);
        List<Statement> newBody = new ArrayList<Statement>(body.subList(1, body.size()));
        return new Bound(newCond, new BlockStatement(thenBody.getLineNumber(), newBody));
    }

    private static InstanceOfExpression findInstanceOf(Expression e) {
        if (e instanceof InstanceOfExpression) return (InstanceOfExpression) e;
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            InstanceOfExpression l = findInstanceOf(b.getLeft());
            if (l != null) return l;
            return findInstanceOf(b.getRight());
        }
        if (e instanceof UnaryOperatorExpression) {
            return findInstanceOf(((UnaryOperatorExpression) e).getExpression());
        }
        return null;
    }

    private static Expression replaceInstanceOf(Expression e, InstanceOfExpression target, InstanceOfExpression repl) {
        if (e == target) return repl;
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            Expression l = replaceInstanceOf(b.getLeft(), target, repl);
            Expression r = replaceInstanceOf(b.getRight(), target, repl);
            if (l == b.getLeft() && r == b.getRight()) return e;
            return new BinaryOperatorExpression(b.getLineNumber(), b.getType(), l, b.getOperator(), r);
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            Expression inner = replaceInstanceOf(u.getExpression(), target, repl);
            if (inner == u.getExpression()) return e;
            return new UnaryOperatorExpression(u.getLineNumber(), u.getType(), u.getOperator(), inner, u.isPrefix());
        }
        return e;
    }

    private static boolean sameType(Type a, Type b) {
        if (a == null || b == null) return false;
        return a.getDescriptor() != null && a.getDescriptor().equals(b.getDescriptor());
    }

    private static boolean sameOperand(Expression a, Expression b) {
        if (a == b) return true;
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            return ((LocalVariableExpression) a).getIndex() == ((LocalVariableExpression) b).getIndex();
        }
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }
}
