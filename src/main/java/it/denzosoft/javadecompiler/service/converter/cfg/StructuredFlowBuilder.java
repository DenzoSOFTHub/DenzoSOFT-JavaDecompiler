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
    // START_CHANGE: BUG-2026-0026-20260325-1 - Track while(true) loop headers (targets of unconditional backward gotos)
    private Map<Integer, Integer> whileTrueHeaders = new HashMap<Integer, Integer>(); // header PC -> goto source PC
    // END_CHANGE: BUG-2026-0026-1
    private Map<Integer, Integer> precomputedMergePoints = new HashMap<Integer, Integer>();
    private int lastEffectiveMergePoint = -1;
    private int recursionDepth = 0;
    // START_CHANGE: ISS-2026-0007-20260324-1 - Track outer loop merge points for labeled break detection
    private List<Integer> outerLoopMergePoints = new ArrayList<Integer>();
    // BUG-2026-0078: exit PCs of enclosing `while(true)` loops; a goto to one of these is a plain `break`.
    private List<Integer> whileTrueExitStack = new ArrayList<Integer>();
    // START_CHANGE: BUG-2026-0085-20260610-1 - Merge PCs of enclosing statement switches; a goto
    // to the innermost entry is a switch `break` (the statement-switch builder never emitted
    // breaks: case bodies walked into the merge and stopped silently, so every printed case
    // fell through and e.g. C_Java7StringSwitch.category returned "unknown" for every input).
    private List<Integer> switchMergeStack = new ArrayList<Integer>();
    // END_CHANGE: BUG-2026-0085-1
    // START_CHANGE: BUG-2026-0086-20260610-3 - Continue-target PCs of enclosing loops: the
    // for-loop increment block (last backjump source) or the do-while condition block. A forward
    // goto to the innermost entry from a nested context (switch arm, nested if) is a `continue`;
    // it used to be emitted as a plain `break` by the generic goto-beyond-stopPc path, silently
    // turning `continue` into loop exit. while(true) loops push a -1 sentinel (their continue is
    // a backward goto, already handled) so a nested goto can never match an OUTER loop's entry.
    private List<Integer> loopContinueTargets = new ArrayList<Integer>();
    // END_CHANGE: BUG-2026-0086-3
    // START_CHANGE: BUG-2026-0083-20260610-3 - Ternaries whose merge block carries the merged
    // value onward on the operand stack (e.g. the first of two ternary call arguments: the
    // merge block IS the next ternary's condition block and has no statements of its own).
    // The consumer statement lives in a LATER merge block and references this ternary's arm
    // value by object identity (exit-stack seeding propagates the same Expression objects).
    // Each entry is {trueValue, falseValue, ternary}; applied and cleared by
    // replaceTernaryInMergeStatements when the eventual consumer statement is rewritten.
    private final List<Expression[]> pendingStackTernaries = new ArrayList<Expression[]>();
    // END_CHANGE: BUG-2026-0083-3
    private Map<Integer, String> labeledBreakLabels = new HashMap<Integer, String>();
    private int labelCounter = 0;
    // END_CHANGE: ISS-2026-0007-1

    public StructuredFlowBuilder(ControlFlowGraph cfg, BytecodeDecoder decoder) {
        this.cfg = cfg;
        this.decoder = decoder;
    }

    // START_CHANGE: BUG-2026-0066-20260610-15 - The method's return type matters for switch
    // EXPRESSION reconstruction: a boolean method's `ireturn` merge consumes int 0/1 arm values
    // that must be rendered as boolean literals (`case SAT, SUN -> true`). The converter sets
    // this from the method descriptor; transforms like BooleanSimplifier run too late (they do
    // not descend into SwitchExpression arms).
    private boolean methodReturnsBoolean = false;

    public void setMethodReturnsBoolean(boolean b) { this.methodReturnsBoolean = b; }
    // END_CHANGE: BUG-2026-0066-15

    // START_CHANGE: BUG-2026-0067-20260610-53 - Bootstrap label lookup for unnamed pattern arms.
    // An unnamed type-pattern arm (`case Integer _ ->`) has NO cast-bind statement in its block
    // (the binding is dead, javac emits nothing), so the pattern type can only come from the
    // SwitchBootstraps.typeSwitch bootstrap arguments. Those are decoded by the converter
    // (keyed `methodName + "_" + line`); this callback reads them live AFTER block decode.
    public interface PatternLabelSource {
        List<String> labelsFor(String key);
    }

    private PatternLabelSource patternLabelSource;

    public void setPatternLabelSource(PatternLabelSource s) { this.patternLabelSource = s; }
    // END_CHANGE: BUG-2026-0067-53

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

        // START_CHANGE: BUG-2026-0026-20260325-2 - Pre-scan: identify while(true) headers (targets of unconditional backward gotos)
        // Only mark as while(true) if the header is NOT already a while(condition) header
        whileTrueHeaders.clear();
        for (BasicBlock block : blocks) {
            if (block.isGoto() && block.trueSuccessor != null
                && block.trueSuccessor.startPc <= block.startPc
                && !doWhileHeaders.contains(block.trueSuccessor.startPc)) {
                BasicBlock header = block.trueSuccessor;
                // Skip if header is a conditional that forms a while(condition) loop
                // (i.e., one of its successors has a back-edge to it)
                boolean isWhileCondition = false;
                if (header.isConditional()) {
                    BasicBlock trueSucc = header.falseSuccessor; // fall-through
                    if (trueSucc != null && hasBackEdgeTo(header.startPc, trueSucc)) {
                        isWhileCondition = true;
                    }
                }
                if (isWhileCondition) continue;
                int headerPc = header.startPc;
                Integer existing = whileTrueHeaders.get(headerPc);
                if (existing == null || block.startPc > existing.intValue()) {
                    whileTrueHeaders.put(headerPc, Integer.valueOf(block.startPc));
                }
            }
        }
        // END_CHANGE: BUG-2026-0026-2

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
                        // START_CHANGE: BUG-2026-0086-20260610-7 - A do-while `continue` is a
                        // forward goto to the condition block: track it as the continue target.
                        loopContinueTargets.add(Integer.valueOf(condBlock.startPc));
                        buildFromBlock(block, bodyStmts, visited, condBlock.startPc);
                        loopContinueTargets.remove(loopContinueTargets.size() - 1);
                        // END_CHANGE: BUG-2026-0086-7

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

            // START_CHANGE: BUG-2026-0026-20260325-3 - Detect while(true) loops from unconditional backward gotos
            if (whileTrueHeaders.containsKey(block.startPc) && !visited.contains(block.startPc)) {
                int gotoSourcePc = whileTrueHeaders.get(block.startPc).intValue();
                whileTrueHeaders.remove(block.startPc);
                // Find the block after the goto (the exit point of the loop)
                BasicBlock gotoBlock = null;
                int exitPc = -1;
                for (BasicBlock b : cfg.getBlocks()) {
                    if (b.isGoto() && b.startPc == gotoSourcePc) {
                        gotoBlock = b;
                        // Exit is the next block after the goto block
                        if (b.endPc < cfg.getBlocks().get(cfg.getBlocks().size() - 1).endPc) {
                            BasicBlock exitBlock = cfg.getBlockAtPc(b.endPc);
                            if (exitBlock != null) {
                                exitPc = exitBlock.startPc;
                            }
                        }
                        break;
                    }
                }
                // Build the loop body: from block up to the goto source block (inclusive)
                // Use a new visited set for the body but share the goto-block boundary
                List<Statement> bodyStmts = new ArrayList<Statement>();
                // The stopPc for the body is: the block AFTER the goto block
                // We use the goto block's endPc as a reasonable boundary
                int bodyStopPc = gotoBlock != null ? gotoBlock.endPc : -1;
                // BUG-2026-0078: mark the loop exit so an inner `goto exit` becomes a plain `break`.
                boolean pushedExit078 = false;
                if (exitPc >= 0) { whileTrueExitStack.add(Integer.valueOf(exitPc)); pushedExit078 = true; }
                // START_CHANGE: BUG-2026-0086-20260610-8 - Sentinel: a while(true) continue is a
                // backward goto (already handled); the sentinel keeps a nested goto from matching
                // an OUTER loop's continue target as a plain `continue`.
                loopContinueTargets.add(Integer.valueOf(-1));
                buildFromBlock(block, bodyStmts, visited, bodyStopPc);
                loopContinueTargets.remove(loopContinueTargets.size() - 1);
                // END_CHANGE: BUG-2026-0086-8
                if (pushedExit078) whileTrueExitStack.remove(whileTrueExitStack.size() - 1);

                int bodyLine = block.lineNumber > 0 ? block.lineNumber : 0;
                // BUG-2026-0067: a `while (true)` whose body unconditionally returns/throws on the first
                // iteration (e.g. a reconstructed guarded pattern switch `return switch(...) {...}`) is
                // equivalent to its body — drop the loop wrapper.
                if (bodyStmts.size() == 1
                        && (bodyStmts.get(0) instanceof ReturnStatement || bodyStmts.get(0) instanceof ThrowStatement)) {
                    output.add(bodyStmts.get(0));
                } else {
                    output.add(new WhileStatement(bodyLine,
                        new BooleanExpression(bodyLine, true),
                        new BlockStatement(bodyLine, bodyStmts)));
                }

                // Continue from exit block if any
                if (exitPc >= 0) {
                    BasicBlock exitBlock = cfg.getBlockAtPc(exitPc);
                    if (exitBlock != null && !visited.contains(exitBlock.startPc)) {
                        block = exitBlock;
                        continue;
                    }
                }
                return;
            }
            // END_CHANGE: BUG-2026-0026-3

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
                        // START_CHANGE: BUG-2026-0085-20260610-6 - Never follow a merge into or
                        // past the innermost enclosing switch's merge PC: the switch statement's
                        // own continuation processes the merge block, and following it from
                        // inside a case body nested the post-switch code into the first case.
                        if (!mergeIsOuterExit && !switchMergeStack.isEmpty()
                                && mergePoint >= switchMergeStack.get(switchMergeStack.size() - 1).intValue()) {
                            mergeIsOuterExit = true;
                        }
                        // END_CHANGE: BUG-2026-0085-6
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
                // START_CHANGE: BUG-2026-0085-20260610-10 - Pass the current build bound so the
                // merge scan ignores gotos that originate outside this switch's region (a nested
                // switch used to adopt the OUTER switch's merge because the outer arms' break
                // gotos outnumbered its own).
                int switchMergePc = findSwitchMergePoint(block, stopPc);
                // END_CHANGE: BUG-2026-0085-10

                // START_CHANGE: BUG-2026-0066-20260608-1 - Try to reconstruct a switch EXPRESSION
                // first (every arm yields a value into a common `return` merge). Falls through to the
                // statement-switch builder if the shape does not match.
                Statement switchExpr = tryBuildSwitchExpression(block, switchMergePc, visited, stopPc);
                if (switchExpr != null) {
                    if (switchExpr instanceof BlockStatement) {
                        output.addAll(((BlockStatement) switchExpr).getStatements());
                    } else {
                        output.add(switchExpr);
                    }
                    // START_CHANGE: BUG-2026-0066-20260610-3 - The merge block was NOT consumed:
                    // its consumer statement now embeds the reconstructed SwitchExpression (in-place
                    // substitution), so continue the normal flow there. For a `return` merge the
                    // flow ends naturally; for a store-merge (e.g. `x = switch(...)` inside an
                    // enclosing switch arm) the merge's own terminal (goto-as-break, fall-through)
                    // is handled by the regular machinery.
                    BasicBlock seMergeBlock = cfg.getBlockAtPc(switchMergePc);
                    if (seMergeBlock != null && !visited.contains(seMergeBlock.startPc)) {
                        block = seMergeBlock;
                        continue;
                    }
                    return;
                    // END_CHANGE: BUG-2026-0066-3
                }
                // END_CHANGE: BUG-2026-0066-1

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
                    // START_CHANGE: BUG-2026-0085-20260610-2 - Build cases in bytecode PC order
                    // with next-target bounding and break emission on verified goto-to-merge
                    // terminals.
                    // (a) Slot the default group at its PC position and sort all groups by target
                    //     PC ascending: bytecode layout order IS the original source case order.
                    //     Key-order processing used to inline a shared fall-through tail into the
                    //     key-order-first case and then drop the default because its target block
                    //     was already visited (C_ControlFlow.switchWithFallAccumulate).
                    // (b) Bound each case body with the NEXT group's target PC so a case that
                    //     physically falls into the next case stops there (genuine fall-through
                    //     preserved, shared tails never inlined into the wrong case).
                    // (c) A goto to the switch merge PC is a `break`: push the merge on
                    //     switchMergeStack (consumed in the goto handling of buildFromBlock0) and
                    //     additionally append a trailing break when the case region's physically
                    //     last block is a goto-to-merge that the structured-pattern machinery
                    //     swallowed (e.g. a conditional branching straight to the merge).
                    if (block.switchDefaultTarget >= 0) {
                        int dIdx = targetPcs.indexOf(Integer.valueOf(block.switchDefaultTarget));
                        if (dIdx >= 0) {
                            // `case X: default:` share the same body: default subsumes the keys.
                            keyGroups.set(dIdx, null);
                        } else {
                            keyGroups.add(null); // null label list = default group
                            targetPcs.add(Integer.valueOf(block.switchDefaultTarget));
                        }
                    }
                    // Sort the groups by target PC ascending (insertion sort: groups are few).
                    for (int a = 1; a < targetPcs.size(); a++) {
                        for (int b = a; b > 0
                                && targetPcs.get(b).intValue() < targetPcs.get(b - 1).intValue(); b--) {
                            Integer tmpPc = targetPcs.get(b);
                            targetPcs.set(b, targetPcs.get(b - 1));
                            targetPcs.set(b - 1, tmpPc);
                            List<Integer> tmpGroup = keyGroups.get(b);
                            keyGroups.set(b, keyGroups.get(b - 1));
                            keyGroups.set(b - 1, tmpGroup);
                        }
                    }
                    boolean pushedMerge085 = false;
                    if (switchMergePc >= 0) {
                        switchMergeStack.add(Integer.valueOf(switchMergePc));
                        pushedMerge085 = true;
                    }
                    for (int g = 0; g < keyGroups.size(); g++) {
                        List<Expression> labels = null;
                        if (keyGroups.get(g) != null) {
                            labels = new ArrayList<Expression>();
                            for (int k = 0; k < keyGroups.get(g).size(); k++) {
                                labels.add(IntegerConstantExpression.valueOf(block.lineNumber, keyGroups.get(g).get(k).intValue()));
                            }
                        }
                        List<Statement> caseStmts = new ArrayList<Statement>();
                        int targetPc = targetPcs.get(g).intValue();
                        // Bound the body at the next group's target (the fall-through boundary).
                        int boundPc = (g + 1 < targetPcs.size())
                            ? targetPcs.get(g + 1).intValue() : caseStopPc;
                        if (targetPc >= 0) {
                            BasicBlock targetBlock = cfg.getBlockAtPc(targetPc);
                            if (targetBlock != null && !visited.contains(targetBlock.startPc)) {
                                buildFromBlock(targetBlock, caseStmts, visited, boundPc);
                                appendSwitchBreak(caseStmts, targetPc, boundPc, switchMergePc);
                            } else if (labels == null) {
                                continue; // default body already consumed elsewhere (legacy behavior)
                            }
                        }
                        cases.add(new SwitchStatement.SwitchCase(labels, caseStmts));
                    }
                    if (pushedMerge085) {
                        switchMergeStack.remove(switchMergeStack.size() - 1);
                    }
                    // END_CHANGE: BUG-2026-0085-2
                }
                // END_CHANGE: BUG-2026-0017-1
                // Default case
                // START_CHANGE: BUG-2026-0085-20260610-3 - Only when the key grouping above could
                // not run; otherwise the default was already slotted at its PC position.
                if (block.switchKeys == null && block.switchDefaultTarget >= 0) {
                // END_CHANGE: BUG-2026-0085-3
                    BasicBlock defaultBlock = cfg.getBlockAtPc(block.switchDefaultTarget);
                    if (defaultBlock != null && !visited.contains(defaultBlock.startPc)) {
                        List<Statement> defaultStmts = new ArrayList<Statement>();
                        buildFromBlock(defaultBlock, defaultStmts, visited, caseStopPc);
                        cases.add(new SwitchStatement.SwitchCase(null, defaultStmts));
                    }
                }

                // Get selector expression - it's the last expression loaded before the switch
                Expression selector = null;
                // START_CHANGE: BUG-2026-0085-20260610-14 - Prefer the block's saved selector:
                // it is the exact expression popped by the tableswitch/lookupswitch opcode. The
                // last-statement heuristic mistook an unrelated trailing assignment (e.g.
                // `var2 = "1"; switch (arg1) {...}`) for the selector setup, switching on the
                // wrong variable.
                if (block.selectorExpression != null) {
                    selector = block.selectorExpression;
                }
                // END_CHANGE: BUG-2026-0085-14
                // Try to extract from block's statements - the last assignment or load is the selector
                if (selector == null && !block.statements.isEmpty()) {
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
                if (selector == null) {
                    selector = new LocalVariableExpression(block.lineNumber, PrimitiveType.INT, "var", 0);
                }
                output.add(new SwitchStatement(block.lineNumber, selector, cases, false));

                // Continue processing from the merge point (if any) instead of returning.
                // This is critical for patterns like string switch where two consecutive
                // switches (hashCode switch + index switch) must be emitted sequentially.
                // START_CHANGE: BUG-2026-0085-20260610-13 - but never past the current build
                // bound: a nested switch whose break gotos exit the enclosing arm used to pull
                // the enclosing merge's code (e.g. the method's return) into its own arm. A
                // backward bound (loop header before the switch) does not constrain the merge.
                if (switchMergePc >= 0
                        && (stopPc < 0 || stopPc <= block.startPc || switchMergePc <= stopPc)) {
                // END_CHANGE: BUG-2026-0085-13
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
                // START_CHANGE: BUG-2026-0085-20260610-4 - A forward goto to the innermost
                // enclosing statement-switch's merge PC is a switch `break`. Previously the case
                // body walked into the merge block and stopped silently (no statement emitted),
                // so the printed cases fell through where the bytecode did not.
                if (target != null && !switchMergeStack.isEmpty()
                        && target.startPc == switchMergeStack.get(switchMergeStack.size() - 1).intValue()) {
                    output.add(new BreakStatement(block.lineNumber));
                    return;
                }
                // END_CHANGE: BUG-2026-0085-4
                // BUG-2026-0078: a forward goto to an enclosing while(true)'s exit PC is a plain `break`.
                // (Checked before the labeled-break logic so the innermost loop stays unlabeled.)
                if (target != null && whileTrueExitStack.contains(Integer.valueOf(target.startPc))) {
                    output.add(new BreakStatement(block.lineNumber));
                    return;
                }
                // START_CHANGE: BUG-2026-0086-20260610-4 - A forward goto to the innermost
                // enclosing loop's continue target is a `continue` (e.g. `case 1: continue;`
                // inside a for loop jumps to the increment block). target == stopPc is excluded:
                // that is the natural end of the current region, not a statement.
                if (target != null && !loopContinueTargets.isEmpty() && target.startPc != stopPc
                        && target.startPc == loopContinueTargets.get(loopContinueTargets.size() - 1).intValue()) {
                    output.add(new ContinueStatement(block.lineNumber));
                    return;
                }
                // END_CHANGE: BUG-2026-0086-4
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
            } else if (block.isConditional()) {
                // START_CHANGE: BUG-2026-0086-20260610-2 - Harden: an unmatched conditional must
                // never silently drop both successors (whole loop/catch bodies vanished while the
                // result still compiled). Emit a structural if/else with both forward successors
                // recursively built, bounded by the current stopPc. The display condition is true
                // on the fall-through (falseSuccessor) path.
                BasicBlock thenTarget = block.falseSuccessor;
                BasicBlock elseTarget = block.trueSuccessor;
                int hLine = block.lineNumber > 0 ? block.lineNumber : 0;
                Expression hCond = block.condition != null
                    ? block.condition : new BooleanExpression(hLine, true);
                List<Statement> hThen = new ArrayList<Statement>();
                if (thenTarget != null && thenTarget.startPc > block.startPc
                        && thenTarget.startPc != stopPc && !visited.contains(thenTarget.startPc)) {
                    buildFromBlock(thenTarget, hThen, visited, stopPc);
                }
                List<Statement> hElse = new ArrayList<Statement>();
                if (elseTarget != null && elseTarget.startPc > block.startPc
                        && elseTarget.startPc != stopPc && !visited.contains(elseTarget.startPc)) {
                    buildFromBlock(elseTarget, hElse, visited, stopPc);
                }
                if (!hThen.isEmpty() && !hElse.isEmpty()) {
                    output.add(new IfElseStatement(hLine, hCond,
                        new BlockStatement(hLine, hThen), new BlockStatement(hLine, hElse)));
                } else if (!hThen.isEmpty()) {
                    output.add(new IfStatement(hLine, hCond, new BlockStatement(hLine, hThen)));
                } else if (!hElse.isEmpty()) {
                    output.add(new IfStatement(hLine, negateCondition(hCond, hLine),
                        new BlockStatement(hLine, hElse)));
                }
                return;
                // END_CHANGE: BUG-2026-0086-2
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
                    // START_CHANGE: BUG-2026-0095-20260610-1 - The && merge used to take ONLY
                    // secondCond.condition and silently DROP secondCond.statements (code guarded
                    // by the left conjunct): instanceof pattern-binding casts and record-pattern
                    // component stores vanished while the body still referenced them
                    // (C_InstanceofPattern.bothNonEmpty, C_RecordPattern.isDiagonal). Merge as
                    // before only when the block carries no statements. When its single statement
                    // is the pattern-binding cast of the rightmost conjunct's unbound instanceof
                    // (javac's lowering of `a instanceof T v && ...`), fold it back into a bound
                    // `a instanceof T v` and merge. Otherwise REFUSE the merge: the block stays a
                    // nested if with its statements intact (always semantically correct).
                    if (cond2 != null) {
                        Expression left095 = condition;
                        if (secondCond.statements != null && !secondCond.statements.isEmpty()) {
                            left095 = bindPatternCastIntoRightmostConjunct(condition,
                                secondCond.statements);
                        }
                        if (left095 != null) {
                            condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                                left095, "&&", cond2);
                            visited.add(secondCond.startPc);
                            trueTarget = secondCond.falseSuccessor;
                            compoundFound = true; // try again for next &&
                        }
                    }
                    // END_CHANGE: BUG-2026-0095-1
                } else if (secondCond.falseSuccessor != null &&
                           falseTarget.startPc == secondCond.falseSuccessor.startPc) {
                    // OR pattern: first cond false -> target, second cond fall-through -> same target
                    Expression cond2 = secondCond.condition;
                    // START_CHANGE: BUG-2026-0095-20260610-2 - Same statement-drop guard for the
                    // || merge. An || operand evaluates only when the left one is FALSE, so it can
                    // never carry a definitely-assigned pattern binding: require an empty statement
                    // list, otherwise refuse the merge (nested if keeps the statements).
                    if (cond2 != null
                            && (secondCond.statements == null || secondCond.statements.isEmpty())) {
                    // END_CHANGE: BUG-2026-0095-2
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
                    // START_CHANGE: BUG-2026-0095-20260610-3 - Statement-drop guard for the
                    // false-target || chain: merging used to discard secondCond.statements (code
                    // that runs only when the first condition is false). Require an empty
                    // statement list, otherwise refuse the merge.
                    if (cond2 != null
                            && (secondCond.statements == null || secondCond.statements.isEmpty())) {
                    // END_CHANGE: BUG-2026-0095-3
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
                            boolean pureReturn = mergeBlock.isReturn()
                                && (mergeBlock.statements == null || mergeBlock.statements.isEmpty());
                            // START_CHANGE: BUG-2026-0083-20260610-1 - The BUG-2026-0025 shortcut treated ANY
                            // single-ReturnStatement merge block as a pure return and emitted `return <ternary>`,
                            // discarding the decoded return expression. That is only correct when the returned
                            // expression IS the arm value (the merge stack is seeded with the same Expression
                            // objects as the branch stackTopExpressions, so identity matching works). When the
                            // arm value is embedded deeper (e.g. `return new Slope(cond ? a : b)`), the shortcut
                            // silently deleted the consumer expression. Take the shortcut only for a direct
                            // arm-value return; everything else goes through the generalized consumer rewriter.
                            if (!pureReturn && mergeBlock.isReturn()
                                && mergeBlock.statements != null && mergeBlock.statements.size() == 1
                                && mergeBlock.statements.get(0) instanceof ReturnStatement) {
                                Expression retExpr = ((ReturnStatement) mergeBlock.statements.get(0)).getExpression();
                                if (retExpr == null || isTernaryArmValue(retExpr, trueValue, falseValue, false)) {
                                    pureReturn = true;
                                }
                            }
                            if (pureReturn) {
                                // return condition ? A : B;
                                visited.add(mergeBlock.startPc);
                                preStatements.add(new ReturnStatement(line, ternary));
                            } else if (mergeBlock.statements != null && !mergeBlock.statements.isEmpty()) {
                                // The merge block consumes the merged value (local store/declaration
                                // initializer, field write, invocation/constructor argument, return
                                // value, ...). Locate the seeded arm-value reference inside the merge
                                // statements and substitute the ternary there, recursing into nested
                                // expressions. Previously only a leading ExpressionStatement wrapping a
                                // MethodInvocationExpression (return merges) or an AssignmentExpression
                                // (store merges) was handled; every other consumer shape copied the merge
                                // statements verbatim with ONE arm's value, silently deleting the
                                // condition and the other branch (C_FlexibleCtor$Sub's lstore-to-
                                // declaration shape).
                                //
                                // The substitution is done IN PLACE on the merge block's statement list
                                // and the block is deliberately NOT marked visited: the merge block can
                                // continue the flow (fall-through chain, another conditional sharing the
                                // block, return/goto handling). Direct consumption used to truncate the
                                // method right after the ternary (`int a = c ? x : y;` followed by more
                                // code dropped everything after the store). Setting
                                // lastEffectiveMergePoint steers the caller INTO the merge block so the
                                // regular machinery emits it exactly once.
                                Expression receiver = condBlock.stackTopExpression;
                                List<Statement> rewritten = new ArrayList<Statement>();
                                replaceTernaryInMergeStatements(rewritten, mergeBlock.statements, ternary, trueValue, falseValue, receiver);
                                mergeBlock.statements.clear();
                                mergeBlock.statements.addAll(rewritten);
                                lastEffectiveMergePoint = mergeBlock.startPc;
                            } else if (mergeBlock.isConditional() && !visited.contains(mergeBlock.startPc)) {
                                // No statements: the merged value stays on the operand stack and the
                                // merge block immediately starts the next condition (chained ternary
                                // arguments: `fmt(c1 ? a : b, c2 ? x : y)`). Register the ternary as
                                // pending — the eventual consumer (a later merge block's statement)
                                // references this ternary's arm value via the seeded stack — and
                                // continue INTO the merge block so the chain keeps being processed.
                                pendingStackTernaries.add(new Expression[] { trueValue, falseValue, ternary });
                                lastEffectiveMergePoint = mergeBlock.startPc;
                            } else {
                                visited.add(mergeBlock.startPc);
                                preStatements.add(new ExpressionStatement(ternary));
                            }
                            // END_CHANGE: BUG-2026-0083-1
                            // END_CHANGE: BUG-2026-0015-1
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
            // START_CHANGE: BUG-2026-0086-20260610-5 - Track the loop's continue target: the
            // start of the LAST block that jumps back to the header (for-loop increment block /
            // while-loop backjump). A nested goto to it is a `continue` (see change -4).
            int continueTargetPc = -1;
            for (BasicBlock b : cfg.getBlocks()) {
                if (b.isGoto() && b.branchTargetPc == condBlock.startPc
                        && b.startPc > condBlock.startPc && b.startPc > continueTargetPc) {
                    continueTargetPc = b.startPc;
                }
            }
            loopContinueTargets.add(Integer.valueOf(continueTargetPc));
            // END_CHANGE: BUG-2026-0086-5
            // while(condition) { body }
            List<Statement> body = new ArrayList<Statement>();
            buildFromBlock(trueTarget, body, visited, condBlock.startPc);

            // START_CHANGE: ISS-2026-0007-20260324-3 - Pop outer loop merge point
            // START_CHANGE: BUG-2026-0086-20260610-6 - and the continue target.
            loopContinueTargets.remove(loopContinueTargets.size() - 1);
            // END_CHANGE: BUG-2026-0086-6
            if (loopExitPc >= 0) {
                outerLoopMergePoints.remove(outerLoopMergePoints.size() - 1);
            }
            // END_CHANGE: ISS-2026-0007-3

            // START_CHANGE: BUG-2026-0016-20260326-1 - Merge assignment into while condition
            // Detect pattern: last statement assigns a variable used in condition
            // e.g. line = reader.readLine(); while(line != null) → while((line = reader.readLine()) != null)
            List<Statement> result = new ArrayList<Statement>();
            Expression mergedCondition = condition;
            List<Statement> preStmts = condBlock.statements;
            if (!preStmts.isEmpty()) {
                Statement lastStmt = preStmts.get(preStmts.size() - 1);
                String assignedVarName = null;
                Expression assignmentExpr = null;
                boolean isDeclaration = false;
                Type declType = null;
                String declName = null;

                if (lastStmt instanceof ExpressionStatement) {
                    Expression expr = ((ExpressionStatement) lastStmt).getExpression();
                    if (expr instanceof AssignmentExpression) {
                        AssignmentExpression ae = (AssignmentExpression) expr;
                        if (ae.getLeft() instanceof LocalVariableExpression && "=".equals(ae.getOperator())) {
                            assignedVarName = ((LocalVariableExpression) ae.getLeft()).getName();
                            assignmentExpr = expr;
                        }
                    }
                } else if (lastStmt instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement vds = (VariableDeclarationStatement) lastStmt;
                    if (vds.hasInitializer()) {
                        assignedVarName = vds.getName();
                        isDeclaration = true;
                        declType = vds.getType();
                        declName = vds.getName();
                        LocalVariableExpression lve = new LocalVariableExpression(vds.getLineNumber(), vds.getType(), vds.getName(), -1);
                        assignmentExpr = new AssignmentExpression(vds.getLineNumber(), vds.getType(), lve, "=", vds.getInitializer());
                    }
                }

                if (assignedVarName != null && conditionUsesVariable(condition, assignedVarName)) {
                    for (int pi = 0; pi < preStmts.size() - 1; pi++) {
                        result.add(preStmts.get(pi));
                    }
                    if (isDeclaration) {
                        result.add(new VariableDeclarationStatement(lastStmt.getLineNumber(), declType, declName, null, false, false));
                    }
                    mergedCondition = replaceVariableInCondition(condition, assignedVarName, assignmentExpr);
                } else {
                    result.addAll(preStmts);
                }
            }
            // END_CHANGE: BUG-2026-0016-1

            WhileStatement ws = new WhileStatement(line, mergedCondition,
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
                // START_CHANGE: BUG-2026-0086-20260610-1 - Continue-outer / break-inner shape: a
                // branch that is a goto to an enclosing loop's exit PC is a labeled break, not a
                // merge violation. Redirect the merge to the OTHER branch so the if-then path
                // builds the goto branch as the then-body (the goto handling emits the labeled
                // break and registers the label Pattern 1 wraps around the loop). Previously the
                // merge was voided, Pattern 3 failed too, matchConditionalPattern returned null
                // and the unmatched conditional dropped BOTH successors — the whole inner loop
                // body vanished (C_ControlFlow.labeledContinue: empty infinite inner while).
                boolean redirected086 = false;
                if (trueTarget.isGoto()
                        && outerLoopMergePoints.contains(Integer.valueOf(trueTarget.branchTargetPc))
                        && falseTarget.startPc > condBlock.startPc) {
                    mergePoint = falseTarget.startPc;
                    redirected086 = true;
                } else if (falseTarget.isGoto()
                        && outerLoopMergePoints.contains(Integer.valueOf(falseTarget.branchTargetPc))
                        && trueTarget.startPc > condBlock.startPc) {
                    mergePoint = trueTarget.startPc;
                    redirected086 = true;
                }
                if (!redirected086) {
                    mergePoint = -1; // Force fall-through to Pattern 3
                }
                // END_CHANGE: BUG-2026-0086-1
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

                // START_CHANGE: BUG-2026-0095-20260610-5 - Shared constant-return false path:
                // several conditionals can share one `iconst_x; ireturn` exit (javac's boolean
                // short-circuit lowering). Once a ternary/other branch consumed it, the else
                // build hit the visited guard and produced an EMPTY else, losing `return false;`
                // for every other path (C_RecordPattern.isDiagonal). Re-materialize the constant
                // return: tail-duplicating a `return <const>` is always sound.
                if (elseBody.isEmpty() && visited.contains(Integer.valueOf(falseTarget.startPc))) {
                    Statement rematerialized095 = materializeSharedConstantReturn(falseTarget, line);
                    if (rematerialized095 != null) {
                        elseBody.add(rematerialized095);
                    }
                }
                // END_CHANGE: BUG-2026-0095-5

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

    // START_CHANGE: BUG-2026-0095-20260610-6 - Helpers for the &&/|| statement-drop fix.
    /**
     * BUG-2026-0095: javac lowers {@code a instanceof T v && <rest>} to
     * {@code [a instanceof T; ifeq] -> [checkcast T; astore v; <rest cond>]}, so the second
     * condition block carries exactly one statement: the pattern-binding cast
     * {@code T v = (T) a;}. When that cast's operand and type match the rightmost {@code &&}
     * conjunct's unbound {@code instanceof} (still wrapped in the {@code != 0} scaffold that
     * extractBranchCondition adds for ifeq; BooleanSimplifier strips it later), fold the
     * binding back into a bound {@code a instanceof T v} so the merge preserves it.
     *
     * @return the rewritten left-hand condition to merge with, or {@code null} to REFUSE the
     *         merge (the caller then leaves the block as a nested if, which is always correct).
     */
    private Expression bindPatternCastIntoRightmostConjunct(Expression condition,
                                                            List<Statement> stmts) {
        if (stmts == null || stmts.size() != 1) return null;

        // Recognize the binding cast: `T v = (T) opnd;` or `v = (T) opnd;`.
        String varName = null;
        CastExpression cast = null;
        Statement s = stmts.get(0);
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (vds.hasInitializer() && vds.getInitializer() instanceof CastExpression) {
                varName = vds.getName();
                cast = (CastExpression) vds.getInitializer();
            }
        } else if (s instanceof ExpressionStatement
                && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            if (ae.getLeft() instanceof LocalVariableExpression && "=".equals(ae.getOperator())
                    && ae.getRight() instanceof CastExpression) {
                varName = ((LocalVariableExpression) ae.getLeft()).getName();
                cast = (CastExpression) ae.getRight();
            }
        }
        if (varName == null || cast == null) return null;

        // Rightmost conjunct of the accumulated && chain.
        Expression rightmost = condition;
        while (rightmost instanceof BinaryOperatorExpression
                && "&&".equals(((BinaryOperatorExpression) rightmost).getOperator())) {
            rightmost = ((BinaryOperatorExpression) rightmost).getRight();
        }
        // Unwrap the `!= 0` ifeq scaffold.
        Expression candidate = rightmost;
        BinaryOperatorExpression zeroWrapper = null;
        if (candidate instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) candidate;
            if ("!=".equals(b.getOperator()) && isZeroConst(b.getRight())) {
                zeroWrapper = b;
                candidate = b.getLeft();
            }
        }
        if (!(candidate instanceof InstanceOfExpression)) return null;
        InstanceOfExpression io = (InstanceOfExpression) candidate;
        if (io.hasPatternVariable() || io.hasRecordPattern()) return null;

        // Same type + same operand as the cast (cf. InstanceOfPatternReconstructor).
        Type castType = cast.getType();
        if (castType == null || io.getCheckType() == null) return null;
        if (castType.getDescriptor() == null
                || !castType.getDescriptor().equals(io.getCheckType().getDescriptor())) return null;
        if (!sameInstanceOfOperand(cast.getExpression(), io.getExpression())) return null;

        Expression bound = new InstanceOfExpression(io.getLineNumber(), io.getExpression(),
            io.getCheckType(), varName);
        if (zeroWrapper != null) {
            bound = new BinaryOperatorExpression(zeroWrapper.getLineNumber(), PrimitiveType.BOOLEAN,
                bound, "!=", zeroWrapper.getRight());
        }
        return replaceRightmostConjunct(condition, bound);
    }

    /** Replace the rightmost {@code &&} conjunct of {@code cond} with {@code repl}. */
    private Expression replaceRightmostConjunct(Expression cond, Expression repl) {
        if (cond instanceof BinaryOperatorExpression
                && "&&".equals(((BinaryOperatorExpression) cond).getOperator())) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) cond;
            return new BinaryOperatorExpression(b.getLineNumber(), PrimitiveType.BOOLEAN,
                b.getLeft(), "&&", replaceRightmostConjunct(b.getRight(), repl));
        }
        return repl;
    }

    /** Operand identity for the binding-cast fold: same local slot or same rendered text. */
    private boolean sameInstanceOfOperand(Expression a, Expression b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof LocalVariableExpression && b instanceof LocalVariableExpression) {
            return ((LocalVariableExpression) a).getIndex() == ((LocalVariableExpression) b).getIndex();
        }
        return a.toString().equals(b.toString());
    }

    /**
     * BUG-2026-0095: re-materialize a shared constant-return exit that was already consumed by
     * another branch. Two shapes: a bare constant-push value block ({@code iconst_x}) flowing
     * into a value return, or the {@code iconst_x; ireturn} block itself. Returns a fresh
     * {@code return <const>;} statement, or {@code null} when the block is anything else.
     */
    private Statement materializeSharedConstantReturn(BasicBlock block, int line) {
        if (block == null) return null;
        // Shape A: statement-free constant value block falling/jumping into a bare return.
        if ((block.statements == null || block.statements.isEmpty())
                && block.stackTopExpression instanceof IntegerConstantExpression) {
            BasicBlock next = null;
            if (block.isGoto()) {
                next = cfg.getBlockAtPc(block.branchTargetPc);
            } else if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
                next = block.trueSuccessor;
            }
            if (next != null && next.isReturn()
                    && (next.statements == null || next.statements.isEmpty()
                        || (next.statements.size() == 1
                            && next.statements.get(0) instanceof ReturnStatement))) {
                return new ReturnStatement(line, block.stackTopExpression);
            }
        }
        // Shape B: the block IS the constant return.
        if (block.isReturn() && block.statements != null && block.statements.size() == 1
                && block.statements.get(0) instanceof ReturnStatement) {
            Expression e = ((ReturnStatement) block.statements.get(0)).getExpression();
            if (e instanceof IntegerConstantExpression) {
                return new ReturnStatement(line, e);
            }
        }
        return null;
    }
    // END_CHANGE: BUG-2026-0095-6

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
    // START_CHANGE: BUG-2026-0062-20260421-2 - Return the lowest-pc block that comes strictly
    // after the given conditional block in bytecode order and whose PC is the endPc of a
    // successor, unvisited. Used as a fallback when findMergePoint gives up: a method shaped
    // as `if (a) { ... } if (b) { ... } return X;` has two sibling if-statements with no merge
    // point between them; we still need to continue to the second if.
    private BasicBlock findNextSequentialBlock(BasicBlock condBlock) {
        int best = Integer.MAX_VALUE;
        BasicBlock bestBlock = null;
        int baseline = condBlock.endPc;
        for (BasicBlock b : cfg.getBlocks()) {
            if (b.startPc >= baseline && b.startPc < best) {
                // Only accept blocks that look like normal sequential code (not exception
                // handlers, not inside an already-visited region)
                best = b.startPc;
                bestBlock = b;
            }
        }
        return bestBlock;
    }
    // END_CHANGE: BUG-2026-0062-2

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
            // START_CHANGE: BUG-2026-0032-20260325-2 - Non-recursive end-of-true scan
            // Find the last goto in the true-path region (without calling findBlockEnd to avoid recursion)
            BasicBlock endOfTrue = null;
            for (BasicBlock b : cfg.getBlocks()) {
                if (b.startPc >= trueTarget.startPc && b.startPc < falseTarget.startPc) {
                    if (b.isGoto()) {
                        if (endOfTrue == null || b.startPc > endOfTrue.startPc) {
                            endOfTrue = b;
                        }
                    }
                }
            }
            // END_CHANGE: BUG-2026-0032-2
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
            // START_CHANGE: BUG-2026-0032-20260325-1 - Skip nested conditionals without recursion
            if (current.isConditional()) {
                // Instead of recursively computing merge points (which causes StackOverflow
                // on deeply nested JDK classes), use a simple heuristic:
                // Scan forward from the conditional's branch target to find the first block
                // that both branches can reach (the goto targets in the region)
                BasicBlock branchTarget = current.trueSuccessor; // branch target (usually farther)
                BasicBlock fallThrough = current.falseSuccessor;

                if (branchTarget != null && branchTarget.startPc < beforePc
                    && fallThrough != null && fallThrough.startPc < beforePc) {
                    // The merge is likely the farther of the two targets
                    int candidateMerge = Math.max(branchTarget.startPc, fallThrough.startPc);
                    // Or look for goto targets in the region that point beyond the conditional
                    int bestMerge = -1;
                    for (BasicBlock b : cfg.getBlocks()) {
                        if (b.startPc >= current.startPc && b.startPc < beforePc && b.isGoto()) {
                            if (b.branchTargetPc >= candidateMerge && b.branchTargetPc <= beforePc) {
                                if (bestMerge < 0 || b.branchTargetPc < bestMerge) {
                                    bestMerge = b.branchTargetPc;
                                }
                            }
                        }
                    }
                    if (bestMerge > 0 && bestMerge < beforePc) {
                        BasicBlock mergeBlock = cfg.getBlockAtPc(bestMerge);
                        if (mergeBlock != null && !seen.contains(mergeBlock.startPc)) {
                            current = mergeBlock;
                            continue;
                        }
                    }
                    if (bestMerge > 0 && bestMerge == beforePc) {
                        // The merge IS the beforePc - find the last goto pointing there
                        BasicBlock bestGoto = null;
                        for (BasicBlock b : cfg.getBlocks()) {
                            if (b.startPc >= current.startPc && b.startPc < beforePc
                                && b.isGoto() && b.branchTargetPc == beforePc) {
                                if (bestGoto == null || b.startPc > bestGoto.startPc) {
                                    bestGoto = b;
                                }
                            }
                        }
                        if (bestGoto != null) return bestGoto;
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

        // START_CHANGE: BUG-2026-0086-20260610-9 - Traverse switch successors: a loop whose body
        // starts with (or contains) a switch was never recognized as a loop because the walk
        // stopped dead at the SWITCH block, so Pattern 1 failed and the loop degenerated into a
        // bodyless while(true) (S5 regression-suite shape: for + switch with break/continue).
        if (block.type == BasicBlock.SWITCH) {
            if (block.switchTargets != null) {
                for (int t : block.switchTargets) {
                    if (t > block.startPc) {
                        BasicBlock tb = cfg.getBlockAtPc(t);
                        if (tb != null && hasDirectBackEdge(headerPc, tb, seen)) return true;
                    }
                }
            }
            if (block.switchDefaultTarget > block.startPc) {
                BasicBlock db = cfg.getBlockAtPc(block.switchDefaultTarget);
                if (db != null && hasDirectBackEdge(headerPc, db, seen)) return true;
            }
            return false;
        }
        // END_CHANGE: BUG-2026-0086-9
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
    // START_CHANGE: BUG-2026-0066-20260608-3 - Switch-expression reconstruction.
    private Statement tryBuildSwitchExpression(BasicBlock block, int mergePc, Set<Integer> visited,
                                               int stopPc) {
        if (mergePc < 0 || block.switchKeys == null || block.switchDefaultTarget < 0) return null;
        BasicBlock mergeBlock = cfg.getBlockAtPc(mergePc);
        if (mergeBlock == null) return null;
        // START_CHANGE: BUG-2026-0066-20260610-4 - The merge no longer has to be a bare `*return`
        // block: any merge whose consumer statement embeds an arm value (assignment store-merge,
        // switch-expr as call argument, `return f(switch...)`) is accepted via in-place
        // substitution below. It must still carry statements to host the substitution, must not
        // have been emitted already, and the caller must be able to continue into it (the merge
        // at or before the current build bound is processed by this or the enclosing region).
        if (visited.contains(mergeBlock.startPc)) return null;
        if (mergeBlock.statements == null || mergeBlock.statements.isEmpty()) return null;
        boolean mergeReachable = stopPc < 0 || stopPc <= block.startPc || mergePc <= stopPc;
        // NOTE: the !mergeReachable bail is applied below, AFTER the pattern-switch detection:
        // a guarded pattern switch lives inside a while(true) restart loop whose body bound is
        // the back-edge goto, so its merge legitimately lies beyond stopPc (BUG-2026-0066-18).
        // END_CHANGE: BUG-2026-0066-4
        int line = block.lineNumber > 0 ? block.lineNumber : 0;

        // Selector.
        // START_CHANGE: BUG-2026-0066-20260610-5 - Prefer the block's saved selector (the exact
        // expression popped by the tableswitch/lookupswitch opcode) over the last-statement
        // heuristic, mirroring BUG-2026-0085-14 on the statement-switch path: an unrelated
        // trailing assignment must neither become the selector nor be dropped from the output.
        Expression selector = block.selectorExpression;
        boolean selFromStmt = false;
        if (selector == null && block.statements != null && !block.statements.isEmpty()) {
            Statement last = block.statements.get(block.statements.size() - 1);
            if (last instanceof ExpressionStatement) {
                Expression e = ((ExpressionStatement) last).getExpression();
                selector = (e instanceof AssignmentExpression) ? ((AssignmentExpression) e).getLeft() : e;
                selFromStmt = true;
            }
        }
        // END_CHANGE: BUG-2026-0066-5
        if (selector == null) return null;

        // BUG-2026-0067: pattern switch — `switch (SwitchBootstraps.typeSwitch(sel, idx))`. The real
        // selector is the first bootstrap argument; arms are `Type b = (Type) sel; <value>`.
        boolean patternSwitch = false;
        Expression realSelector = selector;
        if (selector instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) selector;
            if (("typeSwitch".equals(smie.getMethodName()) || "enumSwitch".equals(smie.getMethodName()))
                    && smie.getArguments() != null && !smie.getArguments().isEmpty()) {
                patternSwitch = true;
                realSelector = smie.getArguments().get(0);
            }
        }
        // START_CHANGE: BUG-2026-0066-20260610-18 - Apply the merge-bound bail (see change -4):
        // pattern switches are exempt because their while(true) restart-loop body bound always
        // precedes the merge; the caller's continue-into-merge walks past the bound and the
        // single resulting `return switch(...)` statement makes the loop wrapper collapse.
        if (!mergeReachable && !patternSwitch) return null;
        // END_CHANGE: BUG-2026-0066-18

        // Group keys that share a target into a single case.
        List<List<Integer>> keyGroups = new ArrayList<List<Integer>>();
        List<Integer> targetPcs = new ArrayList<Integer>();
        for (int i = 0; i < block.switchKeys.length; i++) {
            int targetPc = (block.switchTargets != null && i < block.switchTargets.length) ? block.switchTargets[i] : -1;
            int gi = targetPcs.indexOf(Integer.valueOf(targetPc));
            if (gi >= 0) {
                keyGroups.get(gi).add(Integer.valueOf(block.switchKeys[i]));
            } else {
                List<Integer> g = new ArrayList<Integer>();
                g.add(Integer.valueOf(block.switchKeys[i]));
                keyGroups.add(g);
                targetPcs.add(Integer.valueOf(targetPc));
            }
        }

        // For a guarded pattern switch the arms restart the typeSwitch loop; the restart index is the
        // second bootstrap argument and the loop header is this switch block.
        Expression restartVar = null;
        if (patternSwitch && selector instanceof StaticMethodInvocationExpression) {
            List<Expression> sargs = ((StaticMethodInvocationExpression) selector).getArguments();
            if (sargs != null && sargs.size() > 1) restartVar = sargs.get(1);
        }
        int switchStartPc = block.startPc;

        List<SwitchExpression.SwitchCase> exprCases = new ArrayList<SwitchExpression.SwitchCase>();
        Type valType = null;
        Set<Integer> armPcs = new HashSet<Integer>();
        // START_CHANGE: BUG-2026-0066-20260610-17 - Leaf arm values for identity substitution
        // (filled by simpleSwitchExprArm, including nested arms' leaves).
        List<Expression> leakValues = new ArrayList<Expression>();
        // END_CHANGE: BUG-2026-0066-17
        for (int g = 0; g < keyGroups.size(); g++) {
            BasicBlock tb = cfg.getBlockAtPc(targetPcs.get(g).intValue());
            SwitchExpression.SwitchCase sc;
            if (patternSwitch) {
                // `case null` arm: the typeSwitch returns -1 for null; the arm is a plain value.
                if (keyGroups.get(g).size() == 1 && keyGroups.get(g).get(0).intValue() == -1) {
                    Expression nv = switchArmValue(tb, mergePc);
                    if (nv == null) return null;
                    List<Expression> nl = new ArrayList<Expression>();
                    nl.add(NullExpression.INSTANCE);
                    sc = new SwitchExpression.SwitchCase(nl, nv);
                } else {
                    sc = patternArm(tb, mergePc);
                    if (sc == null) sc = guardedPatternArm(tb, mergePc, switchStartPc, restartVar, armPcs);
                    // START_CHANGE: BUG-2026-0067-20260610-54 - Unnamed type-pattern arm
                    // (`case Integer _ ->`): 0 statements, value-only block. The pattern type is
                    // synthesized from the typeSwitch bootstrap label for the arm's switch key.
                    if (sc == null) {
                        sc = unnamedPatternArm(tb, mergePc,
                            (StaticMethodInvocationExpression) selector, keyGroups.get(g));
                    }
                    // END_CHANGE: BUG-2026-0067-54
                    if (sc == null) return null;
                }
            } else {
                List<Expression> labels = new ArrayList<Expression>();
                for (int k = 0; k < keyGroups.get(g).size(); k++) {
                    labels.add(IntegerConstantExpression.valueOf(line, keyGroups.get(g).get(k).intValue()));
                }
                // START_CHANGE: BUG-2026-0066-20260610-8 - Accept block-bodied, throwing and
                // nested arms, not just bare values (see simpleSwitchExprArm).
                sc = simpleSwitchExprArm(tb, mergePc, labels, armPcs, leakValues, 0);
                if (sc == null) return null;
                // END_CHANGE: BUG-2026-0066-8
            }
            if (valType == null && sc.getValue() != null && sc.getValue().getType() != null) valType = sc.getValue().getType();
            exprCases.add(sc);
            armPcs.add(Integer.valueOf(tb.startPc));
        }
        BasicBlock db = cfg.getBlockAtPc(block.switchDefaultTarget);
        if (patternSwitch && isMatchExceptionDefault(db)) {
            // Exhaustive pattern switch: the synthetic `default -> throw MatchException` is implicit.
            armPcs.add(Integer.valueOf(db.startPc));
        } else {
            // START_CHANGE: BUG-2026-0066-20260610-9 - The default arm may also be a block-bodied
            // or throwing arm. An exhaustive enum-ordinal switch expression compiles to a plain
            // tableswitch over `e.ordinal()` whose synthetic default is
            // `throw new MatchException(null, null)`; emitting it verbatim keeps the int-keyed
            // switch exhaustive for javac and is runtime-identical.
            SwitchExpression.SwitchCase dsc = simpleSwitchExprArm(db, mergePc, null, armPcs, leakValues, 0);
            if (dsc == null) return null;
            if (valType == null && dsc.getValue() != null && dsc.getValue().getType() != null) {
                valType = dsc.getValue().getType();
            }
            exprCases.add(dsc);
            armPcs.add(Integer.valueOf(db.startPc));
            // END_CHANGE: BUG-2026-0066-9
        }
        selector = realSelector;

        // The merge return must be reached ONLY from the switch arms. If any other block flows into
        // it (e.g. a nested switch-expr whose merge is shared with an outer fall-through), consuming
        // the merge would drop the outer path's return. Bail in that case.
        for (BasicBlock b : cfg.getBlocks()) {
            if (b == block || armPcs.contains(Integer.valueOf(b.startPc)) || b.startPc == mergePc) continue;
            boolean reachesMerge = (b.branchTargetPc == mergePc) || (b.endPc == mergePc);
            if (reachesMerge) return null;
        }

        // START_CHANGE: BUG-2026-0066-20260610-10 - Commit by IN-PLACE SUBSTITUTION: the merge
        // block's first statement (its consumer: `return <v>`, `Type x = <v>`, `x = <v>`,
        // `f(<v>)`, `return f(<v>)`) holds ONE arm's leaked value; replace that value with the
        // reconstructed SwitchExpression and leave the merge block UNCONSUMED so the caller
        // continues the normal flow there (the old code always emitted `return switch(...)` and
        // swallowed the merge, which silently dropped post-switch code on store merges).
        // (a) Boolean target: an int-valued merge (ireturn/istore of iconst_0/iconst_1) whose
        //     consumer is boolean-typed means the arms are boolean literals.
        Statement consumer = mergeBlock.statements.get(0);
        boolean booleanTarget = false;
        if (consumer instanceof ReturnStatement) {
            Expression re = ((ReturnStatement) consumer).getExpression();
            booleanTarget = methodReturnsBoolean || re instanceof BooleanExpression
                || (re != null && re.getType() == PrimitiveType.BOOLEAN);
        } else if (consumer instanceof VariableDeclarationStatement) {
            booleanTarget = ((VariableDeclarationStatement) consumer).getType() == PrimitiveType.BOOLEAN;
        } else if (consumer instanceof ExpressionStatement
                && ((ExpressionStatement) consumer).getExpression() instanceof AssignmentExpression) {
            Expression lhs = ((AssignmentExpression) ((ExpressionStatement) consumer).getExpression()).getLeft();
            booleanTarget = lhs != null && lhs.getType() == PrimitiveType.BOOLEAN;
        }
        List<Expression> originalValues = new ArrayList<Expression>(leakValues);
        for (int ci = 0; ci < exprCases.size(); ci++) {
            if (exprCases.get(ci).getValue() != null) originalValues.add(exprCases.get(ci).getValue());
        }
        if (booleanTarget) {
            for (int ci = 0; ci < exprCases.size(); ci++) {
                Expression v = exprCases.get(ci).getValue();
                if (v instanceof IntegerConstantExpression) {
                    int iv = ((IntegerConstantExpression) v).getValue();
                    if (iv == 0 || iv == 1) {
                        exprCases.get(ci).setValue(new BooleanExpression(line, iv == 1));
                    }
                }
            }
            valType = PrimitiveType.BOOLEAN;
        }

        SwitchExpression se = new SwitchExpression(line,
            valType != null ? valType : PrimitiveType.INT, selector, exprCases);

        // (b) Substitute: identity-match one of the original leaked arm values inside the
        //     consumer. If the converter re-materialized the leaked constant (e.g. iconst_1
        //     became the literal `true` of a boolean ireturn), fall back to replacing a bare
        //     literal return wholesale — a literal cannot embed the leak in a sub-expression.
        boolean substituted = false;
        for (int vi = 0; vi < originalValues.size() && !substituted; vi++) {
            substituted = substituteSwitchValue(mergeBlock, originalValues.get(vi), se);
        }
        if (!substituted && consumer instanceof ReturnStatement
                && isLiteralExpr(((ReturnStatement) consumer).getExpression())) {
            mergeBlock.statements.set(0, new ReturnStatement(line, se));
            substituted = true;
        }
        if (!substituted) return null; // nothing mutated up to this point: safe fallback

        for (Integer pc : armPcs) visited.add(pc);
        // NOTE: the merge block is deliberately NOT marked visited - the caller continues there.

        List<Statement> out = new ArrayList<Statement>(block.statements);
        if (selFromStmt && !out.isEmpty()) out.remove(out.size() - 1);
        return new BlockStatement(line, out);
        // END_CHANGE: BUG-2026-0066-10
    }

    // START_CHANGE: BUG-2026-0066-20260610-11 - A plain, block-bodied, throwing or nested arm of
    // a non-pattern switch expression. Plain: the arm block leaves a value on the stack and flows
    // to the merge. Block-bodied: same, plus straight-line statements executed first
    // (`case X -> { stmts; yield v; }`). Throwing: the arm completes abruptly with athrow and
    // never reaches the merge (`default -> throw new MatchException(null, null);`). Nested: the
    // arm block is itself a switch whose arms all flow to the SAME outer merge
    // (`case X -> switch (sel) { ... }`). `consumedPcs` collects the entry PCs of inner arms so
    // the caller marks them visited; `leaks` collects every leaf arm value (one of them is the
    // expression leaked into the merge's consumer statement, used for identity substitution).
    private SwitchExpression.SwitchCase simpleSwitchExprArm(BasicBlock tb, int mergePc,
                                                            List<Expression> labels,
                                                            Set<Integer> consumedPcs,
                                                            List<Expression> leaks, int depth) {
        if (tb == null) return null;
        boolean gotoMerge = tb.isGoto() && tb.branchTargetPc == mergePc;
        boolean fallMerge = tb.endPc == mergePc;
        if (tb.stackTopExpression != null && (gotoMerge || fallMerge)) {
            SwitchExpression.SwitchCase sc = new SwitchExpression.SwitchCase(labels, tb.stackTopExpression);
            if (tb.statements != null && !tb.statements.isEmpty()) {
                sc.setBodyStatements(new ArrayList<Statement>(tb.statements));
            }
            leaks.add(tb.stackTopExpression);
            return sc;
        }
        if (tb.stackTopExpression == null && tb.statements != null && !tb.statements.isEmpty()
                && tb.statements.get(tb.statements.size() - 1) instanceof ThrowStatement) {
            SwitchExpression.SwitchCase sc = new SwitchExpression.SwitchCase(labels, null);
            sc.setBodyStatements(new ArrayList<Statement>(tb.statements));
            return sc;
        }
        // Nested switch-expression arm.
        if (depth < 3 && tb.type == BasicBlock.SWITCH && tb.switchKeys != null
                && tb.switchDefaultTarget >= 0 && tb.selectorExpression != null
                && (tb.statements == null || tb.statements.isEmpty())) {
            int line = tb.lineNumber > 0 ? tb.lineNumber : 0;
            List<List<Integer>> kg = new ArrayList<List<Integer>>();
            List<Integer> tp = new ArrayList<Integer>();
            for (int i = 0; i < tb.switchKeys.length; i++) {
                int t = (tb.switchTargets != null && i < tb.switchTargets.length) ? tb.switchTargets[i] : -1;
                int gi = tp.indexOf(Integer.valueOf(t));
                if (gi >= 0) {
                    kg.get(gi).add(Integer.valueOf(tb.switchKeys[i]));
                } else {
                    List<Integer> grp = new ArrayList<Integer>();
                    grp.add(Integer.valueOf(tb.switchKeys[i]));
                    kg.add(grp);
                    tp.add(Integer.valueOf(t));
                }
            }
            List<SwitchExpression.SwitchCase> innerCases = new ArrayList<SwitchExpression.SwitchCase>();
            Set<Integer> innerPcs = new HashSet<Integer>();
            List<Expression> innerLeaks = new ArrayList<Expression>();
            Type innerType = null;
            for (int g = 0; g < kg.size(); g++) {
                BasicBlock itb = cfg.getBlockAtPc(tp.get(g).intValue());
                List<Expression> il = new ArrayList<Expression>();
                for (int k = 0; k < kg.get(g).size(); k++) {
                    il.add(IntegerConstantExpression.valueOf(line, kg.get(g).get(k).intValue()));
                }
                SwitchExpression.SwitchCase isc = simpleSwitchExprArm(itb, mergePc, il, innerPcs, innerLeaks, depth + 1);
                if (isc == null) return null;
                if (innerType == null && isc.getValue() != null && isc.getValue().getType() != null) {
                    innerType = isc.getValue().getType();
                }
                innerCases.add(isc);
                innerPcs.add(Integer.valueOf(itb.startPc));
            }
            BasicBlock idb = cfg.getBlockAtPc(tb.switchDefaultTarget);
            SwitchExpression.SwitchCase idc = simpleSwitchExprArm(idb, mergePc, null, innerPcs, innerLeaks, depth + 1);
            if (idc == null) return null;
            if (innerType == null && idc.getValue() != null && idc.getValue().getType() != null) {
                innerType = idc.getValue().getType();
            }
            innerCases.add(idc);
            innerPcs.add(Integer.valueOf(idb.startPc));
            consumedPcs.addAll(innerPcs);
            leaks.addAll(innerLeaks);
            SwitchExpression inner = new SwitchExpression(line,
                innerType != null ? innerType : PrimitiveType.INT, tb.selectorExpression, innerCases);
            return new SwitchExpression.SwitchCase(labels, inner);
        }
        return null;
    }

    // In-place substitution of the reconstructed switch expression for the leaked arm value
    // (identity match) inside the merge block's consumer statement. Returns false (with NO
    // mutation) when the consumer shape is not recognized.
    private boolean substituteSwitchValue(BasicBlock mergeBlock, Expression target, Expression repl) {
        Statement consumer = mergeBlock.statements.get(0);
        if (consumer instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) consumer;
            if (rs.getExpression() == target) {
                mergeBlock.statements.set(0, new ReturnStatement(rs.getLineNumber(), repl));
                return true;
            }
            return substituteInCallArgs(rs.getExpression(), target, repl);
        }
        if (consumer instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) consumer;
            if (vds.getInitializer() == target) {
                VariableDeclarationStatement nv = new VariableDeclarationStatement(
                    vds.getLineNumber(), vds.getType(), vds.getName(), repl,
                    vds.isFinal(), vds.isVar());
                nv.setGenericSignature(vds.getGenericSignature());
                mergeBlock.statements.set(0, nv);
                return true;
            }
            return substituteInCallArgs(vds.getInitializer(), target, repl);
        }
        if (consumer instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) consumer).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                if (ae.getRight() == target) {
                    mergeBlock.statements.set(0, new ExpressionStatement(
                        new AssignmentExpression(ae.getLineNumber(), ae.getType(),
                            ae.getLeft(), ae.getOperator(), repl)));
                    return true;
                }
                return substituteInCallArgs(ae.getRight(), target, repl);
            }
            return substituteInCallArgs(e, target, repl);
        }
        return false;
    }

    // Replace `target` (identity) with `repl` inside a call's argument list, recursing through
    // nested call arguments. Argument lists are mutable in place.
    private boolean substituteInCallArgs(Expression e, Expression target, Expression repl) {
        List<Expression> args = null;
        if (e instanceof MethodInvocationExpression) {
            args = ((MethodInvocationExpression) e).getArguments();
        } else if (e instanceof StaticMethodInvocationExpression) {
            args = ((StaticMethodInvocationExpression) e).getArguments();
        } else if (e instanceof NewExpression) {
            args = ((NewExpression) e).getArguments();
        }
        if (args == null) return false;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) == target) {
                args.set(i, repl);
                return true;
            }
            if (substituteInCallArgs(args.get(i), target, repl)) return true;
        }
        return false;
    }

    private boolean isLiteralExpr(Expression e) {
        return e instanceof IntegerConstantExpression || e instanceof BooleanExpression
            || e instanceof StringConstantExpression || e instanceof LongConstantExpression
            || e instanceof DoubleConstantExpression || e instanceof FloatConstantExpression
            || e instanceof NullExpression;
    }
    // END_CHANGE: BUG-2026-0066-11

    private Expression switchArmValue(BasicBlock tb, int mergePc) {
        if (tb == null || tb.stackTopExpression == null) return null;
        if (tb.statements != null && !tb.statements.isEmpty()) return null;
        boolean gotoMerge = tb.isGoto() && tb.branchTargetPc == mergePc;
        boolean fallMerge = tb.endPc == mergePc;
        return (gotoMerge || fallMerge) ? tb.stackTopExpression : null;
    }
    // END_CHANGE: BUG-2026-0066-3

    // START_CHANGE: BUG-2026-0067-20260608-1 - A type-pattern switch arm: exactly a binding cast
    // (`Type b = (Type) sel;`) plus a value on the stack, flowing to the switch merge.
    private SwitchExpression.SwitchCase patternArm(BasicBlock tb, int mergePc) {
        if (tb == null || tb.stackTopExpression == null) return null;
        if (tb.statements == null || tb.statements.size() != 1) return null;
        Statement s0 = tb.statements.get(0);
        Type ptype = null; String pbind = null;
        if (s0 instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s0;
            if (vds.hasInitializer() && vds.getInitializer() instanceof CastExpression) {
                ptype = vds.getType(); pbind = vds.getName();
            }
        } else if (s0 instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s0).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                if (ae.getLeft() instanceof LocalVariableExpression && ae.getRight() instanceof CastExpression) {
                    pbind = ((LocalVariableExpression) ae.getLeft()).getName();
                    ptype = ((CastExpression) ae.getRight()).getType();
                }
            }
        }
        if (ptype == null || pbind == null) return null;
        boolean gotoMerge = tb.isGoto() && tb.branchTargetPc == mergePc;
        boolean fallMerge = tb.endPc == mergePc;
        if (!(gotoMerge || fallMerge)) return null;
        SwitchExpression.SwitchCase sc = new SwitchExpression.SwitchCase(null, tb.stackTopExpression);
        sc.setPatternType(ptype);
        sc.setPatternBinding(pbind);
        return sc;
    }

    // START_CHANGE: BUG-2026-0067-20260610-55 - Unnamed type-pattern arm (`case Integer _ ->`):
    // the binding is dead, so javac emits NO cast-bind statement — the arm block is value-only
    // (load constant/expression, goto merge). The pattern type comes from the typeSwitch
    // bootstrap label at the arm's switch key; the `_` binding is the CORRECT reconstruction
    // because the class file cannot carry an unnamed binding in the LVT (Java 21+ output).
    private SwitchExpression.SwitchCase unnamedPatternArm(BasicBlock tb, int mergePc,
                                                          StaticMethodInvocationExpression typeSwitchCall,
                                                          List<Integer> keys) {
        if (tb == null || tb.stackTopExpression == null) return null;
        if (tb.statements != null && !tb.statements.isEmpty()) return null;
        boolean gotoMerge = tb.isGoto() && tb.branchTargetPc == mergePc;
        boolean fallMerge = tb.endPc == mergePc;
        if (!(gotoMerge || fallMerge)) return null;
        if (patternLabelSource == null || typeSwitchCall == null) return null;
        // One key per arm: the case-model carries a single pattern type. (A multi-label unnamed
        // arm `case Integer _, String _ ->` is left to the safe statement fallback.)
        if (keys == null || keys.size() != 1) return null;
        int key = keys.get(0).intValue();
        if (key < 0) return null; // -1 is `case null`, handled by the caller
        List<String> labels = patternLabelSource.labelsFor(
            typeSwitchCall.getMethodName() + "_" + typeSwitchCall.getLineNumber());
        if (labels == null || key >= labels.size()) return null;
        String label = labels.get(key);
        // Only CONSTANT_Class bootstrap labels name a type pattern; constant labels (quoted
        // strings, enum names, integers) are real `case` constants, not type patterns. Array
        // classes (`[Ljava/lang/String;`) would need descriptor parsing — leave to fallback.
        if (label == null || label.length() == 0) return null;
        char c0 = label.charAt(0);
        if (c0 == '"' || c0 == '-' || c0 == '[' || (c0 >= '0' && c0 <= '9')) return null;
        SwitchExpression.SwitchCase sc = new SwitchExpression.SwitchCase(null, tb.stackTopExpression);
        sc.setPatternType(new ObjectType(label));
        sc.setPatternBinding("_");
        return sc;
    }
    // END_CHANGE: BUG-2026-0067-55

    // A guarded pattern arm: `Type b = (Type) sel;` then a conditional whose two successors are a
    // value (-> merge) and a restart (set the typeSwitch index + goto the loop header). Reconstructs
    // `case Type b when <guard> -> <value>`.
    private SwitchExpression.SwitchCase guardedPatternArm(BasicBlock tb, int mergePc,
                                                          int switchStartPc, Expression restartVar,
                                                          Set<Integer> consumed) {
        if (tb == null || !tb.isConditional() || tb.condition == null) return null;
        if (tb.statements == null || tb.statements.size() != 1) return null;
        Type ptype = null; String pbind = null;
        Statement s0 = tb.statements.get(0);
        if (s0 instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s0;
            if (vds.hasInitializer() && vds.getInitializer() instanceof CastExpression) {
                ptype = vds.getType(); pbind = vds.getName();
            }
        }
        if (ptype == null || pbind == null) return null;
        BasicBlock ts = tb.trueSuccessor, fs = tb.falseSuccessor;
        if (ts == null || fs == null) return null;

        BasicBlock valueB = null;
        boolean valueIsFallThrough = false;
        if (switchArmValue(ts, mergePc) != null && isRestart(fs, switchStartPc, restartVar)) {
            valueB = ts; valueIsFallThrough = false; // value is the branch target
        } else if (switchArmValue(fs, mergePc) != null && isRestart(ts, switchStartPc, restartVar)) {
            valueB = fs; valueIsFallThrough = true;  // value is the fall-through
        } else {
            return null;
        }
        // The displayed condition is true for the fall-through successor; orient the guard so it is
        // true on the value path.
        Expression guard = valueIsFallThrough ? tb.condition : negateCondition(tb.condition, tb.lineNumber);
        guard = simplifyGuard(guard);
        SwitchExpression.SwitchCase sc = new SwitchExpression.SwitchCase(null, valueB.stackTopExpression);
        sc.setPatternType(ptype);
        sc.setPatternBinding(pbind);
        sc.setGuard(guard);
        consumed.add(Integer.valueOf(ts.startPc));
        consumed.add(Integer.valueOf(fs.startPc));
        return sc;
    }

    // `boolExpr != 0` -> `boolExpr`; `boolExpr == 0` -> `!boolExpr` (the guard came from an `ifeq`/`ifne`
    // over a boolean-valued expression, which would otherwise render as an illegal `boolean != int`).
    private Expression simplifyGuard(Expression g) {
        if (g instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) g;
            if (isZeroConst(b.getRight()) && isBooleanValued(b.getLeft())) {
                if ("!=".equals(b.getOperator())) return b.getLeft();
                if ("==".equals(b.getOperator())) {
                    return new UnaryOperatorExpression(b.getLineNumber(), PrimitiveType.BOOLEAN, "!", b.getLeft(), true);
                }
            }
        }
        return g;
    }

    private boolean isZeroConst(Expression e) {
        return e instanceof IntegerConstantExpression && ((IntegerConstantExpression) e).getValue() == 0;
    }

    private boolean isBooleanValued(Expression e) {
        if (e instanceof MethodInvocationExpression) {
            String d = ((MethodInvocationExpression) e).getDescriptor();
            return d != null && d.endsWith(")Z");
        }
        if (e instanceof StaticMethodInvocationExpression) {
            String d = ((StaticMethodInvocationExpression) e).getDescriptor();
            return d != null && d.endsWith(")Z");
        }
        return e != null && e.getType() == PrimitiveType.BOOLEAN;
    }

    private boolean isRestart(BasicBlock b, int switchStartPc, Expression restartVar) {
        if (b == null || !b.isGoto() || b.branchTargetPc != switchStartPc) return false;
        if (!(restartVar instanceof LocalVariableExpression) || b.statements == null) return false;
        String rname = ((LocalVariableExpression) restartVar).getName();
        for (Statement s : b.statements) {
            if (s instanceof ExpressionStatement) {
                Expression e = ((ExpressionStatement) s).getExpression();
                if (e instanceof AssignmentExpression
                        && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression
                        && rname.equals(((LocalVariableExpression) ((AssignmentExpression) e).getLeft()).getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isMatchExceptionDefault(BasicBlock db) {
        if (db == null || db.statements == null) return false;
        for (Statement s : db.statements) {
            Expression e = null;
            if (s instanceof ThrowStatement) e = ((ThrowStatement) s).getExpression();
            if (e instanceof NewExpression) {
                String tn = ((NewExpression) e).getInternalTypeName();
                if (tn != null && tn.endsWith("MatchException")) return true;
            }
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0067-1

    // START_CHANGE: BUG-2026-0085-20260610-11 - regionStopPc: the build bound active when the
    // switch is reached; goto blocks at or beyond it belong to an enclosing region, not to this
    // switch's arms, and must not vote for the merge point.
    private int findSwitchMergePoint(BasicBlock switchBlock, int regionStopPc) {
    // END_CHANGE: BUG-2026-0085-11
        // Collect all switch target PCs (case targets only, not default)
        Set<Integer> casePcs = new HashSet<Integer>();
        // START_CHANGE: BUG-2026-0085-20260610-7 - The merge can never precede an arm's entry:
        // arm regions are laid out in PC order and the merge follows the last one. Without this
        // lower bound a loop back-edge goto INSIDE an arm (e.g. a while loop in one case body)
        // could win the goto count and yield a "merge" in the middle of an arm, corrupting every
        // bound derived from it (AttributeParser.parseTypeAnnotation lost its whole tail).
        int maxTargetPc = switchBlock.switchDefaultTarget;
        // END_CHANGE: BUG-2026-0085-7
        if (switchBlock.switchTargets != null) {
            for (int t : switchBlock.switchTargets) {
                casePcs.add(t);
                // START_CHANGE: BUG-2026-0085-20260610-8 - Track the highest arm entry PC.
                if (t > maxTargetPc) maxTargetPc = t;
                // END_CHANGE: BUG-2026-0085-8
            }
        }

        // Look for goto targets from blocks reachable from case bodies.
        // Count ALL goto targets including the default target - the most common
        // goto destination from case bodies is the merge point.
        Map<Integer, Integer> gotoCounts = new HashMap<Integer, Integer>();
        // START_CHANGE: BUG-2026-0066-20260610-12 - Two passes: first collect the candidate
        // merge targets, then count, skipping gotos that originate at/after a NEARER candidate
        // (they belong to that candidate's downstream region, not to this switch's arms). A
        // switch whose default region is a second switch (javac's string-switch-expression
        // lowering: hash lookupswitch + index dispatch tableswitch) had its merge stolen by the
        // dispatch arms' gotos (4 gotos to the ireturn beat 3 gotos to the dispatch entry),
        // nesting the dispatch inside the first hash case and dropping the method tail.
        Set<Integer> candidatePcs = new HashSet<Integer>();
        for (BasicBlock b : cfg.getBlocks()) {
            if (regionStopPc >= 0 && regionStopPc > switchBlock.startPc
                    && b.startPc >= regionStopPc) continue;
            if (b.startPc > switchBlock.startPc && b.isGoto() && b.branchTargetPc > switchBlock.startPc) {
                if ((b.branchTargetPc == switchBlock.switchDefaultTarget
                        || !casePcs.contains(b.branchTargetPc))
                        && b.branchTargetPc >= maxTargetPc) {
                    candidatePcs.add(Integer.valueOf(b.branchTargetPc));
                }
            }
        }
        // END_CHANGE: BUG-2026-0066-12
        for (BasicBlock b : cfg.getBlocks()) {
            // START_CHANGE: BUG-2026-0085-20260610-12 - Ignore gotos from outside the region.
            // Only applicable to a forward bound: inside a loop body the bound is the header PC,
            // which lies BEFORE the switch.
            if (regionStopPc >= 0 && regionStopPc > switchBlock.startPc
                    && b.startPc >= regionStopPc) continue;
            // END_CHANGE: BUG-2026-0085-12
            if (b.startPc > switchBlock.startPc && b.isGoto() && b.branchTargetPc > switchBlock.startPc) {
                // Only exclude gotos back to case entry points (not the default target)
                // START_CHANGE: BUG-2026-0085-20260610-9 - and require the candidate to sit at or
                // after the last arm entry (see change -7 above). The default target stays a
                // candidate even when it is ALSO a case target: a tableswitch maps its hole keys
                // to the default entry, and when the source default is empty that entry IS the
                // merge all the break gotos point at.
                if ((b.branchTargetPc == switchBlock.switchDefaultTarget
                        || !casePcs.contains(b.branchTargetPc))
                        && b.branchTargetPc >= maxTargetPc) {
                // END_CHANGE: BUG-2026-0085-9
                    // START_CHANGE: BUG-2026-0066-20260610-13 - Skip gotos downstream of a
                    // nearer candidate (see change -12 above).
                    boolean downstreamOfNearerCandidate = false;
                    for (Iterator<Integer> ci = candidatePcs.iterator(); ci.hasNext();) {
                        int c = ci.next().intValue();
                        if (c < b.branchTargetPc && c <= b.startPc) {
                            downstreamOfNearerCandidate = true;
                            break;
                        }
                    }
                    if (downstreamOfNearerCandidate) continue;
                    // END_CHANGE: BUG-2026-0066-13
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

    // START_CHANGE: BUG-2026-0085-20260610-5 - Append the switch `break` the structured-pattern
    // machinery swallowed: when the case region's physically last block is a goto to the switch
    // merge PC, every path reaching the region end exits the switch. Only append when the built
    // body can still complete normally — otherwise the break would be duplicated or unreachable
    // (an unreachable statement is a javac error).
    private void appendSwitchBreak(List<Statement> caseStmts, int targetPc, int boundPc,
                                   int switchMergePc) {
        if (switchMergePc < 0 || caseStmts.isEmpty()) return;
        if (completesAbruptly(caseStmts.get(caseStmts.size() - 1))) return;
        int regionEnd = boundPc >= 0 ? boundPc : switchMergePc;
        BasicBlock endBlock = null;
        for (BasicBlock b : cfg.getBlocks()) {
            if (b.startPc >= targetPc && b.startPc < regionEnd
                    && (endBlock == null || b.startPc > endBlock.startPc)) {
                endBlock = b;
            }
        }
        if (endBlock != null && endBlock.isGoto() && endBlock.branchTargetPc == switchMergePc) {
            int line = endBlock.lineNumber > 0 ? endBlock.lineNumber : 0;
            caseStmts.add(new BreakStatement(line));
        }
    }

    /** Conservative: true only when the statement provably completes abruptly on every path. */
    private boolean completesAbruptly(Statement s) {
        if (s instanceof ReturnStatement || s instanceof ThrowStatement
                || s instanceof BreakStatement || s instanceof ContinueStatement) {
            return true;
        }
        if (s instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) s).getStatements();
            return !stmts.isEmpty() && completesAbruptly(stmts.get(stmts.size() - 1));
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ie = (IfElseStatement) s;
            return completesAbruptly(ie.getThenBody()) && completesAbruptly(ie.getElseBody());
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0085-5

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
    // START_CHANGE: BUG-2026-0032-20260325-3 - Fix infinite recursion in ternary detection
    // START_CHANGE: BUG-2026-0057-20260608-1 - canFormTernary is a PROBE: it must not mutate the
    // caller's emission `visited` set. Previously it added every block it inspected to `visited`,
    // so probing a sequential `if (a) return X; if (b) return Y; ...` cascade (which is NOT a
    // ternary) permanently marked the 2nd/3rd condition blocks as "emitted"; the real continuation
    // then saw them visited and bailed, dropping every branch after the first (silent truncation +
    // "missing return"). The probe now uses its OWN cycle-guard set and only READS `visited`.
    private boolean canFormTernary(BasicBlock condBlock, Set<Integer> visited) {
        return canFormTernary(condBlock, visited, new HashSet<Integer>());
    }

    private boolean canFormTernary(BasicBlock condBlock, Set<Integer> visited, Set<Integer> probing) {
        // Already emitted elsewhere -> not a fresh ternary branch.
        if (visited.contains(condBlock.startPc)) return false;
        // Cycle within the probe (loop, not a ternary).
        if (!probing.add(condBlock.startPc)) return false;

        // START_CHANGE: BUG-2026-0095-20260610-4 - A conditional block that carries statements
        // cannot be folded into a ternary EXPRESSION: buildTernaryExpression emits only the
        // condition and the two arm values, so the statements would be silently dropped
        // (C_RecordPattern.isDiagonal lost the `var2 = var5;` component store this way once the
        // unsound && merge was refused). Let it be built as a nested if instead.
        if (condBlock.statements != null && !condBlock.statements.isEmpty()) return false;
        // END_CHANGE: BUG-2026-0095-4

        BasicBlock tTrue = condBlock.falseSuccessor;  // fall-through = true
        BasicBlock tFalse = condBlock.trueSuccessor;   // branch = false
        if (tTrue == null || tFalse == null) return false;

        // A ternary branch must be a "value producer": has a stackTopExpression and no statements
        boolean trueOk = tTrue.stackTopExpression != null &&
            (tTrue.statements == null || tTrue.statements.isEmpty()) &&
            (tTrue.isGoto() || tTrue.type == BasicBlock.FALL_THROUGH);
        boolean falseOk = tFalse.stackTopExpression != null &&
            (tFalse.statements == null || tFalse.statements.isEmpty());

        // Nested ternary: a branch is itself a conditional that forms a ternary
        // Only recurse on FORWARD conditionals (target PC > current PC) to prevent loops
        if (!trueOk && tTrue.isConditional()
            && tTrue.startPc > condBlock.startPc
            && !visited.contains(tTrue.startPc) && !probing.contains(tTrue.startPc)) {
            trueOk = canFormTernary(tTrue, visited, probing);
        }
        if (!falseOk && tFalse.isConditional()
            && tFalse.startPc > condBlock.startPc
            && !visited.contains(tFalse.startPc) && !probing.contains(tFalse.startPc)) {
            falseOk = canFormTernary(tFalse, visited, probing);
        }

        return trueOk && falseOk;
    }
    // END_CHANGE: BUG-2026-0057-1
    // END_CHANGE: BUG-2026-0032-3

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
    // START_CHANGE: BUG-2026-0083-20260610-2 - Generalized recursive consumer rewriter. The merge
    // block's operand stack is seeded with the SAME Expression objects as the branch
    // stackTopExpressions (BUG-2026-0051 exit-stack seeding), so the statement that consumes the
    // merged value embeds one arm value by object identity — possibly nested inside an
    // invocation/constructor argument, a cast, a binary operand, a declaration initializer, an
    // assignment RHS, a return value, etc. Find that reference and substitute the full
    // TernaryExpression in place. Previously only `i == 0` ExpressionStatement +
    // MethodInvocationExpression was handled; every other consumer shape copied the merge
    // statements verbatim with ONE arm's value, silently deleting the condition and the other
    // branch (e.g. C_FlexibleCtor$Sub: `long abs = stamp < 0 ? -stamp : stamp` became
    // `long var4 = -arg1`).
    private void replaceTernaryInMergeStatements(List<Statement> output,
            List<Statement> mergeStmts, Expression ternary,
            Expression trueValue, Expression falseValue, Expression receiver) {
        List<Statement> result = new ArrayList<Statement>();
        substituteTernaryIntoStatements(result, mergeStmts, ternary, trueValue, falseValue, receiver);
        // Earlier ternaries whose value rode the operand stack through statement-less merge
        // blocks (chained ternary arguments) are also consumed here: replace their arm-value
        // references in the rewritten statements.
        applyPendingStackTernaries(result);
        output.addAll(result);
    }

    /** Apply (and clear) pending stack-carried ternary substitutions to the statements. */
    private void applyPendingStackTernaries(List<Statement> stmts) {
        for (int p = pendingStackTernaries.size() - 1; p >= 0; p--) {
            Expression[] pending = pendingStackTernaries.get(p);
            for (int i = 0; i < stmts.size(); i++) {
                Statement replaced = substituteTernaryInStatement(stmts.get(i),
                    pending[2], pending[0], pending[1], true);
                if (replaced != null) {
                    stmts.set(i, replaced);
                    pendingStackTernaries.remove(p);
                    break;
                }
            }
        }
    }

    private void substituteTernaryIntoStatements(List<Statement> output,
            List<Statement> mergeStmts, Expression ternary,
            Expression trueValue, Expression falseValue, Expression receiver) {
        // Pass 1 (strict identity): the consumer references the seeded arm value object.
        for (int i = 0; i < mergeStmts.size(); i++) {
            Statement replaced = substituteTernaryInStatement(mergeStmts.get(i), ternary,
                trueValue, falseValue, true);
            if (replaced != null) {
                for (int j = 0; j < mergeStmts.size(); j++) {
                    output.add(j == i ? replaced : mergeStmts.get(j));
                }
                return;
            }
        }
        // Pass 2 (loose): constant re-decoding can break identity (e.g. iconst arms re-created
        // during the merge block decode). Retry the first statement with value-equality matching.
        if (!mergeStmts.isEmpty()) {
            Statement replaced = substituteTernaryInStatement(mergeStmts.get(0), ternary,
                trueValue, falseValue, false);
            if (replaced != null) {
                output.add(replaced);
                for (int j = 1; j < mergeStmts.size(); j++) {
                    output.add(mergeStmts.get(j));
                }
                return;
            }
        }
        // Legacy fallbacks — preserve pre-existing behaviour when no arm-value reference is found.
        Statement first = mergeStmts.isEmpty() ? null : mergeStmts.get(0);
        if (mergeStmts.size() == 1 && first instanceof ReturnStatement) {
            // BUG-2026-0025 behaviour: a lone opaque return consumes the merged value directly
            // (e.g. the decoder rebuilt the returned constant as a different object).
            output.add(new ReturnStatement(first.getLineNumber(), ternary));
            return;
        }
        if (first instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) first).getExpression();
            if (expr instanceof MethodInvocationExpression) {
                // BUG-2026-0015 behaviour: force-replace the last invocation argument, overriding
                // the receiver with the condition block's leftover stack top when available.
                Expression replaced = replaceArgWithTernary(expr, ternary, trueValue, falseValue, receiver);
                if (replaced != null) {
                    output.add(new ExpressionStatement(replaced));
                    for (int j = 1; j < mergeStmts.size(); j++) {
                        output.add(mergeStmts.get(j));
                    }
                    return;
                }
            } else if (expr instanceof AssignmentExpression) {
                // Pre-0083 store-merge behaviour: force the assignment RHS to the ternary.
                AssignmentExpression ae = (AssignmentExpression) expr;
                output.add(new ExpressionStatement(new AssignmentExpression(
                    ae.getLineNumber(), ternary.getType(), ae.getLeft(), "=", ternary)));
                for (int j = 1; j < mergeStmts.size(); j++) {
                    output.add(mergeStmts.get(j));
                }
                return;
            }
        }
        // Last resort: keep the merge statements unchanged rather than dropping them.
        output.addAll(mergeStmts);
    }

    /**
     * Check whether {@code expr} is one of the ternary's arm values. With {@code strict} the
     * match is by object identity only; otherwise constant value-equality is also accepted.
     * Built (nested) TernaryExpression arms are unwrapped: the merge stack is seeded with the
     * LEAF arm value object, not with the synthesized inner ternary.
     */
    private boolean isTernaryArmValue(Expression expr, Expression trueValue, Expression falseValue,
            boolean strict) {
        if (expr == null) return false;
        if (matchesArm(expr, trueValue, strict) || matchesArm(expr, falseValue, strict)) {
            return true;
        }
        if (trueValue instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) trueValue;
            if (isTernaryArmValue(expr, te.getTrueExpression(), te.getFalseExpression(), strict)) {
                return true;
            }
        }
        if (falseValue instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) falseValue;
            if (isTernaryArmValue(expr, te.getTrueExpression(), te.getFalseExpression(), strict)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesArm(Expression expr, Expression value, boolean strict) {
        if (strict) {
            return expr == value;
        }
        return expressionMatchesValue(expr, value);
    }

    /**
     * Substitute the ternary for the arm-value reference inside a merge statement.
     * Returns the rewritten statement, or null if the statement does not reference an arm value.
     */
    private Statement substituteTernaryInStatement(Statement s, Expression ternary,
            Expression trueValue, Expression falseValue, boolean strict) {
        if (s instanceof ExpressionStatement) {
            Expression r = substituteTernaryInExpression(((ExpressionStatement) s).getExpression(),
                ternary, trueValue, falseValue, strict);
            return r != null ? new ExpressionStatement(r) : null;
        }
        if (s instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) s;
            Expression r = substituteTernaryInExpression(rs.getExpression(),
                ternary, trueValue, falseValue, strict);
            return r != null ? new ReturnStatement(rs.getLineNumber(), r) : null;
        }
        if (s instanceof ThrowStatement) {
            ThrowStatement ts = (ThrowStatement) s;
            Expression r = substituteTernaryInExpression(ts.getExpression(),
                ternary, trueValue, falseValue, strict);
            return r != null ? new ThrowStatement(ts.getLineNumber(), r) : null;
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            Expression r = substituteTernaryInExpression(vds.getInitializer(),
                ternary, trueValue, falseValue, strict);
            if (r != null) {
                VariableDeclarationStatement nv = new VariableDeclarationStatement(
                    vds.getLineNumber(), vds.getType(), vds.getName(), r,
                    vds.isFinal(), vds.isVar());
                nv.setGenericSignature(vds.getGenericSignature());
                return nv;
            }
            return null;
        }
        return null;
    }

    /**
     * Recursively locate the arm-value reference inside {@code expr} and substitute the ternary.
     * Returns a rebuilt expression (nodes along the matched path are copied; everything else is
     * shared), or null when {@code expr} does not reference an arm value. Exactly one
     * occurrence — the first found in stack-top-biased order (arguments last-to-first, RHS
     * before LHS) — is replaced.
     */
    private Expression substituteTernaryInExpression(Expression expr, Expression ternary,
            Expression trueValue, Expression falseValue, boolean strict) {
        if (expr == null) return null;
        if (isTernaryArmValue(expr, trueValue, falseValue, strict)) {
            return ternary;
        }
        if (expr instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) expr;
            Expression r = substituteTernaryInExpression(ae.getRight(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new AssignmentExpression(ae.getLineNumber(), ae.getType(), ae.getLeft(),
                    ae.getOperator(), r);
            }
            r = substituteTernaryInExpression(ae.getLeft(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new AssignmentExpression(ae.getLineNumber(), ae.getType(), r,
                    ae.getOperator(), ae.getRight());
            }
            return null;
        }
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            List<Expression> args = mie.getArguments();
            if (args != null) {
                for (int i = args.size() - 1; i >= 0; i--) {
                    Expression r = substituteTernaryInExpression(args.get(i), ternary, trueValue, falseValue, strict);
                    if (r != null) {
                        List<Expression> newArgs = new ArrayList<Expression>(args);
                        newArgs.set(i, r);
                        return new MethodInvocationExpression(mie.getLineNumber(), mie.getType(),
                            mie.getObject(), mie.getOwnerInternalName(), mie.getMethodName(),
                            mie.getDescriptor(), newArgs);
                    }
                }
            }
            Expression r = substituteTernaryInExpression(mie.getObject(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new MethodInvocationExpression(mie.getLineNumber(), mie.getType(), r,
                    mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), args);
            }
            return null;
        }
        if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            List<Expression> args = smie.getArguments();
            if (args != null) {
                for (int i = args.size() - 1; i >= 0; i--) {
                    Expression r = substituteTernaryInExpression(args.get(i), ternary, trueValue, falseValue, strict);
                    if (r != null) {
                        List<Expression> newArgs = new ArrayList<Expression>(args);
                        newArgs.set(i, r);
                        return new StaticMethodInvocationExpression(smie.getLineNumber(), smie.getType(),
                            smie.getOwnerInternalName(), smie.getMethodName(), smie.getDescriptor(), newArgs);
                    }
                }
            }
            return null;
        }
        if (expr instanceof NewExpression) {
            NewExpression ne = (NewExpression) expr;
            List<Expression> args = ne.getArguments();
            if (args != null) {
                for (int i = args.size() - 1; i >= 0; i--) {
                    Expression r = substituteTernaryInExpression(args.get(i), ternary, trueValue, falseValue, strict);
                    if (r != null) {
                        List<Expression> newArgs = new ArrayList<Expression>(args);
                        newArgs.set(i, r);
                        return new NewExpression(ne.getLineNumber(), ne.getType(),
                            ne.getInternalTypeName(), ne.getDescriptor(), newArgs);
                    }
                }
            }
            return null;
        }
        if (expr instanceof CastExpression) {
            CastExpression ce = (CastExpression) expr;
            Expression r = substituteTernaryInExpression(ce.getExpression(), ternary, trueValue, falseValue, strict);
            return r != null ? new CastExpression(ce.getLineNumber(), ce.getType(), r) : null;
        }
        if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression ue = (UnaryOperatorExpression) expr;
            Expression r = substituteTernaryInExpression(ue.getExpression(), ternary, trueValue, falseValue, strict);
            if (r == null) return null;
            // A ternary as a unary operand would render without the parentheses Java
            // requires (`-c ? x : y` re-parses as `(-c) ? x : y`). Distributing the pure
            // prefix operator into the arms is always evaluation-order-safe.
            if (r instanceof TernaryExpression && ue.isPrefix()
                && ("-".equals(ue.getOperator()) || "~".equals(ue.getOperator())
                    || "!".equals(ue.getOperator()) || "+".equals(ue.getOperator()))) {
                TernaryExpression t = (TernaryExpression) r;
                return new TernaryExpression(t.getLineNumber(), ue.getType(), t.getCondition(),
                    new UnaryOperatorExpression(ue.getLineNumber(), ue.getType(), ue.getOperator(),
                        t.getTrueExpression(), true),
                    new UnaryOperatorExpression(ue.getLineNumber(), ue.getType(), ue.getOperator(),
                        t.getFalseExpression(), true));
            }
            if (r instanceof TernaryExpression) {
                return null; // would mis-render; let the fallback tiers handle it
            }
            return new UnaryOperatorExpression(ue.getLineNumber(), ue.getType(),
                ue.getOperator(), r, ue.isPrefix());
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            Expression r = substituteTernaryInExpression(boe.getRight(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                if (r instanceof TernaryExpression) {
                    // `left op <ternary>` would render without the parentheses Java requires
                    // (ternary binds lowest: `a + c ? x : y` re-parses as `(a + c) ? x : y`,
                    // sometimes still compiling with WRONG semantics). Distribute the binary
                    // into the arms — `c ? left op x : left op y` — when that cannot change
                    // observable evaluation order: `left` must be a simple operand (its read
                    // is hoisted past the condition) and the condition side-effect-free.
                    TernaryExpression t = (TernaryExpression) r;
                    if (isSimpleOperand(boe.getLeft()) && isSideEffectFree(t.getCondition())) {
                        return new TernaryExpression(t.getLineNumber(), boe.getType(), t.getCondition(),
                            new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                                boe.getLeft(), boe.getOperator(), t.getTrueExpression()),
                            new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                                boe.getLeft(), boe.getOperator(), t.getFalseExpression()));
                    }
                    return null; // would mis-render; let the fallback tiers handle it
                }
                return new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                    boe.getLeft(), boe.getOperator(), r);
            }
            r = substituteTernaryInExpression(boe.getLeft(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                if (r instanceof TernaryExpression) {
                    // `<ternary> op right`: evaluation order (condition, arm, right) is
                    // preserved by distribution; only `right` gets duplicated, so it must
                    // be a simple operand.
                    TernaryExpression t = (TernaryExpression) r;
                    if (isSimpleOperand(boe.getRight())) {
                        return new TernaryExpression(t.getLineNumber(), boe.getType(), t.getCondition(),
                            new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                                t.getTrueExpression(), boe.getOperator(), boe.getRight()),
                            new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                                t.getFalseExpression(), boe.getOperator(), boe.getRight()));
                    }
                    return null; // would mis-render; let the fallback tiers handle it
                }
                return new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(),
                    r, boe.getOperator(), boe.getRight());
            }
            return null;
        }
        if (expr instanceof ArrayAccessExpression) {
            ArrayAccessExpression aae = (ArrayAccessExpression) expr;
            Expression r = substituteTernaryInExpression(aae.getIndex(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new ArrayAccessExpression(aae.getLineNumber(), aae.getType(), aae.getArray(), r);
            }
            r = substituteTernaryInExpression(aae.getArray(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new ArrayAccessExpression(aae.getLineNumber(), aae.getType(), r, aae.getIndex());
            }
            return null;
        }
        if (expr instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) expr;
            Expression r = substituteTernaryInExpression(fae.getObject(), ternary, trueValue, falseValue, strict);
            return r != null ? new FieldAccessExpression(fae.getLineNumber(), fae.getType(), r,
                fae.getOwnerInternalName(), fae.getName(), fae.getDescriptor()) : null;
        }
        if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            Expression r = substituteTernaryInExpression(te.getTrueExpression(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new TernaryExpression(te.getLineNumber(), te.getType(),
                    te.getCondition(), r, te.getFalseExpression());
            }
            r = substituteTernaryInExpression(te.getFalseExpression(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new TernaryExpression(te.getLineNumber(), te.getType(),
                    te.getCondition(), te.getTrueExpression(), r);
            }
            r = substituteTernaryInExpression(te.getCondition(), ternary, trueValue, falseValue, strict);
            if (r != null) {
                return new TernaryExpression(te.getLineNumber(), te.getType(),
                    r, te.getTrueExpression(), te.getFalseExpression());
            }
            return null;
        }
        if (expr instanceof NewArrayExpression) {
            NewArrayExpression nae = (NewArrayExpression) expr;
            List<Expression> dims = nae.getDimensionExpressions();
            if (dims != null) {
                for (int i = dims.size() - 1; i >= 0; i--) {
                    Expression r = substituteTernaryInExpression(dims.get(i), ternary, trueValue, falseValue, strict);
                    if (r != null) {
                        List<Expression> newDims = new ArrayList<Expression>(dims);
                        newDims.set(i, r);
                        NewArrayExpression rebuilt = new NewArrayExpression(nae.getLineNumber(),
                            nae.getType(), newDims);
                        if (nae.hasInitValues()) {
                            for (int v = 0; v < nae.getInitValues().size(); v++) {
                                rebuilt.addInitValue(nae.getInitValues().get(v));
                            }
                        }
                        return rebuilt;
                    }
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Simple operand: re-reading it after the ternary condition (instead of before) cannot be
     * observed — constants, local variable loads and `this`.
     */
    private boolean isSimpleOperand(Expression e) {
        return e instanceof IntegerConstantExpression || e instanceof LongConstantExpression
            || e instanceof FloatConstantExpression || e instanceof DoubleConstantExpression
            || e instanceof StringConstantExpression || e instanceof TextBlockExpression
            || e instanceof NullExpression || e instanceof BooleanExpression
            || e instanceof ClassExpression || e instanceof LocalVariableExpression
            || e instanceof ThisExpression;
    }

    /**
     * Conservative whitelist: true only when the expression provably has no side effects
     * (no invocations, allocations, assignments or increments anywhere in the tree).
     */
    private boolean isSideEffectFree(Expression e) {
        if (e == null) return true;
        if (isSimpleOperand(e)) return true;
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression u = (UnaryOperatorExpression) e;
            if ("++".equals(u.getOperator()) || "--".equals(u.getOperator())) return false;
            return isSideEffectFree(u.getExpression());
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression b = (BinaryOperatorExpression) e;
            return isSideEffectFree(b.getLeft()) && isSideEffectFree(b.getRight());
        }
        if (e instanceof CastExpression) {
            return isSideEffectFree(((CastExpression) e).getExpression());
        }
        if (e instanceof FieldAccessExpression) {
            return isSideEffectFree(((FieldAccessExpression) e).getObject());
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression aa = (ArrayAccessExpression) e;
            return isSideEffectFree(aa.getArray()) && isSideEffectFree(aa.getIndex());
        }
        if (e instanceof InstanceOfExpression) {
            return isSideEffectFree(((InstanceOfExpression) e).getExpression());
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e;
            return isSideEffectFree(t.getCondition()) && isSideEffectFree(t.getTrueExpression())
                && isSideEffectFree(t.getFalseExpression());
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0083-2

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

    // START_CHANGE: BUG-2026-0016-20260326-3 - Helper methods for assignment-in-condition merging
    private static boolean conditionUsesVariable(Expression expr, String varName) {
        if (expr instanceof LocalVariableExpression) {
            return varName.equals(((LocalVariableExpression) expr).getName());
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            return conditionUsesVariable(boe.getLeft(), varName) || conditionUsesVariable(boe.getRight(), varName);
        }
        if (expr instanceof UnaryOperatorExpression) {
            return conditionUsesVariable(((UnaryOperatorExpression) expr).getExpression(), varName);
        }
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if (mie.getObject() != null && conditionUsesVariable(mie.getObject(), varName)) return true;
            if (mie.getArguments() != null) {
                for (Expression arg : mie.getArguments()) {
                    if (conditionUsesVariable(arg, varName)) return true;
                }
            }
        }
        return false;
    }

    private static Expression replaceVariableInCondition(Expression expr, String varName, Expression replacement) {
        if (expr instanceof LocalVariableExpression) {
            if (varName.equals(((LocalVariableExpression) expr).getName())) {
                return replacement;
            }
            return expr;
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            Expression newLeft = replaceVariableInCondition(boe.getLeft(), varName, replacement);
            Expression newRight = replaceVariableInCondition(boe.getRight(), varName, replacement);
            if (newLeft != boe.getLeft() || newRight != boe.getRight()) {
                return new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(), newLeft, boe.getOperator(), newRight);
            }
            return expr;
        }
        if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            Expression newInner = replaceVariableInCondition(uoe.getExpression(), varName, replacement);
            if (newInner != uoe.getExpression()) {
                return new UnaryOperatorExpression(uoe.getLineNumber(), uoe.getType(), uoe.getOperator(), newInner, uoe.isPrefix());
            }
        }
        return expr;
    }
    // END_CHANGE: BUG-2026-0016-3
}
