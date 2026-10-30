/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;
import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds short-circuit boolean expressions from the ternary trees the flow builder emits for
 * them (BUG-2026-0176).
 *
 * <p>javac compiles {@code (a && b && c) || (d && e && f) || ...} to a DAG of conditional jumps in
 * which every "false" exit of one conjunction jumps to the head of the next. The flow builder turns
 * each conditional into a ternary and DUPLICATES every shared successor, so the output grows
 * exponentially with the number of terms: {@code JLayer.isEventEnabled} (13 terms) became a single
 * 1.5 MB {@code return} that took javac minutes to compile. The value it computes is correct, the
 * shape is not source.</p>
 *
 * <p>This pass maps a boolean ternary tree to a decision graph, hash-consing structurally equal
 * sub-trees back into shared nodes, and applies the classic short-circuit reduction (A's successor B
 * has A as its only predecessor and shares A's other successor: {@code A && B}, {@code A || B},
 * {@code !A && B}, {@code !A || B}). Every reduction is an exact boolean identity and preserves the
 * evaluation order of the conditions, so the rewrite never changes what is computed. When the graph
 * does not reduce to one condition the original expression is kept.</p>
 */
public final class ShortCircuitRebuilder {
    private ShortCircuitRebuilder() {}

    public static List<Statement> rebuild(List<Statement> body) {
        if (body == null) return body;
        return new AstMapper() {
            @Override
            public Expression map(Expression e) {
                if (e instanceof TernaryExpression && isBooleanTernary((TernaryExpression) e)) {
                    Expression r = reduce((TernaryExpression) e);
                    if (r != null) return r;
                }
                return super.map(e);
            }
        }.mapList(body);
    }

    // ------------------------------------------------------------------ graph

    private static final class Node {
        Expression cond;            // null for the TRUE/FALSE leaves
        Node t, f;
        final boolean value;
        Node(Expression cond, Node t, Node f) { this.cond = cond; this.t = t; this.f = f; this.value = false; }
        Node(boolean value) { this.cond = null; this.value = value; }
        boolean leaf() { return cond == null; }
    }

    private static boolean isBooleanTernary(TernaryExpression te) {
        return isBoolConst(te.getTrueExpression()) || isBoolConst(te.getFalseExpression())
            || (te.getType() == PrimitiveType.BOOLEAN && isBoolValued(te.getTrueExpression())
                && isBoolValued(te.getFalseExpression()));
    }

    private static boolean isBoolConst(Expression e) { return e instanceof BooleanExpression; }

    private static boolean isBoolValued(Expression e) {
        if (e instanceof BooleanExpression || e instanceof InstanceOfExpression) return true;
        if (e instanceof TernaryExpression) return isBooleanTernary((TernaryExpression) e);
        if (e instanceof UnaryOperatorExpression) return "!".equals(((UnaryOperatorExpression) e).getOperator());
        if (e instanceof BinaryOperatorExpression) {
            String op = ((BinaryOperatorExpression) e).getOperator();
            return "==".equals(op) || "!=".equals(op) || "<".equals(op) || "<=".equals(op) || ">".equals(op)
                || ">=".equals(op) || "&&".equals(op) || "||".equals(op);
        }
        return e != null && e.getType() == PrimitiveType.BOOLEAN;
    }

    private static Expression reduce(TernaryExpression root) {
        Map<String, Node> cons = new HashMap<String, Node>();
        Node T = new Node(true), F = new Node(false);
        int[] budget = new int[] { 20000 };
        Node r = build(root, cons, T, F, budget);
        if (r == null || budget[0] <= 0) return null;
        Node out = reduceGraph(r, T, F);
        if (out == null) return null;
        int line = root.getLineNumber();
        if (out.t == T && out.f == F) return out.cond;
        if (out.t == F && out.f == T) return not(line, out.cond);
        return null;
    }

    private static Node build(Expression e, Map<String, Node> cons, Node T, Node F, int[] budget) {
        if (--budget[0] <= 0) return null;
        if (e instanceof BooleanExpression) return ((BooleanExpression) e).getValue() ? T : F;
        Expression c; Node t, f;
        if (e instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) e;
            if (!isBoolValued(te.getTrueExpression()) || !isBoolValued(te.getFalseExpression())) return null;
            c = leaf(te.getCondition());
            t = build(te.getTrueExpression(), cons, T, F, budget);
            f = build(te.getFalseExpression(), cons, T, F, budget);
            if (t == null || f == null) return null;
        } else if (isBoolValued(e)) {
            c = leaf(e); t = T; f = F;
        } else {
            return null;
        }
        String ck = key(c);
        if (ck == null) return null;
        String k = ck + "?" + System.identityHashCode(t) + ":" + System.identityHashCode(f);
        Node n = cons.get(k);
        if (n == null) { n = new Node(c, t, f); cons.put(k, n); }
        return n;
    }

    /** Only the `(a <=> b) OP 0` comparison shape is rewritten; anything else is kept as is
     *  (BooleanSimplifier's int-vs-boolean guesses are not safe on arbitrary leaves). */
    private static Expression leaf(Expression e) {
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            boolean cmpLeft = b.getLeft() instanceof BinaryOperatorExpression
                && ((BinaryOperatorExpression) b.getLeft()).getOperator().startsWith("<=>")
                && b.getRight() instanceof IntegerConstantExpression
                && ((IntegerConstantExpression) b.getRight()).getValue() == 0;
            if (cmpLeft) return BooleanSimplifier.simplifyBooleanExpr(e);
        }
        return e;
    }

    private static Node reduceGraph(Node root, Node T, Node F) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 10000) {
            changed = false;
            Map<Node, Integer> preds = new IdentityHashMap<Node, Integer>();
            List<Node> order = new ArrayList<Node>();
            collect(root, preds, order, new IdentityHashMap<Node, Boolean>());
            for (Node a : order) {
                if (tryMerge(a, a.t, preds, true) || tryMerge(a, a.f, preds, false)) { changed = true; break; }
            }
        }
        return root.leaf() ? null : root;
    }

    private static void collect(Node n, Map<Node, Integer> preds, List<Node> order, Map<Node, Boolean> seen) {
        if (n.leaf() || seen.containsKey(n)) return;
        seen.put(n, Boolean.TRUE);
        order.add(n);
        for (Node s : new Node[] { n.t, n.f }) {
            Integer p = preds.get(s);
            preds.put(s, p == null ? 1 : p + 1);
            collect(s, preds, order, seen);
        }
    }

    /** Fold B (A's successor on the {@code viaTrue} side) into A when B has no other predecessor. */
    private static boolean tryMerge(Node a, Node b, Map<Node, Integer> preds, boolean viaTrue) {
        if (b.leaf() || b == a) return false;
        Integer p = preds.get(b);
        if (p == null || p != 1) return false;
        Node other = viaTrue ? a.f : a.t;
        int line = a.cond.getLineNumber();
        Expression nc;
        if (viaTrue && other == b.f) nc = and(line, a.cond, b.cond);              // a && b
        else if (viaTrue && other == b.t) nc = or(line, not(line, a.cond), b.cond); // !a || b
        else if (!viaTrue && other == b.t) nc = or(line, a.cond, b.cond);          // a || b
        else if (!viaTrue && other == b.f) nc = and(line, not(line, a.cond), b.cond); // !a && b
        else return false;
        // rebuild A in place: same identity keeps the other references valid
        replace(a, nc, b.t, b.f);
        return true;
    }

    private static void replace(Node a, Expression cond, Node t, Node f) {
        a.cond = cond;
        a.t = t;
        a.f = f;
    }

    private static Expression and(int line, Expression a, Expression b) {
        return new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "&&", b);
    }

    private static Expression or(int line, Expression a, Expression b) {
        return new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "||", b);
    }

    private static Expression not(int line, Expression a) {
        if (a instanceof UnaryOperatorExpression && "!".equals(((UnaryOperatorExpression) a).getOperator())) {
            return ((UnaryOperatorExpression) a).getExpression();
        }
        // De Morgan: push the negation to the leaves (`!(a && b)` -> `!a || !b`), which turns the
        // inverted-jump encoding javac uses for `x <= c` back into the source comparison.
        if (a instanceof BinaryOperatorExpression && ("&&".equals(((BinaryOperatorExpression) a).getOperator())
                || "||".equals(((BinaryOperatorExpression) a).getOperator()))) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) a;
            Expression l = not(line, b.getLeft()), r = not(line, b.getRight());
            return "&&".equals(b.getOperator()) ? or(line, l, r) : and(line, l, r);
        }
        if (a instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) a;
            String inv = null;
            String op = b.getOperator();
            if ("==".equals(op)) inv = "!=";
            else if ("!=".equals(op)) inv = "==";
            else if ("<".equals(op)) inv = ">=";
            else if (">=".equals(op)) inv = "<";
            else if (">".equals(op)) inv = "<=";
            else if ("<=".equals(op)) inv = ">";
            // relational inversion is wrong for NaN operands: only integral comparisons invert
            if (inv != null && ("==".equals(op) || "!=".equals(op) || integral(b.getLeft()))) {
                return new BinaryOperatorExpression(b.getLineNumber(), PrimitiveType.BOOLEAN, b.getLeft(), inv, b.getRight());
            }
        }
        return new UnaryOperatorExpression(line, PrimitiveType.BOOLEAN, "!", a, true);
    }

    private static boolean integral(Expression e) {
        return e != null && (e.getType() == PrimitiveType.INT || e.getType() == PrimitiveType.LONG
            || e.getType() == PrimitiveType.SHORT || e.getType() == PrimitiveType.BYTE
            || e.getType() == PrimitiveType.CHAR);
    }

    // ------------------------------------------------------------------ structural key

    /** Structural identity of a side-effect-free expression, or null when it cannot be keyed. */
    private static String key(Expression e) {
        StringBuilder sb = new StringBuilder();
        return key(e, sb) ? sb.toString() : null;
    }

    private static boolean key(Expression e, StringBuilder sb) {
        if (sb.length() > 4000) return false;
        if (e == null) { sb.append("_"); return true; }
        if (e instanceof LocalVariableExpression) { sb.append("L").append(((LocalVariableExpression) e).getName()); return true; }
        if (e instanceof ThisExpression) { sb.append("this"); return true; }
        if (e instanceof NullExpression) { sb.append("null"); return true; }
        if (e instanceof IntegerConstantExpression) { sb.append("I").append(((IntegerConstantExpression) e).getValue()); return true; }
        if (e instanceof LongConstantExpression) { sb.append("J").append(((LongConstantExpression) e).getValue()); return true; }
        if (e instanceof BooleanExpression) { sb.append(((BooleanExpression) e).getValue()); return true; }
        if (e instanceof StringConstantExpression) { sb.append("S\"").append(((StringConstantExpression) e).getValue()).append('"'); return true; }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression f = (FieldAccessExpression) e;
            sb.append("F(");
            if (!key(f.getObject(), sb)) return false;
            sb.append('.').append(f.getOwnerInternalName()).append('.').append(f.getName()).append(')');
            return true;
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            sb.append("B(");
            if (!key(b.getLeft(), sb)) return false;
            sb.append(b.getOperator());
            if (!key(b.getRight(), sb)) return false;
            sb.append(')');
            return true;
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            if ("++".equals(u.getOperator()) || "--".equals(u.getOperator())) return false;
            sb.append("U(").append(u.getOperator());
            if (!key(u.getExpression(), sb)) return false;
            sb.append(')');
            return true;
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression i = (InstanceOfExpression) e;
            if (i.getPatternVariableName() != null || i.getRecordPattern() != null) return false;
            sb.append("IO(");
            if (!key(i.getExpression(), sb)) return false;
            sb.append(' ').append(i.getCheckType() != null ? i.getCheckType().getDescriptor() : "?").append(')');
            return true;
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e;
            sb.append("C(").append(c.getType() != null ? c.getType().getDescriptor() : "?");
            if (!key(c.getExpression(), sb)) return false;
            sb.append(')');
            return true;
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression a = (ArrayAccessExpression) e;
            sb.append("A(");
            if (!key(a.getArray(), sb)) return false;
            sb.append('[');
            if (!key(a.getIndex(), sb)) return false;
            sb.append("])");
            return true;
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            sb.append("M(");
            if (!key(m.getObject(), sb)) return false;
            sb.append('.').append(m.getMethodName()).append(m.getDescriptor());
            for (Expression a : m.getArguments()) { sb.append(','); if (!key(a, sb)) return false; }
            sb.append(')');
            return true;
        }
        if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) e;
            sb.append("SM(").append(m.getOwnerInternalName()).append('.').append(m.getMethodName()).append(m.getDescriptor());
            for (Expression a : m.getArguments()) { sb.append(','); if (!key(a, sb)) return false; }
            sb.append(')');
            return true;
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            sb.append("T(");
            if (!key(t.getCondition(), sb)) return false;
            sb.append('?');
            if (!key(t.getTrueExpression(), sb)) return false;
            sb.append(':');
            if (!key(t.getFalseExpression(), sb)) return false;
            sb.append(')');
            return true;
        }
        return false;
    }
}
