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
 * Folds the (already-scaffolding-stripped) Java 21 record-deconstruction extraction back into a real
 * deconstruction pattern (BUG-2026-0067).
 *
 * After {@link RecordPatternReconstructor} and {@link InstanceOfPatternReconstructor}, a flat record
 * pattern looks like:
 * <pre>
 *   if (o instanceof Point p) {
 *       int t = p.x(); int dead = t; int x = t;   // component 0
 *       t = p.y(); dead = t; int y = t;            // component 1
 *       &lt;body using x, y&gt;
 *   }
 * </pre>
 * which is folded to {@code if (o instanceof Point(int x, int y)) { <body> }}.
 *
 * Every uncertain shape leaves the node untouched (returns null), so the worst case is verbose-but-correct
 * legacy output, never miscompilation.
 */
public final class RecordDeconstructionFolder {

    private RecordDeconstructionFolder() { }

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
            Statement folded = tryFoldIf(is.getCondition(), then, is.getLineNumber());
            if (folded != null) return folded;
            return new IfStatement(is.getLineNumber(), is.getCondition(), then);
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement is = (IfElseStatement) stmt;
            return new IfElseStatement(is.getLineNumber(), is.getCondition(), visit(is.getThenBody()), visit(is.getElseBody()));
        }
        if (stmt instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) stmt;
            return new WhileStatement(w.getLineNumber(), w.getCondition(), visit(w.getBody()));
        }
        if (stmt instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) stmt;
            return new DoWhileStatement(d.getLineNumber(), d.getCondition(), visit(d.getBody()));
        }
        if (stmt instanceof ForStatement) {
            ForStatement f = (ForStatement) stmt;
            return new ForStatement(f.getLineNumber(), f.getInit(), f.getCondition(), f.getUpdate(), visit(f.getBody()));
        }
        if (stmt instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) stmt;
            return new ForEachStatement(f.getLineNumber(), f.getVariableType(), f.getVariableName(), f.getIterable(), visit(f.getBody()));
        }
        if (stmt instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) stmt;
            return new LabelStatement(l.getLineNumber(), l.getLabel(), visit(l.getBody()));
        }
        if (stmt instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) stmt;
            return new SynchronizedStatement(sy.getLineNumber(), sy.getMonitor(), visit(sy.getBody()));
        }
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) stmt;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, visit(cc.body)));
            }
            Statement fin = t.hasFinally() ? visit(t.getFinallyBody()) : null;
            return new TryCatchStatement(t.getLineNumber(), visit(t.getTryBody()), catches, fin, t.getResources());
        }
        if (stmt instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) stmt;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                cases.add(new SwitchStatement.SwitchCase(c.getLabels(), reconstruct(c.getStatements())));
            }
            return new SwitchStatement(sw.getLineNumber(), sw.getSelector(), cases, sw.isArrowStyle());
        }
        return stmt;
    }

    private static Statement tryFoldIf(Expression condition, Statement thenBody, int line) {
        InstanceOfExpression io = findInstanceOf(condition);
        if (io == null || io.hasRecordPattern() || !io.hasPatternVariable()) return null;
        if (!(thenBody instanceof BlockStatement)) return null;
        List<Statement> body = ((BlockStatement) thenBody).getStatements();
        Fold f = foldFlat(io.getPatternVariableName(), body);
        if (f == null) return null;

        InstanceOfExpression folded = new InstanceOfExpression(io.getLineNumber(), io.getExpression(),
            io.getCheckType(), null, new RecordPattern(f.components));
        Expression newCond = replaceInstanceOf(condition, io, folded);
        // The real body is whatever remains in the (possibly innermost-descended) statement list.
        List<Statement> newBody = new ArrayList<Statement>(f.finalStmts.subList(f.bodyStart, f.finalStmts.size()));
        return new IfStatement(line, newCond, new BlockStatement(thenBody.getLineNumber(), newBody));
    }

    private static final class Fold {
        final List<RecordPattern.Component> components;
        final List<Statement> finalStmts;
        final int bodyStart;
        // START_CHANGE: BUG-2026-0067-20260610-38 - Top-list consumption (see Walk.topConsumed).
        final int topConsumed;
        Fold(List<RecordPattern.Component> c, List<Statement> s, int b, int top) { this.components = c; this.finalStmts = s; this.bodyStart = b; this.topConsumed = top; }
        // END_CHANGE: BUG-2026-0067-38
    }

    /**
     * BUG-2026-0079: public entry for SWITCH-arm folding. Folds the record deconstruction of {@code subject}
     * over {@code body} (the statements after the `Type subject = (Type) sel;` cast). Returns the component
     * list + the remaining body (the arm value), or null if the body is not a deconstruction.
     */
    public static ArmFold foldArm(String subject, List<Statement> body) {
        Fold f = foldFlat(subject, body);
        if (f == null) return null;
        // START_CHANGE: BUG-2026-0067-20260610-35 - Expose how much of the TOP-LEVEL list the walk
        // consumed (flat walk: the machinery prefix; descended walk: up to and including the nested if),
        // so the typeSwitch tail-arm reclaim can verify it absorbs the whole post-switch tail.
        boolean flat = f.finalStmts == body;
        return new ArmFold(f.components,
            new ArrayList<Statement>(f.finalStmts.subList(f.bodyStart, f.finalStmts.size())),
            flat, flat ? f.bodyStart : f.topConsumed);
        // END_CHANGE: BUG-2026-0067-35
    }

    public static final class ArmFold {
        public final List<RecordPattern.Component> components;
        public final List<Statement> remainingBody;
        // START_CHANGE: BUG-2026-0067-20260610-36 - Top-level coverage info for the tail-arm reclaim.
        /** Whether the walk stayed in the passed-in list (no nested-if descent). */
        public final boolean flatWalk;
        /** Statements consumed from the passed-in list (excluding {@link #remainingBody} when flat). */
        public final int consumed;
        ArmFold(List<RecordPattern.Component> c, List<Statement> r, boolean flat, int consumed) {
            this.components = c; this.remainingBody = r; this.flatWalk = flat; this.consumed = consumed;
        }
        // END_CHANGE: BUG-2026-0067-36
    }

    /** Consume the component-extraction prefix of a then-block for subject {@code subjectName}. */
    private static Fold foldFlat(String subjectName, List<Statement> stmts) {
        Walk w = new Walk(stmts, 0);
        List<RecordPattern.Component> comps = foldOuter(subjectName, w, w.fullScan);
        if (comps == null || comps.isEmpty()) return null;
        return new Fold(comps, w.stmts, w.i, w.topConsumed);
    }

    /** Mutable cursor: a statement list + index; the list may switch to a nested if-body during descent. */
    private static final class Walk {
        List<Statement> stmts; int i; final List<Statement> fullScan;
        // START_CHANGE: BUG-2026-0067-20260610-37 - Top-list consumption marker, frozen on first descent.
        int topConsumed;
        // END_CHANGE: BUG-2026-0067-37
        Walk(List<Statement> s, int idx) { this.stmts = s; this.i = idx; this.fullScan = s; }
    }

    /**
     * Fold the components of one record subject, descending through nested `if (scratch instanceof T)`
     * checks. Pending nested subjects are folded (recursively) from the innermost body afterwards.
     * Returns the component list, or null if the shape does not match. Advances {@code w}.
     */
    private static List<RecordPattern.Component> foldOuter(String subject, Walk w, List<Statement> liveScope) {
        List<RecordPattern.Component> comps = new ArrayList<RecordPattern.Component>();
        // (type, nestedSubject, indexInComps) for components that are nested record patterns.
        List<Object[]> pending = new ArrayList<Object[]>();
        int guard = 0;
        while (w.i < w.stmts.size() && guard++ < 256) {
            Head head = matchAccessorHead(w.stmts.get(w.i), subject);
            if (head == null) break; // leaf-extraction-for-nested / real body starts here
            int before = w.i;
            w.i++;
            // Nested check on the scratch slot?
            Object[] nest = matchNestedCheck(w.i < w.stmts.size() ? w.stmts.get(w.i) : null, head.scratch);
            if (nest != null) {
                Type innerType = (Type) nest[0];
                List<Statement> innerBody = castStmts(nest[1]);
                String nestedSubject = (String) nest[2]; // non-null if the check carries a pattern var
                w.i++; // consume the nested-if
                if (innerBody.isEmpty()) return null;
                int innerStart;
                if (nestedSubject != null) {
                    innerStart = 0; // `if (scratch instanceof T sub)` — no separate binding statement
                } else {
                    nestedSubject = simpleCopyDest(innerBody.get(0), head.scratch);
                    if (nestedSubject == null) return null;
                    innerStart = 1; // skip the `sub = scratch` binding statement
                }
                pending.add(new Object[]{ innerType, nestedSubject, Integer.valueOf(comps.size()) });
                comps.add(null); // placeholder, filled below
                // Descend: continue collecting THIS subject's remaining components inside the if body.
                // START_CHANGE: BUG-2026-0067-20260610-39 - Freeze the top-list consumption on the first
                // descent (everything up to and including the nested if belongs to the pattern machinery).
                if (w.stmts == w.fullScan) w.topConsumed = w.i;
                // END_CHANGE: BUG-2026-0067-39
                w.stmts = innerBody; w.i = innerStart;
                continue;
            }
            // Leaf component: head -> dead copies -> exactly one live binding.
            List<Copy> copies = new ArrayList<Copy>();
            while (w.i < w.stmts.size()) {
                Copy cp = matchCopyOf(w.stmts.get(w.i), head.scratch);
                if (cp == null) break;
                copies.add(cp);
                w.i++;
            }
            Copy live = null;
            for (Copy cp : copies) {
                if (isReadIn(cp.destName, liveScope)) {
                    if (live != null) return null;
                    live = cp;
                }
            }
            // START_CHANGE: BUG-2026-0067-20260610-40 - Dead component -> unnamed pattern. When no copy
            // of the extracted component is live AND the scratch slot itself is dead downstream
            // (write-before-read), the source bound a component it never used: emit `Type _` / `var _`
            // instead of aborting the whole fold (C_Unnamed onlyX/firstX).
            if (live == null) {
                if (!scratchDeadFrom(head.scratch, w.stmts, w.i)) return null;
                Type deadType = head.castType;
                if (deadType == null && !isObjectType(head.accessorType)) deadType = head.accessorType;
                if (deadType == null && !copies.isEmpty()) deadType = copies.get(0).bindType;
                if (deadType != null) comps.add(new RecordPattern.Component(deadType, "_", false));
                else comps.add(new RecordPattern.Component(null, "_", true)); // `var _`
                if (w.i <= before) return null;
                continue;
            }
            // A cast on the accessor head (generic erasure: `String s = (String) t.value()`) carries the
            // real component type; the alias copy's declared slot is then erased (Object) and must be
            // overridden.
            Type bindType = head.castType != null ? head.castType : live.bindType;
            // The copy's declared slot may be erased to Object by slot reuse; the accessor's static
            // return type is more precise (C_RecordPattern.describe: Line(Point a, Point b)).
            if (isObjectType(bindType) && head.accessorType != null && !isObjectType(head.accessorType)) {
                bindType = head.accessorType;
            }
            // END_CHANGE: BUG-2026-0067-40
            comps.add(new RecordPattern.Component(bindType, live.destName, false));
            if (w.i <= before) return null;
        }
        // Fold each pending nested subject's components from the (now innermost) body.
        for (int p = 0; p < pending.size(); p++) {
            Object[] entry = pending.get(p);
            Type innerType = (Type) entry[0];
            String nestedSubject = (String) entry[1];
            int idx = ((Integer) entry[2]).intValue();
            List<RecordPattern.Component> inner = foldOuter(nestedSubject, w, liveScope);
            if (inner == null || inner.isEmpty()) return null;
            comps.set(idx, new RecordPattern.Component(innerType, new RecordPattern(inner)));
        }
        return comps;
    }

    @SuppressWarnings("unchecked")
    private static List<Statement> castStmts(Object o) { return (List<Statement>) o; }

    /**
     * `if (scratch instanceof Type [sub]) { body }`. Returns {Type, bodyStmts, subjectName-or-null}.
     * subjectName is non-null when the check already carries a pattern variable (folded by
     * InstanceOfPatternReconstructor); otherwise the nested subject is the leading `sub = scratch` copy.
     */
    private static Object[] matchNestedCheck(Statement s, String scratch) {
        Statement then = null; Expression cond = null;
        if (s instanceof IfStatement) { cond = ((IfStatement) s).getCondition(); then = ((IfStatement) s).getThenBody(); }
        else if (s instanceof IfElseStatement) { cond = ((IfElseStatement) s).getCondition(); then = ((IfElseStatement) s).getThenBody(); }
        else return null;
        if (!(cond instanceof InstanceOfExpression)) return null;
        InstanceOfExpression io = (InstanceOfExpression) cond;
        if (io.hasRecordPattern()) return null;
        if (!(io.getExpression() instanceof LocalVariableExpression)
                || !scratch.equals(((LocalVariableExpression) io.getExpression()).getName())) return null;
        if (!(then instanceof BlockStatement)) return null;
        return new Object[]{ io.getCheckType(), ((BlockStatement) then).getStatements(),
            io.hasPatternVariable() ? io.getPatternVariableName() : null };
    }

    /** `dest = scratch` (decl or assignment, no cast); returns dest name. */
    private static String simpleCopyDest(Statement s, String scratch) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            if (v.hasInitializer() && v.getInitializer() instanceof LocalVariableExpression
                    && scratch.equals(((LocalVariableExpression) v.getInitializer()).getName())) return v.getName();
        } else if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                if (ae.getLeft() instanceof LocalVariableExpression && ae.getRight() instanceof LocalVariableExpression
                        && scratch.equals(((LocalVariableExpression) ae.getRight()).getName())) {
                    return ((LocalVariableExpression) ae.getLeft()).getName();
                }
            }
        }
        return null;
    }

    private static final class Head {
        final String scratch; final Type castType;
        // START_CHANGE: BUG-2026-0067-20260610-41 - The accessor's static return type (from the method
        // descriptor) — more precise than an erased Object copy slot.
        final Type accessorType;
        Head(String s, Type c, Type accessor) { this.scratch = s; this.castType = c; this.accessorType = accessor; }
        // END_CHANGE: BUG-2026-0067-41
    }
    private static final class Copy {
        final String destName; final Type bindType;
        Copy(String d, Type t) { this.destName = d; this.bindType = t; }
    }

    /** `scratch = subject.accessor()` or `scratch = (T) subject.accessor()` (decl or assignment). */
    private static Head matchAccessorHead(Statement s, String subject) {
        String dest = null; Expression rhs = null;
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            if (v.hasInitializer()) { dest = v.getName(); rhs = v.getInitializer(); }
        } else if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                dest = ((LocalVariableExpression) ((AssignmentExpression) e).getLeft()).getName();
                rhs = ((AssignmentExpression) e).getRight();
            }
        }
        if (dest == null) return null;
        Type castType = null;
        if (rhs instanceof CastExpression) {
            castType = ((CastExpression) rhs).getType();
            rhs = ((CastExpression) rhs).getExpression();
        }
        // START_CHANGE: BUG-2026-0067-20260610-42 - Carry the accessor's static return type.
        if (!isAccessorOn(rhs, subject)) return null;
        return new Head(dest, castType, rhs.getType());
        // END_CHANGE: BUG-2026-0067-42
    }

    // START_CHANGE: BUG-2026-0067-20260610-43 - Helpers for the dead-component / erased-type fixes.
    /** Whether {@code t} is unknown or the erased {@code java.lang.Object}. */
    private static boolean isObjectType(Type t) {
        if (t == null) return true;
        if (t instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) {
            return "java/lang/Object".equals(
                ((it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) t).getInternalName());
        }
        return "java.lang.Object".equals(t.getName()) || "Object".equals(t.getName());
    }

    /**
     * Whether the scratch slot {@code name} is dead from {@code stmts[from]} on: a top-level plain
     * reassignment (whose RHS does not read it) kills it before any read. Conservative: any read first
     * (including inside nested control flow) means live.
     */
    private static boolean scratchDeadFrom(String name, List<Statement> stmts, int from) {
        for (int k = from; k < stmts.size(); k++) {
            Statement s = stmts.get(k);
            if (isPlainWriteTo(s, name)) return true; // killed before any read
            if (readsStmt(s, name)) return false;
        }
        return true; // never read again
    }

    /** `name = rhs;` or `Type name = rhs;` where rhs does not read {@code name}. */
    private static boolean isPlainWriteTo(Statement s, String name) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            return name.equals(v.getName()) && (!v.hasInitializer() || !readsExpr(v.getInitializer(), name));
        }
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                return "=".equals(ae.getOperator())
                    && ae.getLeft() instanceof LocalVariableExpression
                    && name.equals(((LocalVariableExpression) ae.getLeft()).getName())
                    && !readsExpr(ae.getRight(), name);
            }
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0067-43

    private static boolean isAccessorOn(Expression e, String subject) {
        if (!(e instanceof MethodInvocationExpression)) return false;
        MethodInvocationExpression m = (MethodInvocationExpression) e;
        return m.getObject() instanceof LocalVariableExpression
            && subject.equals(((LocalVariableExpression) m.getObject()).getName())
            && (m.getArguments() == null || m.getArguments().isEmpty());
    }

    /** `dest = scratch` or `dest = (Cast) scratch`; returns dest + its declared/cast type. */
    private static Copy matchCopyOf(Statement s, String scratch) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            if (!v.hasInitializer()) return null;
            Type t = aliasOf(v.getInitializer(), scratch, v.getType());
            return t != null ? new Copy(v.getName(), t) : null;
        }
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    Type declared = ((LocalVariableExpression) ae.getLeft()).getType();
                    Type t = aliasOf(ae.getRight(), scratch, declared);
                    return t != null ? new Copy(((LocalVariableExpression) ae.getLeft()).getName(), t) : null;
                }
            }
        }
        return null;
    }

    /** If {@code rhs} is `scratch` or `(Cast) scratch`, return the binding type (cast type preferred). */
    private static Type aliasOf(Expression rhs, String scratch, Type declared) {
        if (rhs instanceof LocalVariableExpression
                && scratch.equals(((LocalVariableExpression) rhs).getName())) {
            return declared;
        }
        if (rhs instanceof CastExpression) {
            CastExpression c = (CastExpression) rhs;
            if (c.getExpression() instanceof LocalVariableExpression
                    && scratch.equals(((LocalVariableExpression) c.getExpression()).getName())) {
                return c.getType(); // generic-erasure case: the cast carries the real type
            }
        }
        return null;
    }

    /** Whether a local named {@code name} is READ (used as a value, not an assignment LHS) anywhere. */
    private static boolean isReadIn(String name, List<Statement> stmts) {
        for (Statement s : stmts) {
            if (readsStmt(s, name)) return true;
        }
        return false;
    }

    private static boolean readsStmt(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof ExpressionStatement) {
            return readsExprNonLhs(((ExpressionStatement) s).getExpression(), name);
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            return v.hasInitializer() && readsExpr(v.getInitializer(), name);
        }
        if (s instanceof ReturnStatement) {
            return ((ReturnStatement) s).hasExpression() && readsExpr(((ReturnStatement) s).getExpression(), name);
        }
        if (s instanceof ThrowStatement) return readsExpr(((ThrowStatement) s).getExpression(), name);
        if (s instanceof IfStatement) {
            return readsExpr(((IfStatement) s).getCondition(), name) || readsStmt(((IfStatement) s).getThenBody(), name);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            return readsExpr(i.getCondition(), name) || readsStmt(i.getThenBody(), name) || readsStmt(i.getElseBody(), name);
        }
        if (s instanceof BlockStatement) {
            for (Statement x : ((BlockStatement) s).getStatements()) if (readsStmt(x, name)) return true;
            return false;
        }
        if (s instanceof WhileStatement) return readsExpr(((WhileStatement) s).getCondition(), name) || readsStmt(((WhileStatement) s).getBody(), name);
        if (s instanceof DoWhileStatement) return readsExpr(((DoWhileStatement) s).getCondition(), name) || readsStmt(((DoWhileStatement) s).getBody(), name);
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            return readsStmt(f.getInit(), name) || readsExpr(f.getCondition(), name) || readsStmt(f.getUpdate(), name) || readsStmt(f.getBody(), name);
        }
        if (s instanceof ForEachStatement) return readsExpr(((ForEachStatement) s).getIterable(), name) || readsStmt(((ForEachStatement) s).getBody(), name);
        if (s instanceof SynchronizedStatement) return readsExpr(((SynchronizedStatement) s).getMonitor(), name) || readsStmt(((SynchronizedStatement) s).getBody(), name);
        if (s instanceof LabelStatement) return readsStmt(((LabelStatement) s).getBody(), name);
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (readsStmt(t.getTryBody(), name)) return true;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) if (readsStmt(cc.body, name)) return true;
            return t.getFinallyBody() != null && readsStmt(t.getFinallyBody(), name);
        }
        if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                for (Statement x : c.getStatements()) if (readsStmt(x, name)) return true;
            }
            return readsExpr(((SwitchStatement) s).getSelector(), name);
        }
        return false;
    }

    // For an assignment statement, the LHS local is a WRITE, not a read.
    private static boolean readsExprNonLhs(Expression e, String name) {
        if (e instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) e;
            boolean lhsIsName = ae.getLeft() instanceof LocalVariableExpression
                && name.equals(((LocalVariableExpression) ae.getLeft()).getName());
            // a compound `x op= ...` reads x; a plain `x = ...` does not.
            boolean reads = (!lhsIsName && readsExpr(ae.getLeft(), name)) || readsExpr(ae.getRight(), name);
            if (lhsIsName && !"=".equals(ae.getOperator())) reads = true;
            return reads;
        }
        return readsExpr(e, name);
    }

    private static boolean readsExpr(Expression e, String name) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) return name.equals(((LocalVariableExpression) e).getName());
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            return readsExpr(b.getLeft(), name) || readsExpr(b.getRight(), name);
        }
        if (e instanceof UnaryOperatorExpression) return readsExpr(((UnaryOperatorExpression) e).getExpression(), name);
        if (e instanceof CastExpression) return readsExpr(((CastExpression) e).getExpression(), name);
        if (e instanceof AssignmentExpression) {
            return readsExpr(((AssignmentExpression) e).getLeft(), name) || readsExpr(((AssignmentExpression) e).getRight(), name);
        }
        if (e instanceof FieldAccessExpression) return readsExpr(((FieldAccessExpression) e).getObject(), name);
        if (e instanceof ArrayAccessExpression) return readsExpr(((ArrayAccessExpression) e).getArray(), name) || readsExpr(((ArrayAccessExpression) e).getIndex(), name);
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            return readsExpr(t.getCondition(), name) || readsExpr(t.getTrueExpression(), name) || readsExpr(t.getFalseExpression(), name);
        }
        if (e instanceof InstanceOfExpression) return readsExpr(((InstanceOfExpression) e).getExpression(), name);
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            if (readsExpr(m.getObject(), name)) return true;
            return readsArgs(m.getArguments(), name);
        }
        if (e instanceof StaticMethodInvocationExpression) return readsArgs(((StaticMethodInvocationExpression) e).getArguments(), name);
        if (e instanceof NewExpression) return readsArgs(((NewExpression) e).getArguments(), name);
        if (e instanceof NewArrayExpression) {
            if (readsArgs(((NewArrayExpression) e).getDimensionExpressions(), name)) return true;
            return readsArgs(((NewArrayExpression) e).getInitValues(), name);
        }
        if (e instanceof MethodReferenceExpression) return readsExpr(((MethodReferenceExpression) e).getObject(), name);
        return false;
    }

    private static boolean readsArgs(List<Expression> args, String name) {
        if (args == null) return false;
        for (Expression a : args) if (readsExpr(a, name)) return true;
        return false;
    }

    // --- condition helpers (copied from InstanceOfPatternReconstructor) ---

    private static InstanceOfExpression findInstanceOf(Expression e) {
        if (e instanceof InstanceOfExpression) return (InstanceOfExpression) e;
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            InstanceOfExpression l = findInstanceOf(b.getLeft());
            if (l != null) return l;
            return findInstanceOf(b.getRight());
        }
        if (e instanceof UnaryOperatorExpression) return findInstanceOf(((UnaryOperatorExpression) e).getExpression());
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
}
