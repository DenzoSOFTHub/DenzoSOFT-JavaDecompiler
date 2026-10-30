/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.*;

/**
 * Splits a local variable into independent variables when one name carries values of
 * incompatible types (BUG-2026-0158).
 *
 * <p>Without a LocalVariableTable a local is named after its slot, and javac reuses a dead slot
 * for an unrelated variable: {@code var4} may hold a {@code String} in one block and an
 * {@code Iterator} in the next. The decoder separates such reuses only when the verifier
 * category changes (int vs reference), so two reference variables of different classes end up
 * under one declaration and the second assignment does not compile.</p>
 *
 * <p>This pass runs a reaching-definitions analysis over the structured body. Every read is
 * linked to the definitions that can reach it; definitions linked through a common read form
 * one <em>web</em> - one variable of the original program. When the webs of a name carry values
 * of different types, every web but the first gets a fresh name and the type of its own
 * values. Renaming a whole web is exact: no read ever sees a definition of another web.</p>
 */
public final class VariableWebSplitter {

    /** A definition: a declaration, an assignment, an increment, or a parameter. */
    private static final class Def {
        final int id;
        final String name;
        final Type valueType;   // null when unknown
        final boolean param;
        Def(int id, String name, Type valueType, boolean param) {
            this.id = id; this.name = name; this.valueType = valueType; this.param = param;
        }
    }

    /** Reaching-definition state: name -> definitions; null map = unreachable. */
    private static Map<String, Set<Def>> copy(Map<String, Set<Def>> s) {
        if (s == null) return null;
        Map<String, Set<Def>> c = new HashMap<String, Set<Def>>();
        for (Map.Entry<String, Set<Def>> e : s.entrySet()) c.put(e.getKey(), new HashSet<Def>(e.getValue()));
        return c;
    }

    private static Map<String, Set<Def>> merge(Map<String, Set<Def>> a, Map<String, Set<Def>> b) {
        if (a == null) return copy(b);
        if (b == null) return copy(a);
        Map<String, Set<Def>> c = copy(a);
        for (Map.Entry<String, Set<Def>> e : b.entrySet()) {
            Set<Def> s = c.get(e.getKey());
            if (s == null) { s = new HashSet<Def>(); c.put(e.getKey(), s); }
            s.addAll(e.getValue());
        }
        return c;
    }

    private static boolean sameState(Map<String, Set<Def>> a, Map<String, Set<Def>> b) {
        if (a == null || b == null) return a == b;
        return a.equals(b);
    }

    // per-node bookkeeping (identity keyed)
    private final Map<Object, Def> defOf = new IdentityHashMap<Object, Def>();
    private final Map<LocalVariableExpression, Set<Def>> useDefs = new IdentityHashMap<LocalVariableExpression, Set<Def>>();
    private final List<Def> allDefs = new ArrayList<Def>();
    private final Map<String, Type> declaredType = new HashMap<String, Type>();
    private final Set<String> excluded = new HashSet<String>();
    private int nextId;
    private boolean recording; // false during loop fixpoint iterations

    // break / continue targets
    private final LinkedList<Object[]> targets = new LinkedList<Object[]>(); // {Statement, label, List<state> breaks, List<state> continues, Boolean loop}
    private final LinkedList<List<Map<String, Set<Def>>>> trySinks = new LinkedList<List<Map<String, Set<Def>>>>();

    private VariableWebSplitter() { }

    public static List<Statement> split(List<Statement> body, List<String> paramNames, List<Type> paramTypes) {
        if (body == null || body.isEmpty()) return body;
        VariableWebSplitter w = new VariableWebSplitter();
        Map<String, Set<Def>> state = new HashMap<String, Set<Def>>();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.size(); i++) {
                Type t = paramTypes != null && i < paramTypes.size() ? paramTypes.get(i) : null;
                Def d = w.newDef(paramNames.get(i), t, true);
                Set<Def> s = new HashSet<Def>();
                s.add(d);
                state.put(paramNames.get(i), s);
                w.declaredType.put(paramNames.get(i), t);
            }
        }
        try {
            w.recording = true;
            w.list(body, state);
        } catch (RuntimeException e) {
            return body; // anything unexpected: leave the body alone
        }
        return w.rewrite(body);
    }

    private Def newDef(String name, Type t, boolean param) {
        Def d = new Def(nextId++, name, t, param);
        allDefs.add(d);
        return d;
    }

    // =================================================================== analysis

    private Map<String, Set<Def>> list(List<Statement> l, Map<String, Set<Def>> s) {
        if (l == null) return s;
        for (Statement st : l) s = stmt(st, s);
        return s;
    }

    private Map<String, Set<Def>> stmt(Statement st, Map<String, Set<Def>> s) {
        if (st == null || s == null) return s;
        if (st instanceof BlockStatement) return list(((BlockStatement) st).getStatements(), s);
        if (st instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) st;
            if (v.hasInitializer()) expr(v.getInitializer(), s);
            if (!declaredType.containsKey(v.getName())) declaredType.put(v.getName(), v.getType());
            else if (!sameType(declaredType.get(v.getName()), v.getType())) excluded.add(v.getName());
            Type vt = v.hasInitializer() ? valueType(v.getInitializer()) : null;
            if (vt == null && !v.isVar()) vt = v.getType(); // the declaration fixes the web's type
            define(st, v.getName(), vt, s);
            noteSink(s);
            return s;
        }
        if (st instanceof ExpressionStatement) {
            expr(((ExpressionStatement) st).getExpression(), s);
            noteSink(s);
            return s;
        }
        if (st instanceof ReturnStatement) {
            expr(((ReturnStatement) st).getExpression(), s);
            return null;
        }
        if (st instanceof ThrowStatement) {
            expr(((ThrowStatement) st).getExpression(), s);
            return null;
        }
        if (st instanceof YieldStatement) {
            expr(((YieldStatement) st).getExpression(), s);
            return null;
        }
        if (st instanceof BreakStatement) {
            Object[] t = find(((BreakStatement) st).getLabel(), false);
            if (t != null) addState(t, 2, s);
            return null;
        }
        if (st instanceof ContinueStatement) {
            Object[] t = find(((ContinueStatement) st).getLabel(), true);
            if (t != null) addState(t, 3, s);
            return null;
        }
        if (st instanceof IfStatement) {
            IfStatement i = (IfStatement) st;
            expr(i.getCondition(), s);
            Map<String, Set<Def>> t = stmt(i.getThenBody(), copy(s));
            return merge(t, s);
        }
        if (st instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) st;
            expr(i.getCondition(), s);
            Map<String, Set<Def>> t = stmt(i.getThenBody(), copy(s));
            Map<String, Set<Def>> e = stmt(i.getElseBody(), copy(s));
            if (t == null) return e;
            if (e == null) return t;
            return merge(t, e);
        }
        if (st instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) st;
            Statement body = l.getBody();
            if (body instanceof WhileStatement || body instanceof DoWhileStatement
                    || body instanceof ForStatement || body instanceof ForEachStatement) {
                return loop(body, l.getLabel(), s);
            }
            Object[] t = push(l, l.getLabel(), false);
            Map<String, Set<Def>> r;
            try {
                r = stmt(body, s);
            } finally {
                targets.remove(t);
            }
            return mergeAll(r, states(t, 2));
        }
        if (st instanceof WhileStatement || st instanceof DoWhileStatement
                || st instanceof ForStatement || st instanceof ForEachStatement) {
            return loop(st, null, s);
        }
        if (st instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) st;
            expr(sy.getMonitor(), s);
            return stmt(sy.getBody(), s);
        }
        if (st instanceof AssertStatement) {
            AssertStatement a = (AssertStatement) st;
            Map<String, Set<Def>> c = copy(s);
            expr(a.getCondition(), c);
            expr(a.getMessage(), c);
            return merge(s, c);
        }
        if (st instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) st;
            List<Map<String, Set<Def>>> sink = new ArrayList<Map<String, Set<Def>>>();
            sink.add(copy(s));
            trySinks.push(sink);
            Map<String, Set<Def>> afterTry;
            try {
                if (t.getResources() != null) s = list(t.getResources(), s);
                afterTry = stmt(t.getTryBody(), s);
            } finally {
                trySinks.pop();
            }
            if (afterTry != null) sink.add(copy(afterTry));
            Map<String, Set<Def>> inCatch = null;
            for (Map<String, Set<Def>> x : sink) inCatch = merge(inCatch, x);
            Map<String, Set<Def>> out = afterTry;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                Map<String, Set<Def>> c = copy(inCatch);
                if (c != null) {
                    Def d = newDef(cc.variableName, cc.exceptionTypes != null && cc.exceptionTypes.size() == 1
                        ? cc.exceptionTypes.get(0) : null, true);
                    Set<Def> ds = new HashSet<Def>();
                    ds.add(d);
                    c.put(cc.variableName, ds);
                    // START_CHANGE: BUG-2026-0194-20261018-1 - The catch parameter is no longer
                    // excluded from splitting. It is a param-def, so its own web always keeps the
                    // name; a later reuse of its slot inside the clause (`throw e;` then
                    // `e = (ClassNotFoundException) Thread.currentThread().getContextClassLoader()`
                    // in com.sun.corba.se.impl.util.Utility.loadClassOfType) is a web of its own
                    // and gets a fresh name and its own type.
                    // END_CHANGE: BUG-2026-0194-1
                }
                Map<String, Set<Def>> e = stmt(cc.body, c);
                out = out == null ? e : (e == null ? out : merge(out, e));
            }
            if (t.hasFinally()) {
                Map<String, Set<Def>> f = merge(out, inCatch);
                Map<String, Set<Def>> fe = stmt(t.getFinallyBody(), f);
                if (fe == null) return null;
                return out == null ? null : merge(out, fe);
            }
            return out;
        }
        if (st instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) st;
            expr(sw.getSelector(), s);
            Object[] t = push(sw, null, false);
            Map<String, Set<Def>> fall = null;
            boolean hasDefault = false;
            try {
                for (SwitchStatement.SwitchCase c : sw.getCases()) {
                    if (c.isDefault()) hasDefault = true;
                    Map<String, Set<Def>> in = merge(copy(s), fall);
                    if (c.getPatternBindings() != null) {
                        for (String b : c.getPatternBindings()) excluded.add(b);
                    }
                    fall = list(c.getStatements(), in);
                    if (sw.isArrowStyle()) {
                        addState(t, 2, fall);
                        fall = null;
                    }
                }
            } finally {
                targets.remove(t);
            }
            Map<String, Set<Def>> out = mergeAll(fall, states(t, 2));
            if (!hasDefault) out = merge(out, s);
            return out;
        }
        return s;
    }

    private Map<String, Set<Def>> loop(Statement st, String label, Map<String, Set<Def>> s) {
        Map<String, Set<Def>> entry = s;
        if (st instanceof ForStatement) {
            ForStatement f = (ForStatement) st;
            entry = stmt(f.getInit(), s);
        } else if (st instanceof ForEachStatement) {
            expr(((ForEachStatement) st).getIterable(), s);
            excluded.add(((ForEachStatement) st).getVariableName());
        }
        // Head of the loop: the entry state plus every definition made anywhere in the loop
        // (a sound over-approximation of the fixpoint, computed in one pass)
        Map<String, Set<Def>> head = merge(entry, defsWithin(st));
        Object[] t = push(st, label, true);
        Map<String, Set<Def>> back;
        try {
            back = loopBody(st, copy(head));
        } finally {
            targets.remove(t);
        }
        Map<String, Set<Def>> exit;
        boolean infinite = (st instanceof WhileStatement && isTrue(((WhileStatement) st).getCondition()))
            || (st instanceof ForStatement && (((ForStatement) st).getCondition() == null
                || isTrue(((ForStatement) st).getCondition())))
            || (st instanceof DoWhileStatement && isTrue(((DoWhileStatement) st).getCondition()));
        if (st instanceof DoWhileStatement) {
            exit = infinite ? null : back;
        } else {
            exit = infinite ? null : head;
        }
        return mergeAll(exit, states(t, 2));
    }

    /** All definitions inside a statement, keyed by name (created on demand, shared with define). */
    private Map<String, Set<Def>> defsWithin(Statement st) {
        final Map<String, Set<Def>> out = new HashMap<String, Set<Def>>();
        new AstMapper() {
            @Override
            public Statement map(Statement s) {
                if (s instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                    Type vt = v.hasInitializer() ? valueType(v.getInitializer()) : null;
                    if (vt == null && !v.isVar()) vt = v.getType();
                    add(out, defFor(s, v.getName(), vt));
                }
                return super.map(s);
            }
            @Override
            public Expression map(Expression e) {
                if (e instanceof AssignmentExpression && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                    AssignmentExpression a = (AssignmentExpression) e;
                    LocalVariableExpression lv = (LocalVariableExpression) a.getLeft();
                    add(out, defFor(a, lv.getName(), "=".equals(a.getOperator()) ? valueType(a.getRight()) : lv.getType()));
                } else if (e instanceof UnaryOperatorExpression
                        && ("++".equals(((UnaryOperatorExpression) e).getOperator()) || "--".equals(((UnaryOperatorExpression) e).getOperator()))
                        && ((UnaryOperatorExpression) e).getExpression() instanceof LocalVariableExpression) {
                    LocalVariableExpression lv = (LocalVariableExpression) ((UnaryOperatorExpression) e).getExpression();
                    add(out, defFor(e, lv.getName(), lv.getType()));
                }
                return super.map(e);
            }
            @Override
            protected boolean enterLambda(LambdaExpression l) { return false; }
        }.map(st);
        return out;
    }

    private static void add(Map<String, Set<Def>> m, Def d) {
        Set<Def> s = m.get(d.name);
        if (s == null) { s = new HashSet<Def>(); m.put(d.name, s); }
        s.add(d);
    }

    private Def defFor(Object node, String name, Type t) {
        Def d = defOf.get(node);
        if (d == null) {
            d = newDef(name, t, false);
            defOf.put(node, d);
        }
        return d;
    }

    /** One pass over the loop condition + body; returns the state flowing back to the head. */
    private Map<String, Set<Def>> loopBody(Statement st, Map<String, Set<Def>> head) {
        if (st instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) st;
            expr(w.getCondition(), head);
            return stmt(w.getBody(), head);
        }
        if (st instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) st;
            Map<String, Set<Def>> b = stmt(d.getBody(), head);
            if (b != null) expr(d.getCondition(), b);
            return b;
        }
        if (st instanceof ForStatement) {
            ForStatement f = (ForStatement) st;
            expr(f.getCondition(), head);
            Map<String, Set<Def>> b = stmt(f.getBody(), head);
            if (b != null) b = stmt(f.getUpdate(), b);
            return b;
        }
        ForEachStatement fe = (ForEachStatement) st;
        return stmt(fe.getBody(), head);
    }

    private static boolean isTrue(Expression e) {
        return e instanceof BooleanExpression && ((BooleanExpression) e).getValue();
    }

    private Object[] push(Statement st, String label, boolean loop) {
        Object[] t = new Object[]{st, label, new ArrayList<Map<String, Set<Def>>>(),
            new ArrayList<Map<String, Set<Def>>>(), Boolean.valueOf(loop)};
        targets.push(t);
        return t;
    }

    private Object[] find(String label, boolean cont) {
        for (Object[] t : targets) {
            boolean loop = ((Boolean) t[4]).booleanValue();
            if (label != null) {
                if (label.equals(t[1])) return t;
            } else if (cont ? loop : (loop || t[0] instanceof SwitchStatement)) {
                return t;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void addState(Object[] t, int idx, Map<String, Set<Def>> s) {
        if (s != null) ((List<Map<String, Set<Def>>>) t[idx]).add(copy(s));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Set<Def>>> states(Object[] t, int idx) {
        return (List<Map<String, Set<Def>>>) t[idx];
    }

    private static Map<String, Set<Def>> mergeAll(Map<String, Set<Def>> a, List<Map<String, Set<Def>>> l) {
        Map<String, Set<Def>> r = a;
        for (Map<String, Set<Def>> x : l) r = r == null ? copy(x) : merge(r, x);
        return r;
    }

    private void noteSink(Map<String, Set<Def>> s) {
        if (!trySinks.isEmpty() && s != null) trySinks.peek().add(copy(s));
    }

    private void define(Object node, String name, Type valueType, Map<String, Set<Def>> s) {
        Def d = defOf.get(node);
        if (d == null) {
            d = newDef(name, valueType, false);
            defOf.put(node, d);
        }
        Set<Def> ds = new HashSet<Def>();
        ds.add(d);
        s.put(name, ds);
    }

    private final List<Def[]> links = new ArrayList<Def[]>();

    private void link(Def d, Set<Def> before) {
        if (d == null || before == null || !recording) return;
        for (Def b : before) links.add(new Def[]{d, b});
    }

    private void use(LocalVariableExpression lv, Map<String, Set<Def>> s) {
        if (!recording) return;
        Set<Def> ds = s.get(lv.getName());
        Set<Def> prev = useDefs.get(lv);
        Set<Def> all = new HashSet<Def>();
        if (prev != null) all.addAll(prev);
        if (ds != null) all.addAll(ds);
        useDefs.put(lv, all);
    }

    /** Evaluates an expression in order, recording uses and applying definitions to s. */
    private void expr(Expression e, Map<String, Set<Def>> s) {
        if (e == null || s == null) return;
        if (e instanceof LocalVariableExpression) {
            use((LocalVariableExpression) e, s);
            return;
        }
        if (e instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) e;
            if (a.getLeft() instanceof LocalVariableExpression) {
                LocalVariableExpression lv = (LocalVariableExpression) a.getLeft();
                Set<Def> before = s.get(lv.getName());
                if (!"=".equals(a.getOperator())) use(lv, s);
                expr(a.getRight(), s);
                Type vt = "=".equals(a.getOperator()) ? valueType(a.getRight()) : lv.getType();
                define(a, lv.getName(), vt, s);
                // a compound assignment updates the same variable it reads
                if (!"=".equals(a.getOperator())) link(defOf.get(a), before);
                return;
            }
            lvalueParts(a.getLeft(), s);
            expr(a.getRight(), s);
            return;
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            if (("++".equals(u.getOperator()) || "--".equals(u.getOperator()))
                    && u.getExpression() instanceof LocalVariableExpression) {
                LocalVariableExpression lv = (LocalVariableExpression) u.getExpression();
                Set<Def> before = s.get(lv.getName());
                use(lv, s);
                define(u, lv.getName(), lv.getType(), s);
                link(defOf.get(u), before);
                // the increment keeps the same variable: link it to its own input
                return;
            }
            expr(u.getExpression(), s);
            return;
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            String op = b.getOperator();
            expr(b.getLeft(), s);
            if ("&&".equals(op) || "||".equals(op)) {
                Map<String, Set<Def>> r = copy(s);
                expr(b.getRight(), r);
                s.putAll(merge(s, r));
                return;
            }
            expr(b.getRight(), s);
            return;
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            expr(t.getCondition(), s);
            Map<String, Set<Def>> a = copy(s);
            Map<String, Set<Def>> b = copy(s);
            expr(t.getTrueExpression(), a);
            expr(t.getFalseExpression(), b);
            s.clear();
            s.putAll(merge(a, b));
            return;
        }
        if (e instanceof LambdaExpression) {
            // captured locals are effectively final; leave every name used inside alone
            final Map<String, Set<Def>> fs = s;
            new AstMapper() {
                @Override
                protected Expression mapLocal(LocalVariableExpression lv) {
                    excluded.add(lv.getName());
                    return lv;
                }
            }.map(((LambdaExpression) e).getBody());
            return;
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression io = (InstanceOfExpression) e;
            expr(io.getExpression(), s);
            if (io.getPatternVariableName() != null) excluded.add(io.getPatternVariableName());
            return;
        }
        if (e instanceof SwitchExpression) {
            SwitchExpression sw = (SwitchExpression) e;
            expr(sw.getSelector(), s);
            Map<String, Set<Def>> out = null;
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                Map<String, Set<Def>> in = copy(s);
                if (c.getPatternBinding() != null) excluded.add(c.getPatternBinding());
                if (c.getBodyStatements() != null) in = list(c.getBodyStatements(), in);
                if (in != null) {
                    expr(c.getGuard(), in);
                    expr(c.getValue(), in);
                }
                out = merge(out, in);
            }
            if (out != null) { s.clear(); s.putAll(out); }
            return;
        }
        for (Expression c : LocalDeclarationFixer.children(e)) expr(c, s);
    }

    private void lvalueParts(Expression l, Map<String, Set<Def>> s) {
        if (l instanceof ArrayAccessExpression) {
            expr(((ArrayAccessExpression) l).getArray(), s);
            expr(((ArrayAccessExpression) l).getIndex(), s);
        } else if (l instanceof FieldAccessExpression) {
            expr(((FieldAccessExpression) l).getObject(), s);
        } else {
            expr(l, s);
        }
    }

    // =================================================================== typing

    private Type valueType(Expression e) {
        if (e == null || e instanceof NullExpression) return null;
        if (e instanceof CastExpression) return norm(e.getType());
        if (e instanceof NewExpression) return new ObjectType(((NewExpression) e).getInternalTypeName());
        if (e instanceof StringConstantExpression) return ObjectType.STRING;
        if (e instanceof MethodInvocationExpression) return returnType(((MethodInvocationExpression) e).getDescriptor());
        if (e instanceof StaticMethodInvocationExpression) return returnType(((StaticMethodInvocationExpression) e).getDescriptor());
        if (e instanceof FieldAccessExpression) {
            String d = ((FieldAccessExpression) e).getDescriptor();
            if (d != null && d.length() > 0) return TypeCoercer.parseDescriptor(d);
        }
        if (e instanceof NewArrayExpression) {
            Type t = e.getType();
            return t instanceof PrimitiveType ? new ArrayType(t, 1) : norm(t);
        }
        if (e instanceof AssignmentExpression) return valueType(((AssignmentExpression) e).getRight());
        // START_CHANGE: BUG-2026-0195-20261018-1 - A copy of another local carries that local's
        // declared type when it is a definite class type: the switch-on-String temporary
        // `var8 = region` (String) shared a slot with `var8 = getDefaultList(..)` (List) and, typed
        // "unknown", never conflicted with it (ResourceBundle.Control.CandidateListCache).
        if (e instanceof LocalVariableExpression) {
            String src = ((LocalVariableExpression) e).getName();
            Type dt = excluded.contains(src) ? null : declaredType.get(src);
            if (dt instanceof ObjectType && !(dt instanceof GenericType)
                    && !"java/lang/Object".equals(((ObjectType) dt).getInternalName())) return norm(dt);
            return null; // its own web decides
        }
        // END_CHANGE: BUG-2026-0195-1
        return null;
    }

    private static Type returnType(String desc) {
        if (desc == null) return null;
        int c = desc.lastIndexOf(')');
        if (c < 0 || c + 1 >= desc.length()) return null;
        String r = desc.substring(c + 1);
        if ("V".equals(r)) return null;
        return TypeCoercer.parseDescriptor(r);
    }

    private static Type norm(Type t) {
        if (t instanceof ObjectType && ((ObjectType) t).getInternalName() != null
                && ((ObjectType) t).getInternalName().startsWith("[")) {
            return TypeCoercer.parseDescriptor(((ObjectType) t).getInternalName());
        }
        if (t instanceof ObjectType && t.getDimension() > 0) {
            ObjectType o = (ObjectType) t;
            return new ArrayType(new ObjectType(o.getInternalName()), o.getDimension());
        }
        return t;
    }

    private static boolean sameType(Type a, Type b) {
        if (a == null || b == null) return a == b;
        a = norm(a);
        b = norm(b);
        return a.getDescriptor() != null && a.getDescriptor().equals(b.getDescriptor());
    }

    private static boolean isReference(Type t) {
        return t instanceof ObjectType || t instanceof ArrayType;
    }

    // =================================================================== webs and renaming

    private final Map<Def, Def> parent = new HashMap<Def, Def>();

    private Def root(Def d) {
        Def r = d;
        while (parent.containsKey(r) && parent.get(r) != r) r = parent.get(r);
        return r;
    }

    private void union(Def a, Def b) {
        Def ra = root(a), rb = root(b);
        if (ra == rb) return;
        // keep the earliest definition (parameters first) as representative
        if (rb.id < ra.id) { Def t = ra; ra = rb; rb = t; }
        parent.put(rb, ra);
    }

    private List<Statement> rewrite(List<Statement> body) {
        for (Def[] l : links) union(l[0], l[1]);
        for (Set<Def> ds : useDefs.values()) {
            Def first = null;
            for (Def d : ds) {
                if (first == null) first = d; else union(first, d);
            }
        }
        // webs per name
        Map<String, LinkedHashMap<Def, List<Def>>> webs = new HashMap<String, LinkedHashMap<Def, List<Def>>>();
        for (Def d : allDefs) {
            LinkedHashMap<Def, List<Def>> m = webs.get(d.name);
            if (m == null) { m = new LinkedHashMap<Def, List<Def>>(); webs.put(d.name, m); }
            Def r = root(d);
            List<Def> l = m.get(r);
            if (l == null) { l = new ArrayList<Def>(); m.put(r, l); }
            l.add(d);
        }
        final Map<Def, String> newName = new HashMap<Def, String>();
        final Map<Def, Type> newType = new HashMap<Def, Type>();
        Set<String> taken = new HashSet<String>(webs.keySet());
        for (Map.Entry<String, LinkedHashMap<Def, List<Def>>> en : webs.entrySet()) {
            String name = en.getKey();
            if (excluded.contains(name) || en.getValue().size() < 2) continue;
            // the type each web's values agree on
            List<Type> types = new ArrayList<Type>();
            boolean conflict = false;
            boolean allReference = true;
            // START_CHANGE: BUG-2026-0193-20261018-1 - A web whose own values disagree (Format
            // var = null; switch { case: var = NumberFormat..; case: var = DateFormat..; }) has
            // no single type, but it is still a different variable from a web of another type
            // (an earlier `Format[] var = new Format[n]` in the same slot). Treating "mixed" as
            // "unknown" merged the two and the NumberFormat stores did not compile
            // (java/text/MessageFormat.makeFormat). A mixed web now conflicts with every other
            // web whose type has a different array dimension (without a class hierarchy here,
            // subclasses of one type - Op / Op.CharOp - must not count as a conflict).
            List<Boolean> mixed = new ArrayList<Boolean>();
            List<List<Def>> webDefs193 = new ArrayList<List<Def>>();
            for (Map.Entry<Def, List<Def>> web : en.getValue().entrySet()) {
                Type t = webType(web.getValue());
                boolean mx = t == null && isMixedWeb193(web.getValue());
                webDefs193.add(web.getValue());
                types.add(t);
                mixed.add(Boolean.valueOf(mx));
                if (t != null && !isReference(t)) allReference = false;
                if (mx) {
                    for (Def d : web.getValue()) {
                        if (d.valueType != null && !isReference(d.valueType)) allReference = false;
                    }
                }
            }
            for (int i = 0; i < types.size() && !conflict; i++) {
                boolean infoI = types.get(i) != null || mixed.get(i).booleanValue();
                if (!infoI) continue;
                for (int j = i + 1; j < types.size(); j++) {
                    boolean infoJ = types.get(j) != null || mixed.get(j).booleanValue();
                    if (!infoJ) continue;
                    boolean mi = mixed.get(i).booleanValue(), mj = mixed.get(j).booleanValue();
                    boolean c193;
                    if (mi && mj) c193 = false;
                    else if (mi) c193 = dimensionDiffers193(webDefs193.get(i), types.get(j));
                    else if (mj) c193 = dimensionDiffers193(webDefs193.get(j), types.get(i));
                    else c193 = !sameType(types.get(i), types.get(j));
                    if (c193) {
                        conflict = true;
                        break;
                    }
                }
            }
            // END_CHANGE: BUG-2026-0193-1
            if (!conflict || !allReference) continue;
            int k = 0;
            boolean first = true;
            int wi = 0;
            for (Map.Entry<Def, List<Def>> web : en.getValue().entrySet()) {
                Type t = types.get(wi++);
                if (first) { first = false; continue; }
                boolean hasParam = false;
                for (Def d : web.getValue()) if (d.param) hasParam = true;
                if (hasParam) continue;
                String nn;
                do { nn = name + "_" + (++k); } while (taken.contains(nn));
                taken.add(nn);
                for (Def d : web.getValue()) {
                    newName.put(d, nn);
                    // values of several types share this variable: Object holds them all (member
                    // accesses on it get casts from the TypeCoercer)
                    newType.put(d, t != null ? t : ObjectType.OBJECT);
                }
            }
        }
        if (newName.isEmpty()) return body;
        // rename: definitions by node identity, uses by their reaching definitions
        final Map<Object, Def> defNodes = defOf;
        AstMapper renamer = new AstMapper() {
            @Override
            public Statement map(Statement s) {
                if (s instanceof VariableDeclarationStatement) {
                    Def d = defNodes.get(s);
                    if (d != null && newName.containsKey(d)) {
                        VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                        Expression init = v.hasInitializer() ? map(v.getInitializer()) : null;
                        Type t = newType.containsKey(d) ? newType.get(d) : v.getType();
                        return new VariableDeclarationStatement(v.getLineNumber(), t, newName.get(d), init, v.isFinal(), v.isVar());
                    }
                }
                return super.map(s);
            }

            @Override
            public Expression map(Expression e) {
                if (e instanceof AssignmentExpression && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                    Def d = defNodes.get(e);
                    if (d != null && newName.containsKey(d)) {
                        AssignmentExpression a = (AssignmentExpression) e;
                        LocalVariableExpression lv = (LocalVariableExpression) a.getLeft();
                        Type t = newType.containsKey(d) ? newType.get(d) : lv.getType();
                        return new AssignmentExpression(a.getLineNumber(), a.getType(),
                            new LocalVariableExpression(lv.getLineNumber(), t, newName.get(d), lv.getIndex()),
                            a.getOperator(), map(a.getRight()));
                    }
                }
                if (e instanceof UnaryOperatorExpression && ((UnaryOperatorExpression) e).getExpression() instanceof LocalVariableExpression) {
                    Def d = defNodes.get(e);
                    if (d != null && newName.containsKey(d)) {
                        UnaryOperatorExpression u = (UnaryOperatorExpression) e;
                        LocalVariableExpression lv = (LocalVariableExpression) u.getExpression();
                        return new UnaryOperatorExpression(u.getLineNumber(), u.getType(), u.getOperator(),
                            new LocalVariableExpression(lv.getLineNumber(), lv.getType(), newName.get(d), lv.getIndex()),
                            u.isPrefix());
                    }
                }
                return super.map(e);
            }

            @Override
            protected Expression mapLocal(LocalVariableExpression lv) {
                Set<Def> ds = useDefs.get(lv);
                if (ds == null || ds.isEmpty()) return lv;
                Def d = ds.iterator().next();
                String nn = newName.get(d);
                if (nn == null) return lv;
                Type t = newType.containsKey(d) ? newType.get(d) : lv.getType();
                return new LocalVariableExpression(lv.getLineNumber(), t, nn, lv.getIndex());
            }
        };
        return renamer.mapList(body);
    }

    /** The single type all values of the web have, or null when unknown / mixed. */
    // START_CHANGE: BUG-2026-0193-20261018-2
    /** True when the web's definitions carry at least two different value types. */
    private static boolean isMixedWeb193(List<Def> defs) {
        Type t = null;
        for (Def d : defs) {
            if (d.valueType == null) continue;
            if (t == null) t = d.valueType;
            else if (!sameType(t, d.valueType)) return true;
        }
        return false;
    }

    /** True when some value of the mixed web has an array dimension different from {@code t}'s. */
    private static boolean dimensionDiffers193(List<Def> defs, Type t) {
        if (t == null) return false;
        for (Def d : defs) {
            if (d.valueType != null && d.valueType.getDimension() != t.getDimension()) return true;
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0193-2

    private static Type webType(List<Def> defs) {
        Type t = null;
        for (Def d : defs) {
            if (d.valueType == null) continue;
            if (t == null) t = d.valueType;
            else if (!sameType(t, d.valueType)) return null;
        }
        return t;
    }
}
