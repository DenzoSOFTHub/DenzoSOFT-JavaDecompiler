/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.SwitchStatement;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import it.denzosoft.javadecompiler.DecompilerLimits;

import java.util.*;

/**
 * Reconstructs structured control flow (if/else, while, for, do-while)
 * from a Control Flow Graph of basic blocks.
 *
 * Uses pattern matching on the CFG to identify:
 * - if-then: conditional → then-block → merge
 * - if-then-else: conditional → then-block → goto merge, else-block → merge
 * - while: loop-header (conditional) → body → goto header
 * - do-while: body → conditional back to body
 */
public class StructuredFlowBuilder {

    private final ControlFlowGraph cfg;
    private final BytecodeDecoder decoder;
    private Set<Integer> doWhileHeaders = new HashSet<Integer>();
    private Map<Integer, Integer> precomputedMergePoints = new HashMap<Integer, Integer>();
    private int lastEffectiveMergePoint = -1;
    private int recursionDepth = 0;
    // START_CHANGE: ISS-2026-0007-20260324-1 - Track outer loop merge points for labeled break detection
    private List<Integer> outerLoopMergePoints = new ArrayList<Integer>();
    private Map<Integer, String> labeledBreakLabels = new HashMap<Integer, String>();
    private int labelCounter = 0;
    // END_CHANGE: ISS-2026-0007-1

    public StructuredFlowBuilder(ControlFlowGraph cfg, BytecodeDecoder decoder) {
        this.cfg = cfg;
        this.decoder = decoder;
    }

    /**
     * Build structured statements from the CFG.
     * Returns a list of statements representing the method body.
     */
    public List<Statement> buildStatements() {
        List<BasicBlock> blocks = cfg.getBlocks();
        if (blocks.isEmpty()) {
            return new ArrayList<Statement>();
        }

        // Decode each block's instructions
        for (BasicBlock block : blocks) {
            decoder.decodeBlock(block);
        }

        // Pre-scan: identify do-while headers (targets of backward conditional branches)
        doWhileHeaders.clear();
        for (BasicBlock block : blocks) {
            if (block.isConditional()) {
                if (block.trueSuccessor != null && block.trueSuccessor.startPc <= block.startPc) {
                    doWhileHeaders.add(block.trueSuccessor.startPc);
                }
                if (block.falseSuccessor != null && block.falseSuccessor.startPc <= block.startPc) {
                    doWhileHeaders.add(block.falseSuccessor.startPc);
                }
            }
        }

        // Pre-compute merge points for conditional blocks to avoid repeated O(n) scans
        precomputedMergePoints.clear();
        for (BasicBlock block : blocks) {
            if (block.isConditional()) {
                int merge = computeMergePointForCache(block);
                if (merge >= 0) {
                    precomputedMergePoints.put(block.startPc, merge);
                }
            }
        }

        // Build structured statements starting from entry block
        List<Statement> result = new ArrayList<Statement>();
        Set<Integer> visited = new HashSet<Integer>();
        buildFromBlock(cfg.getEntryBlock(), result, visited, -1);

        return result;
    }

    /**
     * Recursively build statements from a block and its successors.
     *
     * @param block   current block to process
     * @param output  accumulator for statements
     * @param visited set of already-processed block PCs
     * @param stopPc  PC to stop at (for nested structures like if-bodies)
     */
    private void buildFromBlock(BasicBlock block, List<Statement> output,
                                 Set<Integer> visited, int stopPc) {
        recursionDepth++;
        if (recursionDepth > DecompilerLimits.MAX_RECURSION_DEPTH) {
            recursionDepth--;
            return;
        }
        try {
        buildFromBlock0(block, output, visited, stopPc);
        } finally {
            recursionDepth--;
        }
    }

    private void buildFromBlock0(BasicBlock block, List<Statement> output,
                                  Set<Integer> visited, int stopPc) {
        while (block != null) {
            if (block.startPc == stopPc) return;
            if (visited.contains(block.startPc)) return;

            // Check for do-while: this block is the start of a do-while body
            if (doWhileHeaders.contains(block.startPc) && !visited.contains(block.startPc)) {
                BasicBlock condBlock = findDoWhileCondition(block.startPc);

                // Special case: self-loop (body and condition in same block)
                if (condBlock == null && block.isConditional() &&
                    block.trueSuccessor != null && block.trueSuccessor.startPc == block.startPc) {
                    condBlock = block;
                }

                if (condBlock != null) {
                    // Remove header to prevent re-detection during body processing
                    doWhileHeaders.remove(block.startPc);

                    List<Statement> bodyStmts;
                    if (condBlock == block) {
                        // Self-loop: body statements are in the same block as the condition
                        bodyStmts = new ArrayList<Statement>(block.statements);
                        visited.add(block.startPc);
                    } else {
                        // Separate condition block: collect body blocks up to condition
                        bodyStmts = new ArrayList<Statement>();
                        buildFromBlock(block, bodyStmts, visited, condBlock.startPc);

                        // Decode the condition block to get the condition expression
                        decoder.decodeBlock(condBlock);
                    }

                    Expression condition = condBlock.condition;
                    if (condition == null) {
                        condition = new BooleanExpression(block.lineNumber, true);
                    }

                    // The condition may need negation:
                    // extractBranchCondition inverts the bytecode condition.
                    // For do-while, if the branch target goes BACK to body start,
                    // the bytecode says "if X goto body" but extractBranchCondition
                    // gives us "!X". We want X for the do-while condition.
                    if (condBlock.trueSuccessor != null && condBlock.trueSuccessor.startPc == block.startPc) {
                        condition = negateCondition(condition, block.lineNumber);
                    }

                    visited.add(condBlock.startPc);

                    output.add(new DoWhileStatement(block.lineNumber, condition,
                        new BlockStatement(block.lineNumber, bodyStmts)));

                    // Continue after the do-while (the exit path of the condition)
                    BasicBlock exitBlock = null;
                    if (condBlock.trueSuccessor != null && condBlock.trueSuccessor.startPc != block.startPc) {
                        exitBlock = condBlock.trueSuccessor;
                    } else if (condBlock.falseSuccessor != null && condBlock.falseSuccessor.startPc != block.startPc) {
                        exitBlock = condBlock.falseSuccessor;
                    }
                    if (exitBlock != null) {
                        block = exitBlock;
                        continue;
                    }
                    return;
                }
            }

            visited.add(block.startPc);

            if (block.isConditional()) {
                // Try to match structured patterns
                Statement structured = matchConditionalPattern(block, visited, stopPc);
                if (structured != null) {
                    // Flatten BlockStatement wrappers to avoid extra indentation
                    if (structured instanceof BlockStatement) {
                        output.addAll(((BlockStatement) structured).getStatements());
                    } else {
                        output.add(structured);
                    }
                    // Continue after the structured region
                    // Use effective merge point (accounts for compound boolean rewriting)
                    int mergePoint = lastEffectiveMergePoint >= 0 ? lastEffectiveMergePoint : findMergePoint(block);
                    lastEffectiveMergePoint = -1;
                    // START_CHANGE: ISS-2026-0007-20260324-10 - Don't follow merge beyond outer loop exit
                    boolean mergeIsOuterExit = false;
                    if (mergePoint >= 0) {
                        for (int oli = 0; oli < outerLoopMergePoints.size(); oli++) {
                            if (mergePoint >= outerLoopMergePoints.get(oli).intValue()) {
                                mergeIsOuterExit = true;
                                break;
                            }
                        }
                    }
                    if (mergePoint >= 0 && mergePoint != stopPc && !mergeIsOuterExit) {
                        BasicBlock mergeBlock = cfg.getBlockAtPc(mergePoint);
                        if (mergeBlock != null && !visited.contains(mergeBlock.startPc)) {
                            block = mergeBlock;
                            continue;
                        }
                    }
                    // END_CHANGE: ISS-2026-0007-10
                    return;
                }
                // Couldn't match - fall through to emitting block statements
            }

            if (block.type == BasicBlock.SWITCH) {
                // Compute merge point for the switch: the PC where all cases converge
                int switchMergePc = findSwitchMergePoint(block);

                // Effective stopPc for case bodies: use the switch merge point
                // so that case bodies don't bleed into subsequent code
                int caseStopPc = switchMergePc >= 0 ? switchMergePc : stopPc;

                // Emit statements before the switch (setup code)
                output.addAll(block.statements);

                // START_CHANGE: BUG-2026-0017-20260324-1 - Group switch keys with same target PC
                // Build switch cases, grouping keys that share the same target
                List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
                if (block.switchKeys != null) {
                    // Build ordered groups: keys with same target PC get combined labels
                    List<List<Integer>> keyGroups = new ArrayList<List<Integer>>();
                    List<Integer> targetPcs = new ArrayList<Integer>();
                    for (int i = 0; i < block.switchKeys.length; i++) {
                        int targetPc = (block.switchTargets != null && i < block.switchTargets.length)
                            ? block.switchTargets[i] : -1;
                        int groupIdx = -1;
                        for (int g = 0; g < targetPcs.size(); g++) {
                            if (targetPcs.get(g).intValue() == targetPc) {
                                groupIdx = g;
                                break;
                            }
                        }
                        if (groupIdx >= 0) {
                            keyGroups.get(groupIdx).add(Integer.valueOf(block.switchKeys[i]));
                        } else {
                            List<Integer> group = new ArrayList<Integer>();
                            group.add(Integer.valueOf(block.switchKeys[i]));
                            keyGroups.add(group);
                            targetPcs.add(Integer.valueOf(targetPc));
                        }
                    }
                    for (int g = 0; g < keyGroups.size(); g++) {
                        List<Expression> labels = new ArrayList<Expression>();
                        for (int k = 0; k < keyGroups.get(g).size(); k++) {
                            labels.add(IntegerConstantExpression.valueOf(block.lineNumber, keyGroups.get(g).get(k).intValue()));
                        }
                        List<Statement> caseStmts = new ArrayList<Statement>();
                        int targetPc = targetPcs.get(g).intValue();
                        if (targetPc >= 0) {
                            BasicBlock targetBlock = cfg.getBlockAtPc(targetPc);
                            if (targetBlock != null && !visited.contains(targetBlock.startPc)) {
                                buildFromBlock(targetBlock, caseStmts, visited, caseStopPc);
                            }
                        }
                        cases.add(new SwitchStatement.SwitchCase(labels, caseStmts));
                    }
                }
                // END_CHANGE: BUG-2026-0017-1
                // Default case
                if (block.switchDefaultTarget >= 0) {
                    BasicBlock defaultBlock = cfg.getBlockAtPc(block.switchDefaultTarget);
                    if (defaultBlock != null && !visited.contains(defaultBlock.startPc)) {
                        List<Statement> defaultStmts = new ArrayList<Statement>();
                        buildFromBlock(defaultBlock, defaultStmts, visited, caseStopPc);
                        cases.add(new SwitchStatement.SwitchCase(null, defaultStmts));
                    }
                }

                // Get selector expression - it's the last expression loaded before the switch
                Expression selector = null;
                // Try to extract from block's statements - the last assignment or load is the selector
                if (!block.statements.isEmpty()) {
                    Statement lastStmt = block.statements.get(block.statements.size() - 1);
                    if (lastStmt instanceof ExpressionStatement) {
                        Expression expr = ((ExpressionStatement) lastStmt).getExpression();
                        if (expr instanceof AssignmentExpression) {
                            selector = ((AssignmentExpression) expr).getLeft();
                        } else {
                            selector = expr;
                        }
                        // Remove the selector setup from the pre-switch statements
                        block.statements.remove(block.statements.size() - 1);
                    }
                }
                // Try to get selector from block's saved selector expression
                if (selector == null && block.selectorExpression != null) {
                    selector = block.selectorExpression;
                }
                if (selector == null) {
                    selector = new LocalVariableExpression(block.lineNumber, PrimitiveType.INT, "var", 0);
                }
                output.add(new SwitchStatement(block.lineNumber, selector, cases, false));

                // Continue processing from the merge point (if any) instead of returning.
                // This is critical for patterns like string switch where two consecutive
                // switches (hashCode switch + index switch) must be emitted sequentially.
                if (switchMergePc >= 0) {
                    BasicBlock mergeBlock = cfg.getBlockAtPc(switchMergePc);
                    if (mergeBlock != null && !visited.contains(mergeBlock.startPc)) {
                        block = mergeBlock;
                        continue;
                    }
                }
                return;
            }

            // Emit the block's decoded statements
            output.addAll(block.statements);

            // Determine next block
            if (block.isReturn() || block.isThrow()) {
                return; // End of flow
            } else if (block.isGoto()) {
                BasicBlock target = block.trueSuccessor;
                if (target != null && target.startPc <= block.startPc) {
                    // Backward goto = loop back-edge (while loop detected)
                    // The loop was already handled in matchConditionalPattern
                    return;
                }
                // START_CHANGE: ISS-2026-0007-20260324-5 - Detect labeled break (goto targets beyond current stopPc)
                if (target != null && stopPc >= 0 && target.startPc > stopPc) {
                    // Check if this target matches an outer loop merge point
                    int targetPc = target.startPc;
                    for (int oli = outerLoopMergePoints.size() - 1; oli >= 0; oli--) {
                        int outerMerge = outerLoopMergePoints.get(oli).intValue();
                        if (targetPc == outerMerge) {
                            String label = labeledBreakLabels.get(outerMerge);
                            if (label == null) {
                                label = "outer" + (labelCounter > 0 ? String.valueOf(labelCounter) : "");
                                labelCounter++;
                                labeledBreakLabels.put(outerMerge, label);
                            }
                            output.add(new BreakStatement(block.lineNumber, label));
                            return;
                        }
                    }
                    // Goto beyond stopPc but not matching any outer loop - emit break
                    output.add(new BreakStatement(block.lineNumber));
                    return;
                }
                // END_CHANGE: ISS-2026-0007-5
                block = target;
            } else if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
                block = block.trueSuccessor;
            } else {
                return;
            }
        }
    }

    /**
     * Match a conditional block to a structured pattern.
     */
    private Statement matchConditionalPattern(BasicBlock condBlock,
                                                Set<Integer> visited, int stopPc) {
        int line = condBlock.lineNumber > 0 ? condBlock.lineNumber : 0;

        // Get condition from the conditional block
        Expression condition = condBlock.condition;
        if (condition == null) {
            condition = new StringConstantExpression(line, "/* condition */");
        }

        // Emit statements before the branch (e.g., variable loads that contribute to condition)
        BasicBlock trueTarget = condBlock.falseSuccessor;  // fall-through = true branch (condition inverted in bytecode)
        BasicBlock falseTarget = condBlock.trueSuccessor;   // branch target = false branch (skip if condition true → invert)

        // In bytecode, ifeq means "if value == 0, jump to target" (i.e., if NOT condition, jump)
        // So: fall-through = condition is TRUE, branch target = condition is FALSE
        // We invert: the condition displayed is the NEGATION of the bytecode condition

        if (trueTarget == null || falseTarget == null) {
            return null;
        }

        // Compound AND detection - iterate to handle 3+ conditions
        boolean compoundFound = true;
        while (compoundFound) {
            compoundFound = false;
            if (trueTarget != null && trueTarget.isConditional() && !visited.contains(trueTarget.startPc)) {
                BasicBlock secondCond = trueTarget;
                // Decode if needed
                if (secondCond.condition == null) {
                    decoder.decodeBlock(secondCond);
                }
                // AND: both branch to same false target
                if (secondCond.trueSuccessor != null &&
                    falseTarget.startPc == secondCond.trueSuccessor.startPc) {
                    Expression cond2 = secondCond.condition;
                    if (cond2 != null) {
                        condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                            condition, "&&", cond2);
                        visited.add(secondCond.startPc);
                        trueTarget = secondCond.falseSuccessor;
                        compoundFound = true; // try again for next &&
                    }
                } else if (secondCond.falseSuccessor != null &&
                           falseTarget.startPc == secondCond.falseSuccessor.startPc) {
                    // OR pattern: first cond false -> target, second cond fall-through -> same target
                    Expression cond2 = secondCond.condition;
                    if (cond2 != null) {
                        condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                            negateCondition(condition, line), "||", cond2);
                        visited.add(secondCond.startPc);
                        BasicBlock newFalse = secondCond.trueSuccessor;
                        trueTarget = falseTarget;
                        falseTarget = newFalse;
                        compoundFound = true; // try again for next ||
                    }
                }
            }
        }

        // Compound OR detection (false-target chain) - iterate to handle 3+ conditions
        compoundFound = true;
        while (compoundFound) {
            compoundFound = false;
            if (falseTarget != null && falseTarget.isConditional() && !visited.contains(falseTarget.startPc)) {
                BasicBlock secondCond = falseTarget;
                if (secondCond.condition == null) {
                    decoder.decodeBlock(secondCond);
                }
                if (secondCond.trueSuccessor != null && secondCond.falseSuccessor != null &&
                    trueTarget.startPc == secondCond.falseSuccessor.startPc) {
                    Expression cond2 = secondCond.condition;
                    if (cond2 != null) {
                        condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                            condition, "||", cond2);
                        visited.add(secondCond.startPc);
                        falseTarget = secondCond.trueSuccessor;
                        compoundFound = true; // try again for next ||
                    }
                }
            }
        }

        // Pattern 0: Ternary expression - both branches produce a value, no statements
        // Bytecode: conditional -> value_A + goto merge -> value_B -> merge (return/store)
        // Also handles nested ternary: one branch is value-only, other is a conditional (inner ternary)
        {
            Expression trueValue = trueTarget.stackTopExpression;
            Expression falseValue = falseTarget.stackTopExpression;

            boolean trueIsValueOnly = trueValue != null &&
                (trueTarget.statements == null || trueTarget.statements.isEmpty()) &&
                (trueTarget.isGoto() || trueTarget.type == BasicBlock.FALL_THROUGH);
            boolean falseIsValueOnly = falseValue != null &&
                (falseTarget.statements == null || falseTarget.statements.isEmpty());

            // Check if false branch is a nested ternary (conditional block that produces a value)
            boolean falseIsNestedTernary = false;
            if (!falseIsValueOnly && falseTarget.isConditional() && !visited.contains(falseTarget.startPc)) {
                falseIsNestedTernary = canFormTernary(falseTarget, visited);
            }
            // Check if true branch is a nested ternary
            boolean trueIsNestedTernary = false;
            if (!trueIsValueOnly && trueTarget.isConditional() && !visited.contains(trueTarget.startPc)) {
                trueIsNestedTernary = canFormTernary(trueTarget, visited);
            }

            if ((trueIsValueOnly || trueIsNestedTernary) && (falseIsValueOnly || falseIsNestedTernary)) {
                // Find the merge point
                int mergePc = -1;
                if (trueIsValueOnly) {
                    if (trueTarget.isGoto()) {
                        mergePc = trueTarget.branchTargetPc;
                    } else if (trueTarget.trueSuccessor != null) {
                        mergePc = trueTarget.trueSuccessor.startPc;
                    }
                } else {
                    // For nested ternary, find merge from the outermost goto in the nested structure
                    mergePc = findNestedTernaryMerge(trueTarget);
                }

                if (mergePc >= 0) {
                    BasicBlock mergeBlock = cfg.getBlockAtPc(mergePc);

                    // Build true value
                    if (trueIsNestedTernary) {
                        trueValue = buildTernaryExpression(trueTarget, visited, line);
                    }
                    // Build false value
                    if (falseIsNestedTernary) {
                        falseValue = buildTernaryExpression(falseTarget, visited, line);
                    }

                    if (trueValue == null || falseValue == null) {
                        // Fall through to other patterns if we can't build the ternary values
                    } else {
                        // Determine the ternary type from the values
                        Type ternaryType = trueValue.getType() != null ? trueValue.getType() : PrimitiveType.INT;

                        // Create ternary expression
                        Expression ternary = new TernaryExpression(line, ternaryType,
                            condition, trueValue, falseValue);

                        // Mark value blocks as visited
                        visited.add(trueTarget.startPc);
                        visited.add(falseTarget.startPc);

                        List<Statement> preStatements = new ArrayList<Statement>();
                        preStatements.addAll(condBlock.statements);

                        // Check what the merge block does with the value
                        if (mergeBlock != null) {
                            // START_CHANGE: BUG-2026-0015-20260324-1 - Only emit return if merge block is pure return (no prior statements)
                            visited.add(mergeBlock.startPc);
                            boolean pureReturn = mergeBlock.isReturn()
                                && (mergeBlock.statements == null || mergeBlock.statements.isEmpty());
                            if (pureReturn) {
                                // return condition ? A : B;
                                preStatements.add(new ReturnStatement(line, ternary));
                            } else if (mergeBlock.isReturn() && mergeBlock.statements != null && !mergeBlock.statements.isEmpty()) {
                                // Merge block has statements then return - ternary is consumed as argument
                                // Replace the stack-derived argument in the merge block's statements with the ternary
                                // Use condBlock's stackTopExpression as the method receiver if available
                                Expression receiver = condBlock.stackTopExpression;
                                replaceTernaryInMergeStatements(preStatements, mergeBlock.statements, ternary, trueValue, falseValue, receiver);
                            } else if (mergeBlock.statements != null && !mergeBlock.statements.isEmpty()) {
                            // END_CHANGE: BUG-2026-0015-1
                                // The merge block has a store - replace with ternary assignment
                                Statement mergeStmt = mergeBlock.statements.get(0);
                                if (mergeStmt instanceof ExpressionStatement) {
                                    Expression mergeExpr = ((ExpressionStatement) mergeStmt).getExpression();
                                    if (mergeExpr instanceof AssignmentExpression) {
                                        AssignmentExpression ae = (AssignmentExpression) mergeExpr;
                                        preStatements.add(new ExpressionStatement(
                                            new AssignmentExpression(line, ternaryType, ae.getLeft(), "=", ternary)));
                                        // Add remaining merge statements
                                        for (int mi = 1; mi < mergeBlock.statements.size(); mi++) {
                                            preStatements.add(mergeBlock.statements.get(mi));
                                        }
                                    } else {
                                        preStatements.add(new ExpressionStatement(ternary));
                                    }
                                } else {
                                    preStatements.add(new ExpressionStatement(ternary));
                                }
                            } else {
                                preStatements.add(new ExpressionStatement(ternary));
                            }
                        } else {
                            preStatements.add(new ReturnStatement(line, ternary));
                        }

                        return new BlockStatement(line, preStatements);
                    }
                }
            }
        }

        // Pattern 1: While loop - condition branches forward, body loops back
        // Header: if (!cond) goto exit; body...; goto header;
        if (hasBackEdgeTo(condBlock.startPc, trueTarget)) {
            // START_CHANGE: ISS-2026-0007-20260324-2 - Push outer loop merge point for labeled break
            int loopExitPc = falseTarget != null ? falseTarget.startPc : -1;
            if (loopExitPc >= 0) {
                outerLoopMergePoints.add(loopExitPc);
            }
            // END_CHANGE: ISS-2026-0007-2
            // while(condition) { body }
            List<Statement> body = new ArrayList<Statement>();
            buildFromBlock(trueTarget, body, visited, condBlock.startPc);

            // START_CHANGE: ISS-2026-0007-20260324-3 - Pop outer loop merge point
            if (loopExitPc >= 0) {
                outerLoopMergePoints.remove(outerLoopMergePoints.size() - 1);
            }
            // END_CHANGE: ISS-2026-0007-3

            // Add pre-condition statements
            List<Statement> result = new ArrayList<Statement>();
            result.addAll(condBlock.statements);

            WhileStatement ws = new WhileStatement(line, condition,
                new BlockStatement(line, body));
            // START_CHANGE: ISS-2026-0007-20260324-4 - Wrap with label if labeled break targets this loop
            String label = labeledBreakLabels.remove(loopExitPc);
            if (label != null) {
                result.add(new LabelStatement(line, label, ws));
            } else {
                result.add(ws);
            }
            // END_CHANGE: ISS-2026-0007-4
            // Find merge point (where false branch goes)
            return new BlockStatement(line, result);
        }

        // Pattern 2: if-then-else - true block ends with goto merge, false block falls to merge
        int mergePoint = findMergePoint(condBlock, trueTarget, falseTarget);

        // START_CHANGE: BUG-2026-0014-20260324-1 - Only skip merge if it's beyond an outer loop exit, not just stopPc
        // Previously: mergePoint > stopPc caused inner loop body loss
        // Now: only reject merge if it targets an outer loop exit point
        if (mergePoint >= 0 && stopPc >= 0 && mergePoint > stopPc) {
            boolean mergeExceedsOuterExit = false;
            for (int oli = 0; oli < outerLoopMergePoints.size(); oli++) {
                if (mergePoint >= outerLoopMergePoints.get(oli).intValue()) {
                    mergeExceedsOuterExit = true;
                    break;
                }
            }
            if (mergeExceedsOuterExit) {
                mergePoint = -1; // Force fall-through to Pattern 3
            }
        }
        // END_CHANGE: BUG-2026-0014-1

        if (mergePoint >= 0) {
            lastEffectiveMergePoint = mergePoint;
            // Check if it's if-then (no else) or if-then-else
            boolean hasElse = false;
            BasicBlock trueEnd = findBlockEnd(trueTarget, mergePoint);
            if (trueEnd != null && trueEnd.isGoto() &&
                trueEnd.branchTargetPc == mergePoint &&
                falseTarget.startPc != mergePoint) {
                hasElse = true;
            }

            List<Statement> preStatements = new ArrayList<Statement>();
            preStatements.addAll(condBlock.statements);

            if (hasElse) {
                // if-then-else
                List<Statement> thenBody = new ArrayList<Statement>();
                buildFromBlock(trueTarget, thenBody, visited, mergePoint);

                List<Statement> elseBody = new ArrayList<Statement>();
                buildFromBlock(falseTarget, elseBody, visited, mergePoint);

                IfElseStatement ifs = new IfElseStatement(line, condition,
                    new BlockStatement(line, thenBody),
                    new BlockStatement(line, elseBody));
                preStatements.add(ifs);
                return new BlockStatement(line, preStatements);
            } else {
                // if-then (no else)
                List<Statement> thenBody = new ArrayList<Statement>();

                if (falseTarget.startPc == mergePoint) {
                    // Branch target is merge → fall-through is then-body
                    buildFromBlock(trueTarget, thenBody, visited, mergePoint);
                } else {
                    // Fall-through goes to merge → branch target is then-body (condition inverted)
                    buildFromBlock(falseTarget, thenBody, visited, mergePoint);
                    condition = negateCondition(condition, line);
                }

                IfStatement ifs = new IfStatement(line, condition,
                    new BlockStatement(line, thenBody));
                preStatements.add(ifs);
                return new BlockStatement(line, preStatements);
            }
        }

        // Pattern 3: Simple if-then with return in body
        if (trueTarget != null && (isTerminalBlock(trueTarget) || trueTarget.isReturn())) {
            lastEffectiveMergePoint = falseTarget != null ? falseTarget.startPc : -1;
            List<Statement> thenBody = new ArrayList<Statement>();
            buildFromBlock(trueTarget, thenBody, visited, stopPc);

            List<Statement> preStatements = new ArrayList<Statement>();
            preStatements.addAll(condBlock.statements);
            IfStatement ifs = new IfStatement(line, condition,
                new BlockStatement(line, thenBody));
            preStatements.add(ifs);
            return new BlockStatement(line, preStatements);
        }

        return null;
    }

    /**
     * Compute merge point for pre-computation cache (delegates to full computation).
     */
    private int computeMergePointForCache(BasicBlock condBlock) {
        BasicBlock trueTarget = condBlock.falseSuccessor;
        BasicBlock falseTarget = condBlock.trueSuccessor;
        return computeMergePointImpl(condBlock, trueTarget, falseTarget);
    }

    /**
     * Find the merge point of an if-then, if-then-else, or while structure.
     * The merge point is where control flow continues after the structured region.
     * Uses pre-computed cache when available.
     */
    private int findMergePoint(BasicBlock condBlock) {
        Integer cached = precomputedMergePoints.get(condBlock.startPc);
        if (cached != null) {
            return cached.intValue();
        }
        BasicBlock trueTarget = condBlock.falseSuccessor; // fall-through
        BasicBlock falseTarget = condBlock.trueSuccessor;  // branch
        return computeMergePointImpl(condBlock, trueTarget, falseTarget);
    }

    /**
     * Find the merge point using effective targets (after compound boolean rewriting).
     */
    private int findMergePoint(BasicBlock condBlock, BasicBlock trueTarget, BasicBlock falseTarget) {
        return computeMergePointImpl(condBlock, trueTarget, falseTarget);
    }

    private int computeMergePointImpl(BasicBlock condBlock, BasicBlock trueTarget, BasicBlock falseTarget) {

        if (trueTarget == null || falseTarget == null) return -1;

        // For while loops: the merge point is the exit (branch target = after loop)
        if (hasBackEdgeTo(condBlock.startPc, trueTarget)) {
            return falseTarget.startPc;
        }

        // If branch target is after all the then-block, it's the merge point
        if (falseTarget.startPc > trueTarget.startPc) {
            // Check if there's a goto at the end of the true path that goes to false target or beyond
            BasicBlock endOfTrue = findBlockEnd(trueTarget, falseTarget.startPc);
            if (endOfTrue != null && endOfTrue.isGoto()) {
                return endOfTrue.branchTargetPc;
            }

            // START_CHANGE: ISS-2026-0001-20260323-1 - Follow goto chains for nested if-else merge detection
            // For nested structures: scan all blocks in the true-path region
            // for any goto that targets beyond falseTarget (= if-then-else merge)
            // Also follow goto chains: if a goto in the true-path goes to another goto, follow it.
            int bestMerge = -1;
            for (BasicBlock block : cfg.getBlocks()) {
                if (block.startPc >= trueTarget.startPc && block.startPc < falseTarget.startPc) {
                    if (block.isGoto()) {
                        int target = block.branchTargetPc;
                        // Follow single-hop goto chain
                        BasicBlock targetBlock = cfg.getBlockAtPc(target);
                        if (targetBlock != null && targetBlock.isGoto() && targetBlock.branchTargetPc >= falseTarget.startPc) {
                            target = targetBlock.branchTargetPc;
                        }
                        if (target >= falseTarget.startPc) {
                            if (bestMerge < 0 || target < bestMerge) {
                                bestMerge = target;
                            }
                        }
                    }
                }
            }
            // END_CHANGE: ISS-2026-0001-1
            if (bestMerge >= 0) {
                // Also verify that the false-path reaches the same merge
                // (check for goto at end of else-block region)
                boolean elseReachesMerge = false;
                for (BasicBlock block : cfg.getBlocks()) {
                    if (block.startPc >= falseTarget.startPc && block.startPc < bestMerge) {
                        if (block.isGoto() && block.branchTargetPc == bestMerge) {
                            elseReachesMerge = true;
                            break;
                        }
                        if (block.type == BasicBlock.FALL_THROUGH && block.endPc == bestMerge) {
                            elseReachesMerge = true;
                            break;
                        }
                    }
                }
                if (elseReachesMerge) {
                    return bestMerge;
                }
            }

            return falseTarget.startPc;
        }

        return -1;
    }

    /**
     * Find the last block in a sequence starting at 'start' before reaching 'beforePc'.
     */
    private BasicBlock findBlockEnd(BasicBlock start, int beforePc) {
        BasicBlock current = start;
        BasicBlock last = start;
        Set<Integer> seen = new HashSet<Integer>();

        while (current != null && current.startPc < beforePc) {
            if (seen.contains(current.startPc)) break;
            seen.add(current.startPc);
            last = current;

            if (current.isGoto() || current.isReturn() || current.isThrow()) break;
            // START_CHANGE: ISS-2026-0001-20260323-2 - Skip past nested conditionals by finding their merge point
            if (current.isConditional()) {
                // Try to find the merge point of this nested conditional and continue from there
                int nestedMerge = computeMergePointImpl(current,
                    current.falseSuccessor, current.trueSuccessor);
                if (nestedMerge >= 0 && nestedMerge < beforePc) {
                    BasicBlock mergeBlock = cfg.getBlockAtPc(nestedMerge);
                    if (mergeBlock != null && !seen.contains(mergeBlock.startPc)) {
                        current = mergeBlock;
                        continue;
                    }
                }
                // If the nested merge equals beforePc, find the last goto block in the
                // nested region that targets the merge point (this is the effective end)
                if (nestedMerge >= 0 && nestedMerge == beforePc) {
                    BasicBlock bestGoto = null;
                    for (BasicBlock b : cfg.getBlocks()) {
                        if (b.startPc >= current.startPc && b.startPc < beforePc) {
                            if (b.isGoto() && b.branchTargetPc == beforePc) {
                                if (bestGoto == null || b.startPc > bestGoto.startPc) {
                                    bestGoto = b;
                                }
                            }
                        }
                    }
                    if (bestGoto != null) {
                        return bestGoto;
                    }
                }
                break;
            }
            // END_CHANGE: ISS-2026-0001-2

            current = current.trueSuccessor;
        }
        return last;
    }

    /**
     * Check if any block reachable from 'start' has a DIRECT backward edge to 'headerPc'.
     * Only follows forward edges and backward edges that target headerPc directly.
     * Does NOT follow backward edges to other targets (which would be other loops).
     */
    private boolean hasBackEdgeTo(int headerPc, BasicBlock start) {
        Set<Integer> seen = new HashSet<Integer>();
        return hasDirectBackEdge(headerPc, start, seen);
    }

    private boolean hasDirectBackEdge(int headerPc, BasicBlock block, Set<Integer> seen) {
        if (block == null) return false;
        if (block.startPc == headerPc) return true;
        if (seen.contains(block.startPc)) return false;
        seen.add(block.startPc);

        // Direct goto back to header
        if (block.isGoto() && block.branchTargetPc == headerPc) return true;

        // Conditional branch back to header
        if (block.isConditional()) {
            if (block.branchTargetPc == headerPc) return true;
            if (block.fallThroughPc == headerPc) return true;
        }

        // Follow FORWARD edges only (don't follow backward edges to other targets)
        if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
            if (block.trueSuccessor != null && block.trueSuccessor.startPc > block.startPc) {
                return hasDirectBackEdge(headerPc, block.trueSuccessor, seen);
            }
            return false;
        }
        if (block.isGoto()) {
            // Only follow forward gotos; backward gotos not targeting headerPc are other loops
            if (block.branchTargetPc > block.startPc && block.trueSuccessor != null) {
                return hasDirectBackEdge(headerPc, block.trueSuccessor, seen);
            }
            return false;
        }
        if (block.isConditional()) {
            // Follow both successors but only if forward
            boolean found = false;
            if (block.falseSuccessor != null && block.falseSuccessor.startPc > block.startPc) {
                found = hasDirectBackEdge(headerPc, block.falseSuccessor, seen);
            }
            if (!found && block.trueSuccessor != null && block.trueSuccessor.startPc > block.startPc) {
                found = hasDirectBackEdge(headerPc, block.trueSuccessor, seen);
            }
            return found;
        }

        return false;
    }

    /**
     * Find the merge point for a switch block: the first PC where all case branches
     * converge. This is the target of goto instructions at the end of case bodies,
     * or the default target if all non-default cases goto there.
     */
    private int findSwitchMergePoint(BasicBlock switchBlock) {
        // Collect all switch target PCs (case targets only, not default)
        Set<Integer> casePcs = new HashSet<Integer>();
        if (switchBlock.switchTargets != null) {
            for (int t : switchBlock.switchTargets) {
                casePcs.add(t);
            }
        }

        // Look for goto targets from blocks reachable from case bodies.
        // Count ALL goto targets including the default target - the most common
        // goto destination from case bodies is the merge point.
        Map<Integer, Integer> gotoCounts = new HashMap<Integer, Integer>();
        for (BasicBlock b : cfg.getBlocks()) {
            if (b.startPc > switchBlock.startPc && b.isGoto() && b.branchTargetPc > switchBlock.startPc) {
                // Only exclude gotos back to case entry points (not the default target)
                if (!casePcs.contains(b.branchTargetPc)) {
                    Integer count = gotoCounts.get(b.branchTargetPc);
                    gotoCounts.put(b.branchTargetPc, count == null ? 1 : count + 1);
                }
            }
        }

        // The merge point is the goto target that appears most frequently
        int bestTarget = -1;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : gotoCounts.entrySet()) {
            int target = entry.getKey();
            int count = entry.getValue();
            if (count > bestCount || (count == bestCount && (bestTarget < 0 || target < bestTarget))) {
                bestTarget = target;
                bestCount = count;
            }
        }

        // Fallback: if all non-default case targets eventually reach the default target,
        // then the default target IS the merge point (e.g., string switch hashCode phase)
        if (bestTarget < 0 && switchBlock.switchDefaultTarget >= 0) {
            boolean allGotoDefault = true;
            if (switchBlock.switchTargets != null) {
                for (int t : switchBlock.switchTargets) {
                    if (t == switchBlock.switchDefaultTarget) continue;
                    BasicBlock tb = cfg.getBlockAtPc(t);
                    boolean reachesDefault = false;
                    Set<Integer> seen = new HashSet<Integer>();
                    while (tb != null && !seen.contains(tb.startPc)) {
                        seen.add(tb.startPc);
                        if (tb.isGoto() && tb.branchTargetPc == switchBlock.switchDefaultTarget) {
                            reachesDefault = true;
                            break;
                        }
                        if (tb.isReturn() || tb.isThrow()) break;
                        tb = tb.trueSuccessor;
                    }
                    if (!reachesDefault) {
                        allGotoDefault = false;
                        break;
                    }
                }
            }
            if (allGotoDefault) {
                bestTarget = switchBlock.switchDefaultTarget;
            }
        }

        return bestTarget;
    }

    /**
     * Check if a block terminates (return or throw) without continuing.
     */
    private boolean isTerminalBlock(BasicBlock block) {
        Set<Integer> seen = new HashSet<Integer>();
        while (block != null && !seen.contains(block.startPc)) {
            seen.add(block.startPc);
            if (block.isReturn() || block.isThrow()) return true;
            if (block.isConditional()) return false;
            if (block.isGoto()) {
                block = block.trueSuccessor;
            } else {
                block = block.trueSuccessor;
            }
        }
        return false;
    }

    /**
     * Find the conditional block that forms the tail of a do-while loop
     * whose body starts at bodyStartPc.
     */
    private BasicBlock findDoWhileCondition(int bodyStartPc) {
        for (BasicBlock b : cfg.getBlocks()) {
            if (b.isConditional()) {
                if ((b.trueSuccessor != null && b.trueSuccessor.startPc == bodyStartPc && b.startPc > bodyStartPc) ||
                    (b.falseSuccessor != null && b.falseSuccessor.startPc == bodyStartPc && b.startPc > bodyStartPc)) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Negate a boolean condition expression.
     */
    private Expression negateCondition(Expression condition, int line) {
        if (condition instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) condition;
            String negOp = negateOp(boe.getOperator());
            if (negOp != null) {
                return new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    boe.getLeft(), negOp, boe.getRight());
            }
        }
        return new UnaryOperatorExpression(line, PrimitiveType.BOOLEAN, "!", condition, true);
    }

    private String negateOp(String op) {
        if ("==".equals(op)) return "!=";
        if ("!=".equals(op)) return "==";
        if ("<".equals(op)) return ">=";
        if (">=".equals(op)) return "<";
        if (">".equals(op)) return "<=";
        if ("<=".equals(op)) return ">";
        return null;
    }

    /**
     * Check if a conditional block can form a ternary expression.
     * Both of its branches must produce values (or be nested ternaries themselves).
     */
    private boolean canFormTernary(BasicBlock condBlock, Set<Integer> visited) {
        BasicBlock tTrue = condBlock.falseSuccessor;  // fall-through = true
        BasicBlock tFalse = condBlock.trueSuccessor;   // branch = false
        if (tTrue == null || tFalse == null) return false;

        boolean trueOk = tTrue.stackTopExpression != null &&
            (tTrue.statements == null || tTrue.statements.isEmpty()) &&
            (tTrue.isGoto() || tTrue.type == BasicBlock.FALL_THROUGH);
        boolean falseOk = tFalse.stackTopExpression != null &&
            (tFalse.statements == null || tFalse.statements.isEmpty());

        // Allow one level of nesting
        if (!trueOk && tTrue.isConditional() && !visited.contains(tTrue.startPc)) {
            trueOk = canFormTernarySimple(tTrue);
        }
        if (!falseOk && tFalse.isConditional() && !visited.contains(tFalse.startPc)) {
            falseOk = canFormTernarySimple(tFalse);
        }

        return trueOk && falseOk;
    }

    /**
     * Simple (non-recursive) check: both branches of condBlock produce values.
     */
    private boolean canFormTernarySimple(BasicBlock condBlock) {
        BasicBlock tTrue = condBlock.falseSuccessor;
        BasicBlock tFalse = condBlock.trueSuccessor;
        if (tTrue == null || tFalse == null) return false;
        boolean trueOk = tTrue.stackTopExpression != null &&
            (tTrue.statements == null || tTrue.statements.isEmpty());
        boolean falseOk = tFalse.stackTopExpression != null &&
            (tFalse.statements == null || tFalse.statements.isEmpty());
        return trueOk && falseOk;
    }

    /**
     * Find the merge PC for a nested ternary structure.
     */
    private int findNestedTernaryMerge(BasicBlock condBlock) {
        BasicBlock tTrue = condBlock.falseSuccessor;
        if (tTrue != null && tTrue.stackTopExpression != null && tTrue.isGoto()) {
            return tTrue.branchTargetPc;
        }
        if (tTrue != null && tTrue.stackTopExpression != null && tTrue.trueSuccessor != null) {
            return tTrue.trueSuccessor.startPc;
        }
        // Recurse into nested conditional
        if (tTrue != null && tTrue.isConditional()) {
            return findNestedTernaryMerge(tTrue);
        }
        return -1;
    }

    /**
     * Build a ternary expression from a conditional block that forms a ternary.
     */
    private Expression buildTernaryExpression(BasicBlock condBlock, Set<Integer> visited, int line) {
        if (condBlock.condition == null) {
            decoder.decodeBlock(condBlock);
        }
        Expression cond = condBlock.condition;
        if (cond == null) return null;

        BasicBlock tTrue = condBlock.falseSuccessor;
        BasicBlock tFalse = condBlock.trueSuccessor;
        if (tTrue == null || tFalse == null) return null;

        Expression trueVal = tTrue.stackTopExpression;
        Expression falseVal = tFalse.stackTopExpression;

        // Recursively build nested ternary for true branch
        if (trueVal == null && tTrue.isConditional()) {
            trueVal = buildTernaryExpression(tTrue, visited, line);
        }
        // Recursively build nested ternary for false branch
        if (falseVal == null && tFalse.isConditional()) {
            falseVal = buildTernaryExpression(tFalse, visited, line);
        }

        if (trueVal == null || falseVal == null) return null;

        visited.add(condBlock.startPc);
        visited.add(tTrue.startPc);
        visited.add(tFalse.startPc);

        Type type = trueVal.getType() != null ? trueVal.getType() : PrimitiveType.INT;
        return new TernaryExpression(line, type, cond, trueVal, falseVal);
    }

    // START_CHANGE: BUG-2026-0015-20260324-2 - Replace ternary value in merge block statements
    private void replaceTernaryInMergeStatements(List<Statement> output,
            List<Statement> mergeStmts, Expression ternary,
            Expression trueValue, Expression falseValue, Expression receiver) {
        for (int i = 0; i < mergeStmts.size(); i++) {
            Statement s = mergeStmts.get(i);
            if (i == 0 && s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                // Replace the value argument with the ternary expression
                Expression replaced = replaceArgWithTernary(expr, ternary, trueValue, falseValue, receiver);
                if (replaced != null) {
                    output.add(new ExpressionStatement(replaced));
                    continue;
                }
            }
            output.add(s);
        }
    }

    private Expression replaceArgWithTernary(Expression expr, Expression ternary,
            Expression trueValue, Expression falseValue, Expression receiver) {
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            List<Expression> args = mie.getArguments();
            // Determine the correct object receiver
            Expression obj = mie.getObject();
            if (receiver != null) {
                obj = receiver;
            }
            if (args != null && !args.isEmpty()) {
                List<Expression> newArgs = new ArrayList<Expression>(args);
                // Replace last arg that matches one of the ternary values
                for (int i = newArgs.size() - 1; i >= 0; i--) {
                    Expression arg = newArgs.get(i);
                    if (expressionMatchesValue(arg, trueValue) || expressionMatchesValue(arg, falseValue)) {
                        newArgs.set(i, ternary);
                        return new MethodInvocationExpression(
                            mie.getLineNumber(), mie.getType(), obj,
                            mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), newArgs);
                    }
                }
                // If no direct match, replace the last argument
                newArgs.set(newArgs.size() - 1, ternary);
                return new MethodInvocationExpression(
                    mie.getLineNumber(), mie.getType(), obj,
                    mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), newArgs);
            } else {
                // No args - the ternary is the sole argument
                List<Expression> newArgs = new ArrayList<Expression>();
                newArgs.add(ternary);
                return new MethodInvocationExpression(
                    mie.getLineNumber(), mie.getType(), obj,
                    mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), newArgs);
            }
        }
        return null;
    }

    private boolean expressionMatchesValue(Expression a, Expression b) {
        if (a == null || b == null) return false;
        if (a instanceof StringConstantExpression && b instanceof StringConstantExpression) {
            return ((StringConstantExpression) a).getValue().equals(((StringConstantExpression) b).getValue());
        }
        if (a instanceof IntegerConstantExpression && b instanceof IntegerConstantExpression) {
            return ((IntegerConstantExpression) a).getValue() == ((IntegerConstantExpression) b).getValue();
        }
        return a == b;
    }
    // END_CHANGE: BUG-2026-0015-2

    /**
     * Interface for the bytecode decoder that populates block statements and conditions.
     */
    public interface BytecodeDecoder {
        /**
         * Decode the instructions in a basic block.
         * Populates block.statements and block.condition (for conditional blocks).
         */
        void decodeBlock(BasicBlock block);
    }
}
