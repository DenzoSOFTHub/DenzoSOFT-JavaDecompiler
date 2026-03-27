/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;
import it.denzosoft.javadecompiler.service.converter.JavaSyntaxResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-processing transformer for deobfuscation.
 * Applied when --deobfuscate is enabled.
 *
 * Handles:
 * 1. Return-type overloading: rename methods with same name+params but different return
 * 2. Encrypted string annotation: detect XOR-based decrypt patterns and annotate calls
 * 3. Opaque predicate detection: simplify always-true/false conditions
 * 4. Control flow flattening detection: annotate while(true){switch(state)} patterns
 * 5. Reflection annotation: annotate Class.forName(constant) calls
 */
// START_CHANGE: IMP-2026-0009-20260327-1 - Deobfuscation transformer
public final class DeobfuscationTransformer {

    private DeobfuscationTransformer() {}

    /**
     * Apply all deobfuscation transformations to the result.
     */
    public static void transform(JavaSyntaxResult result) {
        if (result == null) return;
        renameReturnTypeOverloads(result);
        Set<String> encryptMethods = detectStringEncryptionMethods(result);
        if (!encryptMethods.isEmpty()) {
            annotateEncryptedStrings(result, encryptMethods);
        }
        for (JavaSyntaxResult.MethodDeclaration method : result.getMethods()) {
            if (method.body != null) {
                detectOpaquePredicates(method.body);
                detectControlFlowFlattening(method.body);
                annotateReflectionCalls(method.body);
            }
        }
    }

    // ========== 1. Return-type overloading ==========

    private static void renameReturnTypeOverloads(JavaSyntaxResult result) {
        List<JavaSyntaxResult.MethodDeclaration> methods = result.getMethods();
        // Group by name + param descriptor
        Map<String, List<JavaSyntaxResult.MethodDeclaration>> groups =
            new HashMap<String, List<JavaSyntaxResult.MethodDeclaration>>();
        for (JavaSyntaxResult.MethodDeclaration md : methods) {
            String key = md.name + ":" + paramKey(md);
            List<JavaSyntaxResult.MethodDeclaration> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<JavaSyntaxResult.MethodDeclaration>();
                groups.put(key, group);
            }
            group.add(md);
        }
        // For groups with >1 method (return-type overloads), rename
        Map<String, String> renameMap = new HashMap<String, String>(); // old descriptor → new name
        for (Map.Entry<String, List<JavaSyntaxResult.MethodDeclaration>> entry : groups.entrySet()) {
            List<JavaSyntaxResult.MethodDeclaration> group = entry.getValue();
            if (group.size() <= 1) continue;
            for (JavaSyntaxResult.MethodDeclaration md : group) {
                String retName = md.returnType != null ? md.returnType.getName() : "void";
                // Clean the return type name for use as suffix
                retName = retName.replace("[]", "_arr").replace(".", "_");
                String newName = md.name + "_" + retName;
                renameMap.put(md.name + md.descriptor, newName);
                // Mutate the method name via reflection (MethodDeclaration.name is final)
                // We need a different approach - store rename map for writer to use
            }
        }
        // Store rename map in result for writer access
        if (!renameMap.isEmpty()) {
            result.setReturnTypeOverloadRenames(renameMap);
        }
    }

    private static String paramKey(JavaSyntaxResult.MethodDeclaration md) {
        StringBuilder sb = new StringBuilder();
        for (Type t : md.parameterTypes) {
            sb.append(t.getDescriptor()).append(",");
        }
        return sb.toString();
    }

    // ========== 2. Encrypted string detection ==========

    /**
     * Detect methods that look like string decryption routines.
     * Pattern: static method taking String, returning String, body contains XOR/shift.
     */
    private static Set<String> detectStringEncryptionMethods(JavaSyntaxResult result) {
        Set<String> encryptMethods = new HashSet<String>();
        for (JavaSyntaxResult.MethodDeclaration md : result.getMethods()) {
            if (!md.isStatic()) continue;
            if (md.body == null || md.body.isEmpty()) continue;
            // Check: returns String, takes 1 String param
            if (md.returnType == null || !"String".equals(md.returnType.getName())) continue;
            if (md.parameterTypes.size() != 1) continue;
            Type paramType = md.parameterTypes.get(0);
            if (!"String".equals(paramType.getName())) continue;
            // Check body for XOR/shift operations (typical decrypt pattern)
            if (containsXorOrShift(md.body)) {
                encryptMethods.add(md.name);
            }
        }
        return encryptMethods;
    }

    private static boolean containsXorOrShift(List<Statement> stmts) {
        for (Statement stmt : stmts) {
            if (stmtContainsXor(stmt)) return true;
        }
        return false;
    }

    private static boolean stmtContainsXor(Statement stmt) {
        if (stmt instanceof ExpressionStatement) {
            return exprContainsXor(((ExpressionStatement) stmt).getExpression());
        }
        if (stmt instanceof ReturnStatement && ((ReturnStatement) stmt).hasExpression()) {
            return exprContainsXor(((ReturnStatement) stmt).getExpression());
        }
        if (stmt instanceof WhileStatement) {
            return stmtContainsXor(((WhileStatement) stmt).getBody());
        }
        if (stmt instanceof ForStatement) {
            return stmtContainsXor(((ForStatement) stmt).getBody());
        }
        if (stmt instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) stmt).getStatements()) {
                if (stmtContainsXor(s)) return true;
            }
        }
        if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            if (vds.hasInitializer() && exprContainsXor(vds.getInitializer())) return true;
        }
        return false;
    }

    private static boolean exprContainsXor(Expression expr) {
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            if ("^".equals(boe.getOperator()) || ">>".equals(boe.getOperator())
                || "<<".equals(boe.getOperator()) || ">>>".equals(boe.getOperator())) {
                return true;
            }
            return exprContainsXor(boe.getLeft()) || exprContainsXor(boe.getRight());
        }
        if (expr instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) expr;
            if ("^=".equals(ae.getOperator())) return true;
            return exprContainsXor(ae.getRight());
        }
        if (expr instanceof CastExpression) {
            return exprContainsXor(((CastExpression) expr).getExpression());
        }
        return false;
    }

    /**
     * Walk all method bodies and annotate calls to encrypt methods.
     * Adds a CommentExpression wrapper around the call.
     */
    private static void annotateEncryptedStrings(JavaSyntaxResult result, Set<String> encryptMethods) {
        // Store the encrypt method names in the result for the writer to annotate
        result.setEncryptedStringMethods(encryptMethods);
    }

    // ========== 3. Opaque predicate detection ==========

    /**
     * Detect and simplify always-true/false conditions.
     * Known patterns: x*x >= 0, x*(x+1) % 2 == 0
     */
    private static void detectOpaquePredicates(List<Statement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            if (stmt instanceof IfStatement) {
                IfStatement is = (IfStatement) stmt;
                Boolean result = evaluateOpaquePredicate(is.getCondition());
                if (result != null && result.booleanValue()) {
                    // Always true: replace if with just the body
                    stmts.set(i, is.getThenBody());
                }
                // Always false: remove the if entirely
                if (result != null && !result.booleanValue()) {
                    stmts.remove(i);
                    i--;
                }
            }
            if (stmt instanceof IfElseStatement) {
                IfElseStatement ies = (IfElseStatement) stmt;
                Boolean result = evaluateOpaquePredicate(ies.getCondition());
                if (result != null && result.booleanValue()) {
                    stmts.set(i, ies.getThenBody());
                }
                if (result != null && !result.booleanValue()) {
                    stmts.set(i, ies.getElseBody());
                }
            }
            // Recurse into compound statements
            if (stmt instanceof WhileStatement) {
                WhileStatement ws = (WhileStatement) stmt;
                if (ws.getBody() instanceof BlockStatement) {
                    detectOpaquePredicates(((BlockStatement) ws.getBody()).getStatements());
                }
            }
            if (stmt instanceof BlockStatement) {
                detectOpaquePredicates(((BlockStatement) stmt).getStatements());
            }
        }
    }

    /**
     * Evaluate known opaque predicate patterns.
     * Returns Boolean.TRUE if always true, Boolean.FALSE if always false, null if unknown.
     */
    private static Boolean evaluateOpaquePredicate(Expression condition) {
        if (!(condition instanceof BinaryOperatorExpression)) return null;
        BinaryOperatorExpression cond = (BinaryOperatorExpression) condition;
        String op = cond.getOperator();
        Expression left = cond.getLeft();
        Expression right = cond.getRight();

        // Pattern: x*x >= 0 (always true for int)
        if (">=".equals(op) && isIntConstant(right, 0) && isSquare(left)) {
            return Boolean.TRUE;
        }
        // Pattern: x*x < 0 (always false for int)
        if ("<".equals(op) && isIntConstant(right, 0) && isSquare(left)) {
            return Boolean.FALSE;
        }
        // Pattern: (x*(x+1)) % 2 == 0 (always true - product of consecutive ints is even)
        if ("==".equals(op) && isIntConstant(right, 0) && isModulo2OfConsecutiveProduct(left)) {
            return Boolean.TRUE;
        }
        // Pattern: (x*(x+1)) % 2 != 0 (always false)
        if ("!=".equals(op) && isIntConstant(right, 0) && isModulo2OfConsecutiveProduct(left)) {
            return Boolean.FALSE;
        }

        return null;
    }

    private static boolean isIntConstant(Expression expr, int value) {
        return expr instanceof IntegerConstantExpression
            && ((IntegerConstantExpression) expr).getValue() == value;
    }

    private static boolean isSquare(Expression expr) {
        // x*x pattern
        if (!(expr instanceof BinaryOperatorExpression)) return false;
        BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
        if (!"*".equals(boe.getOperator())) return false;
        return sameVariable(boe.getLeft(), boe.getRight());
    }

    private static boolean isModulo2OfConsecutiveProduct(Expression expr) {
        if (!(expr instanceof BinaryOperatorExpression)) return false;
        BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
        if (!"%".equals(boe.getOperator())) return false;
        if (!isIntConstant(boe.getRight(), 2)) return false;
        // Check left is x*(x+1) or (x+1)*x
        return isConsecutiveProduct(boe.getLeft());
    }

    private static boolean isConsecutiveProduct(Expression expr) {
        if (!(expr instanceof BinaryOperatorExpression)) return false;
        BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
        if (!"*".equals(boe.getOperator())) return false;
        return (isVarPlusOne(boe.getRight(), boe.getLeft()) || isVarPlusOne(boe.getLeft(), boe.getRight()));
    }

    private static boolean isVarPlusOne(Expression plusOne, Expression var) {
        if (!(plusOne instanceof BinaryOperatorExpression)) return false;
        BinaryOperatorExpression boe = (BinaryOperatorExpression) plusOne;
        if (!"+".equals(boe.getOperator())) return false;
        if (!isIntConstant(boe.getRight(), 1)) return false;
        return sameVariable(boe.getLeft(), var);
    }

    private static boolean sameVariable(Expression a, Expression b) {
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            return ((LocalVariableExpression) a).getName().equals(((LocalVariableExpression) b).getName());
        }
        return false;
    }

    // ========== 4. Control flow flattening detection ==========

    /**
     * Detect while(true) { switch(stateVar) { ... } } pattern.
     * Annotate with a comment in the statement list.
     */
    private static void detectControlFlowFlattening(List<Statement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            if (!(stmt instanceof WhileStatement)) continue;
            WhileStatement ws = (WhileStatement) stmt;
            // Check: while(true) - condition is always true (BooleanExpression.TRUE or IntConst 1)
            if (!isAlwaysTrue(ws.getCondition())) continue;
            // Check body contains a single switch
            Statement body = ws.getBody();
            if (body instanceof BlockStatement) {
                List<Statement> bodyStmts = ((BlockStatement) body).getStatements();
                if (bodyStmts.size() == 1 && bodyStmts.get(0) instanceof SwitchStatement) {
                    SwitchStatement sw = (SwitchStatement) bodyStmts.get(0);
                    // Check: switch selector is a local variable
                    if (sw.getSelector() instanceof LocalVariableExpression) {
                        // This looks like control flow flattening
                        // Add annotation comment before the while
                        stmts.add(i, new CommentStatement(ws.getLineNumber(),
                            "// Obfuscation: control flow flattening detected (state machine dispatcher)"));
                        i++; // skip the comment we just added
                    }
                }
            }
        }
    }

    private static boolean isAlwaysTrue(Expression expr) {
        if (expr instanceof BooleanExpression) {
            return ((BooleanExpression) expr).getValue();
        }
        if (expr instanceof IntegerConstantExpression) {
            return ((IntegerConstantExpression) expr).getValue() != 0;
        }
        return false;
    }

    // ========== 5. Reflection annotation ==========

    /**
     * Detect Class.forName("constant") and annotate with comment.
     */
    private static void annotateReflectionCalls(List<Statement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            if (stmt instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) stmt).getExpression();
                String target = extractReflectionTarget(expr);
                if (target != null) {
                    stmts.add(i, new CommentStatement(stmt.getLineNumber(),
                        "// Reflection: target class = " + target));
                    i++;
                }
            }
            if (stmt instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
                if (vds.hasInitializer()) {
                    String target = extractReflectionTarget(vds.getInitializer());
                    if (target != null) {
                        stmts.add(i, new CommentStatement(stmt.getLineNumber(),
                            "// Reflection: target class = " + target));
                        i++;
                    }
                }
            }
            // Recurse
            if (stmt instanceof BlockStatement) {
                annotateReflectionCalls(((BlockStatement) stmt).getStatements());
            }
        }
    }

    private static String extractReflectionTarget(Expression expr) {
        // Pattern: Class.forName("com.example.Foo")
        if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            if ("forName".equals(smie.getMethodName())
                && "java/lang/Class".equals(smie.getOwnerInternalName())
                && smie.getArguments().size() >= 1) {
                Expression arg = smie.getArguments().get(0);
                if (arg instanceof StringConstantExpression) {
                    return ((StringConstantExpression) arg).getValue();
                }
            }
        }
        // Pattern: expr.getMethod("methodName")
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if ("getMethod".equals(mie.getMethodName()) || "getDeclaredMethod".equals(mie.getMethodName())) {
                String target = extractReflectionTarget(mie.getObject());
                if (target != null && mie.getArguments().size() >= 1) {
                    Expression nameArg = mie.getArguments().get(0);
                    if (nameArg instanceof StringConstantExpression) {
                        return target + "." + ((StringConstantExpression) nameArg).getValue() + "()";
                    }
                }
                return target;
            }
            // Recurse on the object for chained calls
            return extractReflectionTarget(mie.getObject());
        }
        return null;
    }
}
// END_CHANGE: IMP-2026-0009-1
