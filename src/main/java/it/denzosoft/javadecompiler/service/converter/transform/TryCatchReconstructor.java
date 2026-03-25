/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;
import it.denzosoft.javadecompiler.service.converter.cfg.BasicBlock;
import it.denzosoft.javadecompiler.service.converter.cfg.ControlFlowGraph;

import java.util.*;

/**
 * Wraps decompiled statement lists with try-catch-finally blocks
 * by analysing the exception table from a Code attribute.
 */
public class TryCatchReconstructor {

    private final ControlFlowGraph cfg;
    private final Map<Integer, Integer> pcToLine;
    private final Map<Integer, String> localVarNames;
    private final byte[] bytecode;
    private final ConstantPool pool;
    // START_CHANGE: ISS-2026-0005-20260324-6 - Handler PC to exception variable name map
    private final Map<Integer, String> handlerVarNames;
    // END_CHANGE: ISS-2026-0005-6

    public TryCatchReconstructor(ControlFlowGraph cfg,
                                  Map<Integer, Integer> pcToLine,
                                  Map<Integer, String> localVarNames,
                                  byte[] bytecode,
                                  ConstantPool pool) {
        this(cfg, pcToLine, localVarNames, bytecode, pool, new HashMap<Integer, String>());
    }

    // START_CHANGE: ISS-2026-0005-20260324-7 - Constructor with handler var name map
    public TryCatchReconstructor(ControlFlowGraph cfg,
                                  Map<Integer, Integer> pcToLine,
                                  Map<Integer, String> localVarNames,
                                  byte[] bytecode,
                                  ConstantPool pool,
                                  Map<Integer, String> handlerVarNames) {
        this.cfg = cfg;
        this.pcToLine = pcToLine;
        this.localVarNames = localVarNames;
        this.bytecode = bytecode;
        this.pool = pool;
        this.handlerVarNames = handlerVarNames;
    }
    // END_CHANGE: ISS-2026-0005-7

    public List<Statement> reconstruct(List<Statement> statements,
                                        CodeAttribute.ExceptionEntry[] exceptionTable) {
        return wrapWithTryCatch(statements, exceptionTable);
    }

    private List<Statement> wrapWithTryCatch(List<Statement> statements,
                                              CodeAttribute.ExceptionEntry[] exceptionTable) {
        if (exceptionTable == null || exceptionTable.length == 0) {
            return statements;
        }

        // Group exception entries by (startPc, endPc) - same try region
        Map<String, List<CodeAttribute.ExceptionEntry>> groups =
            new LinkedHashMap<String, List<CodeAttribute.ExceptionEntry>>();
        for (int i = 0; i < exceptionTable.length; i++) {
            CodeAttribute.ExceptionEntry entry = exceptionTable[i];
            String key = entry.startPc + "-" + entry.endPc;
            List<CodeAttribute.ExceptionEntry> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<CodeAttribute.ExceptionEntry>();
                groups.put(key, list);
            }
            list.add(entry);
        }

        // Build a sorted array of all PCs that have line numbers
        List<Integer> sortedPcs = new ArrayList<Integer>(pcToLine.keySet());
        Collections.sort(sortedPcs);

        // Process groups in reverse order of startPc so inner try-catch is wrapped first
        List<String> groupKeys = new ArrayList<String>(groups.keySet());
        Collections.sort(groupKeys, new Comparator<String>() {
            public int compare(String a, String b) {
                int aStart = Integer.parseInt(a.split("-")[0]);
                int bStart = Integer.parseInt(b.split("-")[0]);
                return bStart - aStart;
            }
        });

        for (String key : groupKeys) {
            List<CodeAttribute.ExceptionEntry> groupEntries = groups.get(key);
            CodeAttribute.ExceptionEntry firstEntry = groupEntries.get(0);
            int tryStartPc = firstEntry.startPc;
            int tryEndPc = firstEntry.endPc;

            int tryStartLine = findLineForPc(tryStartPc, sortedPcs);
            int tryEndLine = findLineBeforePc(tryEndPc, sortedPcs);

            if (tryStartLine < 0) continue;

            List<Statement> tryBody = new ArrayList<Statement>();
            List<Statement> beforeTry = new ArrayList<Statement>();
            List<Statement> afterTry = new ArrayList<Statement>();

            int firstHandlerPc = Integer.MAX_VALUE;
            for (CodeAttribute.ExceptionEntry entry : groupEntries) {
                if (entry.handlerPc < firstHandlerPc) {
                    firstHandlerPc = entry.handlerPc;
                }
            }
            int firstHandlerLine = findLineForPc(firstHandlerPc, sortedPcs);

            boolean inTryRegion = false;
            for (Statement s : statements) {
                int sLine = s.getLineNumber();
                if (sLine > 0 && sLine >= tryStartLine && sLine <= tryEndLine) {
                    inTryRegion = true;
                    tryBody.add(s);
                } else if (!inTryRegion) {
                    beforeTry.add(s);
                } else {
                    if (firstHandlerLine > 0 && sLine >= firstHandlerLine) {
                        continue;
                    }
                    afterTry.add(s);
                }
            }

            if (tryBody.isEmpty()) continue;

            List<TryCatchStatement.CatchClause> catchClauses =
                new ArrayList<TryCatchStatement.CatchClause>();
            Statement finallyBody = null;

            List<CodeAttribute.ExceptionEntry> sortedEntries =
                new ArrayList<CodeAttribute.ExceptionEntry>(groupEntries);
            Collections.sort(sortedEntries, new Comparator<CodeAttribute.ExceptionEntry>() {
                public int compare(CodeAttribute.ExceptionEntry a, CodeAttribute.ExceptionEntry b) {
                    return a.handlerPc - b.handlerPc;
                }
            });

            int mergePc = findTryCatchMergePc(tryEndPc);

            // Group entries by handlerPc to merge multi-catch (same handler, different exception types)
            Map<Integer, List<CodeAttribute.ExceptionEntry>> handlerGroups =
                new LinkedHashMap<Integer, List<CodeAttribute.ExceptionEntry>>();
            for (int hi = 0; hi < sortedEntries.size(); hi++) {
                CodeAttribute.ExceptionEntry entry = sortedEntries.get(hi);
                Integer hpc = Integer.valueOf(entry.handlerPc);
                List<CodeAttribute.ExceptionEntry> group = handlerGroups.get(hpc);
                if (group == null) {
                    group = new ArrayList<CodeAttribute.ExceptionEntry>();
                    handlerGroups.put(hpc, group);
                }
                group.add(entry);
            }

            for (Map.Entry<Integer, List<CodeAttribute.ExceptionEntry>> hgEntry : handlerGroups.entrySet()) {
                int handlerPc = hgEntry.getKey().intValue();
                List<CodeAttribute.ExceptionEntry> hgEntries = hgEntry.getValue();
                CodeAttribute.ExceptionEntry firstHEntry = hgEntries.get(0);

                // Decode handler body once for the shared handlerPc
                // Find the index in sortedEntries for the first entry of this group
                int hiFirst = 0;
                for (int hi = 0; hi < sortedEntries.size(); hi++) {
                    if (sortedEntries.get(hi) == firstHEntry) {
                        hiFirst = hi;
                        break;
                    }
                }
                List<Statement> handlerBody = decodeHandlerBlocks(
                    handlerPc, sortedEntries, hiFirst, mergePc);

                String varName = findExceptionVarName(handlerPc);

                // START_CHANGE: ISS-2026-0005-20260324-12 - Rename misnamed exception variable in handler body
                // If the handler's first astore uses a slot with a different name in localVarNames,
                // rename all references in the handler body to the correct exception variable name.
                String slotName = findSlotNameAtHandler(handlerPc);
                if (slotName != null && !slotName.equals(varName)) {
                    handlerBody = renameVarInStatements(handlerBody, slotName, varName);
                }
                // Also rename synthetic var names (e.g., "var3") that come from unnamed slots
                if (slotName == null) {
                    int handlerSlot = findSlotAtHandler(handlerPc);
                    if (handlerSlot >= 0) {
                        String syntheticName = "var" + handlerSlot;
                        if (!syntheticName.equals(varName)) {
                            handlerBody = renameVarInStatements(handlerBody, syntheticName, varName);
                        }
                    }
                }
                // END_CHANGE: ISS-2026-0005-12

                handlerBody = removeInitialExceptionStore(handlerBody, varName);

                int handlerLine = findLineForPc(handlerPc, sortedPcs);
                if (handlerLine < 0) handlerLine = tryStartLine;

                // Check if all entries in this group are typed (catch) or untyped (finally)
                boolean allTyped = true;
                boolean anyUntyped = false;
                for (CodeAttribute.ExceptionEntry he : hgEntries) {
                    if (he.catchType <= 0) {
                        anyUntyped = true;
                        allTyped = false;
                    }
                }

                if (allTyped) {
                    // Strip addSuppressed boilerplate from catch bodies
                    handlerBody = stripAddSuppressed(handlerBody);
                    // Merge all exception types into a single multi-catch clause
                    List<Type> types = new ArrayList<Type>();
                    for (CodeAttribute.ExceptionEntry he : hgEntries) {
                        String typeName = pool.getClassName(he.catchType);
                        if (typeName == null) {
                            typeName = "java/lang/Exception";
                        }
                        types.add(new ObjectType(typeName));
                    }
                    Statement catchBody = new BlockStatement(handlerLine,
                        handlerBody.isEmpty() ? new ArrayList<Statement>() : handlerBody);
                    catchClauses.add(new TryCatchStatement.CatchClause(
                        types, varName, catchBody));
                } else if (anyUntyped) {
                    List<Statement> filteredFinally = filterFinallyBody(handlerBody);
                    if (!filteredFinally.isEmpty()) {
                        finallyBody = new BlockStatement(handlerLine, filteredFinally);
                    }
                }
            }

            if (catchClauses.isEmpty() && finallyBody == null) continue;

            if (finallyBody != null && finallyBody instanceof BlockStatement) {
                int finallySize = ((BlockStatement) finallyBody).getStatements().size();
                if (finallySize > 0) {
                    tryBody = removeDuplicatedFinally(tryBody, finallySize);

                    List<TryCatchStatement.CatchClause> cleanedClauses =
                        new ArrayList<TryCatchStatement.CatchClause>();
                    for (TryCatchStatement.CatchClause cc : catchClauses) {
                        if (cc.body instanceof BlockStatement) {
                            List<Statement> cleanedBody = removeDuplicatedFinally(
                                ((BlockStatement) cc.body).getStatements(), finallySize);
                            cleanedClauses.add(new TryCatchStatement.CatchClause(
                                cc.exceptionTypes, cc.variableName,
                                new BlockStatement(cc.body.getLineNumber(), cleanedBody)));
                        } else {
                            cleanedClauses.add(cc);
                        }
                    }
                    catchClauses = cleanedClauses;
                }
            }

            // Validate try-catch quality: if the try body is empty or only contains
            // trivial statements (variable declarations/assignments), skip the try-catch
            // wrapper. This handles try-with-resources patterns where the compiler generates
            // complex nested try-catch for resource management but the actual body is lost
            // during decompilation. Emitting the original linear statements is more compilable.
            if (isTryBodyTrivial(tryBody) && isTryWithResourcesPattern(catchClauses, finallyBody)) {
                continue; // skip this exception group - emit original statements unchanged
            }

            TryCatchStatement tcs = new TryCatchStatement(
                tryStartLine,
                new BlockStatement(tryStartLine, tryBody),
                catchClauses,
                finallyBody,
                null);

            List<Statement> newStatements = new ArrayList<Statement>();
            newStatements.addAll(beforeTry);
            newStatements.add(tcs);
            newStatements.addAll(afterTry);
            statements = newStatements;
        }

        return statements;
    }

    /**
     * Check if the try body is trivial (empty or only variable declarations/assignments).
     * A trivial try body means the decompiler failed to capture the real method logic
     * inside the try block.
     */
    private boolean isTryBodyTrivial(List<Statement> tryBody) {
        if (tryBody.isEmpty()) return true;
        for (Statement s : tryBody) {
            if (s instanceof VariableDeclarationStatement) {
                continue; // trivial
            }
            if (s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                if (expr instanceof AssignmentExpression) {
                    continue; // trivial assignment
                }
            }
            return false; // non-trivial statement found
        }
        return true;
    }

    /**
     * Check if this looks like a try-with-resources compiler pattern.
     * Indicators: catches Throwable, or has finally with close() call,
     * or catch body references addSuppressed.
     */
    private boolean isTryWithResourcesPattern(List<TryCatchStatement.CatchClause> catchClauses,
                                               Statement finallyBody) {
        // Check if any catch clause catches Throwable (compiler-generated)
        for (TryCatchStatement.CatchClause cc : catchClauses) {
            if (cc.exceptionTypes != null) {
                for (Type t : cc.exceptionTypes) {
                    if (t instanceof ObjectType) {
                        String name = ((ObjectType) t).getInternalName();
                        if ("java/lang/Throwable".equals(name) || "Throwable".equals(name)) {
                            return true;
                        }
                    }
                }
            }
        }
        // Check if finally body contains close() call (resource cleanup)
        if (finallyBody instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) finallyBody).getStatements()) {
                if (s instanceof ExpressionStatement) {
                    Expression expr = ((ExpressionStatement) s).getExpression();
                    if (expr instanceof MethodInvocationExpression) {
                        String methodName = ((MethodInvocationExpression) expr).getMethodName();
                        if ("close".equals(methodName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private List<Statement> decodeHandlerBlocks(int handlerPc,
                                                 List<CodeAttribute.ExceptionEntry> sortedEntries,
                                                 int currentIndex,
                                                 int mergePc) {
        List<Statement> result = new ArrayList<Statement>();
        BasicBlock handlerBlock = cfg.getBlockAtPc(handlerPc);
        if (handlerBlock == null) return result;

        Set<Integer> stopPcs = new HashSet<Integer>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            // Skip entries that share the same handlerPc as the current entry
            // (multi-catch handlers) - they should not be stop points
            if (sortedEntries.get(i).handlerPc != handlerPc) {
                stopPcs.add(sortedEntries.get(i).handlerPc);
            }
        }
        if (mergePc >= 0) {
            stopPcs.add(mergePc);
        }

        Set<Integer> visited = new HashSet<Integer>();
        collectHandlerStatements(handlerBlock, result, visited, stopPcs);

        return result;
    }

    private int findTryCatchMergePc(int tryEndPc) {
        BasicBlock gotoBlock = cfg.getBlockAtPc(tryEndPc);
        if (gotoBlock != null && gotoBlock.isGoto()) {
            return gotoBlock.branchTargetPc;
        }

        for (BasicBlock block : cfg.getBlocks()) {
            if (block.endPc == tryEndPc && block.isGoto()) {
                return block.branchTargetPc;
            }
        }

        if (gotoBlock != null) {
            return tryEndPc;
        }
        return -1;
    }

    private void collectHandlerStatements(BasicBlock block, List<Statement> output,
                                           Set<Integer> visited, Set<Integer> stopPcs) {
        while (block != null) {
            if (visited.contains(block.startPc)) return;
            if (stopPcs.contains(block.startPc)) return;
            visited.add(block.startPc);

            if (block.statements != null && !block.statements.isEmpty()) {
                output.addAll(block.statements);
            }

            if (block.isReturn() || block.isThrow()) {
                return;
            } else if (block.isGoto()) {
                BasicBlock target = block.trueSuccessor;
                if (target != null && target.startPc > block.startPc
                    && !stopPcs.contains(target.startPc)) {
                    block = target;
                } else {
                    return;
                }
            } else if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
                block = block.trueSuccessor;
            } else {
                return;
            }
        }
    }

    public int findLineForPc(int pc, List<Integer> sortedPcs) {
        Integer line = pcToLine.get(pc);
        if (line != null) return line.intValue();

        int result = -1;
        for (int i = 0; i < sortedPcs.size(); i++) {
            int sortedPc = sortedPcs.get(i).intValue();
            if (sortedPc <= pc) {
                result = pcToLine.get(sortedPcs.get(i)).intValue();
            } else {
                break;
            }
        }
        return result;
    }

    public int findLineBeforePc(int pc, List<Integer> sortedPcs) {
        int result = -1;
        for (int i = 0; i < sortedPcs.size(); i++) {
            int sortedPc = sortedPcs.get(i).intValue();
            if (sortedPc < pc) {
                result = pcToLine.get(sortedPcs.get(i)).intValue();
            } else {
                break;
            }
        }
        return result;
    }

    public String findExceptionVarName(int handlerPc) {
        // START_CHANGE: ISS-2026-0005-20260324-8 - Prefer handler-specific var name from LVT
        if (handlerVarNames != null) {
            String hvName = handlerVarNames.get(handlerPc);
            if (hvName != null) return hvName;
        }
        // END_CHANGE: ISS-2026-0005-8
        if (bytecode == null || handlerPc >= bytecode.length) return "e";

        int opcode = bytecode[handlerPc] & 0xFF;
        int varIndex = -1;

        if (opcode == 0x3A) {
            if (handlerPc + 1 < bytecode.length) {
                varIndex = bytecode[handlerPc + 1] & 0xFF;
            }
        } else if (opcode >= 0x4B && opcode <= 0x4E) {
            varIndex = opcode - 0x4B;
        }

        if (varIndex >= 0) {
            String name = localVarNames.get(varIndex);
            if (name != null) return name;
            // Use the same auto-generated name format as the bytecode decoder
            return "var" + varIndex;
        }

        return "e";
    }

    public static List<Statement> removeInitialExceptionStore(List<Statement> handlerBody, String varName) {
        if (handlerBody.isEmpty()) return handlerBody;

        Statement first = handlerBody.get(0);
        boolean shouldRemove = false;

        if (first instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) first;
            // START_CHANGE: ISS-2026-0005-20260324-9 - Remove initial exception store by name match or slot reuse
            if (varName.equals(vds.getName()) || isExceptionStoreAssignment(first)) {
                shouldRemove = true;
            }
            // END_CHANGE: ISS-2026-0005-9
        } else if (first instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) first).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    String name = ((LocalVariableExpression) ae.getLeft()).getName();
                    // START_CHANGE: ISS-2026-0005-20260324-10 - Also remove if RHS is null (exception store with reused slot)
                    if (varName.equals(name) || ae.getRight() instanceof NullExpression) {
                        shouldRemove = true;
                    }
                    // END_CHANGE: ISS-2026-0005-10
                }
            }
        }

        if (shouldRemove) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 1; i < handlerBody.size(); i++) {
                filtered.add(handlerBody.get(i));
            }
            return filtered;
        }
        return handlerBody;
    }

    // START_CHANGE: ISS-2026-0005-20260324-11 - Check if a statement is a null-assignment (exception store artifact)
    private static boolean isExceptionStoreAssignment(Statement stmt) {
        if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            return vds.hasInitializer() && vds.getInitializer() instanceof NullExpression;
        }
        return false;
    }
    // END_CHANGE: ISS-2026-0005-11

    // START_CHANGE: ISS-2026-0005-20260324-13 - Find slot variable name at handler PC from localVarNames
    private String findSlotNameAtHandler(int handlerPc) {
        if (bytecode == null || handlerPc >= bytecode.length) return null;
        int varIndex = findSlotAtHandler(handlerPc);
        if (varIndex >= 0) {
            return localVarNames.get(varIndex);
        }
        return null;
    }

    private int findSlotAtHandler(int handlerPc) {
        if (bytecode == null || handlerPc >= bytecode.length) return -1;
        int opcode = bytecode[handlerPc] & 0xFF;
        if (opcode == 0x3A && handlerPc + 1 < bytecode.length) {
            return bytecode[handlerPc + 1] & 0xFF;
        } else if (opcode >= 0x4B && opcode <= 0x4E) {
            return opcode - 0x4B;
        }
        return -1;
    }

    private static List<Statement> renameVarInStatements(List<Statement> stmts, String oldName, String newName) {
        List<Statement> result = new ArrayList<Statement>(stmts.size());
        for (Statement s : stmts) {
            result.add(renameVarInStatement(s, oldName, newName));
        }
        return result;
    }

    private static Statement renameVarInStatement(Statement s, String oldName, String newName) {
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            Expression renamed = renameVarInExpression(expr, oldName, newName);
            if (renamed != expr) {
                return new ExpressionStatement(renamed);
            }
        } else if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (oldName.equals(vds.getName())) {
                return new VariableDeclarationStatement(vds.getLineNumber(), vds.getType(), newName,
                    vds.hasInitializer() ? renameVarInExpression(vds.getInitializer(), oldName, newName) : null,
                    vds.isFinal(), vds.isVar());
            }
        } else if (s instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) s;
            if (rs.hasExpression()) {
                Expression renamed = renameVarInExpression(rs.getExpression(), oldName, newName);
                if (renamed != rs.getExpression()) {
                    return new ReturnStatement(rs.getLineNumber(), renamed);
                }
            }
        } else if (s instanceof ThrowStatement) {
            ThrowStatement ts = (ThrowStatement) s;
            Expression renamed = renameVarInExpression(ts.getExpression(), oldName, newName);
            if (renamed != ts.getExpression()) {
                return new ThrowStatement(ts.getLineNumber(), renamed);
            }
        } else if (s instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) s;
            return new BlockStatement(bs.getLineNumber(), renameVarInStatements(bs.getStatements(), oldName, newName));
        }
        return s;
    }

    private static Expression renameVarInExpression(Expression expr, String oldName, String newName) {
        if (expr instanceof LocalVariableExpression) {
            LocalVariableExpression lve = (LocalVariableExpression) expr;
            if (oldName.equals(lve.getName())) {
                return new LocalVariableExpression(lve.getLineNumber(), lve.getType(), newName, lve.getIndex());
            }
        } else if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            Expression obj = renameVarInExpression(mie.getObject(), oldName, newName);
            List<Expression> args = renameVarInExpressions(mie.getArguments(), oldName, newName);
            if (obj != mie.getObject() || args != mie.getArguments()) {
                return new MethodInvocationExpression(mie.getLineNumber(), mie.getType(), obj,
                    mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), args);
            }
        } else if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            List<Expression> args = renameVarInExpressions(smie.getArguments(), oldName, newName);
            if (args != smie.getArguments()) {
                return new StaticMethodInvocationExpression(smie.getLineNumber(), smie.getType(),
                    smie.getOwnerInternalName(), smie.getMethodName(), smie.getDescriptor(), args);
            }
        } else if (expr instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) expr;
            Expression left = renameVarInExpression(ae.getLeft(), oldName, newName);
            Expression right = renameVarInExpression(ae.getRight(), oldName, newName);
            if (left != ae.getLeft() || right != ae.getRight()) {
                return new AssignmentExpression(ae.getLineNumber(), ae.getType(), left, ae.getOperator(), right);
            }
        } else if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            Expression left = renameVarInExpression(boe.getLeft(), oldName, newName);
            Expression right = renameVarInExpression(boe.getRight(), oldName, newName);
            if (left != boe.getLeft() || right != boe.getRight()) {
                return new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(), left, boe.getOperator(), right);
            }
        } else if (expr instanceof CastExpression) {
            CastExpression ce = (CastExpression) expr;
            Expression inner = renameVarInExpression(ce.getExpression(), oldName, newName);
            if (inner != ce.getExpression()) {
                return new CastExpression(ce.getLineNumber(), ce.getType(), inner);
            }
        }
        return expr;
    }

    private static List<Expression> renameVarInExpressions(List<Expression> exprs, String oldName, String newName) {
        if (exprs == null) return exprs;
        boolean changed = false;
        List<Expression> result = new ArrayList<Expression>(exprs.size());
        for (Expression e : exprs) {
            Expression renamed = renameVarInExpression(e, oldName, newName);
            if (renamed != e) changed = true;
            result.add(renamed);
        }
        return changed ? result : exprs;
    }
    // END_CHANGE: ISS-2026-0005-13

    public static List<Statement> filterFinallyBody(List<Statement> handlerBody) {
        if (handlerBody.isEmpty()) return handlerBody;

        List<Statement> filtered = new ArrayList<Statement>();
        int startIdx = 0;
        int endIdx = handlerBody.size();

        Statement first = handlerBody.get(0);
        if (first instanceof VariableDeclarationStatement) {
            startIdx = 1;
        } else if (first instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) first).getExpression();
            if (expr instanceof AssignmentExpression) {
                startIdx = 1;
            }
        }

        if (endIdx > startIdx) {
            Statement last = handlerBody.get(endIdx - 1);
            if (last instanceof ThrowStatement) {
                endIdx--;
            }
        }

        for (int i = startIdx; i < endIdx; i++) {
            filtered.add(handlerBody.get(i));
        }

        // Strip addSuppressed boilerplate from try-with-resources
        filtered = stripAddSuppressed(filtered);

        return filtered;
    }

    /**
     * Strip addSuppressed boilerplate from try-with-resources compiler output.
     * Removes statements that reference addSuppressed and simplifies the pattern.
     */
    public static List<Statement> stripAddSuppressed(List<Statement> stmts) {
        List<Statement> result = new ArrayList<Statement>();
        for (Statement s : stmts) {
            if (!containsAddSuppressed(s)) {
                result.add(s);
            }
        }
        return result;
    }

    private static boolean containsAddSuppressed(Statement s) {
        if (s instanceof ExpressionStatement) {
            return expressionContainsAddSuppressed(((ExpressionStatement) s).getExpression());
        }
        if (s instanceof TryCatchStatement) {
            // If the entire try-catch is just wrapping addSuppressed, strip it
            TryCatchStatement tcs = (TryCatchStatement) s;
            if (tcs.getTryBody() instanceof BlockStatement) {
                List<Statement> tryStmts = ((BlockStatement) tcs.getTryBody()).getStatements();
                for (Statement ts : tryStmts) {
                    if (containsAddSuppressed(ts)) return true;
                }
            }
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                if (cc.body instanceof BlockStatement) {
                    List<Statement> catchStmts = ((BlockStatement) cc.body).getStatements();
                    for (Statement cs : catchStmts) {
                        if (containsAddSuppressed(cs)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean expressionContainsAddSuppressed(Expression expr) {
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if ("addSuppressed".equals(mie.getMethodName())) return true;
        }
        return false;
    }

    // START_CHANGE: BUG-2026-0021-20260324-1 - Only remove duplicated finally if last statements match
    public static List<Statement> removeDuplicatedFinally(List<Statement> catchBody, int finallySize) {
        if (finallySize <= 0 || catchBody.size() <= finallySize) return catchBody;

        // Check if the last N statements are likely duplicated finally code
        // by verifying they are ExpressionStatements (typical for inline finally)
        int start = catchBody.size() - finallySize;
        boolean allLikelyFinally = true;
        for (int i = start; i < catchBody.size(); i++) {
            Statement s = catchBody.get(i);
            if (s instanceof ExpressionStatement || s instanceof ReturnStatement) {
                continue; // typical finally statement
            }
            allLikelyFinally = false;
            break;
        }
        if (!allLikelyFinally) return catchBody;

        List<Statement> filtered = new ArrayList<Statement>();
        int keepCount = catchBody.size() - finallySize;
        for (int i = 0; i < keepCount; i++) {
            filtered.add(catchBody.get(i));
        }
        return filtered;
    }
    // END_CHANGE: BUG-2026-0021-1
}
