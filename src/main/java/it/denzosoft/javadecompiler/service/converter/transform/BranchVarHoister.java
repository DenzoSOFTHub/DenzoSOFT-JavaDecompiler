/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.List;

// START_CHANGE: BUG-2026-0069-20260610-16 - Branch-scoped declaration hoisting (residual (a)).
// Without LVT the decompiler declares a slot at its first store; when that store is inside an
// if-branch the declaration lands there ({@code if (c) { String r = "x"; } else { r = "y"; } return r;})
// and the else-branch / post-if references do not resolve. This pass hoists a plain declaration
// ({@code String r;}) before the if and demotes the in-branch declarations to assignments.
/**
 * Hoists a local variable that is DECLARED inside one branch of an if/else but assigned in the
 * other branch and/or read after the if (BUG-2026-0069 residual (a)). Modeled on
 * {@link SwitchVarHoister}. Conservative: it only fires when the cross-branch/post-if usage is
 * proven, the other branch's first touch of the name is a type-compatible definite write (or the
 * branch is untouched-and-abrupt), no re-declaration of the name survives anywhere it cannot be
 * demoted, and definite assignment of post-if reads is preserved. Genuinely scoped same-name
 * pairs (a declaration in EACH branch, no post-if use) are left untouched.
 */
public final class BranchVarHoister {

    private BranchVarHoister() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        for (int i = 0; i < statements.size(); i++) {
            recurse(statements.get(i));
            if (statements.get(i) instanceof IfElseStatement) {
                i += hoist(statements, i);
            } else if (statements.get(i) instanceof IfStatement) {
                i += hoistIfOnly(statements, i);
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

    /** Hoist branch-scoped vars of the if/else at index {@code ifIdx}. Returns how many decls were inserted before it. */
    private static int hoist(List<Statement> outer, int ifIdx) {
        IfElseStatement ie = (IfElseStatement) outer.get(ifIdx);
        int inserted = 0;
        inserted += hoistBranch(outer, ifIdx + inserted, ie.getThenBody(), ie.getElseBody());
        inserted += hoistBranch(outer, ifIdx + inserted, ie.getElseBody(), ie.getThenBody());
        return inserted;
    }

    /**
     * Scans {@code declBranch} for top-level {@code Type v [= expr];} declarations whose name is
     * also used in {@code otherBranch} or after the if at {@code ifIdx}, and hoists them.
     */
    private static int hoistBranch(List<Statement> outer, int ifIdx, Statement declBranch, Statement otherBranch) {
        if (!(declBranch instanceof BlockStatement)) return 0;
        List<Statement> body = ((BlockStatement) declBranch).getStatements();
        int inserted = 0;
        for (int si = 0; si < body.size(); si++) {
            if (!(body.get(si) instanceof VariableDeclarationStatement)) continue;
            VariableDeclarationStatement vds = (VariableDeclarationStatement) body.get(si);
            if (vds.getType() == null) continue;
            String name = vds.getName();
            // Already declared before the if in this scope: the in-branch decl is a scoped reuse.
            if (declaresName(outer, ifIdx, name)) continue;
            // The name is touched in this branch before its declaration: entangled, leave alone.
            boolean touchedBefore = false;
            for (int p = 0; p < si && !touchedBefore; p++) {
                if (ForLoopDetector.nameUsedInStatement(body.get(p), name)) touchedBefore = true;
            }
            if (touchedBefore) continue;

            boolean usedInOther = ForLoopDetector.nameUsedInStatement(otherBranch, name);
            boolean usedAfterIf = usedAfter(outer, ifIdx, name);
            if (!usedInOther && !usedAfterIf) continue;
            // A later declaration of the same name (a separate slot-reuse variable) would become a
            // duplicate of the hoisted declaration: bail.
            boolean declAfter = false;
            for (int k = ifIdx + 1; k < outer.size() && !declAfter; k++) {
                if (declaresAnywhere(outer.get(k), name)) declAfter = true;
            }
            if (declAfter) continue;

            int status = writeStatus(otherBranch, name, vds.getType());
            if (status == W_UNSAFE) continue;
            // The other branch declares its own variable of this name and nothing reads the slot
            // after the if: two genuinely scoped locals — leave untouched.
            if (!usedAfterIf && declaresAnywhere(otherBranch, name)) continue;
            // Cross-branch usage must be backed by a definite type-compatible write.
            if (usedInOther && status != W_WRITES) continue;
            if (usedAfterIf) {
                // Definite assignment of the post-if read: each branch must write on all its
                // normally-completing paths.
                if (status == W_NONE && !endsAbruptly(otherBranch)) continue;
                if (!vds.hasInitializer()) {
                    int rem = listWriteStatus(body, si + 1, name, vds.getType());
                    if (rem == W_UNSAFE) continue;
                    if (rem == W_NONE && !(body.size() > si + 1 && endsAbruptly(body.get(body.size() - 1)))) continue;
                }
            }

            // Every surviving re-declaration of the name in EITHER branch must be demotable
            // (reachable through blocks / if-else chains only, with an identical declared type).
            List<DeclSite> sites = new ArrayList<DeclSite>();
            if (!collectDeclSites(declBranch, name, vds.getType(), sites)
                    || !collectDeclSites(otherBranch, name, vds.getType(), sites)) continue;

            // Hoist: `Type name;` before the if, demote every in-branch declaration.
            VariableDeclarationStatement decl = new VariableDeclarationStatement(vds.getLineNumber(),
                vds.getType(), name, null, false, false);
            if (vds.getGenericSignature() != null) decl.setGenericSignature(vds.getGenericSignature());
            outer.add(ifIdx, decl);
            inserted++;
            ifIdx++; // the if shifted right
            for (int k = 0; k < sites.size(); k++) {
                DeclSite site = sites.get(k);
                VariableDeclarationStatement d = (VariableDeclarationStatement) site.list.get(site.index);
                if (d.hasInitializer()) {
                    site.list.set(site.index, new ExpressionStatement(new AssignmentExpression(d.getLineNumber(),
                        d.getType(),
                        new LocalVariableExpression(d.getLineNumber(), d.getType(), name, -1),
                        "=", d.getInitializer())));
                } else {
                    site.list.remove(site.index);
                    if (site.list == body && site.index <= si) si--;
                }
            }
            // The candidate position was rewritten in place (or removed); continue with the next statement.
        }
        return inserted;
    }

    /**
     * Hoists from a plain {@code if} (no else, typically because the then-branch returns and the
     * original else became fall-through: {@code if (c) { int v = 10; return v + 1; } v = 20; ...}).
     * Only safe when the FIRST post-if touch of the name is a definite type-compatible write —
     * otherwise the around-the-if path would read an unassigned variable, so we bail and keep the
     * current output.
     */
    private static int hoistIfOnly(List<Statement> outer, int ifIdx) {
        Statement thenBody = ((IfStatement) outer.get(ifIdx)).getThenBody();
        if (!(thenBody instanceof BlockStatement)) return 0;
        List<Statement> body = ((BlockStatement) thenBody).getStatements();
        int inserted = 0;
        for (int si = 0; si < body.size(); si++) {
            if (!(body.get(si) instanceof VariableDeclarationStatement)) continue;
            VariableDeclarationStatement vds = (VariableDeclarationStatement) body.get(si);
            if (vds.getType() == null) continue;
            String name = vds.getName();
            if (declaresName(outer, ifIdx, name)) continue;
            boolean touchedBefore = false;
            for (int p = 0; p < si && !touchedBefore; p++) {
                if (ForLoopDetector.nameUsedInStatement(body.get(p), name)) touchedBefore = true;
            }
            if (touchedBefore) continue;
            if (!usedAfter(outer, ifIdx, name)) continue;
            boolean declAfter = false;
            for (int k = ifIdx + 1; k < outer.size() && !declAfter; k++) {
                if (declaresAnywhere(outer.get(k), name)) declAfter = true;
            }
            if (declAfter) continue;
            // Definite assignment on the around-the-if path: the post-if statements must write the
            // name (on every normally-completing path) before any read.
            if (listWriteStatus(outer, ifIdx + 1, name, vds.getType()) != W_WRITES) continue;
            if (!vds.hasInitializer()
                    && listWriteStatus(body, si + 1, name, vds.getType()) == W_UNSAFE) continue;
            List<DeclSite> sites = new ArrayList<DeclSite>();
            if (!collectDeclSites(thenBody, name, vds.getType(), sites)) continue;
            VariableDeclarationStatement decl = new VariableDeclarationStatement(vds.getLineNumber(),
                vds.getType(), name, null, false, false);
            if (vds.getGenericSignature() != null) decl.setGenericSignature(vds.getGenericSignature());
            outer.add(ifIdx, decl);
            inserted++;
            ifIdx++;
            for (int k = 0; k < sites.size(); k++) {
                DeclSite site = sites.get(k);
                VariableDeclarationStatement d = (VariableDeclarationStatement) site.list.get(site.index);
                if (d.hasInitializer()) {
                    site.list.set(site.index, new ExpressionStatement(new AssignmentExpression(d.getLineNumber(),
                        d.getType(),
                        new LocalVariableExpression(d.getLineNumber(), d.getType(), name, -1),
                        "=", d.getInitializer())));
                } else {
                    site.list.remove(site.index);
                    if (site.list == body && site.index <= si) si--;
                }
            }
        }
        return inserted;
    }

    // ---- definite-write analysis -------------------------------------------------------------

    private static final int W_WRITES = 0; // every normally-completing path writes name before reading it
    private static final int W_NONE = 1;   // name never touched
    private static final int W_UNSAFE = 2; // read-before-write, type conflict, or undecidable

    private static int writeStatus(Statement s, String name, Type declared) {
        if (s == null) return W_NONE;
        if (s instanceof BlockStatement) return listWriteStatus(((BlockStatement) s).getStatements(), 0, name, declared);
        if (s instanceof VariableDeclarationStatement) return declWriteStatus((VariableDeclarationStatement) s, name, declared);
        if (s instanceof ExpressionStatement) return exprStmtWriteStatus((ExpressionStatement) s, name, declared);
        if (s instanceof IfElseStatement) {
            return combineBranches(writeStatus(((IfElseStatement) s).getThenBody(), name, declared),
                writeStatus(((IfElseStatement) s).getElseBody(), name, declared));
        }
        return ForLoopDetector.nameUsedInStatement(s, name) ? W_UNSAFE : W_NONE;
    }

    private static int listWriteStatus(List<Statement> body, int from, String name, Type declared) {
        for (int i = from; i < body.size(); i++) {
            Statement st = body.get(i);
            if (st instanceof VariableDeclarationStatement) {
                int r = declWriteStatus((VariableDeclarationStatement) st, name, declared);
                if (r != W_NONE) return r;
                continue;
            }
            if (st instanceof ExpressionStatement) {
                int r = exprStmtWriteStatus((ExpressionStatement) st, name, declared);
                if (r != W_NONE) return r;
                continue;
            }
            if (st instanceof IfElseStatement) {
                int r = combineBranches(writeStatus(((IfElseStatement) st).getThenBody(), name, declared),
                    writeStatus(((IfElseStatement) st).getElseBody(), name, declared));
                if (r == W_WRITES || r == W_UNSAFE) return r;
                continue;
            }
            if (ForLoopDetector.nameUsedInStatement(st, name)) return W_UNSAFE;
        }
        return W_NONE;
    }

    /** A same-name re-declaration counts as a write only when type-identical with an independent initializer. */
    private static int declWriteStatus(VariableDeclarationStatement d, String name, Type declared) {
        if (!name.equals(d.getName())) {
            return exprMentions(d.getInitializer(), name) ? W_UNSAFE : W_NONE;
        }
        if (d.hasInitializer() && sameTypeName(declared, d.getType()) && !exprMentions(d.getInitializer(), name)) {
            return W_WRITES;
        }
        return W_UNSAFE;
    }

    /** A top-level plain assignment {@code name = expr} with a type-compatible RHS is a definite write. */
    private static int exprStmtWriteStatus(ExpressionStatement st, String name, Type declared) {
        if (!(st.getExpression() instanceof AssignmentExpression)) {
            return exprMentions(st.getExpression(), name) ? W_UNSAFE : W_NONE;
        }
        AssignmentExpression ae = (AssignmentExpression) st.getExpression();
        if (!(ae.getLeft() instanceof LocalVariableExpression)
                || !name.equals(((LocalVariableExpression) ae.getLeft()).getName())) {
            return exprMentions(ae.getLeft(), name) || exprMentions(ae.getRight(), name) ? W_UNSAFE : W_NONE;
        }
        if (!"=".equals(ae.getOperator())) return W_UNSAFE; // compound assignment reads the name
        if (exprMentions(ae.getRight(), name)) return W_UNSAFE; // self-referencing RHS reads before writing
        if (!typeAssignable(declared, initType(ae.getRight()))) return W_UNSAFE;
        return W_WRITES;
    }

    private static int combineBranches(int a, int b) {
        if (a == W_UNSAFE || b == W_UNSAFE) return W_UNSAFE;
        if (a == W_WRITES && b == W_WRITES) return W_WRITES;
        if (a == W_NONE && b == W_NONE) return W_NONE;
        return W_UNSAFE; // one branch writes, the other does not: not definite, conservatively bail
    }

    /** Whether the statement completes abruptly on every path (return/throw/break/continue). */
    private static boolean endsAbruptly(Statement s) {
        if (s instanceof ReturnStatement || s instanceof ThrowStatement
                || s instanceof BreakStatement || s instanceof ContinueStatement) return true;
        if (s instanceof BlockStatement) {
            List<Statement> b = ((BlockStatement) s).getStatements();
            return !b.isEmpty() && endsAbruptly(b.get(b.size() - 1));
        }
        if (s instanceof IfElseStatement) {
            return endsAbruptly(((IfElseStatement) s).getThenBody()) && endsAbruptly(((IfElseStatement) s).getElseBody());
        }
        return false;
    }

    // ---- declaration collection / demotion ----------------------------------------------------

    private static final class DeclSite {
        final List<Statement> list;
        final int index;
        DeclSite(List<Statement> list, int index) { this.list = list; this.index = index; }
    }

    /**
     * Collects every declaration of {@code name} reachable through blocks and if/else branches.
     * Returns false (bail) when a declaration of the name sits in a context that cannot be safely
     * demoted (loop/try/switch/sync/label body, or a non-block branch) or has a different type.
     */
    private static boolean collectDeclSites(Statement s, String name, Type declared, List<DeclSite> out) {
        if (s == null) return true;
        if (s instanceof BlockStatement) {
            List<Statement> list = ((BlockStatement) s).getStatements();
            for (int i = 0; i < list.size(); i++) {
                Statement st = list.get(i);
                if (st instanceof VariableDeclarationStatement
                        && name.equals(((VariableDeclarationStatement) st).getName())) {
                    if (!sameTypeName(declared, ((VariableDeclarationStatement) st).getType())) return false;
                    out.add(new DeclSite(list, i));
                } else if (st instanceof IfElseStatement) {
                    if (!collectDeclSites(((IfElseStatement) st).getThenBody(), name, declared, out)) return false;
                    if (!collectDeclSites(((IfElseStatement) st).getElseBody(), name, declared, out)) return false;
                } else if (st instanceof IfStatement) {
                    if (!collectDeclSites(((IfStatement) st).getThenBody(), name, declared, out)) return false;
                } else if (declaresAnywhere(st, name)) {
                    return false;
                }
            }
            return true;
        }
        if (s instanceof IfElseStatement) {
            return collectDeclSites(((IfElseStatement) s).getThenBody(), name, declared, out)
                && collectDeclSites(((IfElseStatement) s).getElseBody(), name, declared, out);
        }
        if (s instanceof IfStatement) {
            return collectDeclSites(((IfStatement) s).getThenBody(), name, declared, out);
        }
        return !declaresAnywhere(s, name);
    }

    /** Whether any statement (recursively, all constructs) declares {@code name}. */
    private static boolean declaresAnywhere(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof VariableDeclarationStatement) return name.equals(((VariableDeclarationStatement) s).getName());
        if (s instanceof BlockStatement) {
            for (Statement c : ((BlockStatement) s).getStatements()) if (declaresAnywhere(c, name)) return true;
            return false;
        }
        if (s instanceof IfStatement) return declaresAnywhere(((IfStatement) s).getThenBody(), name);
        if (s instanceof IfElseStatement) {
            return declaresAnywhere(((IfElseStatement) s).getThenBody(), name)
                || declaresAnywhere(((IfElseStatement) s).getElseBody(), name);
        }
        if (s instanceof WhileStatement) return declaresAnywhere(((WhileStatement) s).getBody(), name);
        if (s instanceof DoWhileStatement) return declaresAnywhere(((DoWhileStatement) s).getBody(), name);
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            return (f.getInit() != null && declaresAnywhere(f.getInit(), name)) || declaresAnywhere(f.getBody(), name);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement fe = (ForEachStatement) s;
            return name.equals(fe.getVariableName()) || declaresAnywhere(fe.getBody(), name);
        }
        if (s instanceof LabelStatement) return declaresAnywhere(((LabelStatement) s).getBody(), name);
        if (s instanceof SynchronizedStatement) return declaresAnywhere(((SynchronizedStatement) s).getBody(), name);
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (declaresAnywhere(t.getTryBody(), name)) return true;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                if (name.equals(cc.variableName) || declaresAnywhere(cc.body, name)) return true;
            }
            return t.getFinallyBody() != null && declaresAnywhere(t.getFinallyBody(), name);
        }
        if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                for (Statement cs : c.getStatements()) if (declaresAnywhere(cs, name)) return true;
            }
            return false;
        }
        return false;
    }

    // ---- shared helpers (mirrors SwitchVarHoister) ---------------------------------------------

    private static boolean declaresName(List<Statement> body, int upTo, String name) {
        for (int i = 0; i < upTo; i++) {
            if (body.get(i) instanceof VariableDeclarationStatement
                    && name.equals(((VariableDeclarationStatement) body.get(i)).getName())) return true;
        }
        return false;
    }

    private static boolean usedAfter(List<Statement> outer, int ifIdx, String name) {
        for (int k = ifIdx + 1; k < outer.size(); k++) {
            if (ForLoopDetector.nameUsedInStatement(outer.get(k), name)) return true;
        }
        return false;
    }

    private static boolean sameTypeName(Type a, Type b) {
        return a != null && b != null && a.getName() != null && a.getName().equals(b.getName());
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

    /** Static type of an initializer/RHS expression (null when unknown). */
    private static Type initType(Expression e) {
        if (e == null) return null;
        if (e instanceof CastExpression) return ((CastExpression) e).getType();
        return e.getType();
    }

    /**
     * Conservative assignability: unknown types are assumed compatible (no behavior change);
     * primitive-vs-reference is incompatible; primitives allow identity + widening.
     */
    private static boolean typeAssignable(Type declared, Type written) {
        if (declared == null || written == null) return true;
        boolean dp = declared instanceof PrimitiveType;
        boolean wp = written instanceof PrimitiveType;
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
}
// END_CHANGE: BUG-2026-0069-16
