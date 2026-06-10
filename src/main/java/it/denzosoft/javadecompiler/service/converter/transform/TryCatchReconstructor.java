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
            int lastHandlerPc = -1;
            for (CodeAttribute.ExceptionEntry entry : groupEntries) {
                if (entry.handlerPc < firstHandlerPc) {
                    firstHandlerPc = entry.handlerPc;
                }
                if (entry.handlerPc > lastHandlerPc) {
                    lastHandlerPc = entry.handlerPc;
                }
            }
            int firstHandlerLine = findLineForPc(firstHandlerPc, sortedPcs);

            // START_CHANGE: BUG-2026-0056-20260421-1 - Compute the line at which the try-catch-finally
            // region ENDS (i.e. the "merge point" after all handlers). Statements whose line falls
            // inside the handler region are part of a handler body (decoded separately) and must
            // be dropped; statements with a line strictly past the merge point are plain
            // after-try code and must be preserved. Previously any statement at/after
            // firstHandlerLine was dropped, which lost the trailing `log.info(...); return resp;`
            // that appears AFTER the catch/finally in non-void methods.
            int afterTryStartLine = computeAfterTryStartLine(
                firstHandlerPc, lastHandlerPc, tryEndPc, sortedPcs);
            // END_CHANGE: BUG-2026-0056-1

            boolean inTryRegion = false;
            for (Statement s : statements) {
                int sLine = s.getLineNumber();
                if (sLine > 0 && sLine >= tryStartLine && sLine <= tryEndLine) {
                    inTryRegion = true;
                    tryBody.add(s);
                } else if (!inTryRegion) {
                    beforeTry.add(s);
                } else {
                    // START_CHANGE: BUG-2026-0056-20260421-2 - Restore after-try statements.
                    // Drop statements that fall inside any handler body's line range, but keep
                    // statements past the merge point (they are plain after-try code).
                    if (afterTryStartLine > 0 && sLine >= afterTryStartLine) {
                        afterTry.add(s);
                        continue;
                    }
                    if (firstHandlerLine > 0 && sLine >= firstHandlerLine) {
                        continue;
                    }
                    afterTry.add(s);
                    // END_CHANGE: BUG-2026-0056-2
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

                // START_CHANGE: BUG-2026-0091-20260610-1 - Track whether the initial exception
                // store was removed here, so filterFinallyBody does not strip a SECOND statement
                // (a real finally statement) under the assumption it is the exception store.
                int handlerSizeBeforeStoreStrip = handlerBody.size();
                handlerBody = removeInitialExceptionStore(handlerBody, varName);
                boolean exceptionStoreRemoved = handlerBody.size() < handlerSizeBeforeStoreStrip;
                // END_CHANGE: BUG-2026-0091-1

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
                    // START_CHANGE: BUG-2026-0091-20260610-2 - Tell filterFinallyBody whether the
                    // exception store has already been stripped from the handler body.
                    List<Statement> filteredFinally =
                        filterFinallyBody(handlerBody, exceptionStoreRemoved);
                    // END_CHANGE: BUG-2026-0091-2
                    if (!filteredFinally.isEmpty()) {
                        finallyBody = new BlockStatement(handlerLine, filteredFinally);
                    }
                }
            }

            if (catchClauses.isEmpty() && finallyBody == null) continue;

            if (finallyBody != null && finallyBody instanceof BlockStatement) {
                // START_CHANGE: BUG-2026-0091-20260610-3 - Pass the actual finally statements
                // (not just their count) so dedup only removes a structurally matching tail.
                // The previous count-based truncation deleted real trailing returns (e.g. the
                // synchronized desugar, where finally = [__MONITOREXIT__ marker] and the inlined
                // monitorexit PRECEDES the return).
                List<Statement> finallyStmts = ((BlockStatement) finallyBody).getStatements();
                if (!finallyStmts.isEmpty()) {
                    tryBody = removeDuplicatedFinally(tryBody, finallyStmts);

                    List<TryCatchStatement.CatchClause> cleanedClauses =
                        new ArrayList<TryCatchStatement.CatchClause>();
                    for (TryCatchStatement.CatchClause cc : catchClauses) {
                        if (cc.body instanceof BlockStatement) {
                            List<Statement> cleanedBody = removeDuplicatedFinally(
                                ((BlockStatement) cc.body).getStatements(), finallyStmts);
                            cleanedClauses.add(new TryCatchStatement.CatchClause(
                                cc.exceptionTypes, cc.variableName,
                                new BlockStatement(cc.body.getLineNumber(), cleanedBody)));
                        } else {
                            cleanedClauses.add(cc);
                        }
                    }
                    catchClauses = cleanedClauses;

                    // javac also inlines the finally body on the normal exit path, i.e. at the
                    // START of the code following the try region. Strip a structurally matching
                    // leading duplicate from afterTry. Then, if the finally body itself ends in
                    // a Return/Throw, the whole try statement completes abruptly and anything
                    // left in afterTry is unreachable (javac rejects it) - drop it.
                    afterTry = stripLeadingFinallyDuplicate(afterTry, finallyStmts);
                    Statement lastFinallyStmt = finallyStmts.get(finallyStmts.size() - 1);
                    if ((lastFinallyStmt instanceof ReturnStatement
                            || lastFinallyStmt instanceof ThrowStatement)
                            && !afterTry.isEmpty()) {
                        afterTry = new ArrayList<Statement>();
                    }
                }
                // END_CHANGE: BUG-2026-0091-3
            }

            // Validate try-catch quality: if the try body is empty or only contains
            // trivial statements (variable declarations/assignments), skip the try-catch
            // wrapper. This handles try-with-resources patterns where the compiler generates
            // complex nested try-catch for resource management but the actual body is lost
            // during decompilation. Emitting the original linear statements is more compilable.
            if (isTryBodyTrivial(tryBody) && isTryWithResourcesPattern(catchClauses, finallyBody)) {
                continue; // skip this exception group - emit original statements unchanged
            }

            // START_CHANGE: LIM-0008-20260326-1 - Try-with-resources resource extraction
            List<Statement> resources = null;
            if (isTryWithResourcesPattern(catchClauses, finallyBody)) {
                // Extract resource variable names from finally close() calls
                List<String> closeVarNames = extractCloseVariableNames(finallyBody);
                if (!closeVarNames.isEmpty()) {
                    resources = new ArrayList<Statement>();
                    List<Statement> remainingBefore = new ArrayList<Statement>();
                    for (Statement bs : beforeTry) {
                        String assignedVar = getAssignedVarName(bs);
                        if (assignedVar != null && closeVarNames.contains(assignedVar)) {
                            resources.add(bs);
                        } else {
                            remainingBefore.add(bs);
                        }
                    }
                    if (resources.isEmpty()) {
                        // Also check the tryBody for resource declarations
                        List<Statement> remainingTry = new ArrayList<Statement>();
                        for (Statement ts : tryBody) {
                            String assignedVar = getAssignedVarName(ts);
                            if (assignedVar != null && closeVarNames.contains(assignedVar)) {
                                resources.add(ts);
                            } else {
                                remainingTry.add(ts);
                            }
                        }
                        if (!resources.isEmpty()) {
                            tryBody = remainingTry;
                        }
                    } else {
                        beforeTry = remainingBefore;
                    }
                    if (resources.isEmpty()) {
                        resources = null;
                    } else {
                        // Remove compiler-generated Throwable catches and close() finally
                        catchClauses = filterTWRCatchClauses(catchClauses);
                        finallyBody = null;
                    }
                }
            }
            // END_CHANGE: LIM-0008-1

            TryCatchStatement tcs = new TryCatchStatement(
                tryStartLine,
                new BlockStatement(tryStartLine, tryBody),
                catchClauses,
                finallyBody,
                resources);

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
            // START_CHANGE: BUG-2026-0056-20260421-4 - Walk through conditional blocks in the
            // handler body. The catch body may itself contain an if/else whose branches later
            // merge before the throw/return terminator. Previously we stopped at the first
            // conditional and produced a truncated catch (only the first assignment visible).
            // Follow the fall-through edge by default; if that ends up at a stop PC we try
            // the branch target instead.
            } else if (block.type == BasicBlock.CONDITIONAL) {
                BasicBlock fall = block.falseSuccessor;
                BasicBlock br = block.trueSuccessor;
                if (fall != null && !stopPcs.contains(fall.startPc) && !visited.contains(fall.startPc)) {
                    block = fall;
                } else if (br != null && !stopPcs.contains(br.startPc) && !visited.contains(br.startPc)) {
                    block = br;
                } else {
                    return;
                }
            // END_CHANGE: BUG-2026-0056-4
            } else {
                return;
            }
        }
    }

    // START_CHANGE: BUG-2026-0056-20260421-3 - Find the earliest line number AFTER every handler
    // block of the current try-region. Uses the handler exit / merge PC from the CFG when
    // available, falling back to the line of the PC that follows the last handler block.
    // Returns -1 if no "after-try" region can be identified (callers should then preserve the
    // legacy behaviour of dropping anything past firstHandlerLine).
    private int computeAfterTryStartLine(int firstHandlerPc, int lastHandlerPc,
                                          int tryEndPc, List<Integer> sortedPcs) {
        int mergePc = findTryCatchMergePc(tryEndPc);
        if (mergePc >= 0) {
            int mergeLine = findLineForPc(mergePc, sortedPcs);
            if (mergeLine > 0) return mergeLine;
        }
        // Fall back: take the end PC of the block that contains the last handler.
        if (lastHandlerPc >= 0 && cfg != null) {
            BasicBlock last = cfg.getBlockAtPc(lastHandlerPc);
            if (last != null && last.endPc > lastHandlerPc) {
                int afterLine = findLineForPc(last.endPc, sortedPcs);
                if (afterLine > 0) return afterLine;
            }
        }
        return -1;
    }
    // END_CHANGE: BUG-2026-0056-3

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
        } else if (expr instanceof NewExpression) {
            // BUG-2026-0080 (RC-8): recurse into constructor args (e.g. `throw new RuntimeException(msg, e)`).
            NewExpression ne = (NewExpression) expr;
            List<Expression> args = renameVarInExpressions(ne.getArguments(), oldName, newName);
            if (args != ne.getArguments()) {
                return new NewExpression(ne.getLineNumber(), ne.getType(), ne.getInternalTypeName(), ne.getDescriptor(), args);
            }
        } else if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            Expression inner = renameVarInExpression(uoe.getExpression(), oldName, newName);
            if (inner != uoe.getExpression()) {
                return new UnaryOperatorExpression(uoe.getLineNumber(), uoe.getType(), uoe.getOperator(), inner, uoe.isPrefix());
            }
        } else if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            Expression c = renameVarInExpression(te.getCondition(), oldName, newName);
            Expression t = renameVarInExpression(te.getTrueExpression(), oldName, newName);
            Expression f = renameVarInExpression(te.getFalseExpression(), oldName, newName);
            if (c != te.getCondition() || t != te.getTrueExpression() || f != te.getFalseExpression()) {
                return new TernaryExpression(te.getLineNumber(), te.getType(), c, t, f);
            }
        } else if (expr instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) expr;
            Expression obj = renameVarInExpression(fae.getObject(), oldName, newName);
            if (obj != fae.getObject()) {
                return new FieldAccessExpression(fae.getLineNumber(), fae.getType(), obj,
                    fae.getOwnerInternalName(), fae.getName(), fae.getDescriptor());
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
        return filterFinallyBody(handlerBody, false);
    }

    // START_CHANGE: BUG-2026-0091-20260610-4 - Do not strip the first handler statement as an
    // exception store when removeInitialExceptionStore already removed it; otherwise a real
    // finally statement (declaration/assignment) is silently deleted.
    public static List<Statement> filterFinallyBody(List<Statement> handlerBody,
                                                     boolean exceptionStoreAlreadyRemoved) {
        if (handlerBody.isEmpty()) return handlerBody;

        List<Statement> filtered = new ArrayList<Statement>();
        int startIdx = 0;
        int endIdx = handlerBody.size();

        if (!exceptionStoreAlreadyRemoved) {
            Statement first = handlerBody.get(0);
            if (first instanceof VariableDeclarationStatement) {
                startIdx = 1;
            } else if (first instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) first).getExpression();
                if (expr instanceof AssignmentExpression) {
                    startIdx = 1;
                }
            }
        }
        // END_CHANGE: BUG-2026-0091-4

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

    // START_CHANGE: BUG-2026-0091-20260610-5 - Structural finally dedup (supersedes the
    // count-based truncation of BUG-2026-0021-1). The old code removed the LAST finallySize
    // statements by COUNT with a type-only whitelist that included ReturnStatement, so for the
    // synchronized desugar (finally = [__MONITOREXIT__ marker], inlined monitorexit PRECEDING
    // ireturn) it deleted the trailing `return` instead of the duplicated EXIT marker.
    // Now statements are removed ONLY when they structurally match the actual finally body:
    //   1. the trailing N statements match the finally statements -> remove them;
    //   2. otherwise, if the body ends in a Return/Throw, the N statements immediately BEFORE
    //      that terminator are compared (javac inlines finally before each exit point) and
    //      removed on match, keeping the terminator;
    //   3. fallback: if no structural match, the old count-based truncation is kept ONLY for
    //      tails made exclusively of plain ExpressionStatements (ReturnStatement is no longer
    //      whitelisted, so a trailing `return` can never be deleted by count);
    //   4. otherwise remove nothing.
    public static List<Statement> removeDuplicatedFinally(List<Statement> catchBody,
                                                           List<Statement> finallyStmts) {
        int finallySize = (finallyStmts == null) ? 0 : finallyStmts.size();
        if (finallySize <= 0 || catchBody.size() <= finallySize) return catchBody;

        // Case 1: the trailing N statements structurally match the finally body
        int tailStart = catchBody.size() - finallySize;
        if (statementsMatchFinally(catchBody, tailStart, finallyStmts)) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 0; i < tailStart; i++) {
                filtered.add(catchBody.get(i));
            }
            return filtered;
        }

        // Case 2: the body ends in a Return/Throw and the N statements immediately before
        // it match the finally body - remove those, keep the terminator
        Statement last = catchBody.get(catchBody.size() - 1);
        if (last instanceof ReturnStatement || last instanceof ThrowStatement) {
            int preStart = catchBody.size() - 1 - finallySize;
            if (preStart >= 0 && statementsMatchFinally(catchBody, preStart, finallyStmts)) {
                List<Statement> filtered = new ArrayList<Statement>();
                for (int i = 0; i < preStart; i++) {
                    filtered.add(catchBody.get(i));
                }
                filtered.add(last);
                return filtered;
            }
        }

        // Case 3 (fallback, old count-based behavior minus the ReturnStatement whitelist):
        // remove the trailing N statements when they are ALL plain ExpressionStatements.
        // Inlined finally copies often render differently from the decoded handler body
        // (e.g. different synthetic variable names), so the structural match can miss them;
        // an expression-only tail can never swallow a trailing `return`/`throw`.
        boolean allPlainExpressions = true;
        for (int i = tailStart; i < catchBody.size(); i++) {
            if (!(catchBody.get(i) instanceof ExpressionStatement)) {
                allPlainExpressions = false;
                break;
            }
        }
        if (allPlainExpressions) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 0; i < tailStart; i++) {
                filtered.add(catchBody.get(i));
            }
            return filtered;
        }

        return catchBody; // conservative: no match, remove nothing
    }

    /**
     * Strip a duplicated finally body inlined by javac at the START of the code that
     * follows the try region (the normal completion path).
     */
    private static List<Statement> stripLeadingFinallyDuplicate(List<Statement> afterTry,
                                                                 List<Statement> finallyStmts) {
        int finallySize = (finallyStmts == null) ? 0 : finallyStmts.size();
        if (finallySize <= 0 || afterTry.size() < finallySize) return afterTry;
        if (!statementsMatchFinally(afterTry, 0, finallyStmts)) return afterTry;
        List<Statement> result = new ArrayList<Statement>();
        for (int i = finallySize; i < afterTry.size(); i++) {
            result.add(afterTry.get(i));
        }
        return result;
    }

    private static boolean statementsMatchFinally(List<Statement> body, int offset,
                                                   List<Statement> finallyStmts) {
        for (int i = 0; i < finallyStmts.size(); i++) {
            if (!sameStatementShape(body.get(offset + i), finallyStmts.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Two statements have the same shape when they are of the same class and their
     * line-independent signatures (expression/initializer rendering) are equal.
     * Compound statements (if/loops/blocks) are never considered equal: their content
     * cannot be compared reliably, so dedup conservatively keeps them.
     */
    private static boolean sameStatementShape(Statement a, Statement b) {
        if (a == null || b == null) return false;
        if (!a.getClass().equals(b.getClass())) return false;
        String sigA = statementSignature(a);
        String sigB = statementSignature(b);
        if (sigA == null || sigB == null) return false;
        return sigA.equals(sigB);
    }

    /**
     * Line-independent signature of a simple statement, or null for statement types
     * that cannot be compared reliably.
     */
    private static String statementSignature(Statement s) {
        if (s instanceof ExpressionStatement) {
            return "expr:" + expressionSignature(((ExpressionStatement) s).getExpression());
        }
        if (s instanceof ReturnStatement) {
            return "return:" + expressionSignature(((ReturnStatement) s).getExpression());
        }
        if (s instanceof ThrowStatement) {
            return "throw:" + expressionSignature(((ThrowStatement) s).getExpression());
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            return "decl:" + vds.getName() + "="
                + expressionSignature(vds.getInitializer());
        }
        return null; // compound/unknown statement: never matches
    }

    /**
     * Render an expression for structural comparison. Method invocations include their
     * arguments (the plain toString elides them as "(...)").
     */
    private static String expressionSignature(Expression e) {
        if (e == null) return "null";
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) e;
            StringBuilder sb = new StringBuilder();
            sb.append(expressionSignature(mie.getObject()));
            sb.append('.').append(mie.getMethodName()).append('(');
            List<Expression> args = mie.getArguments();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(expressionSignature(args.get(i)));
                }
            }
            sb.append(')');
            return sb.toString();
        }
        return String.valueOf(e);
    }
    // END_CHANGE: BUG-2026-0091-5

    // START_CHANGE: LIM-0008-20260326-2 - Helpers for try-with-resources resource extraction

    /**
     * Extract variable names that are closed in a finally block.
     * Looks for patterns like: varName.close() in the finally body.
     */
    private List<String> extractCloseVariableNames(Statement finallyBody) {
        List<String> names = new ArrayList<String>();
        if (finallyBody instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) finallyBody).getStatements()) {
                String name = extractCloseVarFromStatement(s);
                if (name != null) {
                    names.add(name);
                }
            }
        } else {
            String name = extractCloseVarFromStatement(finallyBody);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private String extractCloseVarFromStatement(Statement s) {
        // Direct close() call: var.close()
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            if (expr instanceof MethodInvocationExpression) {
                MethodInvocationExpression mie = (MethodInvocationExpression) expr;
                if ("close".equals(mie.getMethodName()) && mie.getObject() instanceof LocalVariableExpression) {
                    return ((LocalVariableExpression) mie.getObject()).getName();
                }
            }
        }
        // Guarded close: if (var != null) { var.close(); }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            List<Statement> body = null;
            if (is.getThenBody() instanceof BlockStatement) {
                body = ((BlockStatement) is.getThenBody()).getStatements();
            } else {
                body = new ArrayList<Statement>();
                body.add(is.getThenBody());
            }
            for (Statement bs : body) {
                String name = extractCloseVarFromStatement(bs);
                if (name != null) return name;
            }
        }
        return null;
    }

    /**
     * Get the variable name assigned by a statement (VarDecl or Assignment).
     */
    private String getAssignedVarName(Statement s) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (vds.hasInitializer()) {
                return vds.getName();
            }
        }
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    return ((LocalVariableExpression) ae.getLeft()).getName();
                }
            }
        }
        return null;
    }

    /**
     * Filter out compiler-generated catch clauses from try-with-resources.
     * Removes catches for Throwable and catches with only addSuppressed calls.
     */
    private List<TryCatchStatement.CatchClause> filterTWRCatchClauses(
            List<TryCatchStatement.CatchClause> catchClauses) {
        List<TryCatchStatement.CatchClause> filtered = new ArrayList<TryCatchStatement.CatchClause>();
        for (TryCatchStatement.CatchClause cc : catchClauses) {
            boolean isCompilerGenerated = false;
            if (cc.exceptionTypes != null) {
                for (Type t : cc.exceptionTypes) {
                    if (t instanceof ObjectType) {
                        String name = ((ObjectType) t).getInternalName();
                        if ("java/lang/Throwable".equals(name) || "Throwable".equals(name)) {
                            isCompilerGenerated = true;
                        }
                    }
                }
            }
            if (!isCompilerGenerated) {
                filtered.add(cc);
            }
        }
        return filtered;
    }
    // END_CHANGE: LIM-0008-2
}
