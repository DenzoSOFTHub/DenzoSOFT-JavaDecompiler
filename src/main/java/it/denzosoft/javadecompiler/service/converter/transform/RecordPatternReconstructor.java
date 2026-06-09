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
 * Cleans up the Java 21 record-pattern desugar (BUG-2026-0069 / F04 record patterns).
 *
 * The compiler wraps record-component extraction in
 * {@code try { ... } catch (Throwable t) { throw new MatchException(...); }} (a record accessor that
 * throws is turned into a {@code MatchException}) and guards each successful component binding with an
 * always-true {@code if (1 != 0) { ... }}. Both are synthetic scaffolding that, when emitted literally,
 * does not compile (the catch references a try-scoped binding; the always-true ifs add dead nesting).
 *
 * This pass performs the safe structural cleanups:
 * <ol>
 *   <li>strip a {@code try/catch(Throwable)->MatchException} wrapper down to its try body;</li>
 *   <li>flatten always-true {@code if}s.</li>
 * </ol>
 * (Folding the component extraction into a deconstruction pattern is a further step not done here.)
 */
public final class RecordPatternReconstructor {

    private RecordPatternReconstructor() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        List<Statement> out = new ArrayList<Statement>();
        boolean changed = false;
        for (Statement s : statements) {
            Statement v = visit(s);
            // Strip a MatchException try -> splice its body in place.
            if (v instanceof TryCatchStatement && isMatchExceptionTry((TryCatchStatement) v)) {
                Statement body = ((TryCatchStatement) v).getTryBody();
                List<Statement> inner = body instanceof BlockStatement
                    ? ((BlockStatement) body).getStatements() : singleton(body);
                out.addAll(reconstruct(inner));
                changed = true;
                continue;
            }
            // Flatten an always-true if.
            if (v instanceof IfStatement && isAlwaysTrue(((IfStatement) v).getCondition())) {
                Statement then = ((IfStatement) v).getThenBody();
                List<Statement> inner = then instanceof BlockStatement
                    ? ((BlockStatement) then).getStatements() : singleton(then);
                out.addAll(reconstruct(inner));
                changed = true;
                continue;
            }
            if (v instanceof IfElseStatement && isAlwaysTrue(((IfElseStatement) v).getCondition())) {
                Statement then = ((IfElseStatement) v).getThenBody();
                List<Statement> inner = then instanceof BlockStatement
                    ? ((BlockStatement) then).getStatements() : singleton(then);
                out.addAll(reconstruct(inner));
                changed = true;
                continue;
            }
            out.add(v);
            if (v != s) changed = true;
        }
        return changed ? out : statements;
    }

    private static List<Statement> singleton(Statement s) {
        List<Statement> l = new ArrayList<Statement>(1);
        l.add(s);
        return l;
    }

    private static Statement visit(Statement s) {
        if (s == null) return null;
        if (s instanceof BlockStatement) {
            return new BlockStatement(s.getLineNumber(), reconstruct(((BlockStatement) s).getStatements()));
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            return new IfStatement(i.getLineNumber(), i.getCondition(), visit(i.getThenBody()));
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            return new IfElseStatement(i.getLineNumber(), i.getCondition(), visit(i.getThenBody()), visit(i.getElseBody()));
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            return new WhileStatement(w.getLineNumber(), w.getCondition(), visit(w.getBody()));
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            return new DoWhileStatement(d.getLineNumber(), d.getCondition(), visit(d.getBody()));
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            return new ForStatement(f.getLineNumber(), f.getInit(), f.getCondition(), f.getUpdate(), visit(f.getBody()));
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            return new ForEachStatement(f.getLineNumber(), f.getVariableType(), f.getVariableName(), f.getIterable(), visit(f.getBody()));
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            return new LabelStatement(l.getLineNumber(), l.getLabel(), visit(l.getBody()));
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            return new SynchronizedStatement(sy.getLineNumber(), sy.getMonitor(), visit(sy.getBody()));
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, visit(cc.body)));
            }
            Statement fin = t.hasFinally() ? visit(t.getFinallyBody()) : null;
            return new TryCatchStatement(t.getLineNumber(), visit(t.getTryBody()), catches, fin, t.getResources());
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                cases.add(new SwitchStatement.SwitchCase(c.getLabels(), reconstruct(c.getStatements())));
            }
            return new SwitchStatement(sw.getLineNumber(), sw.getSelector(), cases, sw.isArrowStyle());
        }
        return s;
    }

    private static boolean isMatchExceptionTry(TryCatchStatement t) {
        if (t.getResources() != null || t.hasFinally()) return false;
        if (t.getCatchClauses() == null || t.getCatchClauses().size() != 1) return false;
        TryCatchStatement.CatchClause cc = t.getCatchClauses().get(0);
        if (cc.exceptionTypes == null || cc.exceptionTypes.size() != 1) return false;
        String d = cc.exceptionTypes.get(0).getDescriptor();
        if (d == null || !d.contains("Throwable")) return false;
        // Catch body throws a MatchException (possibly after a primaryExc assignment).
        List<Statement> body = cc.body instanceof BlockStatement
            ? ((BlockStatement) cc.body).getStatements() : null;
        if (body == null || body.isEmpty()) return false;
        Statement last = body.get(body.size() - 1);
        if (!(last instanceof ThrowStatement)) return false;
        Expression e = ((ThrowStatement) last).getExpression();
        return e instanceof NewExpression
            && ((NewExpression) e).getInternalTypeName() != null
            && ((NewExpression) e).getInternalTypeName().endsWith("MatchException");
    }

    private static boolean isAlwaysTrue(Expression e) {
        if (e instanceof BooleanExpression) return ((BooleanExpression) e).getValue();
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            Integer l = intOf(b.getLeft()), r = intOf(b.getRight());
            if (l != null && r != null) {
                if ("!=".equals(b.getOperator())) return l.intValue() != r.intValue();
                if ("==".equals(b.getOperator())) return l.intValue() == r.intValue();
            }
        }
        return false;
    }

    private static Integer intOf(Expression e) {
        return e instanceof IntegerConstantExpression
            ? Integer.valueOf(((IntegerConstantExpression) e).getValue()) : null;
    }
}
