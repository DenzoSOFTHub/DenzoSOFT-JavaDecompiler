/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.*;

/**
 * Last pass on a method body: no local may be declared while a local of the same name is in
 * scope (BUG-2026-0162).
 *
 * <p>Earlier passes move declarations (method-level hoisting, slot pre-declarations, for-each
 * and try-with-resources folding), and Java - unlike the bytecode - forbids shadowing a local.
 * An inner declaration of the same type becomes a plain assignment (it is the same variable
 * re-initialized); any other inner declaration (different type, a for-each, catch or resource
 * variable) is renamed together with every reference in its scope.</p>
 */
public final class DuplicateDeclarationResolver {

    private final LinkedList<Map<String, Type>> scopes = new LinkedList<Map<String, Type>>();
    private final Set<String> allNames = new HashSet<String>();
    private int counter;

    private DuplicateDeclarationResolver() { }

    public static List<Statement> resolve(List<Statement> body, List<String> paramNames, List<Type> paramTypes) {
        if (body == null) return null;
        DuplicateDeclarationResolver r = new DuplicateDeclarationResolver();
        Map<String, Type> params = new HashMap<String, Type>();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), paramTypes != null && i < paramTypes.size() ? paramTypes.get(i) : null);
            }
        }
        r.collectNames(body);
        r.allNames.addAll(params.keySet());
        r.scopes.push(params);
        try {
            r.list(body);
        } catch (RuntimeException e) {
            return body;
        }
        return body;
    }

    private void collectNames(List<Statement> body) {
        new AstMapper() {
            @Override
            protected Expression mapLocal(LocalVariableExpression lv) {
                allNames.add(lv.getName());
                return lv;
            }
            @Override
            protected String mapDeclaredName(String n) {
                allNames.add(n);
                return n;
            }
        }.mapList(body);
    }

    private boolean inScope(String name) {
        for (Map<String, Type> m : scopes) if (m.containsKey(name)) return true;
        return false;
    }

    private Type scopeType(String name) {
        for (Map<String, Type> m : scopes) if (m.containsKey(name)) return m.get(name);
        return null;
    }

    private String fresh(String base) {
        String n;
        do { n = base + "_d" + (++counter); } while (allNames.contains(n));
        allNames.add(n);
        return n;
    }

    private static boolean sameType(Type a, Type b) {
        return a != null && b != null && a.getDescriptor() != null && a.getDescriptor().equals(b.getDescriptor());
    }

    private void list(List<Statement> l) {
        if (l == null) return;
        scopes.push(new HashMap<String, Type>());
        try {
            sequence(l);
        } finally {
            scopes.pop();
        }
    }

    /** Statements of one scope (the scope is already pushed). */
    private void sequence(List<Statement> l) {
        {
            for (int i = 0; i < l.size(); i++) {
                Statement s = l.get(i);
                if (s instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                    if (inScope(v.getName())) {
                        if (sameType(scopeType(v.getName()), v.getType()) && !v.isVar()) {
                            // the same variable re-initialized
                            if (v.hasInitializer()) {
                                l.set(i, new ExpressionStatement(new AssignmentExpression(v.getLineNumber(), v.getType(),
                                    new LocalVariableExpression(v.getLineNumber(), v.getType(), v.getName(), -1), "=",
                                    v.getInitializer())));
                            } else {
                                l.remove(i);
                                i--;
                            }
                            continue;
                        }
                        // a different variable: rename it for the rest of this block
                        String nn = fresh(v.getName());
                        AstMapper.Renamer ren = new AstMapper.Renamer(v.getName(), nn);
                        for (int j = i; j < l.size(); j++) l.set(j, ren.map(l.get(j)));
                        s = l.get(i);
                        v = (VariableDeclarationStatement) s;
                    }
                    scopes.peek().put(v.getName(), v.getType());
                    continue;
                }
                Statement n = stmt(s);
                if (n != s) l.set(i, n);
            }
        }
    }

    private Statement body(Statement s) {
        if (s instanceof BlockStatement) {
            list(((BlockStatement) s).getStatements());
            return s;
        }
        List<Statement> one = new ArrayList<Statement>();
        one.add(s);
        list(one);
        return one.size() == 1 ? one.get(0) : new BlockStatement(s.getLineNumber(), one);
    }

    private Statement stmt(Statement s) {
        if (s instanceof BlockStatement) { list(((BlockStatement) s).getStatements()); return s; }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            Statement t = body(i.getThenBody());
            return t == i.getThenBody() ? s : new IfStatement(i.getLineNumber(), i.getCondition(), t);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            Statement t = body(i.getThenBody());
            Statement e = body(i.getElseBody());
            return t == i.getThenBody() && e == i.getElseBody() ? s : new IfElseStatement(i.getLineNumber(), i.getCondition(), t, e);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            Statement b = body(w.getBody());
            return b == w.getBody() ? s : new WhileStatement(w.getLineNumber(), w.getCondition(), b);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            Statement b = body(d.getBody());
            return b == d.getBody() ? s : new DoWhileStatement(d.getLineNumber(), d.getCondition(), b);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement b = stmt(l.getBody());
            return b == l.getBody() ? s : new LabelStatement(l.getLineNumber(), l.getLabel(), b);
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            Statement b = body(sy.getBody());
            return b == sy.getBody() ? s : new SynchronizedStatement(sy.getLineNumber(), sy.getMonitor(), b);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            Statement init = f.getInit();
            Expression cond = f.getCondition();
            Statement upd = f.getUpdate();
            Statement b = f.getBody();
            scopes.push(new HashMap<String, Type>());
            try {
                if (init instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) init;
                    if (inScope(v.getName())) {
                        if (sameType(scopeType(v.getName()), v.getType()) && v.hasInitializer()) {
                            init = new ExpressionStatement(new AssignmentExpression(v.getLineNumber(), v.getType(),
                                new LocalVariableExpression(v.getLineNumber(), v.getType(), v.getName(), -1), "=",
                                v.getInitializer()));
                        } else {
                            String nn = fresh(v.getName());
                            AstMapper.Renamer ren = new AstMapper.Renamer(v.getName(), nn);
                            init = ren.map(init);
                            cond = ren.map(cond);
                            upd = ren.map(upd);
                            b = ren.map(b);
                            scopes.peek().put(nn, v.getType());
                        }
                    } else {
                        scopes.peek().put(v.getName(), v.getType());
                    }
                }
                b = body(b);
            } finally {
                scopes.pop();
            }
            if (init == f.getInit() && cond == f.getCondition() && upd == f.getUpdate() && b == f.getBody()) return s;
            return new ForStatement(f.getLineNumber(), init, cond, upd, b);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            String name = f.getVariableName();
            Statement b = f.getBody();
            if (inScope(name)) {
                String nn = fresh(name);
                b = new AstMapper.Renamer(name, nn).map(b);
                name = nn;
            }
            scopes.push(new HashMap<String, Type>());
            try {
                scopes.peek().put(name, f.getVariableType());
                b = body(b);
            } finally {
                scopes.pop();
            }
            if (name.equals(f.getVariableName()) && b == f.getBody()) return s;
            return new ForEachStatement(f.getLineNumber(), f.getVariableType(), name, f.getIterable(), b);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<Statement> res = t.getResources();
            Statement tryBody = t.getTryBody();
            scopes.push(new HashMap<String, Type>());
            try {
                if (res != null) {
                    res = new ArrayList<Statement>(res);
                    for (int i = 0; i < res.size(); i++) {
                        if (!(res.get(i) instanceof VariableDeclarationStatement)) continue;
                        VariableDeclarationStatement v = (VariableDeclarationStatement) res.get(i);
                        if (inScope(v.getName())) {
                            String nn = fresh(v.getName());
                            AstMapper.Renamer ren = new AstMapper.Renamer(v.getName(), nn);
                            for (int j = i; j < res.size(); j++) res.set(j, ren.map(res.get(j)));
                            tryBody = ren.map(tryBody);
                            v = (VariableDeclarationStatement) res.get(i);
                        }
                        scopes.peek().put(v.getName(), v.getType());
                    }
                }
                tryBody = body(tryBody);
            } finally {
                scopes.pop();
            }
            List<TryCatchStatement.CatchClause> cs = new ArrayList<TryCatchStatement.CatchClause>();
            boolean changed = tryBody != t.getTryBody() || res != t.getResources();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                String name = cc.variableName;
                Statement cb = cc.body;
                if (name != null && inScope(name)) {
                    String nn = fresh(name);
                    cb = new AstMapper.Renamer(name, nn).map(cb);
                    name = nn;
                }
                scopes.push(new HashMap<String, Type>());
                try {
                    scopes.peek().put(name, cc.exceptionTypes != null && !cc.exceptionTypes.isEmpty() ? cc.exceptionTypes.get(0) : null);
                    cb = body(cb);
                } finally {
                    scopes.pop();
                }
                if (cb != cc.body || name != cc.variableName) changed = true;
                cs.add(cb == cc.body && name == cc.variableName ? cc : new TryCatchStatement.CatchClause(cc.exceptionTypes, name, cb));
            }
            Statement fin = t.hasFinally() ? body(t.getFinallyBody()) : null;
            if (fin != t.getFinallyBody()) changed = true;
            if (!changed) return s;
            return new TryCatchStatement(t.getLineNumber(), tryBody, cs, fin, res);
        }
        if (s instanceof SwitchStatement) {
            scopes.push(new HashMap<String, Type>());
            try {
                // the switch block is one scope: declarations carry over between cases
                for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                    if (c.getStatements() != null) sequence(c.getStatements());
                }
            } finally {
                scopes.pop();
            }
            return s;
        }
        return s;
    }
}
