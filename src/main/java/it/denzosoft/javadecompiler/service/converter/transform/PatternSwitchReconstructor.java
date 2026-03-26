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
import java.util.Map;

// START_CHANGE: LIM-0005-20260326-3 - Pattern switch reconstructor for Java 21+
/**
 * Reconstructs pattern switch statements from SwitchBootstraps.typeSwitch bytecode pattern.
 *
 * Java 21+ compiles pattern switch as:
 *   int idx = SwitchBootstraps.typeSwitch(obj, 0, types...)
 *   switch(idx) { case 0: ... case 1: ... }
 *
 * This reconstructor replaces the integer labels with the original type patterns.
 */
public final class PatternSwitchReconstructor {

    private PatternSwitchReconstructor() {}

    public static List<Statement> reconstruct(List<Statement> statements, Map<String, List<String>> labels) {
        if (statements == null || statements.isEmpty() || labels == null || labels.isEmpty()) {
            return statements;
        }
        List<Statement> result = new ArrayList<Statement>();
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            String switchVarName = null;
            Expression selectorExpr = null;
            String typeSwitchKey = null;

            // Check for: var = typeSwitch(obj, 0)
            if (stmt instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) stmt).getExpression();
                if (expr instanceof AssignmentExpression) {
                    AssignmentExpression ae = (AssignmentExpression) expr;
                    StaticMethodInvocationExpression smie = getTypeSwitchCall(ae.getRight());
                    if (smie != null && ae.getLeft() instanceof LocalVariableExpression) {
                        switchVarName = ((LocalVariableExpression) ae.getLeft()).getName();
                        List<Expression> smieArgs = smie.getArguments();
                        selectorExpr = (smieArgs != null && !smieArgs.isEmpty()) ? smieArgs.get(0) : null;
                        typeSwitchKey = smie.getMethodName() + "_" + smie.getLineNumber();
                    }
                }
            } else if (stmt instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
                if (vds.hasInitializer()) {
                    StaticMethodInvocationExpression smie = getTypeSwitchCall(vds.getInitializer());
                    if (smie != null) {
                        switchVarName = vds.getName();
                        List<Expression> smieArgs = smie.getArguments();
                        selectorExpr = (smieArgs != null && !smieArgs.isEmpty()) ? smieArgs.get(0) : null;
                        typeSwitchKey = smie.getMethodName() + "_" + smie.getLineNumber();
                    }
                }
            }

            if (switchVarName != null && selectorExpr != null && i + 1 < statements.size()) {
                List<String> caseLabels = findCaseLabels(labels, typeSwitchKey);
                if (caseLabels != null) {
                    Statement nextStmt = statements.get(i + 1);
                    if (nextStmt instanceof SwitchStatement) {
                        SwitchStatement sw = (SwitchStatement) nextStmt;
                        if (isSwitchOnVar(sw, switchVarName)) {
                            SwitchStatement rebuilt = rebuildWithPatternLabels(sw, selectorExpr, caseLabels);
                            if (rebuilt != null) {
                                result.add(rebuilt);
                                i++;
                                continue;
                            }
                        }
                    }
                }
            }
            result.add(stmt);
        }
        return result;
    }

    private static StaticMethodInvocationExpression getTypeSwitchCall(Expression expr) {
        if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            if ("typeSwitch".equals(smie.getMethodName()) || "enumSwitch".equals(smie.getMethodName())) {
                return smie;
            }
        }
        return null;
    }

    private static List<String> findCaseLabels(Map<String, List<String>> labels, String key) {
        if (key != null) {
            List<String> found = labels.get(key);
            if (found != null) return found;
        }
        // Fallback: use first available label set
        for (Map.Entry<String, List<String>> entry : labels.entrySet()) {
            return entry.getValue();
        }
        return null;
    }

    private static boolean isSwitchOnVar(SwitchStatement sw, String varName) {
        Expression selector = sw.getSelector();
        if (selector instanceof LocalVariableExpression) {
            return varName.equals(((LocalVariableExpression) selector).getName());
        }
        return false;
    }

    private static SwitchStatement rebuildWithPatternLabels(SwitchStatement sw, Expression selector,
                                                             List<String> caseLabels) {
        List<SwitchStatement.SwitchCase> newCases = new ArrayList<SwitchStatement.SwitchCase>();
        for (SwitchStatement.SwitchCase sc : sw.getCases()) {
            if (sc.isDefault()) {
                newCases.add(sc);
                continue;
            }
            List<Expression> newLabels = new ArrayList<Expression>();
            for (Expression label : sc.getLabels()) {
                if (label instanceof IntegerConstantExpression) {
                    int idx = ((IntegerConstantExpression) label).getValue();
                    if (idx >= 0 && idx < caseLabels.size()) {
                        String patternLabel = caseLabels.get(idx);
                        String simpleName = patternLabel;
                        if (simpleName.contains("/")) {
                            simpleName = simpleName.substring(simpleName.lastIndexOf('/') + 1);
                        }
                        newLabels.add(new StringConstantExpression(label.getLineNumber(), simpleName));
                    } else {
                        newLabels.add(label);
                    }
                } else {
                    newLabels.add(label);
                }
            }
            newCases.add(new SwitchStatement.SwitchCase(newLabels, sc.getStatements()));
        }
        return new SwitchStatement(sw.getLineNumber(), selector, newCases, sw.isArrowStyle());
    }
}
// END_CHANGE: LIM-0005-3
