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
 * Hoists a local variable that is DECLARED inside a switch case but read in another case or after the switch
 * (BUG-2026-0080). The bytecode reuses one slot across all arms, so the decompiler emits
 * {@code case A: String r = "x"; ... case B: r = "y"; ... return r;} where {@code r} is scoped to case A and
 * does not resolve in case B / after the switch. The fix declares {@code String r;} before the switch and turns
 * the in-case declaration into a plain assignment.
 */
public final class SwitchVarHoister {

    private SwitchVarHoister() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        for (int i = 0; i < statements.size(); i++) {
            recurse(statements.get(i));
            if (statements.get(i) instanceof SwitchStatement) {
                i += hoist(statements, i);
            }
        }
        return statements;
    }

    private static void recurse(Statement s) {
        if (s instanceof BlockStatement) reconstruct(((BlockStatement) s).getStatements());
        else if (s instanceof IfStatement) recurse(((IfStatement) s).getThenBody());
        else if (s instanceof IfElseStatement) { recurse(((IfElseStatement) s).getThenBody()); recurse(((IfElseStatement) s).getElseBody()); }
        else if (s instanceof WhileStatement) recurse(((WhileStatement) s).getBody());
        else if (s instanceof DoWhileStatement) recurse(((DoWhileStatement) s).getBody());
        else if (s instanceof ForStatement) recurse(((ForStatement) s).getBody());
        else if (s instanceof ForEachStatement) recurse(((ForEachStatement) s).getBody());
        else if (s instanceof LabelStatement) recurse(((LabelStatement) s).getBody());
        else if (s instanceof SynchronizedStatement) recurse(((SynchronizedStatement) s).getBody());
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            recurse(t.getTryBody());
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) recurse(cc.body);
            if (t.getFinallyBody() != null) recurse(t.getFinallyBody());
        } else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) reconstruct(c.getStatements());
        }
    }

    /** Hoist case-scoped vars of the switch at index {@code swIdx}. Returns how many decls were inserted before it. */
    private static int hoist(List<Statement> outer, int swIdx) {
        SwitchStatement sw = (SwitchStatement) outer.get(swIdx);
        List<SwitchStatement.SwitchCase> cases = sw.getCases();
        int inserted = 0;
        // Scan each case for a leading-or-any top-level `Type v = expr;` declaration.
        for (int ci = 0; ci < cases.size(); ci++) {
            List<Statement> body = cases.get(ci).getStatements();
            for (int si = 0; si < body.size(); si++) {
                if (!(body.get(si) instanceof VariableDeclarationStatement)) continue;
                VariableDeclarationStatement vds = (VariableDeclarationStatement) body.get(si);
                if (!vds.hasInitializer() || vds.getType() == null) continue;
                String name = vds.getName();
                if (usedOutsideCase(cases, ci, name) || usedAfter(outer, swIdx, name)) {
                    // Hoist: `Type name;` before the switch, and `name = expr;` in place.
                    outer.add(swIdx, new VariableDeclarationStatement(vds.getLineNumber(), vds.getType(), name,
                        null, false, false));
                    inserted++;
                    swIdx++; // the switch shifted right
                    AssignmentExpression assign = new AssignmentExpression(vds.getLineNumber(),
                        vds.getType(),
                        new LocalVariableExpression(vds.getLineNumber(), vds.getType(), name, -1),
                        "=", vds.getInitializer());
                    body.set(si, new ExpressionStatement(assign));
                }
            }
        }
        return inserted;
    }

    private static boolean usedOutsideCase(List<SwitchStatement.SwitchCase> cases, int declCase, String name) {
        for (int ci = 0; ci < cases.size(); ci++) {
            if (ci == declCase) continue;
            for (Statement s : cases.get(ci).getStatements()) {
                if (ForLoopDetector.nameUsedInStatement(s, name)) return true;
            }
        }
        return false;
    }

    private static boolean usedAfter(List<Statement> outer, int swIdx, String name) {
        for (int k = swIdx + 1; k < outer.size(); k++) {
            if (ForLoopDetector.nameUsedInStatement(outer.get(k), name)) return true;
        }
        return false;
    }
}
