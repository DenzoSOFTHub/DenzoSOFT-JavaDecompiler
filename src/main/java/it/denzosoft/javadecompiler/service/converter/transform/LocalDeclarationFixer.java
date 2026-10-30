/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Last-resort placement of local variable declarations (BUG-2026-0144).
 *
 * <p>Declarations are decided during decoding, in bytecode order, by "first store of a slot".
 * Once the flow builder has nested that code into if/else, loops and try blocks, the first store
 * may sit in a branch whose scope ends before the next use, or a use may precede it entirely;
 * javac then reports {@code cannot find symbol}. This pass runs on the finished body, finds every
 * reference to a local that no enclosing declaration covers, and declares it:</p>
 *
 * <ol>
 *   <li>When every uncovered reference of a name falls into regions that each start with a plain
 *       {@code name = value;} statement of a block (and the rest of the region is that block's
 *       remainder), each such assignment becomes a declaration. A block re-runs from its start,
 *       so every read in the region still sees the value assigned at its head.</li>
 *   <li>Otherwise the variable is declared once at the top of the method with its default value,
 *       and nested declarations of the same name become assignments. A method-level variable
 *       keeps its value across loop iterations exactly like the bytecode slot did, and the
 *       default value is never observed: the verifier proved every read is preceded by a write.</li>
 * </ol>
 *
 * <p>Names referenced from a lambda body are left alone (moving them would break effective
 * finality).</p>
 */
public final class LocalDeclarationFixer {

    private static final class Frame {
        final List<Statement> list;
        final int index;
        Frame(List<Statement> list, int index) { this.list = list; this.index = index; }
    }

    private static final class Ref {
        final String name;
        final Type type;
        final Frame[] path;
        /** The reference is the left side of `name = rhs;` sitting directly in path's last block. */
        final boolean topAssign;
        Ref(String name, Type type, Frame[] path, boolean topAssign) {
            this.name = name; this.type = type; this.path = path; this.topAssign = topAssign;
        }
    }

    private final LinkedList<Set<String>> scopes = new LinkedList<Set<String>>();
    private final LinkedList<Frame> path = new LinkedList<Frame>();
    private final Map<String, List<Ref>> unbound = new LinkedHashMap<String, List<Ref>>();
    private final Set<String> lambdaNames = new HashSet<String>();
    private final Set<String> allDeclared = new HashSet<String>();
    private int lambdaDepth;
    /** The ExpressionStatement currently visited when it is a direct `x = rhs;` of a block. */
    private Statement currentTopStatement;

    private LocalDeclarationFixer() { }

    public static List<Statement> fix(List<Statement> body, List<String> paramNames, boolean isConstructor) {
        if (body == null || body.isEmpty()) return body;
        LocalDeclarationFixer f = new LocalDeclarationFixer();
        Set<String> params = new HashSet<String>();
        if (paramNames != null) params.addAll(paramNames);
        params.add("this");
        f.scopes.push(params);
        f.block(body);
        if (f.unbound.isEmpty()) return body;
        return f.apply(body, isConstructor);
    }

    // ------------------------------------------------------------------ collection

    private boolean bound(String name) {
        for (Set<String> s : scopes) {
            if (s.contains(name)) return true;
        }
        return false;
    }

    private void declare(String name) {
        if (name != null) {
            scopes.peek().add(name);
            allDeclared.add(name);
        }
    }

    private void block(List<Statement> list) {
        if (list == null) return;
        scopes.push(new HashSet<String>());
        try {
            for (int i = 0; i < list.size(); i++) {
                path.addLast(new Frame(list, i));
                try {
                    Statement s = list.get(i);
                    currentTopStatement = isPlainAssign(s) ? s : null;
                    stmt(s);
                } finally {
                    path.removeLast();
                }
            }
        } finally {
            scopes.pop();
        }
    }

    private static boolean isPlainAssign(Statement s) {
        if (!(s instanceof ExpressionStatement)) return false;
        Expression e = ((ExpressionStatement) s).getExpression();
        return e instanceof AssignmentExpression && "=".equals(((AssignmentExpression) e).getOperator())
            && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression;
    }

    private void scoped(Statement s) {
        if (s instanceof BlockStatement) {
            block(((BlockStatement) s).getStatements());
            return;
        }
        currentTopStatement = null;
        scopes.push(new HashSet<String>());
        try {
            stmt(s);
        } finally {
            scopes.pop();
        }
    }

    private void stmt(Statement s) {
        if (s == null) return;
        if (s instanceof BlockStatement) {
            block(((BlockStatement) s).getStatements());
        } else if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            expr(v.getInitializer());
            declare(v.getName());
        } else if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (s == currentTopStatement) {
                AssignmentExpression a = (AssignmentExpression) e;
                expr(a.getRight());
                LocalVariableExpression lv = (LocalVariableExpression) a.getLeft();
                if (!bound(lv.getName())) record(lv, !references(a.getRight(), lv.getName()));
            } else {
                expr(e);
            }
        } else if (s instanceof ReturnStatement) {
            expr(((ReturnStatement) s).getExpression());
        } else if (s instanceof ThrowStatement) {
            expr(((ThrowStatement) s).getExpression());
        } else if (s instanceof YieldStatement) {
            expr(((YieldStatement) s).getExpression());
        } else if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            expr(i.getCondition());
            scoped(i.getThenBody());
        } else if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            expr(i.getCondition());
            scoped(i.getThenBody());
            scoped(i.getElseBody());
        } else if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            expr(w.getCondition());
            scoped(w.getBody());
        } else if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            scoped(d.getBody());
            expr(d.getCondition());
        } else if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            scopes.push(new HashSet<String>());
            try {
                Statement init = f.getInit();
                if (init instanceof BlockStatement) {
                    for (Statement x : ((BlockStatement) init).getStatements()) { currentTopStatement = null; stmt(x); }
                } else {
                    currentTopStatement = null;
                    stmt(init);
                }
                expr(f.getCondition());
                currentTopStatement = null;
                stmt(f.getUpdate());
                scoped(f.getBody());
            } finally {
                scopes.pop();
            }
        } else if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            expr(f.getIterable());
            scopes.push(new HashSet<String>());
            try {
                declare(f.getVariableName());
                scoped(f.getBody());
            } finally {
                scopes.pop();
            }
        } else if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            expr(sy.getMonitor());
            scoped(sy.getBody());
        } else if (s instanceof AssertStatement) {
            AssertStatement a = (AssertStatement) s;
            expr(a.getCondition());
            expr(a.getMessage());
        } else if (s instanceof LabelStatement) {
            stmt(((LabelStatement) s).getBody());
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            scopes.push(new HashSet<String>());
            try {
                if (t.getResources() != null) {
                    for (Statement r : t.getResources()) { currentTopStatement = null; stmt(r); }
                }
                scoped(t.getTryBody());
            } finally {
                scopes.pop();
            }
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                scopes.push(new HashSet<String>());
                try {
                    declare(cc.variableName);
                    scoped(cc.body);
                } finally {
                    scopes.pop();
                }
            }
            if (t.hasFinally()) scoped(t.getFinallyBody());
        } else if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            expr(sw.getSelector());
            scopes.push(new HashSet<String>());
            try {
                for (SwitchStatement.SwitchCase c : sw.getCases()) {
                    if (c.getPatternBindings() != null) {
                        for (String b : c.getPatternBindings()) declare(b);
                    }
                    // a switch block is one scope, but each case list is its own statement list
                    List<Statement> st = c.getStatements();
                    if (st == null) continue;
                    for (int i = 0; i < st.size(); i++) {
                        path.addLast(new Frame(st, i));
                        try {
                            Statement x = st.get(i);
                            currentTopStatement = isPlainAssign(x) ? x : null;
                            // a declaration in one case is visible in the following cases
                            stmt(x);
                        } finally {
                            path.removeLast();
                        }
                    }
                }
            } finally {
                scopes.pop();
            }
        }
    }

    private void record(LocalVariableExpression lv, boolean topAssign) {
        String name = lv.getName();
        if (!isPlainIdentifier(name)) return;
        if (lambdaDepth > 0) {
            lambdaNames.add(name);
            return;
        }
        List<Ref> l = unbound.get(name);
        if (l == null) { l = new ArrayList<Ref>(); unbound.put(name, l); }
        l.add(new Ref(name, lv.getType(), path.toArray(new Frame[path.size()]), topAssign));
    }

    private static boolean isPlainIdentifier(String n) {
        if (n == null || n.isEmpty() || n.startsWith("$") || n.startsWith("this$") || n.indexOf('.') >= 0) return false;
        if (!Character.isJavaIdentifierStart(n.charAt(0))) return false;
        for (int i = 1; i < n.length(); i++) {
            if (!Character.isJavaIdentifierPart(n.charAt(i))) return false;
        }
        return true;
    }

    private void expr(Expression e) {
        if (e == null) return;
        if (e instanceof LocalVariableExpression) {
            LocalVariableExpression lv = (LocalVariableExpression) e;
            if (!bound(lv.getName())) record(lv, false);
            return;
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression i = (InstanceOfExpression) e;
            expr(i.getExpression());
            // pattern bindings flow into following statements; treat them as declared from here on
            if (i.getPatternVariableName() != null) declareFlowing(i.getPatternVariableName());
            if (i.getRecordPattern() != null) declareRecordBindings(i.getRecordPattern());
            return;
        }
        if (e instanceof LambdaExpression) {
            LambdaExpression l = (LambdaExpression) e;
            lambdaDepth++;
            scopes.push(new HashSet<String>(l.getParameterNames() != null ? l.getParameterNames() : new ArrayList<String>()));
            Statement saved = currentTopStatement;
            try {
                Statement b = l.getBody();
                if (b instanceof BlockStatement) {
                    // lambda bodies are separate methods; only track names from the outer method
                    for (Statement x : ((BlockStatement) b).getStatements()) { currentTopStatement = null; stmt(x); }
                } else {
                    currentTopStatement = null;
                    stmt(b);
                }
            } finally {
                currentTopStatement = saved;
                scopes.pop();
                lambdaDepth--;
            }
            return;
        }
        if (e instanceof SwitchExpression) {
            // An arm's bindings and block declarations are in scope for its guard and value.
            SwitchExpression sw = (SwitchExpression) e;
            expr(sw.getSelector());
            Statement saved = currentTopStatement;
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                scopes.push(new HashSet<String>());
                try {
                    if (c.getPatternBinding() != null) declare(c.getPatternBinding());
                    if (c.getRecordPattern() != null) declareRecordBindings(c.getRecordPattern());
                    expr(c.getGuard());
                    if (c.getBodyStatements() != null) {
                        for (Statement x : c.getBodyStatements()) { currentTopStatement = null; stmt(x); }
                    }
                    expr(c.getValue());
                } finally {
                    scopes.pop();
                }
            }
            currentTopStatement = saved;
            return;
        }
        for (Expression c : children(e)) expr(c);
    }

    private void declareFlowing(String name) {
        // Flow scoping of pattern variables is wider than the block structure; bind it in the
        // outermost scope so no use of it is ever reported (it is never moved by this pass).
        scopes.getLast().add(name);
        allDeclared.add(name);
    }

    private void declareRecordBindings(RecordPattern rp) {
        for (RecordPattern.Component c : rp.getComponents()) {
            if (c.isNested()) declareRecordBindings(c.getNested());
            else if (c.getBindingName() != null) declareFlowing(c.getBindingName());
        }
    }

    public static List<Expression> children(Expression e) {
        List<Expression> out = new ArrayList<Expression>(4);
        if (e instanceof BinaryOperatorExpression) {
            out.add(((BinaryOperatorExpression) e).getLeft());
            out.add(((BinaryOperatorExpression) e).getRight());
        } else if (e instanceof UnaryOperatorExpression) {
            out.add(((UnaryOperatorExpression) e).getExpression());
        } else if (e instanceof CastExpression) {
            out.add(((CastExpression) e).getExpression());
        } else if (e instanceof AssignmentExpression) {
            out.add(((AssignmentExpression) e).getRight());
            out.add(((AssignmentExpression) e).getLeft());
        } else if (e instanceof FieldAccessExpression) {
            out.add(((FieldAccessExpression) e).getObject());
        } else if (e instanceof ArrayAccessExpression) {
            out.add(((ArrayAccessExpression) e).getArray());
            out.add(((ArrayAccessExpression) e).getIndex());
        } else if (e instanceof TernaryExpression) {
            out.add(((TernaryExpression) e).getCondition());
            out.add(((TernaryExpression) e).getTrueExpression());
            out.add(((TernaryExpression) e).getFalseExpression());
        } else if (e instanceof MethodInvocationExpression) {
            out.add(((MethodInvocationExpression) e).getObject());
            if (((MethodInvocationExpression) e).getArguments() != null) out.addAll(((MethodInvocationExpression) e).getArguments());
        } else if (e instanceof StaticMethodInvocationExpression) {
            if (((StaticMethodInvocationExpression) e).getArguments() != null) out.addAll(((StaticMethodInvocationExpression) e).getArguments());
        } else if (e instanceof MethodReferenceExpression) {
            out.add(((MethodReferenceExpression) e).getObject());
        } else if (e instanceof NewExpression) {
            if (((NewExpression) e).getArguments() != null) out.addAll(((NewExpression) e).getArguments());
        } else if (e instanceof NewArrayExpression) {
            NewArrayExpression n = (NewArrayExpression) e;
            if (n.getDimensionExpressions() != null) out.addAll(n.getDimensionExpressions());
            if (n.getInitValues() != null) out.addAll(n.getInitValues());
        } else if (e instanceof SwitchExpression) {
            SwitchExpression sw = (SwitchExpression) e;
            out.add(sw.getSelector());
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                out.add(c.getGuard());
                out.add(c.getValue());
            }
        } else if (e instanceof PatternMatchExpression) {
            out.add(((PatternMatchExpression) e).getExpression());
        } else if (e instanceof ReturnExpression) {
            out.add(((ReturnExpression) e).getExpression());
        }
        return out;
    }

    private static boolean references(Expression e, final String name) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) return name.equals(((LocalVariableExpression) e).getName());
        if (e instanceof LambdaExpression) return true; // conservative
        for (Expression c : children(e)) {
            if (references(c, name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ placement

    private List<Statement> apply(List<Statement> body, boolean isConstructor) {
        Map<String, Type> hoist = new LinkedHashMap<String, Type>();
        Map<Ref, Boolean> inPlace = new LinkedHashMap<Ref, Boolean>();
        for (Map.Entry<String, List<Ref>> en : unbound.entrySet()) {
            String name = en.getKey();
            if (lambdaNames.contains(name)) continue;
            List<Ref> refs = en.getValue();
            List<Ref> heads = new ArrayList<Ref>();
            Ref head = null;
            boolean ok = true;
            for (Ref r : refs) {
                if (head != null && covers(head, r)) continue;
                if (!r.topAssign) { ok = false; break; }
                head = r;
                heads.add(r);
            }
            // Declaring in place next to an existing declaration of the same name in an
            // enclosing block would collide; the method-level route demotes those instead.
            if (ok && !allDeclared.contains(name)) {
                for (Ref h : heads) inPlace.put(h, Boolean.TRUE);
            } else {
                hoist.put(name, chooseType(refs));
            }
        }
        for (Ref h : inPlace.keySet()) {
            Frame f = h.path[h.path.length - 1];
            Statement s = f.list.get(f.index);
            AssignmentExpression a = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            Type t = h.type != null && !(h.type instanceof VoidType) ? h.type : ObjectType.OBJECT;
            try {
                f.list.set(f.index, new VariableDeclarationStatement(s.getLineNumber(), t, h.name,
                    a.getRight(), false, false));
            } catch (UnsupportedOperationException ignore) {
                hoist.put(h.name, t);
            }
        }
        if (hoist.isEmpty()) return body;
        demote(body, hoist.keySet());
        List<Statement> out = new ArrayList<Statement>(body.size() + hoist.size());
        int insertAt = 0;
        if (isConstructor) {
            for (int i = 0; i < body.size(); i++) {
                if (isConstructorCall(body.get(i))) { insertAt = i + 1; break; }
            }
        }
        out.addAll(body.subList(0, insertAt));
        for (Map.Entry<String, Type> h : hoist.entrySet()) {
            out.add(new VariableDeclarationStatement(0, h.getValue(), h.getKey(), defaultValue(h.getValue()), false, false));
        }
        out.addAll(body.subList(insertAt, body.size()));
        return out;
    }

    /** True when r lies in the region opened by head: same block, later index (any depth). */
    private static boolean covers(Ref head, Ref r) {
        int d = head.path.length - 1;
        if (r.path.length <= d) return false;
        for (int i = 0; i < d; i++) {
            if (head.path[i].list != r.path[i].list || head.path[i].index != r.path[i].index) return false;
        }
        return head.path[d].list == r.path[d].list && r.path[d].index > head.path[d].index;
    }

    private static Type chooseType(List<Ref> refs) {
        Type first = null;
        for (Ref r : refs) {
            if (r.type == null || r.type instanceof VoidType) continue;
            if (r.topAssign) return r.type;
            if (first == null) first = r.type;
        }
        return first != null ? first : ObjectType.OBJECT;
    }

    public static boolean isConstructorCall(Statement s) { // public: BUG-2026-0190
        if (!(s instanceof ExpressionStatement)) return false;
        Expression e = ((ExpressionStatement) s).getExpression();
        if (!(e instanceof MethodInvocationExpression)) return false;
        String n = ((MethodInvocationExpression) e).getMethodName();
        return "<init>".equals(n) || "super".equals(n) || "this".equals(n);
    }

    static Expression defaultValue(Type t) {
        if (t == PrimitiveType.BOOLEAN) return BooleanExpression.FALSE;
        if (t == PrimitiveType.LONG) return new LongConstantExpression(0, 0L);
        if (t == PrimitiveType.FLOAT) return new FloatConstantExpression(0, 0f);
        if (t == PrimitiveType.DOUBLE) return new DoubleConstantExpression(0, 0d);
        if (t instanceof PrimitiveType) return IntegerConstantExpression.valueOf(0, 0);
        return NullExpression.INSTANCE;
    }

    /** Turns nested declarations of hoisted names into assignments (or drops bare ones). */
    private static void demote(List<Statement> list, Set<String> names) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Statement s = list.get(i);
            Statement n = demoteStmt(s, names);
            if (n == null) {
                list.remove(i);
                i--;
            } else if (n != s) {
                list.set(i, n);
            }
        }
    }

    private static Statement demoteStmt(Statement s, Set<String> names) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            if (!names.contains(v.getName())) return s;
            if (!v.hasInitializer()) return null;
            return new ExpressionStatement(new AssignmentExpression(v.getLineNumber(), v.getType(),
                new LocalVariableExpression(v.getLineNumber(), v.getType(), v.getName(), -1), "=", v.getInitializer()));
        }
        if (s instanceof BlockStatement) {
            demote(((BlockStatement) s).getStatements(), names);
            return s;
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            Statement t = demoteChild(i.getThenBody(), names);
            return t == i.getThenBody() ? s : new IfStatement(i.getLineNumber(), i.getCondition(), t);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            Statement t = demoteChild(i.getThenBody(), names);
            Statement e = demoteChild(i.getElseBody(), names);
            if (t == i.getThenBody() && e == i.getElseBody()) return s;
            return new IfElseStatement(i.getLineNumber(), i.getCondition(), t, e);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            Statement b = demoteChild(w.getBody(), names);
            return b == w.getBody() ? s : new WhileStatement(w.getLineNumber(), w.getCondition(), b);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            Statement b = demoteChild(d.getBody(), names);
            return b == d.getBody() ? s : new DoWhileStatement(d.getLineNumber(), d.getCondition(), b);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            Statement init = f.getInit();
            Statement ni = init;
            if (init instanceof VariableDeclarationStatement) {
                ni = demoteStmt(init, names);
            } else if (init instanceof BlockStatement) {
                demote(((BlockStatement) init).getStatements(), names);
            }
            Statement b = demoteChild(f.getBody(), names);
            if (ni == init && b == f.getBody()) return s;
            return new ForStatement(f.getLineNumber(), ni, f.getCondition(), f.getUpdate(), b);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            Statement b = demoteChild(f.getBody(), names);
            return b == f.getBody() ? s : new ForEachStatement(f.getLineNumber(), f.getVariableType(),
                f.getVariableName(), f.getIterable(), b);
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            Statement b = demoteChild(sy.getBody(), names);
            return b == sy.getBody() ? s : new SynchronizedStatement(sy.getLineNumber(), sy.getMonitor(), b);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement b = demoteChild(l.getBody(), names);
            return b == l.getBody() ? s : new LabelStatement(l.getLineNumber(), l.getLabel(), b);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            Statement tb = demoteChild(t.getTryBody(), names);
            boolean changed = tb != t.getTryBody();
            List<TryCatchStatement.CatchClause> cs = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                Statement b = demoteChild(cc.body, names);
                if (b != cc.body) changed = true;
                cs.add(b == cc.body ? cc : new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, b));
            }
            Statement fin = t.hasFinally() ? demoteChild(t.getFinallyBody(), names) : null;
            if (fin != t.getFinallyBody()) changed = true;
            return changed ? new TryCatchStatement(t.getLineNumber(), tb, cs, fin, t.getResources()) : s;
        }
        if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                demote(c.getStatements(), names);
            }
            return s;
        }
        return s;
    }

    private static Statement demoteChild(Statement s, Set<String> names) {
        if (s == null) return null;
        Statement n = demoteStmt(s, names);
        return n == null ? new BlockStatement(s.getLineNumber(), new ArrayList<Statement>()) : n;
    }
}
