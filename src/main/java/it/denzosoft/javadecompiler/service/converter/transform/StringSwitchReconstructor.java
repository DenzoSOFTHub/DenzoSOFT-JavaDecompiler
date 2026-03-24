/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs string switch statements from the javac-generated two-phase pattern:
 * Phase 1: switch(str.hashCode()) { case HASH: if (str.equals("literal")) idx=N; ... }
 * Phase 2: switch(idx) { case 0: ...; case 1: ...; }
 *
 * This transform merges them into a single: switch(str) { case "literal": ...; }
 */
public final class StringSwitchReconstructor {

    private StringSwitchReconstructor() {}

    public static List<Statement> reconstruct(List<Statement> statements) {
        // Iterate; may need multiple passes if there are multiple string switches
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Statement> result = tryReconstruct(statements);
            if (result != statements) {
                statements = result;
                changed = true;
            }
        }
        return statements;
    }

    private static List<Statement> tryReconstruct(List<Statement> statements) {
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            if (!(stmt instanceof SwitchStatement)) continue;
            SwitchStatement sw = (SwitchStatement) stmt;

            // Check if selector is a hashCode() call
            if (!isHashCodeSwitch(sw)) continue;

            // Extract index -> string literal mapping from the case bodies
            Map<Integer, String> indexToString = extractStringMapping(sw);
            if (indexToString.isEmpty()) continue;

            // Find the index variable name used in assignments
            String idxVarName = findIndexVarName(sw);
            if (idxVarName == null) continue;

            // Find the original string variable from the hashCode() call
            Expression originalVar = getOriginalVar(sw);
            if (originalVar == null) continue;

            // Find the next switch statement that switches on the index variable
            int indexSwitchPos = -1;
            SwitchStatement indexSwitch = null;
            for (int j = i + 1; j < statements.size(); j++) {
                Statement s = statements.get(j);
                if (s instanceof SwitchStatement) {
                    SwitchStatement candidate = (SwitchStatement) s;
                    Expression sel = candidate.getSelector();
                    if (sel instanceof LocalVariableExpression) {
                        String selName = ((LocalVariableExpression) sel).getName();
                        if (idxVarName.equals(selName)) {
                            indexSwitch = candidate;
                            indexSwitchPos = j;
                            break;
                        }
                    }
                }
            }
            if (indexSwitch == null) continue;

            // START_CHANGE: LIM-0001-20260324-1 - Use original variable as selector, not temp copy
            // If setup statements include an assignment like: var2 = s;
            // then originalVar is var2, but we want s (the right-hand side).
            int setupStart = findSetupStart(statements, i);
            Expression selectorVar = originalVar;
            for (int j = setupStart; j < i; j++) {
                Statement setupStmt = statements.get(j);
                Expression rhs = null;
                String lhsName = null;
                if (setupStmt instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement vds = (VariableDeclarationStatement) setupStmt;
                    if (vds.hasInitializer() && vds.getInitializer() instanceof LocalVariableExpression) {
                        lhsName = vds.getName();
                        rhs = vds.getInitializer();
                    }
                } else if (setupStmt instanceof ExpressionStatement) {
                    Expression expr = ((ExpressionStatement) setupStmt).getExpression();
                    if (expr instanceof AssignmentExpression) {
                        AssignmentExpression ae = (AssignmentExpression) expr;
                        if (ae.getLeft() instanceof LocalVariableExpression
                            && ae.getRight() instanceof LocalVariableExpression) {
                            lhsName = ((LocalVariableExpression) ae.getLeft()).getName();
                            rhs = ae.getRight();
                        }
                    }
                }
                if (lhsName != null && rhs != null && originalVar instanceof LocalVariableExpression) {
                    String origName = ((LocalVariableExpression) originalVar).getName();
                    if (lhsName.equals(origName)) {
                        selectorVar = rhs;
                        break;
                    }
                }
            }
            // END_CHANGE: LIM-0001-1

            // Rebuild the switch with string labels
            SwitchStatement stringSwitch = rebuildWithStringLabels(indexSwitch, indexToString, selectorVar);

            List<Statement> newList = new ArrayList<Statement>();
            for (int j = 0; j < setupStart; j++) {
                newList.add(statements.get(j));
            }
            newList.add(stringSwitch);
            for (int j = indexSwitchPos + 1; j < statements.size(); j++) {
                newList.add(statements.get(j));
            }
            return newList;
        }
        return statements;
    }

    private static boolean isHashCodeSwitch(SwitchStatement sw) {
        Expression sel = sw.getSelector();
        if (sel instanceof MethodInvocationExpression) {
            return "hashCode".equals(((MethodInvocationExpression) sel).getMethodName());
        }
        return false;
    }

    private static Expression getOriginalVar(SwitchStatement sw) {
        Expression sel = sw.getSelector();
        if (sel instanceof MethodInvocationExpression) {
            return ((MethodInvocationExpression) sel).getObject();
        }
        return null;
    }

    private static Map<Integer, String> extractStringMapping(SwitchStatement sw) {
        Map<Integer, String> map = new HashMap<Integer, String>();
        for (SwitchStatement.SwitchCase sc : sw.getCases()) {
            for (Statement s : sc.getStatements()) {
                extractFromStatement(s, map);
            }
        }
        return map;
    }

    /**
     * Extract equals("literal") -> idx = N patterns from if-statements.
     * Pattern: if (var.equals("literal")) { idx = N; }
     * Also handles: if (var.equals("literal")) { idx = N; break; }
     */
    private static void extractFromStatement(Statement s, Map<Integer, String> map) {
        if (s instanceof IfStatement) {
            IfStatement ifStmt = (IfStatement) s;
            extractFromIf(ifStmt.getCondition(), ifStmt.getThenBody(), map);
        } else if (s instanceof IfElseStatement) {
            IfElseStatement ifElse = (IfElseStatement) s;
            extractFromIf(ifElse.getCondition(), ifElse.getThenBody(), map);
            // The else branch may also contain if(var.equals(...)) patterns (hash collision)
            extractFromStatement(ifElse.getElseBody(), map);
        } else if (s instanceof BlockStatement) {
            for (Statement inner : ((BlockStatement) s).getStatements()) {
                extractFromStatement(inner, map);
            }
        }
    }

    private static void extractFromIf(Expression condition, Statement thenBody, Map<Integer, String> map) {
        // condition should be: var.equals("literal")
        String literal = extractEqualsLiteral(condition);
        if (literal == null) return;

        // thenBody should contain: idx = N (possibly in a block)
        Integer idx = extractAssignedIndex(thenBody);
        if (idx == null) return;

        map.put(idx, literal);
    }

    private static String extractEqualsLiteral(Expression condition) {
        if (!(condition instanceof MethodInvocationExpression)) return null;
        MethodInvocationExpression mie = (MethodInvocationExpression) condition;
        if (!"equals".equals(mie.getMethodName())) return null;
        List<Expression> args = mie.getArguments();
        if (args == null || args.size() != 1) return null;
        Expression arg = args.get(0);
        if (arg instanceof StringConstantExpression) {
            return ((StringConstantExpression) arg).getValue();
        }
        return null;
    }

    private static Integer extractAssignedIndex(Statement body) {
        List<Statement> stmts;
        if (body instanceof BlockStatement) {
            stmts = ((BlockStatement) body).getStatements();
        } else {
            stmts = new ArrayList<Statement>();
            stmts.add(body);
        }

        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                if (expr instanceof AssignmentExpression) {
                    AssignmentExpression ae = (AssignmentExpression) expr;
                    if (ae.getRight() instanceof IntegerConstantExpression) {
                        return Integer.valueOf(((IntegerConstantExpression) ae.getRight()).getValue());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Find the name of the index variable assigned in the hashCode switch cases.
     */
    private static String findIndexVarName(SwitchStatement sw) {
        for (SwitchStatement.SwitchCase sc : sw.getCases()) {
            for (Statement s : sc.getStatements()) {
                String name = findIdxVarInStatement(s);
                if (name != null) return name;
            }
        }
        return null;
    }

    private static String findIdxVarInStatement(Statement s) {
        if (s instanceof IfStatement) {
            return findIdxVarInBody(((IfStatement) s).getThenBody());
        } else if (s instanceof IfElseStatement) {
            String name = findIdxVarInBody(((IfElseStatement) s).getThenBody());
            if (name != null) return name;
            return findIdxVarInStatement(((IfElseStatement) s).getElseBody());
        } else if (s instanceof BlockStatement) {
            for (Statement inner : ((BlockStatement) s).getStatements()) {
                String name = findIdxVarInStatement(inner);
                if (name != null) return name;
            }
        }
        return null;
    }

    private static String findIdxVarInBody(Statement body) {
        List<Statement> stmts;
        if (body instanceof BlockStatement) {
            stmts = ((BlockStatement) body).getStatements();
        } else {
            stmts = new ArrayList<Statement>();
            stmts.add(body);
        }

        for (Statement s : stmts) {
            if (s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                if (expr instanceof AssignmentExpression) {
                    AssignmentExpression ae = (AssignmentExpression) expr;
                    if (ae.getLeft() instanceof LocalVariableExpression) {
                        return ((LocalVariableExpression) ae.getLeft()).getName();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Find how many setup statements precede the hashCode switch.
     * Looks backwards for: String var = str; int idx = -1;
     */
    private static int findSetupStart(List<Statement> statements, int hashCodeSwitchIndex) {
        int start = hashCodeSwitchIndex;

        // Look back for variable assignments/declarations that set up the string switch
        for (int j = hashCodeSwitchIndex - 1; j >= 0; j--) {
            Statement s = statements.get(j);
            if (s instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
                if (vds.hasInitializer()) {
                    Expression init = vds.getInitializer();
                    // int idx = -1 pattern
                    if (init instanceof IntegerConstantExpression
                        && ((IntegerConstantExpression) init).getValue() == -1) {
                        start = j;
                        continue;
                    }
                    // String var = str pattern (or var = someExpr)
                    if (init instanceof LocalVariableExpression) {
                        start = j;
                        continue;
                    }
                }
            } else if (s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                if (expr instanceof AssignmentExpression) {
                    AssignmentExpression ae = (AssignmentExpression) expr;
                    // idx = -1 pattern
                    if (ae.getRight() instanceof IntegerConstantExpression
                        && ((IntegerConstantExpression) ae.getRight()).getValue() == -1) {
                        start = j;
                        continue;
                    }
                    // var = str pattern
                    if (ae.getRight() instanceof LocalVariableExpression) {
                        start = j;
                        continue;
                    }
                }
            }
            break;
        }
        return start;
    }

    /**
     * Replace integer case labels with string literals in the index switch.
     */
    private static SwitchStatement rebuildWithStringLabels(SwitchStatement indexSwitch,
                                                            Map<Integer, String> indexToString,
                                                            Expression originalVar) {
        List<SwitchStatement.SwitchCase> newCases = new ArrayList<SwitchStatement.SwitchCase>();

        for (SwitchStatement.SwitchCase sc : indexSwitch.getCases()) {
            if (sc.isDefault()) {
                newCases.add(sc);
                continue;
            }

            List<Expression> newLabels = new ArrayList<Expression>();
            for (Expression label : sc.getLabels()) {
                if (label instanceof IntegerConstantExpression) {
                    int idx = ((IntegerConstantExpression) label).getValue();
                    String str = indexToString.get(Integer.valueOf(idx));
                    if (str != null) {
                        newLabels.add(new StringConstantExpression(label.getLineNumber(), str));
                    } else {
                        newLabels.add(label);
                    }
                } else {
                    newLabels.add(label);
                }
            }
            newCases.add(new SwitchStatement.SwitchCase(newLabels, sc.getStatements()));
        }

        return new SwitchStatement(indexSwitch.getLineNumber(), originalVar, newCases,
            indexSwitch.isArrowStyle());
    }
}
