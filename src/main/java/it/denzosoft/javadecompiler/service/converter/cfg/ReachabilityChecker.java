/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Java Language Specification 14.22 ("Unreachable Statements") over a structured body
 * (BUG-2026-0151).
 *
 * <p>It reports exactly the structural defects javac rejects: a statement that cannot be
 * reached, a non-void method whose body can complete normally ("missing return statement"), and
 * a {@code break}/{@code continue} with no valid target. The structured-flow builder can produce
 * all three when it loses or misplaces part of a method, so the converter uses this check to
 * decide whether a body needs the guaranteed {@link SafeStructurer} instead.</p>
 */
public final class ReachabilityChecker {

    private final List<String> problems = new ArrayList<String>();
    /** Enclosing break/continue targets, innermost first. */
    private final LinkedList<Target> targets = new LinkedList<Target>();
    /** Statements that are the target of at least one reachable break. */
    private final Map<Statement, Boolean> broken = new IdentityHashMap<Statement, Boolean>();
    private final Map<Statement, Boolean> continued = new IdentityHashMap<Statement, Boolean>();

    private static final class Target {
        final Statement stmt;
        final String label;     // null for an unlabeled loop/switch
        final boolean loop;
        final boolean breakable; // loop or switch (unlabeled break allowed)
        Target(Statement stmt, String label, boolean loop, boolean breakable) {
            this.stmt = stmt; this.label = label; this.loop = loop; this.breakable = breakable;
        }
    }

    private ReachabilityChecker() { }

    /** Returns the list of javac-level structural problems of the body (empty when valid). */
    public static List<String> check(List<Statement> body, boolean nonVoid) {
        ReachabilityChecker c = new ReachabilityChecker();
        boolean completes = c.list(body, true);
        if (nonVoid && completes) c.problems.add("missing return statement");
        return c.problems;
    }

    private boolean list(List<Statement> stmts, boolean reachable) {
        if (stmts == null) return reachable;
        boolean r = reachable;
        for (Statement s : stmts) {
            if (s instanceof CommentStatement) continue;
            if (!r) {
                problems.add("unreachable statement");
                return false;
            }
            r = stmt(s, true);
        }
        return r;
    }

    private boolean stmt(Statement s, boolean reachable) {
        if (s == null) return reachable;
        if (s instanceof BlockStatement) return list(((BlockStatement) s).getStatements(), reachable);
        if (s instanceof ReturnStatement || s instanceof ThrowStatement || s instanceof YieldStatement) return false;
        if (s instanceof BreakStatement) {
            Target t = find(((BreakStatement) s).getLabel(), false);
            if (t == null) problems.add("break outside switch or loop");
            else broken.put(t.stmt, Boolean.TRUE);
            return false;
        }
        if (s instanceof ContinueStatement) {
            Target t = find(((ContinueStatement) s).getLabel(), true);
            if (t == null) problems.add("continue outside of loop");
            else continued.put(t.stmt, Boolean.TRUE);
            return false;
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement body = l.getBody();
            boolean isLoop = body instanceof WhileStatement || body instanceof DoWhileStatement
                || body instanceof ForStatement || body instanceof ForEachStatement;
            if (isLoop) {
                // the label names the loop itself: `continue L` / `break L` target the loop
                return loop(body, l.getLabel());
            }
            targets.push(new Target(l, l.getLabel(), false, false));
            boolean c;
            try {
                c = stmt(body, true);
            } finally {
                targets.pop();
            }
            return c || broken.containsKey(l);
        }
        if (s instanceof IfStatement) {
            stmt(((IfStatement) s).getThenBody(), true);
            return true;
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            boolean a = stmt(i.getThenBody(), true);
            boolean b = stmt(i.getElseBody(), true);
            return a || b;
        }
        if (s instanceof WhileStatement || s instanceof DoWhileStatement
                || s instanceof ForStatement || s instanceof ForEachStatement) {
            return loop(s, null);
        }
        if (s instanceof SynchronizedStatement) return stmt(((SynchronizedStatement) s).getBody(), true);
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            boolean c = stmt(t.getTryBody(), true);
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                c |= stmt(cc.body, true);
            }
            if (t.hasFinally()) {
                boolean f = stmt(t.getFinallyBody(), true);
                if (!f) return false;
            }
            return c;
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            if (sw.isArrowStyle()) {
                // arrow arms never fall through; conservatively: completes
                targets.push(new Target(sw, null, false, true));
                try {
                    for (SwitchStatement.SwitchCase c : sw.getCases()) list(c.getStatements(), true);
                } finally {
                    targets.pop();
                }
                return true;
            }
            targets.push(new Target(sw, null, false, true));
            boolean hasDefault = false;
            boolean last = true;
            try {
                // each label group is reachable; fall-through carries completion into the next
                for (SwitchStatement.SwitchCase c : sw.getCases()) {
                    if (c.isDefault()) hasDefault = true;
                    last = list(c.getStatements(), true);
                }
            } finally {
                targets.pop();
            }
            return !hasDefault || last || broken.containsKey(sw) || sw.getCases().isEmpty();
        }
        return reachable; // declarations, expression statements, assert, comments
    }

    private boolean loop(Statement s, String label) {
        targets.push(new Target(s, label, true, true));
        boolean bodyCompletes;
        Expression cond = null;
        try {
            if (s instanceof WhileStatement) {
                cond = ((WhileStatement) s).getCondition();
                bodyCompletes = stmt(((WhileStatement) s).getBody(), !isConstant(cond, false));
            } else if (s instanceof DoWhileStatement) {
                cond = ((DoWhileStatement) s).getCondition();
                bodyCompletes = stmt(((DoWhileStatement) s).getBody(), true);
            } else if (s instanceof ForStatement) {
                cond = ((ForStatement) s).getCondition();
                bodyCompletes = stmt(((ForStatement) s).getBody(), cond == null || !isConstant(cond, false));
                if (cond == null) cond = BooleanExpression.TRUE;
            } else {
                stmt(((ForEachStatement) s).getBody(), true);
                return true;
            }
        } finally {
            targets.pop();
        }
        boolean brk = broken.containsKey(s);
        if (s instanceof DoWhileStatement) {
            boolean condReached = bodyCompletes || continued.containsKey(s);
            return (condReached && !isConstant(cond, true)) || brk;
        }
        return !isConstant(cond, true) || brk;
    }

    private Target find(String label, boolean forContinue) {
        for (Target t : targets) {
            if (label != null) {
                if (label.equals(t.label)) return forContinue && !t.loop ? null : t;
            } else if (forContinue ? t.loop : t.breakable) {
                return t;
            }
        }
        return null;
    }

    /** JLS 15.29 for the shapes the decompiler produces: literals and int/boolean constant comparisons. */
    static boolean isConstant(Expression e, boolean value) {
        Boolean v = constantValue(e);
        return v != null && v.booleanValue() == value;
    }

    private static Boolean constantValue(Expression e) {
        if (e instanceof BooleanExpression) return Boolean.valueOf(((BooleanExpression) e).getValue());
        if (e instanceof UnaryOperatorExpression && "!".equals(((UnaryOperatorExpression) e).getOperator())) {
            Boolean v = constantValue(((UnaryOperatorExpression) e).getExpression());
            return v == null ? null : Boolean.valueOf(!v.booleanValue());
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            if (b.getLeft() instanceof IntegerConstantExpression && b.getRight() instanceof IntegerConstantExpression) {
                int l = ((IntegerConstantExpression) b.getLeft()).getValue();
                int r = ((IntegerConstantExpression) b.getRight()).getValue();
                String op = b.getOperator();
                if ("==".equals(op)) return Boolean.valueOf(l == r);
                if ("!=".equals(op)) return Boolean.valueOf(l != r);
                if ("<".equals(op)) return Boolean.valueOf(l < r);
                if ("<=".equals(op)) return Boolean.valueOf(l <= r);
                if (">".equals(op)) return Boolean.valueOf(l > r);
                if (">=".equals(op)) return Boolean.valueOf(l >= r);
            }
            if ("&&".equals(b.getOperator()) || "||".equals(b.getOperator())) {
                Boolean l = constantValue(b.getLeft());
                Boolean r = constantValue(b.getRight());
                if (l == null || r == null) return null;
                return "&&".equals(b.getOperator()) ? Boolean.valueOf(l.booleanValue() && r.booleanValue())
                    : Boolean.valueOf(l.booleanValue() || r.booleanValue());
            }
        }
        return null;
    }
}
