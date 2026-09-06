/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * START_CHANGE: BUG-2026-0109-20260906-6 - Reconstruct a STATEMENT-form pattern switch.
 *
 * A pattern switch whose arms do not merge into a single value is left by the flow builder as the
 * raw dispatch javac emits:
 *
 * <pre>
 * Object var1 = o;
 * int var2 = 0;
 * switch (SwitchBootstraps.typeSwitch(var1, var2)) {
 *     case 0: Integer i = (Integer) var1; body; break;
 *     case 1: String s = (String) var1;  body; break;
 *     default: body;
 * }
 * </pre>
 *
 * which does not compile. Unlike the expression form, nothing is missing here: each arm's body is
 * present, so the switch can be rebuilt by mapping every case index to its bootstrap label and
 * folding the arm's leading {@code Type b = (Type) sel;} cast into a pattern label:
 *
 * <pre>
 * switch (o) {
 *     case Integer i: body; break;
 *     case String s:  body; break;
 *     default: body;
 * }
 * </pre>
 *
 * Only shapes that are fully understood are rewritten; anything else is left exactly as it was, so
 * the existing diagnostic still reports it rather than the output being silently changed.
 */
public final class PatternSwitchStatementReconstructor {

    private PatternSwitchStatementReconstructor() { }

    public static List<Statement> reconstruct(List<Statement> statements,
                                              Map<String, List<String>> labelsByKey) {
        if (statements == null || labelsByKey == null || labelsByKey.isEmpty()) return statements;
        walk(statements, labelsByKey);
        return statements;
    }

    private static void walk(List<Statement> stmts, Map<String, List<String>> labels) {
        if (stmts == null) return;
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof SwitchStatement) {
                SwitchStatement rebuilt = rebuild((SwitchStatement) s, labels);
                if (rebuilt != null) {
                    stmts.set(i, rebuilt);
                    // The `int idx = 0;` companion right before the switch is now dead.
                    int removed = dropDeadIndexDecl(stmts, i);
                    i -= removed;
                    continue;
                }
            }
            recurse(s, labels);
        }
    }

    private static void recurse(Statement s, Map<String, List<String>> labels) {
        if (s == null) return;
        if (s instanceof BlockStatement) walk(((BlockStatement) s).getStatements(), labels);
        else if (s instanceof IfStatement) recurse(((IfStatement) s).getThenBody(), labels);
        else if (s instanceof IfElseStatement) {
            recurse(((IfElseStatement) s).getThenBody(), labels);
            recurse(((IfElseStatement) s).getElseBody(), labels);
        }
        else if (s instanceof WhileStatement) recurse(((WhileStatement) s).getBody(), labels);
        else if (s instanceof DoWhileStatement) recurse(((DoWhileStatement) s).getBody(), labels);
        else if (s instanceof ForStatement) recurse(((ForStatement) s).getBody(), labels);
        else if (s instanceof ForEachStatement) recurse(((ForEachStatement) s).getBody(), labels);
        else if (s instanceof LabelStatement) recurse(((LabelStatement) s).getBody(), labels);
        else if (s instanceof SynchronizedStatement) recurse(((SynchronizedStatement) s).getBody(), labels);
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            recurse(t.getTryBody(), labels);
            for (int i = 0; i < t.getCatchClauses().size(); i++) recurse(t.getCatchClauses().get(i).body, labels);
            recurse(t.getFinallyBody(), labels);
        }
        else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) walk(c.getStatements(), labels);
        }
    }

    /** The `SwitchBootstraps.typeSwitch(sel, idx)` call driving this switch, or null. */
    private static StaticMethodInvocationExpression typeSwitchCall(SwitchStatement sw) {
        Expression sel = sw.getSelector();
        if (!(sel instanceof StaticMethodInvocationExpression)) return null;
        StaticMethodInvocationExpression c = (StaticMethodInvocationExpression) sel;
        if (!"java/lang/runtime/SwitchBootstraps".equals(c.getOwnerInternalName())) return null;
        if (!"typeSwitch".equals(c.getMethodName())) return null;
        if (c.getArguments() == null || c.getArguments().isEmpty()) return null;
        return c;
    }

    /**
     * Rebuild {@code sw} as a pattern switch, or return null when the shape is not fully
     * understood (in which case the caller leaves the original untouched).
     */
    private static SwitchStatement rebuild(SwitchStatement sw, Map<String, List<String>> labelsByKey) {
        StaticMethodInvocationExpression call = typeSwitchCall(sw);
        if (call == null) return null;
        List<String> labels = labelsByKey.get(call.getMethodName() + "_" + call.getLineNumber());
        if (labels == null || labels.isEmpty()) return null;

        Expression selector = call.getArguments().get(0);

        // A guard/restart scaffold -- an arm that writes the dispatch index and loops back to retry
        // the switch -- is NOT a plain statement switch: its arm values live on the operand stack
        // and are missing from these bodies. Rewriting it would hide that behind valid-looking
        // syntax AND silence the PATTERN_SWITCH_NOT_RECONSTRUCTED diagnostic, turning a reported
        // defect back into a silent one. Leave those exactly as they are.
        String indexName = null;
        if (call.getArguments().size() > 1
                && call.getArguments().get(1) instanceof LocalVariableExpression) {
            indexName = ((LocalVariableExpression) call.getArguments().get(1)).getName();
        }
        if (indexName != null) {
            for (int ci = 0; ci < sw.getCases().size(); ci++) {
                List<Statement> b = sw.getCases().get(ci).getStatements();
                for (int si = 0; b != null && si < b.size(); si++) {
                    if (assignsName(b.get(si), indexName)) return null;
                }
            }
        }

        List<SwitchStatement.SwitchCase> out = new ArrayList<SwitchStatement.SwitchCase>();

        for (int ci = 0; ci < sw.getCases().size(); ci++) {
            SwitchStatement.SwitchCase c = sw.getCases().get(ci);
            List<Statement> body = new ArrayList<Statement>(c.getStatements());

            if (c.isDefault()) {
                out.add(new SwitchStatement.SwitchCase(null, body));
                continue;
            }
            // Every label of a typeSwitch case is an int index into the bootstrap label list;
            // -1 is `case null`. Anything else means this is not the shape we understand.
            List<Type> types = new ArrayList<Type>();
            List<String> binds = new ArrayList<String>();
            boolean nullLabel = false;
            for (int li = 0; li < c.getLabels().size(); li++) {
                Expression lab = c.getLabels().get(li);
                if (!(lab instanceof IntegerConstantExpression)) return null;
                int idx = ((IntegerConstantExpression) lab).getValue();
                if (idx == -1) { nullLabel = true; continue; }
                if (idx < 0 || idx >= labels.size()) return null;
                String label = labels.get(idx);
                if (label == null || label.length() == 0) return null;
                // Only a plain internal class name names a type pattern here. Constant labels
                // (strings, numbers, enum constants, `true`) belong to a constant switch, which
                // this pass does not rewrite.
                char c0 = label.charAt(0);
                if (c0 == '"' || c0 == '-' || c0 == '[' || (c0 >= '0' && c0 <= '9')) return null;
                if (label.indexOf('.') >= 0 || "true".equals(label) || "false".equals(label)) return null;
                types.add(new ObjectType(label));
                binds.add(null); // filled in from the arm's own cast below
            }
            if (types.isEmpty() && !nullLabel) return null;

            // Fold a leading `Type b = (Type) sel;` into the pattern's binding name.
            if (types.size() == 1 && !body.isEmpty() && body.get(0) instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) body.get(0);
                if (isCastOfSelector(vds.getInitializer(), selector)) {
                    binds.set(0, vds.getName());
                    if (vds.getType() != null) types.set(0, vds.getType());
                    body.remove(0);
                }
            }
            SwitchStatement.SwitchCase nc = new SwitchStatement.SwitchCase(null, body);
            if (!types.isEmpty()) nc.setPatterns(types, binds);
            nc.setNullLabel(nullLabel);
            out.add(nc);
        }
        if (out.isEmpty()) return null;
        return new SwitchStatement(sw.getLineNumber(), selector, out, sw.isArrowStyle());
    }


    /** True when the subtree assigns to the local {@code name} (the dispatch-index restart). */
    private static boolean assignsName(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression) {
                Expression l = ((AssignmentExpression) e).getLeft();
                if (l instanceof LocalVariableExpression
                        && name.equals(((LocalVariableExpression) l).getName())) return true;
            }
            return false;
        }
        if (s instanceof BlockStatement) {
            List<Statement> b = ((BlockStatement) s).getStatements();
            for (int i = 0; b != null && i < b.size(); i++) if (assignsName(b.get(i), name)) return true;
            return false;
        }
        if (s instanceof IfStatement) return assignsName(((IfStatement) s).getThenBody(), name);
        if (s instanceof IfElseStatement) {
            return assignsName(((IfElseStatement) s).getThenBody(), name)
                || assignsName(((IfElseStatement) s).getElseBody(), name);
        }
        if (s instanceof WhileStatement) return assignsName(((WhileStatement) s).getBody(), name);
        if (s instanceof DoWhileStatement) return assignsName(((DoWhileStatement) s).getBody(), name);
        if (s instanceof ForStatement) return assignsName(((ForStatement) s).getBody(), name);
        if (s instanceof ForEachStatement) return assignsName(((ForEachStatement) s).getBody(), name);
        if (s instanceof LabelStatement) return assignsName(((LabelStatement) s).getBody(), name);
        if (s instanceof SynchronizedStatement) return assignsName(((SynchronizedStatement) s).getBody(), name);
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (assignsName(t.getTryBody(), name)) return true;
            for (int i = 0; i < t.getCatchClauses().size(); i++) {
                if (assignsName(t.getCatchClauses().get(i).body, name)) return true;
            }
            return assignsName(t.getFinallyBody(), name);
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            for (int i = 0; i < sw.getCases().size(); i++) {
                List<Statement> b = sw.getCases().get(i).getStatements();
                for (int j = 0; b != null && j < b.size(); j++) if (assignsName(b.get(j), name)) return true;
            }
        }
        return false;
    }

    /** True for `(Type) sel` where sel is the switch selector. */
    private static boolean isCastOfSelector(Expression init, Expression selector) {
        if (!(init instanceof CastExpression)) return false;
        Expression inner = ((CastExpression) init).getExpression();
        return sameVariable(inner, selector);
    }

    private static boolean sameVariable(Expression a, Expression b) {
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            String na = ((LocalVariableExpression) a).getName();
            String nb = ((LocalVariableExpression) b).getName();
            return na != null && na.equals(nb);
        }
        return false;
    }

    /**
     * Remove the `int idx = 0;` declaration that fed the dispatch, when it sits immediately before
     * the switch and nothing else reads it. Returns how many statements were removed.
     */
    private static int dropDeadIndexDecl(List<Statement> stmts, int switchIdx) {
        if (switchIdx <= 0) return 0;
        Statement prev = stmts.get(switchIdx - 1);
        if (!(prev instanceof VariableDeclarationStatement)) return 0;
        VariableDeclarationStatement vds = (VariableDeclarationStatement) prev;
        Type t = vds.getType();
        if (t == null || !"I".equals(t.getDescriptor())) return 0;
        if (!(vds.getInitializer() instanceof IntegerConstantExpression)) return 0;
        if (((IntegerConstantExpression) vds.getInitializer()).getValue() != 0) return 0;
        String name = vds.getName();
        if (name == null) return 0;
        for (int i = 0; i < stmts.size(); i++) {
            if (i == switchIdx - 1) continue;
            if (readsName(stmts.get(i), name)) return 0;
        }
        stmts.remove(switchIdx - 1);
        return 1;
    }

    /** Conservative textual containment check for a local's name inside a statement subtree. */
    private static boolean readsName(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            if (exprReads(sw.getSelector(), name)) return true;
            for (int i = 0; i < sw.getCases().size(); i++) {
                List<Statement> body = sw.getCases().get(i).getStatements();
                for (int j = 0; body != null && j < body.size(); j++) {
                    if (readsName(body.get(j), name)) return true;
                }
            }
            return false;
        }
        if (s instanceof ExpressionStatement) return exprReads(((ExpressionStatement) s).getExpression(), name);
        if (s instanceof VariableDeclarationStatement) return exprReads(((VariableDeclarationStatement) s).getInitializer(), name);
        if (s instanceof ReturnStatement) return exprReads(((ReturnStatement) s).getExpression(), name);
        if (s instanceof ThrowStatement) return exprReads(((ThrowStatement) s).getExpression(), name);
        if (s instanceof BlockStatement) {
            List<Statement> b = ((BlockStatement) s).getStatements();
            for (int i = 0; b != null && i < b.size(); i++) if (readsName(b.get(i), name)) return true;
            return false;
        }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            return exprReads(is.getCondition(), name) || readsName(is.getThenBody(), name);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ie = (IfElseStatement) s;
            return exprReads(ie.getCondition(), name) || readsName(ie.getThenBody(), name)
                || readsName(ie.getElseBody(), name);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            return exprReads(w.getCondition(), name) || readsName(w.getBody(), name);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            return exprReads(d.getCondition(), name) || readsName(d.getBody(), name);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            return readsName(f.getInit(), name) || exprReads(f.getCondition(), name)
                || readsName(f.getUpdate(), name) || readsName(f.getBody(), name);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            return exprReads(f.getIterable(), name) || readsName(f.getBody(), name);
        }
        if (s instanceof LabelStatement) return readsName(((LabelStatement) s).getBody(), name);
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            return exprReads(sy.getMonitor(), name) || readsName(sy.getBody(), name);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (readsName(t.getTryBody(), name)) return true;
            for (int i = 0; i < t.getCatchClauses().size(); i++) {
                if (readsName(t.getCatchClauses().get(i).body, name)) return true;
            }
            return readsName(t.getFinallyBody(), name);
        }
        return false;
    }

    private static boolean exprReads(Expression e, String name) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) return name.equals(((LocalVariableExpression) e).getName());
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            return exprReads(b.getLeft(), name) || exprReads(b.getRight(), name);
        }
        if (e instanceof UnaryOperatorExpression) return exprReads(((UnaryOperatorExpression) e).getExpression(), name);
        if (e instanceof CastExpression) return exprReads(((CastExpression) e).getExpression(), name);
        if (e instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) e;
            return exprReads(a.getLeft(), name) || exprReads(a.getRight(), name);
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            if (exprReads(m.getObject(), name)) return true;
            List<Expression> as = m.getArguments();
            for (int i = 0; as != null && i < as.size(); i++) if (exprReads(as.get(i), name)) return true;
            return false;
        }
        if (e instanceof StaticMethodInvocationExpression) {
            List<Expression> as = ((StaticMethodInvocationExpression) e).getArguments();
            for (int i = 0; as != null && i < as.size(); i++) if (exprReads(as.get(i), name)) return true;
            return false;
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression a = (ArrayAccessExpression) e;
            return exprReads(a.getArray(), name) || exprReads(a.getIndex(), name);
        }
        if (e instanceof InstanceOfExpression) return exprReads(((InstanceOfExpression) e).getExpression(), name);
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            return exprReads(t.getCondition(), name) || exprReads(t.getTrueExpression(), name)
                || exprReads(t.getFalseExpression(), name);
        }
        return false;
    }
}
// END_CHANGE: BUG-2026-0109-6
