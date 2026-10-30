/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Final, type-driven repair of the conversions that the JVM performs implicitly but Java source
 * must spell out (BUG-2026-0142).
 *
 * <p>The bytecode has no boolean, byte, short or char operand stack type: everything is an int.
 * The decoder therefore produces expressions such as {@code flag = 1}, {@code if (x)} with an int
 * {@code x}, {@code !array.length}, {@code b == 1} with a boolean {@code b}, or {@code s = a + b}
 * into a short field. All of them are correct JVM semantics and illegal Java. This pass walks a
 * finished method body, computes the static type of every expression from what the class file
 * states for certain (local declarations, field and method descriptors, array element types) and
 * converts each expression to the type its context requires:</p>
 *
 * <ul>
 *   <li>int-like to boolean: constants become {@code true/false}, other values {@code x != 0};</li>
 *   <li>boolean to int-like: {@code b ? 1 : 0};</li>
 *   <li>boolean compared with 0/1: {@code b == 0} becomes {@code !b} and so on;</li>
 *   <li>int into byte/short/char (and other primitive narrowing): an explicit cast;</li>
 *   <li>{@code Object} into a more specific reference type: an explicit cast.</li>
 * </ul>
 *
 * <p>Each rewrite reproduces exactly the value the JVM computed (a JVM boolean is the int 0 or 1,
 * the narrowing casts are the truncation the store performed, the reference cast can never fail
 * because the verifier already proved the type), so the pass changes how the code is written,
 * never what it does. When a type is not known the expression is left untouched.</p>
 */
public final class TypeCoercer {

    private static final int CTX_ASSIGN = 0; // assignment context: int constants may narrow
    private static final int CTX_ARG = 1;    // invocation context: no implicit narrowing at all

    private final LinkedList<Map<String, Type>> scopes = new LinkedList<Map<String, Type>>();
    private Type returnType;
    /** Locals declared with a generic signature (their element types are known to javac). */
    private final java.util.Set<String> genericLocals = new java.util.HashSet<String>();
    private Map<String, Type> fieldTypes;
    private it.denzosoft.javadecompiler.service.converter.cfg.ClassHierarchy hierarchy;
    /** "owner.name" of fields declared with type arguments (null = unknown). */
    private java.util.Set<String> genericFields;

    public static void setGenericFields(java.util.Set<String> f) { GENERIC_FIELDS.set(f); }
    private static final ThreadLocal<java.util.Set<String>> GENERIC_FIELDS = new ThreadLocal<java.util.Set<String>>();
    // START_CHANGE: BUG-2026-0202-20261030-3 - Parameters declared with type arguments (from the
    // method Signature); they count as generic locals for the raw-Iterable test.
    public static void setGenericParams(java.util.Set<String> p) { GENERIC_PARAMS.set(p); }
    private static final ThreadLocal<java.util.Set<String>> GENERIC_PARAMS = new ThreadLocal<java.util.Set<String>>();
    // END_CHANGE: BUG-2026-0202-3

    private TypeCoercer() { }

    /**
     * Coerce a method body in place.
     *
     * @param body       the method statements (modified in place)
     * @param paramNames parameter names as they appear in the body
     * @param paramTypes parameter types (same order)
     * @param returnType the declared return type, or null when unknown
     */
    public static List<Statement> coerce(List<Statement> body, List<String> paramNames,
                                         List<Type> paramTypes, Type returnType) {
        return coerce(body, paramNames, paramTypes, returnType, null);
    }

    /** @param fieldTypes "owner.name" -> source type for fields whose declared type is a type variable */
    public static List<Statement> coerce(List<Statement> body, List<String> paramNames,
                                         List<Type> paramTypes, Type returnType, Map<String, Type> fieldTypes) {
        return coerce(body, paramNames, paramTypes, returnType, fieldTypes, null);
    }

    /** @param hierarchy class hierarchy of the program (null = unknown; no hierarchy-based casts) */
    public static List<Statement> coerce(List<Statement> body, List<String> paramNames,
                                         List<Type> paramTypes, Type returnType, Map<String, Type> fieldTypes,
                                         it.denzosoft.javadecompiler.service.converter.cfg.ClassHierarchy hierarchy) {
        if (body == null) return null;
        TypeCoercer tc = new TypeCoercer();
        tc.hierarchy = hierarchy;
        tc.genericFields = GENERIC_FIELDS.get();
        if (GENERIC_PARAMS.get() != null) tc.genericLocals.addAll(GENERIC_PARAMS.get()); // BUG-2026-0202
        tc.returnType = returnType;
        tc.fieldTypes = fieldTypes;
        Map<String, Type> params = new HashMap<String, Type>();
        if (paramNames != null && paramTypes != null) {
            for (int i = 0; i < paramNames.size() && i < paramTypes.size(); i++) {
                params.put(paramNames.get(i), paramTypes.get(i));
            }
        }
        tc.scopes.push(params);
        tc.refineObjectLocals(body);
        tc.collectArrayUses(body);
        tc.list(body);
        return body;
    }

    // ------------------------------------------------------------------ declaration refinement

    /**
     * A local whose first store was {@code null} is declared {@code Object}; every later use as
     * the real type then fails. When each non-null value ever stored into such a local has the
     * same static type, that type becomes the declared type.
     */
    private void refineObjectLocals(List<Statement> body) {
        final Map<String, List<VariableDeclarationStatement>> decls = new HashMap<String, List<VariableDeclarationStatement>>();
        final Map<String, List<Expression>> values = new HashMap<String, List<Expression>>();
        // START_CHANGE: BUG-2026-0184-20261018-1 - Catch parameters are not in scope yet when this
        // runs: record, per OCCURRENCE, the caught type of every read of a catch parameter inside
        // its clause, so `Object e2 = null; .. e2 = e;` within `catch (IOException e)` refines to
        // IOException (`throw e2;` needs a Throwable). Nested clauses are marked after their
        // enclosing one, so the innermost binding of a reused name wins.
        final Map<Expression, Type> catchReads184 = new java.util.IdentityHashMap<Expression, Type>();
        new AstMapper() {
            @Override
            public Statement map(Statement s) {
                if (s instanceof TryCatchStatement) {
                    for (TryCatchStatement.CatchClause cc : ((TryCatchStatement) s).getCatchClauses()) {
                        if (cc.variableName == null || cc.exceptionTypes == null || cc.exceptionTypes.size() != 1) continue;
                        final String cv = cc.variableName;
                        final Type ct = cc.exceptionTypes.get(0);
                        new AstMapper() {
                            @Override
                            public Expression map(Expression e) {
                                if (e instanceof LocalVariableExpression && cv.equals(((LocalVariableExpression) e).getName())) {
                                    catchReads184.put(e, ct);
                                }
                                return super.map(e);
                            }
                        }.map(cc.body);
                    }
                }
                return super.map(s);
            }
        }.mapList(body);
        // END_CHANGE: BUG-2026-0184-1
        AstMapper collector = new AstMapper() {
            @Override
            public Statement map(Statement s) {
                if (s instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                    List<VariableDeclarationStatement> l = decls.get(v.getName());
                    if (l == null) { l = new ArrayList<VariableDeclarationStatement>(); decls.put(v.getName(), l); }
                    l.add(v);
                    if (v.hasInitializer()) addValue(values, v.getName(), v.getInitializer());
                }
                return super.map(s);
            }
            @Override
            public Expression map(Expression e) {
                if (e instanceof AssignmentExpression) {
                    AssignmentExpression a = (AssignmentExpression) e;
                    if (a.getLeft() instanceof LocalVariableExpression) {
                        String n = ((LocalVariableExpression) a.getLeft()).getName();
                        addValue(values, n, "=".equals(a.getOperator()) ? a.getRight() : null);
                    }
                }
                return super.map(e);
            }
        };
        collector.mapList(body);
        final Map<VariableDeclarationStatement, Type> retype = new java.util.IdentityHashMap<VariableDeclarationStatement, Type>();
        for (Map.Entry<String, List<VariableDeclarationStatement>> en : decls.entrySet()) {
            if (en.getValue().size() != 1) continue;
            VariableDeclarationStatement v = en.getValue().get(0);
            // START_CHANGE: BUG-2026-0164-20261018-1 - A declaration narrower than one of the
            // values stored into it is widened to their least common superclass.
            if (hierarchy != null && !v.isVar() && v.getType() instanceof ObjectType && v.getType().getDimension() == 0
                    && !isObject(v.getType()) && values.get(en.getKey()) != null) {
                String cur = ((ObjectType) v.getType()).getInternalName();
                String lub = cur;
                boolean widen = false;
                for (Expression x : values.get(en.getKey())) {
                    if (x == null || x instanceof NullExpression) continue;
                    Type t = typeOf(x);
                    if (!(t instanceof ObjectType) || t.getDimension() != 0) continue;
                    String tn = ((ObjectType) t).getInternalName();
                    if (Boolean.FALSE.equals(hierarchy.isSubtype(tn, lub))) {
                        String c = hierarchy.leastUpperBound(lub, tn); // BUG-2026-0192: interfaces too
                        if (c == null) { widen = false; break; }
                        lub = c;
                        widen = true;
                    }
                }
                // widening to Object would lose the type its uses rely on (generic arguments)
                if (widen && !lub.equals(cur) && !"java/lang/Object".equals(lub)) retype.put(v, new ObjectType(lub));
                continue;
            }
            // END_CHANGE: BUG-2026-0164-1
            if (v.isVar() || !isObject(v.getType()) || v.getGenericSignature() != null) continue;
            List<Expression> vals = values.get(en.getKey());
            if (vals == null) continue;
            Type common = null;
            boolean ok = true;
            for (Expression x : vals) {
                if (x == null) { ok = false; break; }
                if (x instanceof NullExpression) continue;
                Type t = typeOf(x);
                // BUG-2026-0184: a catch parameter has its caught type
                if ((t == null || isObject(t)) && catchReads184.containsKey(x)) t = catchReads184.get(x);
                // START_CHANGE: BUG-2026-0173-20261018-1 - An Object-typed value is NOT "no
                // information": nothing proved it has the narrower type (no checkcast), so retyping
                // the local and casting the value can throw ClassCastException where the original
                // did not (MessageFormat.subformat: `Object obj = args[i]; .. obj = args;` became
                // `Object[] obj = (Object[]) args[i]`). Keep the declaration as Object; member
                // accesses on it get their casts at the use site.
                if (isObject(t)) { ok = false; break; }
                // END_CHANGE: BUG-2026-0173-1
                if (t == null || t instanceof PrimitiveType || t instanceof VoidType) { ok = false; break; }
                if (t instanceof GenericType) { ok = false; break; }
                if (common == null) common = t;
                else if (!sameType(common, t)) { ok = false; break; }
            }
            if (ok && common != null) retype.put(v, common);
        }
        if (retype.isEmpty()) return;
        AstMapper rewriter = new AstMapper() {
            @Override
            public Statement map(Statement s) {
                if (s instanceof VariableDeclarationStatement && retype.containsKey(s)) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                    Expression init = v.hasInitializer() ? map(v.getInitializer()) : null;
                    return new VariableDeclarationStatement(v.getLineNumber(), retype.get(s), v.getName(),
                        init, v.isFinal(), v.isVar());
                }
                return super.map(s);
            }
        };
        List<Statement> nb = rewriter.mapList(body);
        if (nb != body) {
            for (int i = 0; i < body.size(); i++) body.set(i, nb.get(i));
        }
    }

    private static void addValue(Map<String, List<Expression>> values, String name, Expression e) {
        List<Expression> l = values.get(name);
        if (l == null) { l = new ArrayList<Expression>(); values.put(name, l); }
        l.add(e);
    }

    private static boolean sameType(Type a, Type b) {
        a = normalize(a);
        b = normalize(b);
        return a.getDescriptor() != null && a.getDescriptor().equals(b.getDescriptor());
    }

    // ------------------------------------------------------------------ scopes

    private void declare(String name, Type t) {
        if (name != null && t != null) scopes.peek().put(name, t);
    }

    private Type lookup(String name) {
        for (Map<String, Type> m : scopes) {
            Type t = m.get(name);
            if (t != null) return t;
        }
        return null;
    }

    // ------------------------------------------------------------------ statements

    private void list(List<Statement> stmts) {
        if (stmts == null) return;
        scopes.push(new HashMap<String, Type>());
        try {
            for (int i = 0; i < stmts.size(); i++) {
                Statement s = stmts.get(i);
                Statement n = stmt(s);
                if (n != s) stmts.set(i, n);
            }
        } finally {
            scopes.pop();
        }
    }

    /** A statement that forms its own scope when it is not a block. */
    private Statement scoped(Statement s) {
        if (s == null || s instanceof BlockStatement) return stmt(s);
        scopes.push(new HashMap<String, Type>());
        try {
            return stmt(s);
        } finally {
            scopes.pop();
        }
    }

    private Statement stmt(Statement s) {
        if (s == null) return null;
        if (s instanceof BlockStatement) {
            list(((BlockStatement) s).getStatements());
            return s;
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            Type t = v.isVar() ? null : v.getType();
            Expression init = v.getInitializer();
            Expression ni = init;
            if (init != null) {
                ni = expr(init);
                if (t != null) ni = coerce(ni, t, CTX_ASSIGN);
            }
            declare(v.getName(), v.isVar() && ni != null ? typeOf(ni) : t);
            if (v.getGenericSignature() != null && v.getGenericSignature().indexOf('<') >= 0) genericLocals.add(v.getName());
            if (ni == init) return s;
            VariableDeclarationStatement nv = new VariableDeclarationStatement(v.getLineNumber(), v.getType(),
                v.getName(), ni, v.isFinal(), v.isVar());
            if (v.getGenericSignature() != null) nv.setGenericSignature(v.getGenericSignature());
            return nv;
        }
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            Expression n = expr(e);
            return n == e ? s : new ExpressionStatement(n);
        }
        if (s instanceof ReturnStatement) {
            ReturnStatement r = (ReturnStatement) s;
            if (!r.hasExpression()) return s;
            Expression e = r.getExpression();
            Expression n = expr(e);
            if (returnType != null && !(returnType instanceof VoidType)) n = coerce(n, returnType, CTX_ASSIGN);
            return n == e ? s : new ReturnStatement(r.getLineNumber(), n);
        }
        if (s instanceof ThrowStatement) {
            ThrowStatement t = (ThrowStatement) s;
            Expression n = expr(t.getExpression());
            return n == t.getExpression() ? s : new ThrowStatement(t.getLineNumber(), n);
        }
        if (s instanceof YieldStatement) {
            YieldStatement y = (YieldStatement) s;
            Expression n = expr(y.getExpression());
            return n == y.getExpression() ? s : new YieldStatement(y.getLineNumber(), n);
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            Expression c = cond(i.getCondition());
            Statement t = scoped(i.getThenBody());
            if (c == i.getCondition() && t == i.getThenBody()) return s;
            return new IfStatement(i.getLineNumber(), c, t);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            Expression c = cond(i.getCondition());
            Statement t = scoped(i.getThenBody());
            Statement e = scoped(i.getElseBody());
            if (c == i.getCondition() && t == i.getThenBody() && e == i.getElseBody()) return s;
            return new IfElseStatement(i.getLineNumber(), c, t, e);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            Expression c = cond(w.getCondition());
            Statement b = scoped(w.getBody());
            if (c == w.getCondition() && b == w.getBody()) return s;
            return new WhileStatement(w.getLineNumber(), c, b);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            Statement b = scoped(d.getBody());
            Expression c = cond(d.getCondition());
            if (c == d.getCondition() && b == d.getBody()) return s;
            return new DoWhileStatement(d.getLineNumber(), c, b);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            scopes.push(new HashMap<String, Type>());
            try {
                Statement init = stmt(f.getInit());
                Expression c = f.getCondition() != null ? cond(f.getCondition()) : null;
                Statement upd = stmt(f.getUpdate());
                Statement b = scoped(f.getBody());
                if (init == f.getInit() && c == f.getCondition() && upd == f.getUpdate() && b == f.getBody()) return s;
                return new ForStatement(f.getLineNumber(), init, c, upd, b);
            } finally {
                scopes.pop();
            }
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            Expression it = expr(f.getIterable());
            // An array's element type is exact; an `Object` loop variable over a typed array is
            // only the decoder not knowing better (no LocalVariableTable).
            Type vt = f.getVariableType();
            Type itElem = elementType(typeOf(it));
            if (isObject(vt) && itElem != null && !(itElem instanceof PrimitiveType) && !isObject(itElem)) vt = itElem;
            // START_CHANGE: BUG-2026-0159-20261018-1 - A typed loop variable over a raw Iterable
            // (no generic information survives for locals without a LocalVariableTypeTable) does
            // not compile; iterate Objects and cast each element, as the bytecode's checkcast does.
            if (itElem == null && vt instanceof ObjectType && !isObject(vt) && rawIterable(it)) {
                String tmp = f.getVariableName() + "$e";
                List<Statement> nb = new ArrayList<Statement>();
                nb.add(new VariableDeclarationStatement(f.getLineNumber(), vt, f.getVariableName(),
                    explicitCast(new LocalVariableExpression(f.getLineNumber(), ObjectType.OBJECT, tmp, -1), vt), false, false));
                if (f.getBody() instanceof BlockStatement) nb.addAll(((BlockStatement) f.getBody()).getStatements());
                else if (f.getBody() != null) nb.add(f.getBody());
                Statement rewritten = new ForEachStatement(f.getLineNumber(), ObjectType.OBJECT, tmp, it,
                    new BlockStatement(f.getLineNumber(), nb));
                return stmt(rewritten);
            }
            // END_CHANGE: BUG-2026-0159-1
            scopes.push(new HashMap<String, Type>());
            try {
                declare(f.getVariableName(), vt);
                Statement b = scoped(f.getBody());
                if (it == f.getIterable() && b == f.getBody() && vt == f.getVariableType()) return s;
                return new ForEachStatement(f.getLineNumber(), vt, f.getVariableName(), it, b);
            } finally {
                scopes.pop();
            }
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            Expression m = expr(sy.getMonitor());
            Statement b = scoped(sy.getBody());
            if (m == sy.getMonitor() && b == sy.getBody()) return s;
            return new SynchronizedStatement(sy.getLineNumber(), m, b);
        }
        if (s instanceof AssertStatement) {
            AssertStatement a = (AssertStatement) s;
            Expression c = cond(a.getCondition());
            Expression m = a.getMessage() != null ? expr(a.getMessage()) : null;
            if (c == a.getCondition() && m == a.getMessage()) return s;
            return new AssertStatement(a.getLineNumber(), c, m);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement b = stmt(l.getBody());
            return b == l.getBody() ? s : new LabelStatement(l.getLineNumber(), l.getLabel(), b);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            scopes.push(new HashMap<String, Type>());
            boolean changed = false;
            Statement tryBody;
            try {
                if (t.getResources() != null) {
                    List<Statement> res = t.getResources();
                    for (int i = 0; i < res.size(); i++) {
                        Statement r = res.get(i);
                        Statement nr = stmt(r);
                        if (nr != r) res.set(i, nr);
                    }
                }
                tryBody = scoped(t.getTryBody());
            } finally {
                scopes.pop();
            }
            changed |= tryBody != t.getTryBody();
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                scopes.push(new HashMap<String, Type>());
                try {
                    if (cc.exceptionTypes != null && cc.exceptionTypes.size() == 1) {
                        declare(cc.variableName, cc.exceptionTypes.get(0));
                    }
                    Statement b = scoped(cc.body);
                    changed |= b != cc.body;
                    catches.add(b == cc.body ? cc : new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, b));
                } finally {
                    scopes.pop();
                }
            }
            Statement fin = t.hasFinally() ? scoped(t.getFinallyBody()) : null;
            changed |= fin != t.getFinallyBody();
            if (!changed) return s;
            return new TryCatchStatement(t.getLineNumber(), tryBody, catches, fin, t.getResources());
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            Expression sel = expr(sw.getSelector());
            if (typeOf(sel) == PrimitiveType.BOOLEAN) sel = toInt(sel);
            // The whole switch block is one scope; mutate the case lists in place (cases carry
            // pattern state that a rebuild would have to copy).
            scopes.push(new HashMap<String, Type>());
            try {
                for (SwitchStatement.SwitchCase c : sw.getCases()) {
                    List<Statement> st = c.getStatements();
                    if (st == null) continue;
                    if (c.isPattern() && c.getPatternTypes() != null && c.getPatternBindings() != null) {
                        for (int i = 0; i < c.getPatternTypes().size() && i < c.getPatternBindings().size(); i++) {
                            declare(c.getPatternBindings().get(i), c.getPatternTypes().get(i));
                        }
                    }
                    for (int i = 0; i < st.size(); i++) {
                        Statement x = st.get(i);
                        Statement n = stmt(x);
                        if (n != x) st.set(i, n);
                    }
                }
            } finally {
                scopes.pop();
            }
            if (sel == sw.getSelector()) return s;
            SwitchStatement ns = new SwitchStatement(sw.getLineNumber(), sel, sw.getCases(), sw.isArrowStyle());
            return ns;
        }
        return s;
    }

    // ------------------------------------------------------------------ expressions

    /** Process an expression that must be boolean (a condition). */
    private Expression cond(Expression e) {
        if (e == null) return null;
        return toBoolean(expr(e));
    }

    private List<Expression> args(List<Expression> in, String descriptor) {
        if (in == null || in.isEmpty()) return in;
        List<Type> params = descriptor != null ? paramTypes(descriptor) : null;
        boolean exact = params != null && params.size() == in.size();
        List<Expression> out = null;
        for (int i = 0; i < in.size(); i++) {
            Expression a = in.get(i);
            Expression n = expr(a);
            if (exact) n = coerce(n, params.get(i), CTX_ARG);
            if (n != a && out == null) out = new ArrayList<Expression>(in);
            if (out != null) out.set(i, n);
        }
        return out != null ? out : in;
    }

    private Expression expr(Expression e) {
        if (e == null) return null;
        if (e instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) e;
            Expression l = lvalue(a.getLeft());
            Expression r = expr(a.getRight());
            Type lt = typeOf(l);
            String op = a.getOperator();
            if ("=".equals(op)) {
                if (lt != null) r = coerce(r, lt, CTX_ASSIGN);
            } else if (lt == PrimitiveType.BOOLEAN && ("&=".equals(op) || "|=".equals(op) || "^=".equals(op))) {
                r = toBoolean(r);
            } else if (lt != null && isNumeric(lt) && typeOf(r) == PrimitiveType.BOOLEAN) {
                r = toInt(r);
            }
            if (l == a.getLeft() && r == a.getRight()) return e;
            return new AssignmentExpression(a.getLineNumber(), a.getType(), l, op, r);
        }
        if (e instanceof BinaryOperatorExpression) return binary((BinaryOperatorExpression) e);
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            String op = u.getOperator();
            if ("!".equals(op)) {
                Expression x = expr(u.getExpression());
                Type xt = typeOf(x);
                if (xt != null && isIntLike(xt)) {
                    // `!intValue` is `intValue == 0`
                    return new BinaryOperatorExpression(u.getLineNumber(), PrimitiveType.BOOLEAN,
                        x, "==", IntegerConstantExpression.valueOf(0, 0));
                }
                x = toBoolean(x);
                if (x == u.getExpression()) return e;
                return new UnaryOperatorExpression(u.getLineNumber(), PrimitiveType.BOOLEAN, op, x, u.isPrefix());
            }
            Expression x = "++".equals(op) || "--".equals(op) ? lvalue(u.getExpression()) : expr(u.getExpression());
            if (("-".equals(op) || "~".equals(op) || "+".equals(op)) && typeOf(x) == PrimitiveType.BOOLEAN) {
                x = toInt(x);
            }
            if (x == u.getExpression()) return e;
            return new UnaryOperatorExpression(u.getLineNumber(), u.getType(), op, x, u.isPrefix());
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            Expression c = cond(t.getCondition());
            Expression a = expr(t.getTrueExpression());
            Expression b = expr(t.getFalseExpression());
            Type at = typeOf(a);
            Type bt = typeOf(b);
            // Mixed boolean / int arms: the int arm is a 0/1 constant standing for a boolean.
            if (at == PrimitiveType.BOOLEAN && bt != null && isIntLike(bt) && b instanceof IntegerConstantExpression) {
                b = toBoolean(b);
            } else if (bt == PrimitiveType.BOOLEAN && at != null && isIntLike(at) && a instanceof IntegerConstantExpression) {
                a = toBoolean(a);
            } else if (at == PrimitiveType.BOOLEAN && bt != null && isIntLike(bt)) {
                a = toInt(a);
            } else if (bt == PrimitiveType.BOOLEAN && at != null && isIntLike(at)) {
                b = toInt(b);
            }
            if (c == t.getCondition() && a == t.getTrueExpression() && b == t.getFalseExpression()) return e;
            return new TernaryExpression(t.getLineNumber(), t.getType(), c, a, b);
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e;
            Expression x = expr(c.getExpression());
            Type ct = c.getType();
            if (ct instanceof PrimitiveType && ct != PrimitiveType.BOOLEAN && typeOf(x) == PrimitiveType.BOOLEAN) {
                x = toInt(x);
            } else if (ct == PrimitiveType.BOOLEAN) {
                // (boolean) intValue is not legal Java; the value itself is the conversion
                return toBoolean(x);
            }
            if (x == c.getExpression()) return e;
            CastExpression nc = new CastExpression(c.getLineNumber(), ct, x);
            if (c.isExplicit()) nc.setExplicit(true);
            return nc;
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression a = (ArrayAccessExpression) e;
            Expression arr = arrayReceiver(expr(a.getArray()), a.getType());
            Expression idx = expr(a.getIndex());
            if (typeOf(idx) == PrimitiveType.BOOLEAN) idx = toInt(idx);
            if (arr == a.getArray() && idx == a.getIndex()) return e;
            return new ArrayAccessExpression(a.getLineNumber(), a.getType(), arr, idx);
        }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression f = (FieldAccessExpression) e;
            if (f.getObject() == null) return e;
            Expression o = expr(f.getObject());
            if ("length".equals(f.getName()) && (f.getOwnerInternalName() == null || f.getOwnerInternalName().isEmpty())) {
                o = arrayReceiver(o, null);
            } else {
                o = receiver(o, f.getOwnerInternalName());
            }
            if (o == f.getObject()) return e;
            return new FieldAccessExpression(f.getLineNumber(), f.getType(), o,
                f.getOwnerInternalName(), f.getName(), f.getDescriptor());
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            Expression o = m.getObject() != null ? expr(m.getObject()) : null;
            o = receiver(o, m.getOwnerInternalName());
            String md = m.getDescriptor();
            List<Expression> ma = m.getArguments();
            // BUG-2026-0160: the same tag on `super(..., null)` / `this(..., null)`
            if (("super".equals(m.getMethodName()) || "this".equals(m.getMethodName())) && hasAccessTag(ma, md)) {
                ma = new ArrayList<Expression>(ma.subList(0, ma.size() - 1));
                md = stripLastParam(md);
            }
            List<Expression> as = disambiguateLambdas(m.getMethodName(), args(ma, md), md);
            o = rawReceiver(o, m.getOwnerInternalName(), as, md);
            if (o == m.getObject() && as == m.getArguments() && md == m.getDescriptor()) return e;
            return new MethodInvocationExpression(m.getLineNumber(), m.getType(), o,
                m.getOwnerInternalName(), m.getMethodName(), md, as);
        }
        if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression m = (StaticMethodInvocationExpression) e;
            List<Expression> as = disambiguateLambdas(m.getMethodName(), args(m.getArguments(), m.getDescriptor()), m.getDescriptor());
            if (as == m.getArguments()) return e;
            return new StaticMethodInvocationExpression(m.getLineNumber(), m.getType(),
                m.getOwnerInternalName(), m.getMethodName(), m.getDescriptor(), as);
        }
        if (e instanceof NewExpression) {
            NewExpression n = (NewExpression) e;
            String nd = n.getDescriptor();
            List<Expression> na = n.getArguments();
            // START_CHANGE: BUG-2026-0160-20261018-1 - `new Inner(a, (Outer$1) null)`: javac's
            // access constructor for a private constructor takes a synthetic tag class; the
            // source calls the private constructor itself.
            if (hasAccessTag(na, nd)) {
                na = new ArrayList<Expression>(na.subList(0, na.size() - 1));
                nd = stripLastParam(nd);
            }
            // END_CHANGE: BUG-2026-0160-1
            List<Expression> as = args(na, nd);
            if (as == n.getArguments() && nd == n.getDescriptor()) return e;
            return new NewExpression(n.getLineNumber(), n.getType(), n.getInternalTypeName(), nd, as);
        }
        if (e instanceof NewArrayExpression) {
            NewArrayExpression n = (NewArrayExpression) e;
            List<Expression> dims = n.getDimensionExpressions();
            if (dims != null) {
                for (int i = 0; i < dims.size(); i++) {
                    Expression d = expr(dims.get(i));
                    if (typeOf(d) == PrimitiveType.BOOLEAN) d = toInt(d);
                    if (d != dims.get(i)) {
                        try {
                            dims.set(i, d);
                        } catch (UnsupportedOperationException ignore) {
                            // immutable singleton list: leave the dimension as decoded
                        }
                    }
                }
            }
            List<Expression> iv = n.getInitValues();
            if (iv != null) {
                Type elem = elementType(typeOf(n));
                for (int i = 0; i < iv.size(); i++) {
                    Expression v = expr(iv.get(i));
                    if (elem != null) v = coerce(v, elem, CTX_ASSIGN);
                    if (v != iv.get(i)) iv.set(i, v);
                }
            }
            return e;
        }
        if (e instanceof InstanceOfExpression) {
            InstanceOfExpression i = (InstanceOfExpression) e;
            Expression x = expr(i.getExpression());
            if (x == i.getExpression() || i.hasRecordPattern()) return e;
            return new InstanceOfExpression(i.getLineNumber(), x, i.getCheckType(), i.getPatternVariableName());
        }
        if (e instanceof LambdaExpression) {
            LambdaExpression l = (LambdaExpression) e;
            Type savedReturn = returnType;
            returnType = null;
            scopes.push(new HashMap<String, Type>());
            try {
                if (l.getParameterNames() != null && l.getParameterTypes() != null) {
                    for (int i = 0; i < l.getParameterNames().size() && i < l.getParameterTypes().size(); i++) {
                        declare(l.getParameterNames().get(i), l.getParameterTypes().get(i));
                    }
                }
                Statement b = l.getBody();
                if (b instanceof BlockStatement) {
                    list(((BlockStatement) b).getStatements());
                }
            } finally {
                scopes.pop();
                returnType = savedReturn;
            }
            return e;
        }
        if (e instanceof SwitchExpression) {
            SwitchExpression sw = (SwitchExpression) e;
            for (SwitchExpression.SwitchCase c : sw.getCases()) {
                if (c.getValue() != null) {
                    Expression v = expr(c.getValue());
                    if (v != c.getValue()) c.setValue(v);
                }
            }
            return e;
        }
        return e;
    }

    // START_CHANGE: BUG-2026-0160-20261018-2
    private static boolean hasAccessTag(List<Expression> args, String desc) {
        if (args == null || args.isEmpty() || desc == null) return false;
        Expression last = args.get(args.size() - 1);
        while (last instanceof CastExpression) last = ((CastExpression) last).getExpression();
        if (!(last instanceof NullExpression)) return false;
        List<Type> ps = paramTypes(desc);
        if (ps == null || ps.size() != args.size()) return false;
        Type lt = ps.get(ps.size() - 1);
        if (!(lt instanceof ObjectType)) return false;
        String in = ((ObjectType) lt).getInternalName();
        int d = in.lastIndexOf('$');
        if (d < 0 || d == in.length() - 1) return false;
        for (int i = d + 1; i < in.length(); i++) if (!Character.isDigit(in.charAt(i))) return false;
        return true;
    }

    private static String stripLastParam(String desc) {
        List<Type> ps = paramTypes(desc);
        int close = desc.lastIndexOf(')');
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ps.size() - 1; i++) sb.append(ps.get(i).getDescriptor());
        return sb.append(desc.substring(close)).toString();
    }
    // END_CHANGE: BUG-2026-0160-2

    /** `super` as a receiver (the converter encodes it as a prefix operator "super"). */
    private static boolean isSuperReceiver(Expression o) {
        return o instanceof UnaryOperatorExpression && "super".equals(((UnaryOperatorExpression) o).getOperator());
    }

    // START_CHANGE: BUG-2026-0165-20261018-1 - An argument of unknown static type (Object) for a
    // parameter erased to Object: the source parameter may be a type variable of the receiver's
    // generic type (`Map<K,V>.put`), which an Object does not satisfy. Calling through the raw
    // type accepts it; the erased call is the same invocation.
    private Expression rawReceiver(Expression o, String owner, List<Expression> as, String desc) {
        if (o == null || as == null || as.isEmpty() || owner == null || owner.startsWith("[")) return o;
        if (isSuperReceiver(o)) return o;
        if (o instanceof CastExpression && ((CastExpression) o).isExplicit()) return o;
        List<Type> ps = paramTypes(desc);
        if (ps == null || ps.size() != as.size()) return o;
        boolean unknownArg = false;
        for (int i = 0; i < as.size(); i++) {
            if (!isObject(ps.get(i))) continue;
            Expression a = as.get(i);
            if (a instanceof NullExpression || a instanceof LambdaExpression || a instanceof MethodReferenceExpression) continue;
            Type t = typeOf(a);
            if (t == null || isObject(t)) { unknownArg = true; break; }
        }
        if (!unknownArg) return o;
        return explicitCast(o, new ObjectType(owner));
    }
    // END_CHANGE: BUG-2026-0165-1

    /** True when the iterable's element type cannot be known (a raw local, a cast, an Object). */
    private boolean rawIterable(Expression it) {
        Expression root = it;
        while (root instanceof MethodInvocationExpression && ((MethodInvocationExpression) root).getObject() != null) {
            root = ((MethodInvocationExpression) root).getObject();
        }
        if (root instanceof CastExpression) return true;
        if (root instanceof LocalVariableExpression) {
            return !genericLocals.contains(((LocalVariableExpression) root).getName());
        }
        // BUG-2026-0165: a field without a generic Signature is raw
        if (root instanceof FieldAccessExpression && genericFields != null) {
            FieldAccessExpression fa = (FieldAccessExpression) root;
            return !genericFields.contains(fa.getOwnerInternalName() + "." + fa.getName());
        }
        return isObject(typeOf(it));
    }

    /** A receiver statically typed Object gets cast to the class that declares the member. */
    private Expression receiver(Expression o, String owner) {
        if (o == null || owner == null || owner.isEmpty() || owner.startsWith("[")
                || "java/lang/Object".equals(owner) || o instanceof ThisExpression) return o;
        if (isSuperReceiver(o)) return o;
        Type ot = typeOf(o);
        if (!isObject(ot)) {
            // BUG-2026-0164: a receiver whose class does not declare/inherit the member
            if (hierarchy != null && ot instanceof ObjectType && ot.getDimension() == 0
                    && Boolean.FALSE.equals(hierarchy.isSubtype(((ObjectType) ot).getInternalName(), owner))) {
                return explicitCast(o, new ObjectType(owner));
            }
            return o;
        }
        return explicitCast(o, new ObjectType(owner));
    }

    // START_CHANGE: BUG-2026-0142-20261018-6 - Locals typed Object but indexed somewhere in the
    // method: the indexing opcode tells the array kind (a primitive element type exactly, any
    // reference element type as Object[]); `.length` on such a local uses the same cast.
    private final Map<String, Type> arrayKind = new HashMap<String, Type>();

    private void collectArrayUses(List<Statement> body) {
        final Map<String, Type> kinds = arrayKind;
        final java.util.Set<String> conflict = new java.util.HashSet<String>();
        new AstMapper() {
            @Override
            public Expression map(Expression e) {
                if (e instanceof ArrayAccessExpression && ((ArrayAccessExpression) e).getArray() instanceof LocalVariableExpression) {
                    String n = ((LocalVariableExpression) ((ArrayAccessExpression) e).getArray()).getName();
                    Type et = e.getType();
                    Type k = et instanceof PrimitiveType ? new ArrayType(et, 1) : new ArrayType(ObjectType.OBJECT, 1);
                    Type prev = kinds.get(n);
                    if (prev != null && !prev.getDescriptor().equals(k.getDescriptor())) conflict.add(n);
                    kinds.put(n, k);
                }
                return super.map(e);
            }
        }.mapList(body);
        for (String c : conflict) kinds.remove(c);
    }
    // END_CHANGE: BUG-2026-0142-6

    /** An array operand statically typed Object gets cast to an array of the accessed element type. */
    private Expression arrayReceiver(Expression o, Type elementType) {
        if (o == null || !isObject(typeOf(o))) return o;
        if (elementType == null && o instanceof LocalVariableExpression) {
            Type k = arrayKind.get(((LocalVariableExpression) o).getName());
            if (k != null) return explicitCast(o, k);
        }
        Type el = normalize(elementType);
        if (el == null || el instanceof VoidType) return o;
        // any reference array is an Object[]; a narrower cast could fail where the bytecode did not
        if (!(el instanceof PrimitiveType)) el = ObjectType.OBJECT;
        Type arr = el instanceof ArrayType
            ? new ArrayType(((ArrayType) el).getElementType(), el.getDimension() + 1)
            : new ArrayType(el, 1);
        return explicitCast(o, arr);
    }

    // START_CHANGE: BUG-2026-0156-20261017-1 - A lambda passed to a method overloaded on
    // functional interfaces (`AccessController.doPrivileged(PrivilegedAction | PrivilegedExceptionAction)`,
    // `ExecutorService.submit(Runnable | Callable)`) is ambiguous in source; the class file names
    // the interface it was bound to, so a cast to it selects the same overload.
    private static final java.util.Set<String> OVERLOADED_FUNCTIONAL = new java.util.HashSet<String>(
        java.util.Arrays.asList("doPrivileged", "doPrivilegedWithCombiner", "submit", "schedule",
            "invokeAll", "invokeAny", "callAsync"));

    private static List<Expression> disambiguateLambdas(String method, List<Expression> as, String desc) {
        if (as == null || as.isEmpty() || !OVERLOADED_FUNCTIONAL.contains(method)) return as;
        List<Type> params = paramTypes(desc);
        if (params == null || params.size() != as.size()) return as;
        List<Expression> out = null;
        for (int i = 0; i < as.size(); i++) {
            Expression a = as.get(i);
            if ((a instanceof LambdaExpression || a instanceof MethodReferenceExpression)
                    && params.get(i) instanceof ObjectType) {
                if (out == null) out = new ArrayList<Expression>(as);
                out.set(i, explicitCast(a, params.get(i)));
            }
        }
        return out != null ? out : as;
    }
    // END_CHANGE: BUG-2026-0156-1

    /** The target of an assignment / increment: only its sub-expressions are processed. */
    private Expression lvalue(Expression e) {
        if (e instanceof ArrayAccessExpression || e instanceof FieldAccessExpression) return expr(e);
        return e;
    }

    private Expression binary(BinaryOperatorExpression b) {
        String op = b.getOperator();
        Expression l = expr(b.getLeft());
        Expression r = expr(b.getRight());
        int line = b.getLineNumber();
        if ("&&".equals(op) || "||".equals(op)) {
            l = toBoolean(l);
            r = toBoolean(r);
            if (l == b.getLeft() && r == b.getRight()) return b;
            return new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, l, op, r);
        }
        Type lt = typeOf(l);
        Type rt = typeOf(r);
        boolean lb = lt == PrimitiveType.BOOLEAN;
        boolean rb = rt == PrimitiveType.BOOLEAN;
        if (lb != rb && (lb || rb)) {
            Expression bool = lb ? l : r;
            Expression other = lb ? r : l;
            Type ot = lb ? rt : lt;
            if ("==".equals(op) || "!=".equals(op)) {
                if (other instanceof IntegerConstantExpression) {
                    int v = ((IntegerConstantExpression) other).getValue();
                    // b == 0 -> !b, b != 0 -> b, b == 1 -> b, b != 1 -> !b
                    boolean positive = ("==".equals(op)) == (v != 0);
                    return positive ? bool : negate(bool);
                }
                if (ot != null && isIntLike(ot)) {
                    if (lb) l = toInt(l); else r = toInt(r);
                }
            } else if ("&".equals(op) || "|".equals(op) || "^".equals(op)) {
                if (other instanceof IntegerConstantExpression) {
                    Expression bc = toBoolean(other);
                    if (lb) r = bc; else l = bc;
                } else if (ot != null && isIntLike(ot)) {
                    if (lb) l = toInt(l); else r = toInt(r);
                }
            } else if (ot != null && isNumeric(ot)) {
                // arithmetic, shift or relational operator applied to a boolean
                if (lb) l = toInt(l); else r = toInt(r);
            }
        } else if (lt == null && rb && !lb && l instanceof IntegerConstantExpression
                && ("==".equals(op) || "!=".equals(op))) {
            // constant on the left of an unknown/int comparison handled above when typed
        }
        if (l == b.getLeft() && r == b.getRight()) return b;
        Type nt = b.getType();
        if (isComparison(op)) nt = PrimitiveType.BOOLEAN;
        return new BinaryOperatorExpression(line, nt, l, op, r);
    }

    // ------------------------------------------------------------------ conversions

    private Expression coerce(Expression e, Type target, int ctx) {
        if (e == null || target == null) return e;
        target = normalize(target);
        if (target == PrimitiveType.BOOLEAN) return toBoolean(e);
        Type t = typeOf(e);
        if (t == null) return e;
        if (target instanceof PrimitiveType) {
            if (t == PrimitiveType.BOOLEAN) {
                e = toInt(e);
                t = PrimitiveType.INT;
            }
            if (!(t instanceof PrimitiveType)) return e; // unboxing or unknown: leave to javac
            if (primitiveAssignable((PrimitiveType) t, (PrimitiveType) target)) return e;
            if (ctx == CTX_ASSIGN && e instanceof IntegerConstantExpression
                    && fitsConstant(((IntegerConstantExpression) e).getValue(), (PrimitiveType) target)) {
                return e;
            }
            return explicitCast(e, target);
        }
        if (t == PrimitiveType.BOOLEAN && target instanceof ObjectType) {
            String in = ((ObjectType) target).getInternalName();
            if ("java/lang/Integer".equals(in) || "java/lang/Number".equals(in)) return toInt(e);
            return e;
        }
        if (isObject(t) && !(e instanceof NullExpression) && needsDowncast(target)) {
            return explicitCast(e, target);
        }
        // START_CHANGE: BUG-2026-0164-20261018-2 - A value whose static class is not a subtype of
        // the target (a supertype or a sibling interface): the bytecode's verifier accepted it,
        // so a cast to the target is exact.
        if (hierarchy != null && t instanceof ObjectType && t.getDimension() == 0 && target instanceof ObjectType
                && target.getDimension() == 0 && !(e instanceof NullExpression)
                && Boolean.FALSE.equals(hierarchy.isSubtype(((ObjectType) t).getInternalName(), ((ObjectType) target).getInternalName()))) {
            return explicitCast(e, target);
        }
        // END_CHANGE: BUG-2026-0164-2
        return e;
    }

    private static boolean needsDowncast(Type target) {
        if (target instanceof ArrayType) return true;
        if (target instanceof GenericType) return target.getDimension() == 0;
        if (target instanceof ObjectType) {
            ObjectType ot = (ObjectType) target;
            return !"java/lang/Object".equals(ot.getInternalName());
        }
        return false;
    }

    private static boolean isObject(Type t) {
        return t instanceof ObjectType && t.getDimension() == 0
            && "java/lang/Object".equals(((ObjectType) t).getInternalName());
    }

    private static Expression explicitCast(Expression e, Type target) {
        CastExpression c = new CastExpression(e.getLineNumber(), target, e);
        c.setExplicit(true);
        return c;
    }

    /** Converts an expression to a boolean-valued one, preserving the JVM value. */
    private Expression toBoolean(Expression e) {
        if (e == null) return null;
        if (e instanceof IntegerConstantExpression) {
            return ((IntegerConstantExpression) e).getValue() != 0 ? BooleanExpression.TRUE : BooleanExpression.FALSE;
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            Expression a = toBoolean(t.getTrueExpression());
            Expression b = toBoolean(t.getFalseExpression());
            if (a instanceof BooleanExpression && b instanceof BooleanExpression) {
                boolean av = ((BooleanExpression) a).getValue();
                boolean bv = ((BooleanExpression) b).getValue();
                if (av && !bv) return t.getCondition();
                if (!av && bv) return negate(t.getCondition());
            }
            if (a == t.getTrueExpression() && b == t.getFalseExpression()) return e;
            return new TernaryExpression(t.getLineNumber(), PrimitiveType.BOOLEAN, t.getCondition(), a, b);
        }
        Type t = typeOf(e);
        if (t != null && isIntLike(t)) {
            return new BinaryOperatorExpression(e.getLineNumber(), PrimitiveType.BOOLEAN,
                e, "!=", IntegerConstantExpression.valueOf(0, 0));
        }
        return e;
    }

    /** Converts a boolean expression to the int the JVM holds for it (0 or 1). */
    private static Expression toInt(Expression e) {
        if (e instanceof BooleanExpression) {
            return IntegerConstantExpression.valueOf(0, ((BooleanExpression) e).getValue() ? 1 : 0);
        }
        return new TernaryExpression(e.getLineNumber(), PrimitiveType.INT, e,
            IntegerConstantExpression.valueOf(0, 1), IntegerConstantExpression.valueOf(0, 0));
    }

    private static Expression negate(Expression e) {
        if (e instanceof BooleanExpression) {
            return ((BooleanExpression) e).getValue() ? BooleanExpression.FALSE : BooleanExpression.TRUE;
        }
        if (e instanceof UnaryOperatorExpression && "!".equals(((UnaryOperatorExpression) e).getOperator())) {
            return ((UnaryOperatorExpression) e).getExpression();
        }
        return new UnaryOperatorExpression(e.getLineNumber(), PrimitiveType.BOOLEAN, "!", e, true);
    }

    // ------------------------------------------------------------------ typing

    /** Static type of an expression, or null when it cannot be determined reliably. */
    private Type typeOf(Expression e) {
        if (e == null) return null;
        if (e instanceof BooleanExpression) return PrimitiveType.BOOLEAN;
        if (e instanceof IntegerConstantExpression) return PrimitiveType.INT;
        if (e instanceof LongConstantExpression) return PrimitiveType.LONG;
        if (e instanceof FloatConstantExpression) return PrimitiveType.FLOAT;
        if (e instanceof DoubleConstantExpression) return PrimitiveType.DOUBLE;
        if (e instanceof StringConstantExpression || e instanceof TextBlockExpression) return ObjectType.STRING;
        if (e instanceof NullExpression) return null;
        if (e instanceof InstanceOfExpression) return PrimitiveType.BOOLEAN;
        if (e instanceof LocalVariableExpression) {
            Type t = lookup(((LocalVariableExpression) e).getName());
            return normalize(t != null ? t : e.getType());
        }
        if (e instanceof FieldAccessExpression) {
            if (fieldTypes != null) {
                FieldAccessExpression fa = (FieldAccessExpression) e;
                Type tv = fieldTypes.get(fa.getOwnerInternalName() + "." + fa.getName());
                if (tv != null) return tv;
            }
            String d = ((FieldAccessExpression) e).getDescriptor();
            if (d != null && d.length() > 0) return parseDescriptor(d);
            return normalize(e.getType());
        }
        if (e instanceof MethodInvocationExpression) {
            return returnTypeOf(((MethodInvocationExpression) e).getDescriptor(), e);
        }
        if (e instanceof StaticMethodInvocationExpression) {
            return returnTypeOf(((StaticMethodInvocationExpression) e).getDescriptor(), e);
        }
        if (e instanceof NewExpression) {
            return new ObjectType(((NewExpression) e).getInternalTypeName());
        }
        if (e instanceof NewArrayExpression) {
            Type t = e.getType();
            if (t instanceof PrimitiveType) return new ArrayType(t, 1);
            return normalize(t);
        }
        if (e instanceof ArrayAccessExpression) {
            Type at = typeOf(((ArrayAccessExpression) e).getArray());
            Type el = elementType(at);
            return el != null ? el : normalize(e.getType());
        }
        if (e instanceof CastExpression) return normalize(e.getType());
        if (e instanceof AssignmentExpression) return typeOf(((AssignmentExpression) e).getLeft());
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            if ("!".equals(u.getOperator())) return PrimitiveType.BOOLEAN;
            Type t = typeOf(u.getExpression());
            if ("++".equals(u.getOperator()) || "--".equals(u.getOperator())) return t;
            return t instanceof PrimitiveType ? promote((PrimitiveType) t) : t;
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            String op = b.getOperator();
            if (isComparison(op) || "&&".equals(op) || "||".equals(op)) return PrimitiveType.BOOLEAN;
            Type lt = typeOf(b.getLeft());
            Type rt = typeOf(b.getRight());
            if ("+".equals(op) && (isString(lt) || isString(rt))) return ObjectType.STRING;
            if (lt == PrimitiveType.BOOLEAN && rt == PrimitiveType.BOOLEAN) return PrimitiveType.BOOLEAN;
            if ("<<".equals(op) || ">>".equals(op) || ">>>".equals(op)) {
                return lt instanceof PrimitiveType ? promote((PrimitiveType) lt) : null;
            }
            if (lt instanceof PrimitiveType && rt instanceof PrimitiveType) {
                return binaryPromote((PrimitiveType) lt, (PrimitiveType) rt);
            }
            return null;
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            Type a = typeOf(t.getTrueExpression());
            Type b = typeOf(t.getFalseExpression());
            if (a == PrimitiveType.BOOLEAN && b == PrimitiveType.BOOLEAN) return PrimitiveType.BOOLEAN;
            if (a instanceof PrimitiveType && b instanceof PrimitiveType) {
                if (a == b) return a;
                return binaryPromote((PrimitiveType) a, (PrimitiveType) b);
            }
            if (a == null) return b;
            if (b == null) return a;
            return a.getDescriptor() != null && a.getDescriptor().equals(b.getDescriptor()) ? a : null;
        }
        if (e instanceof ThisExpression) return null; // its class is not known here; never coerce it
        if (e instanceof ClassExpression) return ObjectType.CLASS;
        return null;
    }

    private Type returnTypeOf(String desc, Expression e) {
        if (desc != null) {
            int close = desc.lastIndexOf(')');
            if (close >= 0 && close + 1 < desc.length()) {
                String r = desc.substring(close + 1);
                if ("V".equals(r)) return VoidType.INSTANCE;
                return parseDescriptor(r);
            }
        }
        return normalize(e.getType());
    }

    private static boolean isString(Type t) {
        return t instanceof ObjectType && t.getDimension() == 0
            && "java/lang/String".equals(((ObjectType) t).getInternalName());
    }

    private static boolean isComparison(String op) {
        return "==".equals(op) || "!=".equals(op) || "<".equals(op) || "<=".equals(op)
            || ">".equals(op) || ">=".equals(op);
    }

    private static boolean isIntLike(Type t) {
        return t == PrimitiveType.INT || t == PrimitiveType.SHORT || t == PrimitiveType.BYTE || t == PrimitiveType.CHAR;
    }

    private static boolean isNumeric(Type t) {
        return isIntLike(t) || t == PrimitiveType.LONG || t == PrimitiveType.FLOAT || t == PrimitiveType.DOUBLE;
    }

    private static PrimitiveType promote(PrimitiveType t) {
        if (t == PrimitiveType.BYTE || t == PrimitiveType.SHORT || t == PrimitiveType.CHAR) return PrimitiveType.INT;
        return t;
    }

    private static PrimitiveType binaryPromote(PrimitiveType a, PrimitiveType b) {
        if (a == PrimitiveType.BOOLEAN || b == PrimitiveType.BOOLEAN) return null;
        if (a == PrimitiveType.DOUBLE || b == PrimitiveType.DOUBLE) return PrimitiveType.DOUBLE;
        if (a == PrimitiveType.FLOAT || b == PrimitiveType.FLOAT) return PrimitiveType.FLOAT;
        if (a == PrimitiveType.LONG || b == PrimitiveType.LONG) return PrimitiveType.LONG;
        return PrimitiveType.INT;
    }

    private static int rank(PrimitiveType t) {
        if (t == PrimitiveType.BYTE) return 1;
        if (t == PrimitiveType.SHORT) return 2;
        if (t == PrimitiveType.CHAR) return 2;
        if (t == PrimitiveType.INT) return 3;
        if (t == PrimitiveType.LONG) return 4;
        if (t == PrimitiveType.FLOAT) return 5;
        if (t == PrimitiveType.DOUBLE) return 6;
        return 0;
    }

    /** JLS 5.1.2 widening primitive conversion (or identity). */
    private static boolean primitiveAssignable(PrimitiveType from, PrimitiveType to) {
        if (from == to) return true;
        if (from == PrimitiveType.BOOLEAN || to == PrimitiveType.BOOLEAN) return false;
        if (to == PrimitiveType.CHAR) return false;              // nothing widens to char
        if (from == PrimitiveType.CHAR) return rank(to) >= 3;    // char -> int, long, float, double
        if (to == PrimitiveType.SHORT) return from == PrimitiveType.BYTE;
        return rank(from) < rank(to);
    }

    private static boolean fitsConstant(int v, PrimitiveType t) {
        if (t == PrimitiveType.BYTE) return v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
        if (t == PrimitiveType.SHORT) return v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
        if (t == PrimitiveType.CHAR) return v >= 0 && v <= 0xFFFF;
        return true;
    }

    private static Type elementType(Type t) {
        t = normalize(t);
        if (t instanceof ArrayType) {
            ArrayType at = (ArrayType) t;
            if (at.getDimension() <= 1) return at.getElementType();
            return new ArrayType(at.getElementType(), at.getDimension() - 1);
        }
        return null;
    }

    /** Folds the several array encodings of the AST into ArrayType. */
    private static Type normalize(Type t) {
        if (t instanceof ObjectType) {
            ObjectType ot = (ObjectType) t;
            String in = ot.getInternalName();
            if (in != null && in.startsWith("[")) return parseDescriptor(in);
            if (ot.getDimension() > 0) {
                return new ArrayType(new ObjectType(in, ot.getQualifiedName(), ot.getName()), ot.getDimension());
            }
        }
        if (t instanceof ArrayType) {
            ArrayType at = (ArrayType) t;
            if (at.getElementType() instanceof ArrayType) {
                ArrayType inner = (ArrayType) at.getElementType();
                return new ArrayType(inner.getElementType(), inner.getDimension() + at.getDimension());
            }
        }
        return t;
    }

    static Type parseDescriptor(String d) {
        if (d == null || d.isEmpty()) return null;
        int dim = 0;
        while (dim < d.length() && d.charAt(dim) == '[') dim++;
        if (dim >= d.length()) return null;
        char c = d.charAt(dim);
        Type base;
        if (c == 'L') {
            int semi = d.indexOf(';', dim);
            if (semi < 0) return null;
            base = new ObjectType(d.substring(dim + 1, semi));
        } else if (c == 'T') {
            int semi = d.indexOf(';', dim);
            if (semi < 0) return null;
            base = new GenericType(d.substring(dim + 1, semi));
        } else {
            switch (c) {
                case 'Z': base = PrimitiveType.BOOLEAN; break;
                case 'B': base = PrimitiveType.BYTE; break;
                case 'C': base = PrimitiveType.CHAR; break;
                case 'S': base = PrimitiveType.SHORT; break;
                case 'I': base = PrimitiveType.INT; break;
                case 'J': base = PrimitiveType.LONG; break;
                case 'F': base = PrimitiveType.FLOAT; break;
                case 'D': base = PrimitiveType.DOUBLE; break;
                default: return null;
            }
        }
        return dim > 0 ? new ArrayType(base, dim) : base;
    }

    static List<Type> paramTypes(String desc) {
        if (desc == null || !desc.startsWith("(")) return null;
        List<Type> out = new ArrayList<Type>();
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') {
                i = desc.indexOf(';', i) + 1;
                if (i <= 0) return null;
            } else {
                i++;
            }
            out.add(parseDescriptor(desc.substring(start, i)));
        }
        return out;
    }
}
