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
        // START_CHANGE: BUG-2026-0067-20260610-44 - Track hoisted names: a slot re-declared in a second
        // case must not produce a duplicate hoisted declaration.
        java.util.Set<String> hoisted = new java.util.HashSet<String>();
        // END_CHANGE: BUG-2026-0067-44
        // Scan each case for a leading-or-any top-level `Type v = expr;` declaration.
        for (int ci = 0; ci < cases.size(); ci++) {
            List<Statement> body = cases.get(ci).getStatements();
            for (int si = 0; si < body.size(); si++) {
                if (!(body.get(si) instanceof VariableDeclarationStatement)) continue;
                VariableDeclarationStatement vds = (VariableDeclarationStatement) body.get(si);
                if (!vds.hasInitializer() || vds.getType() == null) continue;
                String name = vds.getName();
                // START_CHANGE: BUG-2026-0067-20260610-45 - Already hoisted: this is a slot reuse of the
                // same name in another case. If type-compatible just demote to an assignment; otherwise
                // rename this case's occurrences (separate variable that happened to share the slot).
                if (hoisted.contains(name)) {
                    if (typeAssignable(vds.getType(), initType(vds.getInitializer()))) {
                        body.set(si, new ExpressionStatement(new AssignmentExpression(vds.getLineNumber(),
                            vds.getType(),
                            new LocalVariableExpression(vds.getLineNumber(), vds.getType(), name, -1),
                            "=", vds.getInitializer())));
                    } else {
                        renameInCase(body, name, name + "_c" + ci);
                    }
                    continue;
                }
                // END_CHANGE: BUG-2026-0067-45
                if (usedOutsideCase(cases, ci, name) || usedAfter(outer, swIdx, name)) {
                    // START_CHANGE: BUG-2026-0067-20260610-46 - Verify type compatibility of cross-case
                    // writes before sharing one declaration. A case that overwrites the slot with an
                    // incompatible type (e.g. `var7 = (Rect) var2;` against `double var7`) is a slot
                    // REUSE, not a shared variable: rename it in that case (declaring it with the written
                    // type). If the conflicting case reads the name before its incompatible write, the
                    // shapes are entangled — leave everything untouched (safe no-op).
                    List<int[]> conflicts = new ArrayList<int[]>(); // {caseIdx}
                    boolean unsafe = false;
                    for (int cj = 0; cj < cases.size() && !unsafe; cj++) {
                        if (cj == ci) continue;
                        int kind = caseWriteConflict(cases.get(cj).getStatements(), name, vds.getType());
                        if (kind == CONFLICT_UNSAFE) unsafe = true;
                        else if (kind == CONFLICT_RENAME) conflicts.add(new int[]{ cj });
                    }
                    if (unsafe) continue;
                    for (int k = 0; k < conflicts.size(); k++) {
                        int cj = conflicts.get(k)[0];
                        renameInCase(cases.get(cj).getStatements(), name, name + "_c" + cj);
                    }
                    // The conflicting uses are renamed away — re-check whether a hoist is still needed.
                    if (!usedOutsideCase(cases, ci, name) && !usedAfter(outer, swIdx, name)) continue;
                    // END_CHANGE: BUG-2026-0067-46
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
                    // START_CHANGE: BUG-2026-0067-20260610-47 - Remember the hoisted name (see above).
                    hoisted.add(name);
                    // END_CHANGE: BUG-2026-0067-47
                }
            }
        }
        return inserted;
    }

    // START_CHANGE: BUG-2026-0067-20260610-48 - Cross-case write compatibility + slot-reuse rename.
    private static final int CONFLICT_NONE = 0;
    private static final int CONFLICT_RENAME = 1;
    private static final int CONFLICT_UNSAFE = 2;

    /**
     * Scans one case for writes to {@code name} that are incompatible with {@code declared}.
     * Returns NONE (no conflicting write), RENAME (first touch is an incompatible top-level plain
     * write — the case can be safely renamed) or UNSAFE (entangled: read happens first, or the
     * incompatible write is not a top-level plain assignment).
     */
    private static int caseWriteConflict(List<Statement> body, String name, it.denzosoft.javadecompiler.model.javasyntax.type.Type declared) {
        for (int i = 0; i < body.size(); i++) {
            Statement s = body.get(i);
            if (s instanceof VariableDeclarationStatement
                    && name.equals(((VariableDeclarationStatement) s).getName())) {
                return CONFLICT_NONE; // re-declaration: handled by the `hoisted` path
            }
            if (s instanceof ExpressionStatement
                    && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
                if (ae.getLeft() instanceof LocalVariableExpression
                        && name.equals(((LocalVariableExpression) ae.getLeft()).getName())
                        && "=".equals(ae.getOperator())) {
                    if (typeAssignable(declared, initType(ae.getRight()))) return CONFLICT_NONE;
                    // Incompatible plain write: renameable only if nothing read the name before it.
                    for (int p = 0; p < i; p++) {
                        if (ForLoopDetector.nameUsedInStatement(body.get(p), name)) return CONFLICT_UNSAFE;
                    }
                    return exprMentions(ae.getRight(), name) ? CONFLICT_UNSAFE : CONFLICT_RENAME;
                }
            }
            if (ForLoopDetector.nameUsedInStatement(s, name)) {
                // The name is used (read, or written in a nested position) before any decidable
                // top-level write — treat any later incompatible write as entangled.
                for (int j = i; j < body.size(); j++) {
                    if (hasIncompatibleWrite(body.get(j), name, declared)) return CONFLICT_UNSAFE;
                }
                return CONFLICT_NONE;
            }
        }
        return CONFLICT_NONE;
    }

    /** Any TOP-LEVEL plain assignment `name = expr` whose expr type is incompatible with declared. */
    private static boolean hasIncompatibleWrite(Statement s, String name, it.denzosoft.javadecompiler.model.javasyntax.type.Type declared) {
        if (s instanceof ExpressionStatement
                && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            return ae.getLeft() instanceof LocalVariableExpression
                && name.equals(((LocalVariableExpression) ae.getLeft()).getName())
                && "=".equals(ae.getOperator())
                && !typeAssignable(declared, initType(ae.getRight()));
        }
        if (s instanceof BlockStatement) {
            for (Statement c : ((BlockStatement) s).getStatements()) {
                if (hasIncompatibleWrite(c, name, declared)) return true;
            }
        }
        if (s instanceof IfStatement) return hasIncompatibleWrite(((IfStatement) s).getThenBody(), name, declared);
        if (s instanceof IfElseStatement) {
            return hasIncompatibleWrite(((IfElseStatement) s).getThenBody(), name, declared)
                || hasIncompatibleWrite(((IfElseStatement) s).getElseBody(), name, declared);
        }
        if (s instanceof WhileStatement) return hasIncompatibleWrite(((WhileStatement) s).getBody(), name, declared);
        if (s instanceof DoWhileStatement) return hasIncompatibleWrite(((DoWhileStatement) s).getBody(), name, declared);
        if (s instanceof ForStatement) return hasIncompatibleWrite(((ForStatement) s).getBody(), name, declared);
        if (s instanceof ForEachStatement) return hasIncompatibleWrite(((ForEachStatement) s).getBody(), name, declared);
        return false;
    }

    /** Whether the expression mentions the local {@code name} anywhere. */
    private static boolean exprMentions(Expression e, String name) {
        if (e == null) return false;
        if (e instanceof LocalVariableExpression) return name.equals(((LocalVariableExpression) e).getName());
        if (e instanceof BinaryOperatorExpression) {
            return exprMentions(((BinaryOperatorExpression) e).getLeft(), name)
                || exprMentions(((BinaryOperatorExpression) e).getRight(), name);
        }
        if (e instanceof UnaryOperatorExpression) return exprMentions(((UnaryOperatorExpression) e).getExpression(), name);
        if (e instanceof CastExpression) return exprMentions(((CastExpression) e).getExpression(), name);
        if (e instanceof AssignmentExpression) {
            return exprMentions(((AssignmentExpression) e).getLeft(), name)
                || exprMentions(((AssignmentExpression) e).getRight(), name);
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            return exprMentions(t.getCondition(), name) || exprMentions(t.getTrueExpression(), name)
                || exprMentions(t.getFalseExpression(), name);
        }
        if (e instanceof InstanceOfExpression) return exprMentions(((InstanceOfExpression) e).getExpression(), name);
        if (e instanceof FieldAccessExpression) return exprMentions(((FieldAccessExpression) e).getObject(), name);
        if (e instanceof ArrayAccessExpression) {
            return exprMentions(((ArrayAccessExpression) e).getArray(), name)
                || exprMentions(((ArrayAccessExpression) e).getIndex(), name);
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression m = (MethodInvocationExpression) e;
            if (exprMentions(m.getObject(), name)) return true;
            return argsMention(m.getArguments(), name);
        }
        if (e instanceof StaticMethodInvocationExpression) return argsMention(((StaticMethodInvocationExpression) e).getArguments(), name);
        if (e instanceof NewExpression) return argsMention(((NewExpression) e).getArguments(), name);
        if (e instanceof NewArrayExpression) {
            return argsMention(((NewArrayExpression) e).getDimensionExpressions(), name)
                || argsMention(((NewArrayExpression) e).getInitValues(), name);
        }
        return false;
    }

    private static boolean argsMention(List<Expression> args, String name) {
        if (args == null) return false;
        for (Expression a : args) if (exprMentions(a, name)) return true;
        return false;
    }

    /** Rename every occurrence of {@code from} (reads, writes and declarations) within the case body. */
    private static void renameInCase(List<Statement> body, String from, String to) {
        final String f = from;
        final String t = to;
        AstLocalRewriter renamer = new AstLocalRewriter() {
            @Override protected Expression onLocal(LocalVariableExpression lv) {
                if (f.equals(lv.getName())) {
                    return new LocalVariableExpression(lv.getLineNumber(), lv.getType(), t, -1);
                }
                return lv;
            }
        };
        for (int i = 0; i < body.size(); i++) {
            Statement s = renamer.rewrite(body.get(i));
            if (s instanceof VariableDeclarationStatement
                    && f.equals(((VariableDeclarationStatement) s).getName())) {
                VariableDeclarationStatement v = (VariableDeclarationStatement) s;
                VariableDeclarationStatement nv = new VariableDeclarationStatement(v.getLineNumber(),
                    v.getType(), t, v.getInitializer(), v.isFinal(), v.isVar());
                if (v.getGenericSignature() != null) nv.setGenericSignature(v.getGenericSignature());
                s = nv;
            }
            // The first (formerly plain) assignment becomes the declaration of the renamed variable.
            if (s instanceof ExpressionStatement
                    && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
                if (ae.getLeft() instanceof LocalVariableExpression
                        && t.equals(((LocalVariableExpression) ae.getLeft()).getName())
                        && "=".equals(ae.getOperator())
                        && !declaresName(body, i, t)) {
                    it.denzosoft.javadecompiler.model.javasyntax.type.Type wt = initType(ae.getRight());
                    if (wt != null) {
                        s = new VariableDeclarationStatement(s.getLineNumber(), wt, t, ae.getRight(), false, false);
                    }
                }
            }
            body.set(i, s);
        }
    }

    /** Whether {@code name} is declared by a top-level statement of {@code body} before index {@code upTo}. */
    private static boolean declaresName(List<Statement> body, int upTo, String name) {
        for (int i = 0; i < upTo; i++) {
            if (body.get(i) instanceof VariableDeclarationStatement
                    && name.equals(((VariableDeclarationStatement) body.get(i)).getName())) return true;
        }
        return false;
    }

    /** Static type of an initializer/RHS expression (null when unknown). */
    private static it.denzosoft.javadecompiler.model.javasyntax.type.Type initType(Expression e) {
        if (e == null) return null;
        if (e instanceof CastExpression) return ((CastExpression) e).getType();
        return e.getType();
    }

    /**
     * Conservative assignability: unknown types are assumed compatible (no behavior change);
     * primitive-vs-reference is incompatible; primitives allow identity + widening.
     */
    private static boolean typeAssignable(it.denzosoft.javadecompiler.model.javasyntax.type.Type declared,
                                          it.denzosoft.javadecompiler.model.javasyntax.type.Type written) {
        if (declared == null || written == null) return true;
        boolean dp = declared instanceof it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;
        boolean wp = written instanceof it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;
        if (dp != wp) {
            // boxing of the matching wrapper is fine; anything else is a slot reuse
            String prim = dp ? declared.getName() : written.getName();
            String ref = dp ? written.getName() : declared.getName();
            return wrapperOf(prim).equals(ref) || "Object".equals(ref);
        }
        if (!dp) return true; // reference-to-reference: hierarchy unknown, assume compatible
        String d = declared.getName();
        String w = written.getName();
        if (d.equals(w)) return true;
        if ("boolean".equals(d) || "boolean".equals(w)) return false;
        return primRank(w) >= 0 && primRank(d) >= 0 && primRank(w) < primRank(d)
            && !("char".equals(w) && "short".equals(d)) && !("short".equals(w) && "char".equals(d))
            && !("byte".equals(w) && "char".equals(d));
    }

    private static int primRank(String n) {
        if ("byte".equals(n)) return 1;
        if ("short".equals(n) || "char".equals(n)) return 2;
        if ("int".equals(n)) return 3;
        if ("long".equals(n)) return 4;
        if ("float".equals(n)) return 5;
        if ("double".equals(n)) return 6;
        return -1;
    }

    private static String wrapperOf(String prim) {
        if ("int".equals(prim)) return "Integer";
        if ("long".equals(prim)) return "Long";
        if ("double".equals(prim)) return "Double";
        if ("float".equals(prim)) return "Float";
        if ("boolean".equals(prim)) return "Boolean";
        if ("byte".equals(prim)) return "Byte";
        if ("short".equals(prim)) return "Short";
        if ("char".equals(prim)) return "Character";
        return "";
    }
    // END_CHANGE: BUG-2026-0067-48

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
