/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.AssignmentExpression;
import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.expression.LocalVariableExpression;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * START_CHANGE: BUG-2026-0107-20260905-1 - Demote a re-declaration to a plain assignment.
 *
 * Java forbids declaring a local whose name is already in scope, but the decoder identifies locals by
 * bytecode slot: when javac reuses one slot for two same-named variables in disjoint source scopes
 * (two consecutive `for (int i = ...)` loops being the common case), and the loop reconstruction
 * hoists each declaration out of its for-header, the output ends up declaring the same name twice in
 * the same block and no longer compiles.
 *
 * This pass keeps a scope stack while walking the statement tree. A declaration whose name is already
 * declared in an enclosing (or the current) scope WITH THE SAME TYPE is turned into an assignment —
 * the two are the same storage anyway — or dropped when it has no initializer.
 *
 * A re-declaration with a DIFFERENT type is left untouched: it is a genuinely distinct variable that
 * needs a fresh name rather than a merge, and silently merging it would change the program.
 */
public final class DuplicateDeclarationDemoter {

    private DuplicateDeclarationDemoter() { }

    public static List<Statement> reconstruct(List<Statement> statements) {
        if (statements == null) return statements;
        List<Map<String, String>> scopes = new ArrayList<Map<String, String>>();
        walk(statements, scopes);
        return statements;
    }

    /** Type key used to decide whether two declarations describe the same variable. */
    private static String typeKey(Type t) {
        if (t == null) return "?";
        String d = t.getDescriptor();
        if (d != null) return d;
        String n = t.getName();
        return n != null ? n : "?";
    }

    private static boolean declaredInScope(List<Map<String, String>> scopes, String name, String key) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String seen = scopes.get(i).get(name);
            if (seen != null) return seen.equals(key);
        }
        return false;
    }

    private static boolean knownName(List<Map<String, String>> scopes, String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).containsKey(name)) return true;
        }
        return false;
    }

    private static void walk(List<Statement> stmts, List<Map<String, String>> scopes) {
        if (stmts == null) return;
        scopes.add(new HashMap<String, String>());
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
                String name = vds.getName();
                String key = typeKey(vds.getType());
                if (name != null && declaredInScope(scopes, name, key)) {
                    Expression init = vds.getInitializer();
                    if (init != null) {
                        Expression target = new LocalVariableExpression(
                            vds.getLineNumber(), vds.getType(), name, -1);
                        stmts.set(i, new ExpressionStatement(new AssignmentExpression(
                            vds.getLineNumber(), vds.getType(), target, "=", init)));
                    } else {
                        stmts.remove(i);
                        i--;
                    }
                    continue;
                }
                if (name != null && !knownName(scopes, name)) {
                    scopes.get(scopes.size() - 1).put(name, key);
                }
                continue;
            }
            recurse(s, scopes);
        }
        scopes.remove(scopes.size() - 1);
    }

    private static void recurse(Statement s, List<Map<String, String>> scopes) {
        if (s == null) return;
        if (s instanceof BlockStatement) {
            walk(((BlockStatement) s).getStatements(), scopes);
        } else if (s instanceof IfStatement) {
            recurse(((IfStatement) s).getThenBody(), scopes);
        } else if (s instanceof IfElseStatement) {
            recurse(((IfElseStatement) s).getThenBody(), scopes);
            recurse(((IfElseStatement) s).getElseBody(), scopes);
        } else if (s instanceof WhileStatement) {
            recurse(((WhileStatement) s).getBody(), scopes);
        } else if (s instanceof DoWhileStatement) {
            recurse(((DoWhileStatement) s).getBody(), scopes);
        } else if (s instanceof ForStatement) {
            // The for-header declares into a scope of its own.
            ForStatement fs = (ForStatement) s;
            scopes.add(new HashMap<String, String>());
            Statement init = fs.getInit();
            if (init instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) init;
                if (vds.getName() != null) {
                    scopes.get(scopes.size() - 1).put(vds.getName(), typeKey(vds.getType()));
                }
            } else {
                recurse(init, scopes);
            }
            recurse(fs.getBody(), scopes);
            scopes.remove(scopes.size() - 1);
        } else if (s instanceof ForEachStatement) {
            ForEachStatement fes = (ForEachStatement) s;
            scopes.add(new HashMap<String, String>());
            if (fes.getVariableName() != null) {
                scopes.get(scopes.size() - 1).put(fes.getVariableName(), typeKey(fes.getVariableType()));
            }
            recurse(fes.getBody(), scopes);
            scopes.remove(scopes.size() - 1);
        } else if (s instanceof LabelStatement) {
            recurse(((LabelStatement) s).getBody(), scopes);
        } else if (s instanceof SynchronizedStatement) {
            recurse(((SynchronizedStatement) s).getBody(), scopes);
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            scopes.add(new HashMap<String, String>());
            walkResources(t.getResources(), scopes);
            recurse(t.getTryBody(), scopes);
            scopes.remove(scopes.size() - 1);
            for (int ci = 0; ci < t.getCatchClauses().size(); ci++) {
                TryCatchStatement.CatchClause cc = t.getCatchClauses().get(ci);
                scopes.add(new HashMap<String, String>());
                if (cc.variableName != null) {
                    scopes.get(scopes.size() - 1).put(cc.variableName, "!catch");
                }
                recurse(cc.body, scopes);
                scopes.remove(scopes.size() - 1);
            }
            recurse(t.getFinallyBody(), scopes);
        } else if (s instanceof SwitchStatement) {
            // All cases of a switch share one scope in Java.
            scopes.add(new HashMap<String, String>());
            List<SwitchStatement.SwitchCase> cases = ((SwitchStatement) s).getCases();
            for (int ci = 0; ci < cases.size(); ci++) {
                walk(cases.get(ci).getStatements(), scopes);
            }
            scopes.remove(scopes.size() - 1);
        }
    }

    private static void walkResources(List<Statement> resources, List<Map<String, String>> scopes) {
        if (resources == null) return;
        for (int i = 0; i < resources.size(); i++) {
            Statement r = resources.get(i);
            if (r instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) r;
                if (vds.getName() != null) {
                    scopes.get(scopes.size() - 1).put(vds.getName(), typeKey(vds.getType()));
                }
            }
        }
    }
}
// END_CHANGE: BUG-2026-0107-1
