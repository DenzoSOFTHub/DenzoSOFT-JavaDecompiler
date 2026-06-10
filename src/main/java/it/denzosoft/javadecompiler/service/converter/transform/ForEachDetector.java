/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects for-each loop patterns (collection iterator and array patterns)
 * and replaces them with ForEachStatement nodes.
 */
public final class ForEachDetector {

    private ForEachDetector() {}

    public static List<Statement> convert(List<Statement> statements) {
        return convertForEachPatterns(statements);
    }

    public static List<Statement> convertForEachPatterns(List<Statement> statements) {
        if (statements == null || statements.size() < 2) return statements;

        List<Statement> result = new ArrayList<Statement>();
        int i = 0;
        while (i < statements.size()) {
            // Try to match collection for-each pattern at position i
            if (i + 1 < statements.size()) {
                Statement iterStmt = statements.get(i);
                Statement nextStmt = statements.get(i + 1);

                ForEachMatch match = matchCollectionForEach(iterStmt, nextStmt);
                if (match != null) {
                    // START_CHANGE: BUG-2026-0069-20260610-1 - Erasure-generics Stage A: the cast
                    // inside the desugared loop (`String s = (String) it.next()`) reveals the
                    // element type of the iterated collection. When the iterable is a raw JDK
                    // collection local declared earlier in the same scope, back-propagate the
                    // element type onto its declaration through the generic-signature channel
                    // (BUG-2026-0065) so `List l = Arrays.asList(...)` renders as `List<String> l`.
                    backPropagateElementType(match.forEachStatement, result);
                    // END_CHANGE: BUG-2026-0069-1
                    result.add(match.forEachStatement);
                    i += 2;
                    continue;
                }
            }

            // Try to match array for-each pattern at position i
            if (i + 3 < statements.size()) {
                ForEachMatch arrayMatch = matchArrayForEach(
                    statements.get(i), statements.get(i + 1),
                    statements.get(i + 2), statements.get(i + 3));
                if (arrayMatch != null) {
                    result.add(arrayMatch.forEachStatement);
                    i += 4;
                    continue;
                }
            }

            // Recursively process statements that contain sub-statements
            Statement stmt = statements.get(i);
            result.add(processNestedForEach(stmt));
            i++;
        }
        return result;
    }

    public static Statement processNestedForEach(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> converted = convertForEachPatterns(bs.getStatements());
            return new BlockStatement(bs.getLineNumber(), converted);
        } else if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Statement newBody = processNestedForEach(is.getThenBody());
            return new IfStatement(is.getLineNumber(), is.getCondition(), newBody);
        } else if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            Statement newThen = processNestedForEach(ies.getThenBody());
            Statement newElse = processNestedForEach(ies.getElseBody());
            return new IfElseStatement(ies.getLineNumber(), ies.getCondition(), newThen, newElse);
        } else if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            Statement newBody = processNestedForEach(ws.getBody());
            return new WhileStatement(ws.getLineNumber(), ws.getCondition(), newBody);
        } else if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt;
            Statement newTryBody = processNestedForEach(tcs.getTryBody());
            List<TryCatchStatement.CatchClause> newClauses = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                Statement newCatchBody = processNestedForEach(cc.body);
                newClauses.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, newCatchBody));
            }
            Statement newFinally = tcs.getFinallyBody() != null ? processNestedForEach(tcs.getFinallyBody()) : null;
            return new TryCatchStatement(tcs.getLineNumber(), newTryBody, newClauses, newFinally, tcs.getResources());
        }
        return stmt;
    }

    // START_CHANGE: BUG-2026-0069-20260610-2 - Erasure-generics Stage A helpers: JDK iterables
    // with exactly ONE type parameter, safe to parameterize from the loop's element type.
    // User types implementing Iterable are excluded (their type-parameter arity is unknown).
    private static final java.util.Set<String> SINGLE_PARAM_ITERABLES =
        new java.util.HashSet<String>(java.util.Arrays.asList(new String[] {
            "java/lang/Iterable", "java/util/Collection", "java/util/List",
            "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector",
            "java/util/Stack", "java/util/Set", "java/util/HashSet",
            "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/SortedSet",
            "java/util/NavigableSet", "java/util/Queue", "java/util/Deque",
            "java/util/ArrayDeque", "java/util/PriorityQueue"
        }));

    /**
     * When a detected for-each iterates a raw single-type-parameter JDK collection local with a
     * known non-Object element type, set the parameterized signature on the collection's
     * declaration (found among the already-emitted same-scope statements). Conservative: first
     * loop wins, existing signatures are never overwritten, unknown/raw element types are skipped.
     */
    // START_CHANGE: BUG-2026-0069-20260610-19 - Stage A extension: a raw JDK Map iterated via
    // entrySet()/keySet()/values() leaves the loop element as Object and fails to recompile.
    // Parameterize the map declaration: entrySet -> <Object,Object>, keySet -> <E,Object>,
    // values -> <Object,E>. First loop wins (signature never overwritten, same as below).
    private static final java.util.Set<String> TWO_PARAM_MAPS =
        new java.util.HashSet<String>(java.util.Arrays.asList(new String[] {
            "java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap",
            "java/util/TreeMap", "java/util/Hashtable", "java/util/SortedMap",
            "java/util/NavigableMap", "java/util/IdentityHashMap", "java/util/WeakHashMap",
            "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentMap"
        }));
    // END_CHANGE: BUG-2026-0069-19

    // START_CHANGE: BUG-2026-0069-20260610-21 - Second back-prop pass, run AFTER the
    // BUG-2026-0077 promotion has materialized first-store declarations: at ForEachDetector
    // time a reused/late slot is still a bare assignment, so the signature has nowhere to go.
    public static void backPropagateSignatures(List<Statement> stmts) {
        if (stmts == null) return;
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ForEachStatement) {
                backPropagateElementType((ForEachStatement) s, stmts.subList(0, i));
                backPropagateSignaturesInto(((ForEachStatement) s).getBody());
            } else {
                backPropagateSignaturesInto(s);
            }
        }
    }

    private static void backPropagateSignaturesInto(Statement s) {
        if (s instanceof BlockStatement) {
            backPropagateSignatures(((BlockStatement) s).getStatements());
        } else if (s instanceof IfStatement) {
            backPropagateSignaturesInto(((IfStatement) s).getThenBody());
        } else if (s instanceof IfElseStatement) {
            backPropagateSignaturesInto(((IfElseStatement) s).getThenBody());
            backPropagateSignaturesInto(((IfElseStatement) s).getElseBody());
        } else if (s instanceof WhileStatement) {
            backPropagateSignaturesInto(((WhileStatement) s).getBody());
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) s;
            backPropagateSignaturesInto(tcs.getTryBody());
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                backPropagateSignaturesInto(cc.body);
            }
            if (tcs.getFinallyBody() != null) backPropagateSignaturesInto(tcs.getFinallyBody());
        }
    }
    // END_CHANGE: BUG-2026-0069-21

    private static void backPropagateElementType(ForEachStatement fes, List<Statement> emitted) {
        // START_CHANGE: BUG-2026-0069-20260610-20 - raw-Map view iteration (see TWO_PARAM_MAPS)
        if (fes.getIterable() instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) fes.getIterable();
            String vm = mie.getMethodName();
            if (("entrySet".equals(vm) || "keySet".equals(vm) || "values".equals(vm))
                    && (mie.getArguments() == null || mie.getArguments().isEmpty())
                    && mie.getObject() instanceof LocalVariableExpression) {
                String mapName = ((LocalVariableExpression) mie.getObject()).getName();
                Type elem = fes.getVariableType();
                String elemInt = (elem instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType
                        && elem.getDimension() == 0)
                    ? ((it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) elem).getInternalName()
                    : null;
                for (int k = emitted.size() - 1; k >= 0; k--) {
                    Statement s = emitted.get(k);
                    if (!(s instanceof VariableDeclarationStatement)) continue;
                    VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
                    if (!mapName.equals(vds.getName())) continue;
                    if (vds.getGenericSignature() != null) return;
                    Type dt = vds.getType();
                    if (!(dt instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType)
                        || dt.getDimension() != 0) return;
                    String rawMap =
                        ((it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) dt).getInternalName();
                    if (!TWO_PARAM_MAPS.contains(rawMap)) return;
                    String kSig = "Ljava/lang/Object;";
                    String vSig = "Ljava/lang/Object;";
                    if ("entrySet".equals(vm)) {
                        // element must be Map.Entry (raw) for <Object,Object> to assign
                        if (elemInt != null && !"java/util/Map$Entry".equals(elemInt)
                                && !"java/lang/Object".equals(elemInt)) return;
                    } else if (elemInt != null && !"java/lang/Object".equals(elemInt)) {
                        if ("keySet".equals(vm)) kSig = "L" + elemInt + ";";
                        else vSig = "L" + elemInt + ";";
                    }
                    vds.setGenericSignature("L" + rawMap + "<" + kSig + vSig + ">;");
                    return;
                }
                return;
            }
        }
        // END_CHANGE: BUG-2026-0069-20
        if (!(fes.getIterable() instanceof LocalVariableExpression)) return;
        Type elemType = fes.getVariableType();
        if (!(elemType instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType)
            || elemType.getDimension() != 0) return;
        String elemInternal =
            ((it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) elemType).getInternalName();
        if (elemInternal == null || "java/lang/Object".equals(elemInternal)) return;
        String iterName = ((LocalVariableExpression) fes.getIterable()).getName();
        for (int k = emitted.size() - 1; k >= 0; k--) {
            Statement s = emitted.get(k);
            if (!(s instanceof VariableDeclarationStatement)) continue;
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (!iterName.equals(vds.getName())) continue;
            if (vds.getGenericSignature() != null) return;
            Type dt = vds.getType();
            if (!(dt instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType)
                || dt.getDimension() != 0) return;
            String rawInternal =
                ((it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType) dt).getInternalName();
            if (!SINGLE_PARAM_ITERABLES.contains(rawInternal)) return;
            vds.setGenericSignature("L" + rawInternal + "<L" + elemInternal + ";>;");
            return;
        }
    }
    // END_CHANGE: BUG-2026-0069-2

    // --- Pattern matching ---

    private static class ForEachMatch {
        final ForEachStatement forEachStatement;
        ForEachMatch(ForEachStatement forEachStatement) {
            this.forEachStatement = forEachStatement;
        }
    }

    private static ForEachMatch matchCollectionForEach(Statement iterStmt, Statement whileStmt) {
        if (!(whileStmt instanceof WhileStatement)) return null;

        String iterVarName = null;
        Expression collectionExpr = null;

        if (iterStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) iterStmt;
            if (vds.hasInitializer() && isIteratorCall(vds.getInitializer())) {
                iterVarName = vds.getName();
                collectionExpr = getIteratorReceiver(vds.getInitializer());
            }
        } else if (iterStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) iterStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression && isIteratorCall(ae.getRight())) {
                    iterVarName = ((LocalVariableExpression) ae.getLeft()).getName();
                    collectionExpr = getIteratorReceiver(ae.getRight());
                }
            }
        }

        if (iterVarName == null || collectionExpr == null) return null;

        WhileStatement ws = (WhileStatement) whileStmt;
        if (!isHasNextCondition(ws.getCondition(), iterVarName)) return null;

        List<Statement> bodyStmts = getStatementList(ws.getBody());
        if (bodyStmts == null || bodyStmts.isEmpty()) return null;

        Statement firstBodyStmt = bodyStmts.get(0);
        String elementVarName = null;
        Type elementType = null;

        if (firstBodyStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) firstBodyStmt;
            if (vds.hasInitializer() && isNextCallOrCast(vds.getInitializer(), iterVarName)) {
                elementVarName = vds.getName();
                elementType = vds.getType();
            }
        } else if (firstBodyStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) firstBodyStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression && isNextCallOrCast(ae.getRight(), iterVarName)) {
                    elementVarName = ((LocalVariableExpression) ae.getLeft()).getName();
                    elementType = ae.getLeft().getType();
                }
            }
        }

        if (elementVarName == null || elementType == null) return null;

        List<Statement> forEachBody = new ArrayList<Statement>();
        for (int j = 1; j < bodyStmts.size(); j++) {
            forEachBody.add(bodyStmts.get(j));
        }

        int lineNum = iterStmt.getLineNumber();
        Statement body = new BlockStatement(lineNum, forEachBody);
        ForEachStatement fes = new ForEachStatement(lineNum, elementType, elementVarName, collectionExpr, body);
        return new ForEachMatch(fes);
    }

    private static ForEachMatch matchArrayForEach(Statement arrayCopyStmt, Statement lengthStmt,
                                                   Statement counterInitStmt, Statement whileStmt) {
        if (!(whileStmt instanceof WhileStatement)) return null;

        String arrCopyVar = null;
        Expression arrayExpr = null;
        if (arrayCopyStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) arrayCopyStmt;
            if (vds.hasInitializer()) {
                arrCopyVar = vds.getName();
                arrayExpr = vds.getInitializer();
            }
        } else if (arrayCopyStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) arrayCopyStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    arrCopyVar = ((LocalVariableExpression) ae.getLeft()).getName();
                    arrayExpr = ae.getRight();
                }
            }
        }
        if (arrCopyVar == null || arrayExpr == null) return null;

        String lengthVar = null;
        if (lengthStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) lengthStmt;
            if (vds.hasInitializer() && isLengthAccess(vds.getInitializer(), arrCopyVar)) {
                lengthVar = vds.getName();
            }
        } else if (lengthStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) lengthStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression && isLengthAccess(ae.getRight(), arrCopyVar)) {
                    lengthVar = ((LocalVariableExpression) ae.getLeft()).getName();
                }
            }
        }
        if (lengthVar == null) return null;

        String counterVar = null;
        if (counterInitStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) counterInitStmt;
            if (vds.hasInitializer() && isZeroConstant(vds.getInitializer())) {
                counterVar = vds.getName();
            }
        } else if (counterInitStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) counterInitStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression && isZeroConstant(ae.getRight())) {
                    counterVar = ((LocalVariableExpression) ae.getLeft()).getName();
                }
            }
        }
        if (counterVar == null) return null;

        WhileStatement ws = (WhileStatement) whileStmt;
        if (!isCounterLessThanLength(ws.getCondition(), counterVar, lengthVar)) return null;

        List<Statement> bodyStmts = getStatementList(ws.getBody());
        if (bodyStmts == null || bodyStmts.size() < 2) return null;

        Statement firstBodyStmt = bodyStmts.get(0);
        String elementVarName = null;
        Type elementType = null;

        if (firstBodyStmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) firstBodyStmt;
            if (vds.hasInitializer() && isArrayAccess(vds.getInitializer(), arrCopyVar, counterVar)) {
                elementVarName = vds.getName();
                elementType = vds.getType();
            }
        } else if (firstBodyStmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) firstBodyStmt).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression && isArrayAccess(ae.getRight(), arrCopyVar, counterVar)) {
                    elementVarName = ((LocalVariableExpression) ae.getLeft()).getName();
                    elementType = ae.getLeft().getType();
                }
            }
        }

        if (elementVarName == null || elementType == null) return null;

        // BUG-2026-0080 (RC-6): the element type of an array foreach is the array's component type, not the
        // (sometimes mis-decoded) type of the synthetic element-copy local.
        if (arrayExpr != null && arrayExpr.getType() instanceof it.denzosoft.javadecompiler.model.javasyntax.type.ArrayType) {
            elementType = ((it.denzosoft.javadecompiler.model.javasyntax.type.ArrayType) arrayExpr.getType()).getElementType();
        }

        Statement lastBodyStmt = bodyStmts.get(bodyStmts.size() - 1);
        if (!isCounterIncrement(lastBodyStmt, counterVar)) return null;

        List<Statement> forEachBody = new ArrayList<Statement>();
        for (int j = 1; j < bodyStmts.size() - 1; j++) {
            forEachBody.add(bodyStmts.get(j));
        }

        int lineNum = arrayCopyStmt.getLineNumber();
        Statement body = new BlockStatement(lineNum, forEachBody);
        ForEachStatement fes = new ForEachStatement(lineNum, elementType, elementVarName, arrayExpr, body);
        return new ForEachMatch(fes);
    }

    // --- Helper methods ---

    public static boolean isIteratorCall(Expression expr) {
        if (expr instanceof MethodInvocationExpression) {
            return "iterator".equals(((MethodInvocationExpression) expr).getMethodName());
        }
        return false;
    }

    private static Expression getIteratorReceiver(Expression expr) {
        if (expr instanceof MethodInvocationExpression) {
            return ((MethodInvocationExpression) expr).getObject();
        }
        return null;
    }

    public static boolean isHasNextCondition(Expression condition, String iterVarName) {
        if (condition instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) condition;
            if (isHasNextCall(boe.getLeft(), iterVarName)) return true;
            if (isHasNextCall(boe.getRight(), iterVarName)) return true;
        }
        if (condition instanceof MethodInvocationExpression) {
            return isHasNextCall(condition, iterVarName);
        }
        return false;
    }

    private static boolean isHasNextCall(Expression expr, String iterVarName) {
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if ("hasNext".equals(mie.getMethodName())) {
                Expression obj = mie.getObject();
                if (obj instanceof LocalVariableExpression) {
                    return iterVarName.equals(((LocalVariableExpression) obj).getName());
                }
            }
        }
        return false;
    }

    public static boolean isNextCallOrCast(Expression expr, String iterVarName) {
        if (expr instanceof CastExpression) {
            return isNextCallOrCast(((CastExpression) expr).getExpression(), iterVarName);
        }
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if ("next".equals(mie.getMethodName())) {
                Expression obj = mie.getObject();
                if (obj instanceof LocalVariableExpression) {
                    return iterVarName.equals(((LocalVariableExpression) obj).getName());
                }
            }
        }
        return false;
    }

    public static boolean isLengthAccess(Expression expr, String arrVarName) {
        if (expr instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) expr;
            if ("length".equals(fae.getName())) {
                Expression obj = fae.getObject();
                if (obj instanceof LocalVariableExpression) {
                    return arrVarName.equals(((LocalVariableExpression) obj).getName());
                }
            }
        }
        return false;
    }

    public static boolean isCounterLessThanLength(Expression condition, String counterVar, String lengthVar) {
        if (condition instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) condition;
            String op = boe.getOperator();
            if ("<".equals(op)) {
                return isVarRef(boe.getLeft(), counterVar) && isVarRef(boe.getRight(), lengthVar);
            }
            if (">=".equals(op)) {
                return isVarRef(boe.getLeft(), counterVar) && isVarRef(boe.getRight(), lengthVar);
            }
        }
        return false;
    }

    public static boolean isVarRef(Expression expr, String varName) {
        if (expr instanceof LocalVariableExpression) {
            return varName.equals(((LocalVariableExpression) expr).getName());
        }
        return false;
    }

    public static boolean isArrayAccess(Expression expr, String arrVarName, String counterVar) {
        if (expr instanceof ArrayAccessExpression) {
            ArrayAccessExpression aae = (ArrayAccessExpression) expr;
            return isVarRef(aae.getArray(), arrVarName) && isVarRef(aae.getIndex(), counterVar);
        }
        return false;
    }

    public static boolean isCounterIncrement(Statement stmt, String counterVar) {
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof UnaryOperatorExpression) {
                UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
                if ("++".equals(uoe.getOperator())) {
                    return isVarRef(uoe.getExpression(), counterVar);
                }
            }
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (isVarRef(ae.getLeft(), counterVar)) {
                    if (ae.getRight() instanceof BinaryOperatorExpression) {
                        BinaryOperatorExpression boe = (BinaryOperatorExpression) ae.getRight();
                        if ("+".equals(boe.getOperator()) && isVarRef(boe.getLeft(), counterVar)) {
                            if (boe.getRight() instanceof IntegerConstantExpression) {
                                return ((IntegerConstantExpression) boe.getRight()).getValue() == 1;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isZeroConstant(Expression expr) {
        return expr instanceof IntegerConstantExpression && ((IntegerConstantExpression) expr).getValue() == 0;
    }

    private static List<Statement> getStatementList(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            return ((BlockStatement) stmt).getStatements();
        }
        List<Statement> list = new ArrayList<Statement>();
        list.add(stmt);
        return list;
    }
}
