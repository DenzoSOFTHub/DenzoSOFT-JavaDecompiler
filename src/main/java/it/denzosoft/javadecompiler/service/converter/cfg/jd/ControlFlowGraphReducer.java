/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-8 - Ported ControlFlowGraphReducer from JD-Core.
 * Decompiled with CFR 0.152.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
// BasicBlock is in same package
// ControlFlowGraph is in same package
// ByteCodeParser is in same package
// WatchDog is in same package
import java.util.List; import java.util.ArrayList;

public class ControlFlowGraphReducer {
    public static boolean reduce(ControlFlowGraph cfg) {
        BasicBlock start = cfg.getStart();
        BitSet jsrTargets = new BitSet();
        BitSet visited = new BitSet(cfg.getBasicBlocks().size());
        return ControlFlowGraphReducer.reduce(visited, start, jsrTargets);
    }

    public static boolean reduce(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        if (!basicBlock.matchType(1266696506) && !visited.get(basicBlock.getIndex())) {
            visited.set(basicBlock.getIndex());
            switch (basicBlock.getType()) {
                case 1: 
                case 4: 
                case 128: 
                case 1024: 
                case 2048: 
                case 4096: 
                case 65536: 
                case 131072: 
                case 0x10000000: {
                    return ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets);
                }
                case 32768: 
                case 262144: 
                case 524288: 
                case 0x100000: 
                case 0x200000: {
                    return ControlFlowGraphReducer.reduceConditionalBranch(visited, basicBlock, jsrTargets);
                }
                case 64: {
                    return ControlFlowGraphReducer.reduceSwitchDeclaration(visited, basicBlock, jsrTargets);
                }
                case 512: {
                    return ControlFlowGraphReducer.reduceTryDeclaration(visited, basicBlock, jsrTargets);
                }
                case 8192: {
                    return ControlFlowGraphReducer.reduceJsr(visited, basicBlock, jsrTargets);
                }
                case 0x400000: {
                    return ControlFlowGraphReducer.reduceLoop(visited, basicBlock, jsrTargets);
                }
            }
        }
        return true;
    }

    protected static boolean reduceConditionalBranch(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        while (ControlFlowGraphReducer.aggregateConditionalBranches(basicBlock)) {
        }
        assert (basicBlock.matchType(0x3C0000));
        if (ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets) & ControlFlowGraphReducer.reduce(visited, basicBlock.getBranch(), jsrTargets)) {
            return ControlFlowGraphReducer.reduceConditionalBranch(basicBlock);
        }
        return false;
    }

    protected static boolean reduceConditionalBranch(BasicBlock basicBlock) {
        BasicBlock nextNext;
        BasicBlock nextLast;
        BasicBlock next = basicBlock.getNext();
        BasicBlock branch = basicBlock.getBranch();
        WatchDog watchdog = new WatchDog();
        if (next == branch) {
            ControlFlowGraphReducer.createIf(basicBlock, BasicBlock.END, BasicBlock.END, branch);
            return true;
        }
        if (next.matchType(1266696506) && next.getPredecessors().size() <= 1) {
            ControlFlowGraphReducer.createIf(basicBlock, next, next, branch);
            return true;
        }
        if (next.matchType(876822205) && next.getPredecessors().size() == 1) {
            nextLast = next;
            nextNext = next.getNext();
            ControlFlowGraph cfg = next.getControlFlowGraph();
            int lineNumber = cfg.getLineNumber(basicBlock.getFromOffset());
            int maxOffset = branch.getFromOffset();
            if (maxOffset == 0 || next.getFromOffset() > branch.getFromOffset()) {
                maxOffset = Integer.MAX_VALUE;
            }
            while (nextLast != nextNext && nextNext.matchType(876822149) && nextNext.getPredecessors().size() == 1 && cfg.getLineNumber(nextNext.getFromOffset()) >= lineNumber && nextNext.getFromOffset() < maxOffset) {
                watchdog.check(nextNext, nextNext.getNext());
                nextLast = nextNext;
                nextNext = nextNext.getNext();
            }
            if (nextNext == branch) {
                ControlFlowGraphReducer.createIf(basicBlock, next, nextLast, branch);
                return true;
            }
            if (nextNext.matchType(1266696506) && nextNext.getFromOffset() < maxOffset) {
                ControlFlowGraphReducer.createIf(basicBlock, next, nextNext, branch);
                return true;
            }
            if (branch.matchType(1266696506)) {
                if (nextNext.getFromOffset() < maxOffset && nextNext.getPredecessors().size() == 1) {
                    ControlFlowGraphReducer.createIf(basicBlock, next, nextNext, branch);
                } else {
                    ControlFlowGraphReducer.createIfElse(131072, basicBlock, next, nextLast, branch, branch, nextNext);
                }
                return true;
            }
            if (branch.matchType(876822149) && branch.getPredecessors().size() == 1) {
                BasicBlock branchNext;
                BasicBlock branchLast = branch;
                watchdog.clear();
                for (branchNext = branch.getNext(); branchLast != branchNext && branchNext.matchType(876822149) && branchNext.getPredecessors().size() == 1 && cfg.getLineNumber(branchNext.getFromOffset()) >= lineNumber; branchNext = branchNext.getNext()) {
                    watchdog.check(branchNext, branchNext.getNext());
                    branchLast = branchNext;
                }
                if (nextNext == branchNext) {
                    if (nextLast.matchType(0x30000000)) {
                        ControlFlowGraphReducer.createIfElse(0x20000000, basicBlock, next, nextLast, branch, branchLast, nextNext);
                    } else {
                        ControlFlowGraphReducer.createIfElse(131072, basicBlock, next, nextLast, branch, branchLast, nextNext);
                    }
                } else if (nextNext.getFromOffset() < maxOffset && nextNext.getPredecessors().size() == 1) {
                    ControlFlowGraphReducer.createIf(basicBlock, next, nextNext, branch);
                } else {
                    ControlFlowGraphReducer.createIfElse(131072, basicBlock, next, nextLast, branch, branchLast.getNext(), nextNext);
                }
                return true;
            }
        }
        if (branch.matchType(876822205) && branch.getPredecessors().size() == 1) {
            BasicBlock branchNext;
            BasicBlock branchLast = branch;
            watchdog.clear();
            for (branchNext = branch.getNext(); branchLast != branchNext && branchNext.matchType(876822149) && branchNext.getPredecessors().size() == 1; branchNext = branchNext.getNext()) {
                watchdog.check(branchNext, branchNext.getNext());
                branchLast = branchNext;
            }
            if (branchNext == next) {
                basicBlock.inverseCondition();
                ControlFlowGraphReducer.createIf(basicBlock, branch, branchLast, next);
                return true;
            }
            if (branchNext.matchType(1266696506) && branchNext.getPredecessors().size() <= 1) {
                basicBlock.inverseCondition();
                ControlFlowGraphReducer.createIf(basicBlock, branch, branchNext, next);
                return true;
            }
        }
        if (next.matchType(56)) {
            next = ControlFlowGraphReducer.clone(basicBlock, next);
            ControlFlowGraphReducer.createIf(basicBlock, next, next, branch);
            return true;
        }
        if (next.matchType(876822149)) {
            nextLast = next;
            watchdog.clear();
            for (nextNext = next.getNext(); nextLast != nextNext && nextNext.matchType(876822149) && nextNext.getPredecessors().size() == 1; nextNext = nextNext.getNext()) {
                watchdog.check(nextNext, nextNext.getNext());
                nextLast = nextNext;
            }
            if (nextNext.matchType(56)) {
                ControlFlowGraphReducer.createIf(basicBlock, next, nextNext, branch);
                return true;
            }
        }
        return false;
    }

    protected static void createIf(BasicBlock basicBlock, BasicBlock sub, BasicBlock last, BasicBlock next) {
        BasicBlock condition = basicBlock.getControlFlowGraph().newBasicBlock(basicBlock);
        int toOffset = last.getToOffset();
        if (toOffset == 0) {
            toOffset = basicBlock.getToOffset();
        }
        last.setNext(BasicBlock.END);
        next.getPredecessors().remove(last);
        basicBlock.setType(65536);
        basicBlock.setToOffset(toOffset);
        basicBlock.setCondition(condition);
        basicBlock.setSub1(sub);
        basicBlock.setSub2(null);
        basicBlock.setNext(next);
    }

    protected static void createIfElse(int type, BasicBlock basicBlock, BasicBlock sub1, BasicBlock last1, BasicBlock sub2, BasicBlock last2, BasicBlock next) {
        BasicBlock condition = basicBlock.getControlFlowGraph().newBasicBlock(basicBlock);
        int toOffset = last2.getToOffset();
        if (toOffset == 0 && (toOffset = last1.getToOffset()) == 0) {
            toOffset = basicBlock.getToOffset();
        }
        last1.setNext(BasicBlock.END);
        next.getPredecessors().remove(last1);
        last2.setNext(BasicBlock.END);
        next.getPredecessors().remove(last2);
        next.getPredecessors().add(basicBlock);
        basicBlock.setType(type);
        basicBlock.setToOffset(toOffset);
        basicBlock.setCondition(condition);
        basicBlock.setSub1(sub1);
        basicBlock.setSub2(sub2);
        basicBlock.setNext(next);
    }

    protected static boolean aggregateConditionalBranches(BasicBlock basicBlock) {
        int lineNumber2;
        BasicBlock nextNext;
        boolean change = false;
        BasicBlock next = basicBlock.getNext();
        BasicBlock branch = basicBlock.getBranch();
        if (next.getType() == 0x10000000 && next.getPredecessors().size() == 1 && (nextNext = next.getNext()).matchType(294912)) {
            int stackDepthNextNext;
            int stackDepth;
            if (branch.matchType(0x10000004) && nextNext == branch.getNext() && branch.getPredecessors().size() == 1 && nextNext.getPredecessors().size() == 2 && (stackDepth = ByteCodeParser.evalStackDepth(basicBlock)) + 1 == -(stackDepthNextNext = ByteCodeParser.evalStackDepth(nextNext))) {
                ControlFlowGraphReducer.updateConditionTernaryOperator(basicBlock, nextNext);
                return true;
            }
            if (nextNext.getNext() == branch && ControlFlowGraphReducer.checkJdk118TernaryOperatorPattern(next, nextNext, 153)) {
                ControlFlowGraphReducer.convertConditionalBranchToGotoInTernaryOperator(basicBlock, next, nextNext);
                return true;
            }
            if (nextNext.getBranch() == branch && ControlFlowGraphReducer.checkJdk118TernaryOperatorPattern(next, nextNext, 154)) {
                ControlFlowGraphReducer.convertConditionalBranchToGotoInTernaryOperator(basicBlock, next, nextNext);
                return true;
            }
            if (nextNext.getPredecessors().size() == 1) {
                ControlFlowGraphReducer.convertGotoInTernaryOperatorToCondition(next, nextNext);
                return true;
            }
        }
        if (next.matchType(3964928)) {
            int lineNumber1 = basicBlock.getLastLineNumber();
            lineNumber2 = next.getFirstLineNumber();
            if (lineNumber2 - lineNumber1 <= 1) {
                change = ControlFlowGraphReducer.aggregateConditionalBranches(next);
                if (next.matchType(3964928) && next.getPredecessors().size() == 1) {
                    if (next.getNext() == branch) {
                        ControlFlowGraphReducer.updateConditionalBranches(basicBlock, ControlFlowGraphReducer.createLeftCondition(basicBlock), 524288, next);
                        return true;
                    }
                    if (next.getBranch() == branch) {
                        ControlFlowGraphReducer.updateConditionalBranches(basicBlock, ControlFlowGraphReducer.createLeftInverseCondition(basicBlock), 0x100000, next);
                        return true;
                    }
                    if (branch.matchType(3964928)) {
                        change = ControlFlowGraphReducer.aggregateConditionalBranches(branch);
                        if (branch.matchType(3964928)) {
                            if (next.getNext() == branch.getNext() && next.getBranch() == branch.getBranch()) {
                                ControlFlowGraphReducer.updateConditionTernaryOperator2(basicBlock);
                                return true;
                            }
                            if (next.getBranch() == branch.getNext() && next.getNext() == branch.getBranch()) {
                                ControlFlowGraphReducer.updateConditionTernaryOperator2(basicBlock);
                                branch.inverseCondition();
                                return true;
                            }
                        }
                    }
                }
            }
        }
        if (branch.matchType(3964928)) {
            int lineNumber1 = basicBlock.getLastLineNumber();
            lineNumber2 = branch.getFirstLineNumber();
            if (lineNumber2 - lineNumber1 <= 1) {
                change = ControlFlowGraphReducer.aggregateConditionalBranches(branch);
                if (branch.matchType(3964928) && branch.getPredecessors().size() == 1) {
                    if (branch.getBranch() == next) {
                        ControlFlowGraphReducer.updateConditionalBranches(basicBlock, ControlFlowGraphReducer.createLeftCondition(basicBlock), 0x100000, branch);
                        return true;
                    }
                    if (branch.getNext() == next) {
                        ControlFlowGraphReducer.updateConditionalBranches(basicBlock, ControlFlowGraphReducer.createLeftInverseCondition(basicBlock), 524288, branch);
                        return true;
                    }
                }
            }
        }
        if (basicBlock.getType() == 32768) {
            basicBlock.setType(262144);
            return true;
        }
        return change;
    }

    protected static BasicBlock createLeftCondition(BasicBlock basicBlock) {
        if (basicBlock.getType() == 32768) {
            return basicBlock.getControlFlowGraph().newBasicBlock(262144, basicBlock.getFromOffset(), basicBlock.getToOffset(), false);
        }
        BasicBlock left = basicBlock.getControlFlowGraph().newBasicBlock(basicBlock);
        left.inverseCondition();
        return left;
    }

    protected static BasicBlock createLeftInverseCondition(BasicBlock basicBlock) {
        if (basicBlock.getType() == 32768) {
            return basicBlock.getControlFlowGraph().newBasicBlock(262144, basicBlock.getFromOffset(), basicBlock.getToOffset());
        }
        return basicBlock.getControlFlowGraph().newBasicBlock(basicBlock);
    }

    protected static void updateConditionalBranches(BasicBlock basicBlock, BasicBlock leftBasicBlock, int operator, BasicBlock subBasicBlock) {
        basicBlock.setType(operator);
        basicBlock.setToOffset(subBasicBlock.getToOffset());
        basicBlock.setNext(subBasicBlock.getNext());
        basicBlock.setBranch(subBasicBlock.getBranch());
        basicBlock.setCondition(BasicBlock.END);
        basicBlock.setSub1(leftBasicBlock);
        basicBlock.setSub2(subBasicBlock);
        subBasicBlock.getNext().replace(subBasicBlock, basicBlock);
        subBasicBlock.getBranch().replace(subBasicBlock, basicBlock);
    }

    protected static void updateConditionTernaryOperator(BasicBlock basicBlock, BasicBlock nextNext) {
        int fromOffset = nextNext.getFromOffset();
        int toOffset = nextNext.getToOffset();
        BasicBlock next = nextNext.getNext();
        BasicBlock branch = nextNext.getBranch();
        if (basicBlock.getType() == 32768) {
            basicBlock.setType(262144);
        }
        if (nextNext.getType() == 262144 && !nextNext.mustInverseCondition()) {
            basicBlock.inverseCondition();
        }
        BasicBlock condition = nextNext;
        condition.setType(basicBlock.getType());
        condition.setFromOffset(basicBlock.getFromOffset());
        condition.setToOffset(basicBlock.getToOffset());
        condition.setNext(BasicBlock.END);
        condition.setBranch(BasicBlock.END);
        condition.setCondition(basicBlock.getCondition());
        condition.setSub1(basicBlock.getSub1());
        condition.setSub2(basicBlock.getSub2());
        condition.getPredecessors().clear();
        basicBlock.setType(0x200000);
        basicBlock.setFromOffset(fromOffset);
        basicBlock.setToOffset(toOffset);
        basicBlock.setCondition(condition);
        basicBlock.setSub1(basicBlock.getNext());
        basicBlock.setSub2(basicBlock.getBranch());
        basicBlock.setNext(next);
        basicBlock.setBranch(branch);
        basicBlock.getSub1().setNext(BasicBlock.END);
        basicBlock.getSub2().setNext(BasicBlock.END);
        next.replace(nextNext, basicBlock);
        branch.replace(nextNext, basicBlock);
        basicBlock.getSub1().getPredecessors().clear();
        basicBlock.getSub2().getPredecessors().clear();
    }

    protected static void updateConditionTernaryOperator2(BasicBlock basicBlock) {
        BasicBlock next = basicBlock.getNext();
        BasicBlock branch = basicBlock.getBranch();
        ControlFlowGraph cfg = basicBlock.getControlFlowGraph();
        BasicBlock condition = cfg.newBasicBlock(262144, basicBlock.getFromOffset(), basicBlock.getToOffset());
        basicBlock.setType(0x200000);
        basicBlock.setToOffset(basicBlock.getFromOffset());
        basicBlock.setCondition(condition);
        basicBlock.setSub1(next);
        basicBlock.setSub2(branch);
        basicBlock.setNext(next.getNext());
        basicBlock.setBranch(next.getBranch());
        next.getNext().replace(next, basicBlock);
        next.getBranch().replace(next, basicBlock);
        branch.getNext().replace(branch, basicBlock);
        branch.getBranch().replace(branch, basicBlock);
        next.getPredecessors().clear();
        branch.getPredecessors().clear();
    }

    protected static void convertGotoInTernaryOperatorToCondition(BasicBlock basicBlock, BasicBlock next) {
        basicBlock.setType(262144);
        basicBlock.setNext(next.getNext());
        basicBlock.setBranch(next.getBranch());
        next.getNext().replace(next, basicBlock);
        next.getBranch().replace(next, basicBlock);
        next.setType(0);
    }

    protected static void convertConditionalBranchToGotoInTernaryOperator(BasicBlock basicBlock, BasicBlock next, BasicBlock nextNext) {
        basicBlock.setType(0x10000000);
        basicBlock.setNext(nextNext);
        basicBlock.getBranch().getPredecessors().remove(basicBlock);
        basicBlock.setBranch(BasicBlock.END);
        basicBlock.setInverseCondition(false);
        nextNext.replace(next, basicBlock);
        next.setType(0);
    }

    protected static boolean checkJdk118TernaryOperatorPattern(BasicBlock next, BasicBlock nextNext, int ifByteCode) {
        if (nextNext.getToOffset() - nextNext.getFromOffset() == 3) {
            byte[] code = ((CodeAttribute) next.getControlFlowGraph().getMethod().findAttribute("Code")).getCode();
            int nextFromOffset = next.getFromOffset();
            int nextNextFromOffset = nextNext.getFromOffset();
            return code[nextFromOffset] == 3 && ((code[nextFromOffset + 1] & 0xFF) == 167 || (code[nextFromOffset + 1] & 0xFF) == 200) && (code[nextNextFromOffset] & 0xFF) == ifByteCode && nextNextFromOffset + 3 == nextNext.getToOffset();
        }
        return false;
    }

    protected static boolean reduceSwitchDeclaration(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        SwitchCase defaultSC = null;
        SwitchCase lastSC = null;
        int maxOffset = -1;
        for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
            if (maxOffset < switchCase.getOffset()) {
                maxOffset = switchCase.getOffset();
            }
            if (switchCase.isDefaultCase()) {
                defaultSC = switchCase;
                continue;
            }
            lastSC = switchCase;
        }
        if (lastSC == null) {
            lastSC = defaultSC;
        }
        BasicBlock lastSwitchCaseBasicBlock = null;
        BitSet v = new BitSet();
        HashSet<BasicBlock> ends = new HashSet<BasicBlock>();
        for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
            BasicBlock bb = switchCase.getBasicBlock();
            if (switchCase.getOffset() == maxOffset) {
                lastSwitchCaseBasicBlock = bb;
                continue;
            }
            ControlFlowGraphReducer.visit(v, bb, maxOffset, ends);
        }
        BasicBlock end = BasicBlock.END;
        for (BasicBlock bb : ends) {
            if (bb.matchType(1266696506) || end != BasicBlock.END && end.getFromOffset() >= bb.getFromOffset()) continue;
            end = bb;
        }
        if (end == BasicBlock.END) {
            if (lastSC.getBasicBlock() == lastSwitchCaseBasicBlock && ControlFlowGraphReducer.searchLoopStart(basicBlock, maxOffset)) {
                ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(new BitSet(), basicBlock);
                end = BasicBlock.LOOP_START;
                defaultSC.setBasicBlock(end);
            } else {
                end = lastSwitchCaseBasicBlock;
            }
        } else {
            ControlFlowGraphReducer.visit(v, lastSwitchCaseBasicBlock, end.getFromOffset(), ends);
        }
        HashSet<BasicBlock> hashSet = end.getPredecessors();
        Iterator endPredecessorIterator = hashSet.iterator();
        while (endPredecessorIterator.hasNext()) {
            BasicBlock endPredecessor = (BasicBlock)endPredecessorIterator.next();
            if (!v.get(endPredecessor.getIndex())) continue;
            endPredecessor.replace(end, BasicBlock.SWITCH_BREAK);
            endPredecessorIterator.remove();
        }
        if (defaultSC.getBasicBlock() == end) {
            Iterator iterator = basicBlock.getSwitchCases().iterator();
            while (iterator.hasNext()) {
                if (((SwitchCase)iterator.next()).getBasicBlock() != end) continue;
                iterator.remove();
            }
        } else {
            for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                if (switchCase.getBasicBlock() != end) continue;
                switchCase.setBasicBlock(BasicBlock.SWITCH_BREAK);
            }
        }
        boolean reduced = true;
        for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
            reduced &= ControlFlowGraphReducer.reduce(visited, switchCase.getBasicBlock(), jsrTargets);
        }
        for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
            BasicBlock bb = switchCase.getBasicBlock();
            assert (bb != end);
            HashSet<BasicBlock> predecessors = bb.getPredecessors();
            if (predecessors.size() <= 1) continue;
            Iterator predecessorIterator = predecessors.iterator();
            while (predecessorIterator.hasNext()) {
                BasicBlock predecessor = (BasicBlock)predecessorIterator.next();
                if (predecessor == basicBlock) continue;
                predecessor.replace(bb, BasicBlock.END);
                predecessorIterator.remove();
            }
        }
        basicBlock.setType(128);
        basicBlock.setNext(end);
        hashSet.add(basicBlock);
        return reduced & ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets);
    }

    protected static boolean searchLoopStart(BasicBlock basicBlock, int maxOffset) {
        WatchDog watchdog = new WatchDog();
        block0: for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
            BasicBlock bb = switchCase.getBasicBlock();
            watchdog.clear();
            while (bb.getFromOffset() < maxOffset) {
                if (bb == BasicBlock.LOOP_START) {
                    return true;
                }
                if (bb.matchType(1270628666)) continue block0;
                BasicBlock next = null;
                if (bb.matchType(876822149)) {
                    next = bb.getNext();
                } else if (bb.getType() == 32768) {
                    next = bb.getBranch();
                } else if (bb.getType() == 64) {
                    int max = bb.getFromOffset();
                    for (SwitchCase sc : bb.getSwitchCases()) {
                        if (max >= sc.getBasicBlock().getFromOffset()) continue;
                        next = sc.getBasicBlock();
                        max = next.getFromOffset();
                    }
                }
                if (bb == next) continue block0;
                watchdog.check(bb, next);
                bb = next;
            }
        }
        return false;
    }

    protected static boolean reduceTryDeclaration(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        HashSet<BasicBlock> predecessors;
        boolean reduced = true;
        BasicBlock finallyBB = null;
        for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
            if (exceptionHandler.getInternalThrowableName() != null) continue;
            reduced = ControlFlowGraphReducer.reduce(visited, exceptionHandler.getBasicBlock(), jsrTargets);
            finallyBB = exceptionHandler.getBasicBlock();
            break;
        }
        BasicBlock jsrTarget = ControlFlowGraphReducer.searchJsrTarget(basicBlock, jsrTargets);
        reduced &= ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets);
        BasicBlock tryBB = basicBlock.getNext();
        if (tryBB.matchType(1140900419)) {
            return false;
        }
        int maxOffset = basicBlock.getFromOffset();
        boolean tryWithResourcesFlag = true;
        BasicBlock tryWithResourcesBB = null;
        block1: for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
            BasicBlock bb;
            if (exceptionHandler.getInternalThrowableName() != null) {
                reduced &= ControlFlowGraphReducer.reduce(visited, exceptionHandler.getBasicBlock(), jsrTargets);
            }
            if ((bb = exceptionHandler.getBasicBlock()).matchType(1140900419)) {
                return false;
            }
            if (maxOffset < bb.getFromOffset()) {
                maxOffset = bb.getFromOffset();
            }
            if (!tryWithResourcesFlag) continue;
            predecessors = bb.getPredecessors();
            if (predecessors.size() == 1) {
                tryWithResourcesFlag = false;
                continue;
            }
            assert (predecessors.size() == 2);
            if (tryWithResourcesBB == null) {
                for (BasicBlock predecessor : predecessors) {
                    if (predecessor == basicBlock) continue;
                    assert (predecessor.getType() == 512);
                    tryWithResourcesBB = predecessor;
                    continue block1;
                }
                continue;
            }
            if (predecessors.contains(tryWithResourcesBB)) continue;
            tryWithResourcesFlag = false;
        }
        if (tryWithResourcesFlag) {
            for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                exceptionHandler.getBasicBlock().getPredecessors().remove(basicBlock);
            }
            for (BasicBlock basicBlock2 : basicBlock.getPredecessors()) {
                basicBlock2.replace(basicBlock, tryBB);
                tryBB.replace(basicBlock, basicBlock2);
            }
            basicBlock.setType(0);
        } else if (reduced) {
            // START_CHANGE: IMP-2026-0062-20260422-10 - CFR decompile dropped the explicit
            // initializer `int n = maxOffset;`. Restore it -- n tracks the max toOffset
            // across all exception handlers so the enclosing try can span them.
            int n = maxOffset;
            // END_CHANGE: IMP-2026-0062-10
            BasicBlock end = ControlFlowGraphReducer.searchEndBlock(basicBlock, maxOffset);
            ControlFlowGraphReducer.updateBlock(tryBB, end, maxOffset);
            if (finallyBB != null && basicBlock.getExceptionHandlers().size() == 1 && tryBB.getType() == 1024 && tryBB.getNext() == BasicBlock.END && basicBlock.getFromOffset() == tryBB.getFromOffset() && !ControlFlowGraphReducer.containsFinally(tryBB)) {
                basicBlock.getExceptionHandlers().addAll(0, tryBB.getExceptionHandlers());
                for (ExceptionHandler exceptionHandler : tryBB.getExceptionHandlers()) {
                    predecessors = exceptionHandler.getBasicBlock().getPredecessors();
                    predecessors.clear();
                    predecessors.add(basicBlock);
                }
                tryBB.setType(0);
                tryBB = tryBB.getSub1();
                HashSet<BasicBlock> hashSet = tryBB.getPredecessors();
                hashSet.clear();
                hashSet.add(basicBlock);
            }
            int n2 = maxOffset;
            for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                BasicBlock last;
                int offset;
                BasicBlock bb = exceptionHandler.getBasicBlock();
                if (bb == end) {
                    exceptionHandler.setBasicBlock(BasicBlock.END);
                    continue;
                }
                int n3 = offset = bb.getFromOffset() == maxOffset ? end.getFromOffset() : maxOffset;
                if (offset == 0) {
                    offset = Integer.MAX_VALUE;
                }
                if (n >= (last = ControlFlowGraphReducer.updateBlock(bb, end, offset)).getToOffset()) continue;
                n = last.getToOffset();
            }
            basicBlock.setSub1(tryBB);
            basicBlock.setNext(end);
            end.getPredecessors().add(basicBlock);
            if (jsrTarget == null) {
                if (finallyBB != null && ControlFlowGraphReducer.checkEclipseFinallyPattern(basicBlock, finallyBB, maxOffset)) {
                    basicBlock.setType(4096);
                } else {
                    basicBlock.setType(1024);
                }
            } else {
                basicBlock.setType(2048);
                ControlFlowGraphReducer.removeJsrAndMergeSubTry(basicBlock);
            }
            basicBlock.setToOffset(n);
        }
        return reduced;
    }

    protected static boolean containsFinally(BasicBlock basicBlock) {
        for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
            if (exceptionHandler.getInternalThrowableName() != null) continue;
            return true;
        }
        return false;
    }

    protected static boolean checkEclipseFinallyPattern(BasicBlock basicBlock, BasicBlock finallyBB, int maxOffset) {
        int nextOpcode = ByteCodeParser.searchNextOpcode(basicBlock, maxOffset);
        if (nextOpcode == 0 || nextOpcode == 167 || nextOpcode == 200) {
            return true;
        }
        BasicBlock next = basicBlock.getNext();
        if (!next.matchType(1266696506) && finallyBB.getFromOffset() < next.getFromOffset()) {
            ControlFlowGraph cfg = finallyBB.getControlFlowGraph();
            int toLineNumber = cfg.getLineNumber(finallyBB.getToOffset() - 1);
            int fromLineNumber = cfg.getLineNumber(next.getFromOffset());
            if (fromLineNumber < toLineNumber) {
                return true;
            }
        }
        return false;
    }

    protected static BasicBlock searchJsrTarget(BasicBlock basicBlock, BitSet jsrTargets) {
        for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
            BasicBlock bb;
            if (exceptionHandler.getInternalThrowableName() != null || (bb = exceptionHandler.getBasicBlock()).getType() != 4 || (bb = bb.getNext()).getType() != 8192 || bb.getNext().getType() != 8) continue;
            BasicBlock jsrTarget = bb.getBranch();
            jsrTargets.set(jsrTarget.getIndex());
            return jsrTarget;
        }
        return null;
    }

    protected static BasicBlock searchEndBlock(BasicBlock basicBlock, int maxOffset) {
        BasicBlock end = null;
        BasicBlock last = ControlFlowGraphReducer.splitSequence(basicBlock.getNext(), maxOffset);
        if (!last.matchType(1266696506)) {
            BasicBlock next = last.getNext();
            if (next.getFromOffset() >= maxOffset || !next.matchType(58720530) && next.getToOffset() < basicBlock.getFromOffset()) {
                return next;
            }
            end = next;
        }
        for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
            BasicBlock next;
            BasicBlock bb = exceptionHandler.getBasicBlock();
            if (bb.getFromOffset() < maxOffset) {
                last = ControlFlowGraphReducer.splitSequence(bb, maxOffset);
                if (last.matchType(1266696506)) continue;
                BasicBlock next2 = last.getNext();
                if (next2.getFromOffset() >= maxOffset || !next2.matchType(58720530) && next2.getToOffset() < basicBlock.getFromOffset()) {
                    return next2;
                }
                if (end == null) {
                    end = next2;
                    continue;
                }
                if (end == next2) continue;
                end = BasicBlock.END;
                continue;
            }
            ControlFlowGraph cfg = bb.getControlFlowGraph();
            int lineNumber = cfg.getLineNumber(bb.getFromOffset());
            WatchDog watchdog = new WatchDog();
            last = bb;
            for (next = bb.getNext(); last != next && last.matchType(876822149) && next.getPredecessors().size() == 1 && lineNumber <= cfg.getLineNumber(next.getFromOffset()); next = next.getNext()) {
                watchdog.check(next, next.getNext());
                last = next;
            }
            if (last.matchType(1266696506)) continue;
            if (!(last == next || next.getPredecessors().size() <= 1 && next.matchType(1266696506))) {
                return next;
            }
            if (end == next || exceptionHandler.getInternalThrowableName() == null) continue;
            end = BasicBlock.END;
        }
        if (end != null && end.matchType(58720512)) {
            return end;
        }
        return BasicBlock.END;
    }

    protected static BasicBlock splitSequence(BasicBlock basicBlock, int maxOffset) {
        BasicBlock next = basicBlock.getNext();
        WatchDog watchdog = new WatchDog();
        while (next.getFromOffset() < maxOffset && next.matchType(876822149)) {
            watchdog.check(next, next.getNext());
            basicBlock = next;
            next = next.getNext();
        }
        if (basicBlock.getToOffset() > maxOffset && basicBlock.getType() == 1024) {
            List<ExceptionHandler> exceptionHandlers = basicBlock.getExceptionHandlers();
            BasicBlock bb = ((ExceptionHandler)exceptionHandlers.get(exceptionHandlers.size() - 1)).getBasicBlock();
            BasicBlock last = ControlFlowGraphReducer.splitSequence(bb, maxOffset);
            next = last.getNext();
            last.setNext(BasicBlock.END);
            basicBlock.setToOffset(last.getToOffset());
            basicBlock.setNext(next);
            next.getPredecessors().remove(last);
            next.getPredecessors().add(basicBlock);
        }
        return basicBlock;
    }

    protected static BasicBlock updateBlock(BasicBlock basicBlock, BasicBlock end, int maxOffset) {
        WatchDog watchdog = new WatchDog();
        while (basicBlock.matchType(876822149)) {
            watchdog.check(basicBlock, basicBlock.getNext());
            BasicBlock next = basicBlock.getNext();
            if (next == end || next.getFromOffset() > maxOffset) {
                next.getPredecessors().remove(basicBlock);
                basicBlock.setNext(BasicBlock.END);
                break;
            }
            basicBlock = next;
        }
        return basicBlock;
    }

    protected static void removeJsrAndMergeSubTry(BasicBlock basicBlock) {
        BasicBlock subTry;
        if (basicBlock.getExceptionHandlers().size() == 1 && (subTry = basicBlock.getSub1()).matchType(7168)) {
            for (ExceptionHandler exceptionHandler : subTry.getExceptionHandlers()) {
                if (exceptionHandler.getInternalThrowableName() != null) continue;
                return;
            }
            for (ExceptionHandler exceptionHandler : subTry.getExceptionHandlers()) {
                BasicBlock bb = exceptionHandler.getBasicBlock();
                basicBlock.addExceptionHandler(exceptionHandler.getInternalThrowableName(), bb);
                bb.replace(subTry, basicBlock);
            }
            basicBlock.setSub1(subTry.getSub1());
            subTry.getSub1().replace(subTry, basicBlock);
        }
    }

    protected static boolean reduceJsr(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        BasicBlock branch = basicBlock.getBranch();
        boolean reduced = ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets) & ControlFlowGraphReducer.reduce(visited, branch, jsrTargets);
        if (branch.getIndex() >= 0 && jsrTargets.get(branch.getIndex())) {
            int delta = basicBlock.getToOffset() - basicBlock.getFromOffset();
            if (delta > 3) {
                int opcode = ByteCodeParser.getLastOpcode(basicBlock);
                if (opcode == 168) {
                    basicBlock.setType(4);
                    basicBlock.setToOffset(basicBlock.getToOffset() - 3);
                    branch.getPredecessors().remove(basicBlock);
                    return true;
                }
                if (delta > 5) {
                    basicBlock.setType(4);
                    basicBlock.setToOffset(basicBlock.getToOffset() - 5);
                    branch.getPredecessors().remove(basicBlock);
                    return true;
                }
            }
            basicBlock.setType(0);
            branch.getPredecessors().remove(basicBlock);
            HashSet<BasicBlock> nextPredecessors = basicBlock.getNext().getPredecessors();
            nextPredecessors.remove(basicBlock);
            for (BasicBlock predecessor : basicBlock.getPredecessors()) {
                predecessor.replace(basicBlock, basicBlock.getNext());
                nextPredecessors.add(predecessor);
            }
            return true;
        }
        if (basicBlock.getBranch().getPredecessors().size() > 1) {
            BasicBlock next = basicBlock.getNext();
            Iterator<BasicBlock> iterator = basicBlock.getBranch().getPredecessors().iterator();
            while (iterator.hasNext()) {
                BasicBlock predecessor = iterator.next();
                if (predecessor == basicBlock || predecessor.getType() != 8192 || predecessor.getNext() != next) continue;
                for (BasicBlock predecessorPredecessor : predecessor.getPredecessors()) {
                    predecessorPredecessor.replace(predecessor, basicBlock);
                    basicBlock.getPredecessors().add(predecessorPredecessor);
                }
                next.getPredecessors().remove(predecessor);
                iterator.remove();
                reduced = true;
            }
        }
        return reduced;
    }

    protected static boolean reduceLoop(BitSet visited, BasicBlock basicBlock, BitSet jsrTargets) {
        Object clone = visited.clone();
        boolean reduced = ControlFlowGraphReducer.reduce(visited, basicBlock.getSub1(), jsrTargets);
        if (!reduced) {
            BitSet visitedMembers = new BitSet();
            BasicBlock updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visitedMembers, basicBlock.getSub1());
            visited = (BitSet)((BitSet)clone).clone();
            reduced = ControlFlowGraphReducer.reduce(visited, basicBlock.getSub1(), jsrTargets);
            if (updateBasicBlock != null) {
                BasicBlock ifBasicBlock = basicBlock.getControlFlowGraph().newBasicBlock(65536, basicBlock.getSub1().getFromOffset(), basicBlock.getToOffset());
                ifBasicBlock.setCondition(BasicBlock.END);
                ifBasicBlock.setSub1(basicBlock.getSub1());
                ifBasicBlock.setNext(updateBasicBlock);
                updateBasicBlock.getPredecessors().add(ifBasicBlock);
                basicBlock.setSub1(ifBasicBlock);
            }
            if (!reduced) {
                visitedMembers.clear();
                BasicBlock conditionalBranch = ControlFlowGraphReducer.getLastConditionalBranch(visitedMembers, basicBlock.getSub1());
                if (conditionalBranch != null && conditionalBranch.getNext() == BasicBlock.LOOP_START) {
                    visitedMembers.clear();
                    visitedMembers.set(conditionalBranch.getIndex());
                    ControlFlowGraphReducer.changeEndLoopToJump(visitedMembers, basicBlock.getNext(), basicBlock.getSub1());
                    BasicBlock newLoopBB = basicBlock.getControlFlowGraph().newBasicBlock(basicBlock);
                    HashSet<BasicBlock> predecessors = conditionalBranch.getPredecessors();
                    for (BasicBlock predecessor : predecessors) {
                        predecessor.replace(conditionalBranch, BasicBlock.LOOP_END);
                    }
                    newLoopBB.setNext(conditionalBranch);
                    predecessors.clear();
                    predecessors.add(newLoopBB);
                    basicBlock.setSub1(newLoopBB);
                    visitedMembers.clear();
                    reduced = ControlFlowGraphReducer.reduce(visitedMembers, newLoopBB, jsrTargets);
                }
            }
        }
        return reduced & ControlFlowGraphReducer.reduce(visited, basicBlock.getNext(), jsrTargets);
    }

    protected static BasicBlock getLastConditionalBranch(BitSet visited, BasicBlock basicBlock) {
        if (!basicBlock.matchType(1266696506) && !visited.get(basicBlock.getIndex())) {
            visited.set(basicBlock.getIndex());
            switch (basicBlock.getType()) {
                case 1: 
                case 4: 
                case 64: 
                case 128: 
                case 512: 
                case 1024: 
                case 2048: 
                case 4096: 
                case 8192: 
                case 131072: 
                case 0x400000: {
                    return ControlFlowGraphReducer.getLastConditionalBranch(visited, basicBlock.getNext());
                }
                case 32768: 
                case 65536: 
                case 262144: 
                case 524288: 
                case 0x100000: {
                    BasicBlock bb = ControlFlowGraphReducer.getLastConditionalBranch(visited, basicBlock.getBranch());
                    if (bb != null) {
                        return bb;
                    }
                    bb = ControlFlowGraphReducer.getLastConditionalBranch(visited, basicBlock.getNext());
                    if (bb != null) {
                        return bb;
                    }
                    return basicBlock;
                }
            }
        }
        return null;
    }

    protected static void visit(BitSet visited, BasicBlock basicBlock, int maxOffset, HashSet<BasicBlock> ends) {
        if (!basicBlock.matchType(1266696506)) {
            if (basicBlock.getFromOffset() >= maxOffset) {
                ends.add(basicBlock);
            } else if (!visited.get(basicBlock.getIndex())) {
                visited.set(basicBlock.getIndex());
                switch (basicBlock.getType()) {
                    case 8192: 
                    case 32768: 
                    case 262144: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getBranch(), maxOffset, ends);
                    }
                    case 1: 
                    case 4: 
                    case 0x400000: 
                    case 0x4000000: 
                    case 0x10000000: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getNext(), maxOffset, ends);
                        break;
                    }
                    case 1024: 
                    case 2048: 
                    case 4096: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getSub1(), maxOffset, ends);
                    }
                    case 512: {
                        for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                            ControlFlowGraphReducer.visit(visited, exceptionHandler.getBasicBlock(), maxOffset, ends);
                        }
                        ControlFlowGraphReducer.visit(visited, basicBlock.getNext(), maxOffset, ends);
                        break;
                    }
                    case 131072: 
                    case 0x20000000: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getSub2(), maxOffset, ends);
                    }
                    case 65536: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getSub1(), maxOffset, ends);
                        ControlFlowGraphReducer.visit(visited, basicBlock.getNext(), maxOffset, ends);
                        break;
                    }
                    case 524288: 
                    case 0x100000: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getSub1(), maxOffset, ends);
                        ControlFlowGraphReducer.visit(visited, basicBlock.getSub2(), maxOffset, ends);
                        break;
                    }
                    case 128: {
                        ControlFlowGraphReducer.visit(visited, basicBlock.getNext(), maxOffset, ends);
                    }
                    case 64: {
                        for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                            ControlFlowGraphReducer.visit(visited, switchCase.getBasicBlock(), maxOffset, ends);
                        }
                        break;
                    }
                }
            }
        }
    }

    protected static void replaceLoopStartWithSwitchBreak(BitSet visited, BasicBlock basicBlock) {
        if (!basicBlock.matchType(1266696506) && !visited.get(basicBlock.getIndex())) {
            visited.set(basicBlock.getIndex());
            basicBlock.replace(BasicBlock.LOOP_START, BasicBlock.SWITCH_BREAK);
            switch (basicBlock.getType()) {
                case 8192: 
                case 32768: 
                case 262144: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getBranch());
                }
                case 1: 
                case 4: 
                case 0x400000: 
                case 0x4000000: 
                case 0x10000000: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getNext());
                    break;
                }
                case 1024: 
                case 2048: 
                case 4096: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getSub1());
                }
                case 512: {
                    for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                        ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, exceptionHandler.getBasicBlock());
                    }
                    break;
                }
                case 131072: 
                case 0x20000000: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getSub2());
                }
                case 65536: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getSub1());
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getNext());
                    break;
                }
                case 524288: 
                case 0x100000: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getSub1());
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getSub2());
                    break;
                }
                case 128: {
                    ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, basicBlock.getNext());
                }
                case 64: {
                    for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                        ControlFlowGraphReducer.replaceLoopStartWithSwitchBreak(visited, switchCase.getBasicBlock());
                    }
                    break;
                }
            }
        }
    }

    protected static BasicBlock searchUpdateBlockAndCreateContinueLoop(BitSet visited, BasicBlock basicBlock) {
        BasicBlock updateBasicBlock = null;
        if (!basicBlock.matchType(1266696506) && !visited.get(basicBlock.getIndex())) {
            visited.set(basicBlock.getIndex());
            switch (basicBlock.getType()) {
                case 8192: 
                case 32768: 
                case 262144: 
                case 0x200000: {
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getBranch());
                }
                case 1: 
                case 4: 
                case 0x400000: 
                case 0x4000000: 
                case 0x10000000: {
                    if (updateBasicBlock != null) break;
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getNext());
                    break;
                }
                case 1024: 
                case 2048: 
                case 4096: {
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getSub1());
                }
                case 512: {
                    for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                        if (updateBasicBlock != null) continue;
                        updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, exceptionHandler.getBasicBlock());
                    }
                    if (updateBasicBlock != null) break;
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getNext());
                    break;
                }
                case 131072: 
                case 0x20000000: {
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getSub2());
                }
                case 65536: {
                    if (updateBasicBlock == null) {
                        updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getSub1());
                    }
                    if (updateBasicBlock != null) break;
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getNext());
                    break;
                }
                case 524288: 
                case 0x100000: {
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getSub1());
                    if (updateBasicBlock != null) break;
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getSub2());
                    break;
                }
                case 128: {
                    updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, basicBlock.getNext());
                }
                case 64: {
                    for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                        if (updateBasicBlock != null) continue;
                        updateBasicBlock = ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, basicBlock, switchCase.getBasicBlock());
                    }
                    break;
                }
            }
        }
        return updateBasicBlock;
    }

    protected static BasicBlock searchUpdateBlockAndCreateContinueLoop(BitSet visited, BasicBlock basicBlock, BasicBlock subBasicBlock) {
        if (subBasicBlock != null) {
            if (basicBlock.getFromOffset() < subBasicBlock.getFromOffset()) {
                if (basicBlock.getFirstLineNumber() == 0) {
                    if (subBasicBlock.matchType(876822149) && subBasicBlock.getNext().getType() == 0x800000) {
                        HashSet<BasicBlock> predecessors;
                        for (int stackDepth = ByteCodeParser.evalStackDepth(subBasicBlock); stackDepth != 0 && (predecessors = subBasicBlock.getPredecessors()).size() == 1; stackDepth += ByteCodeParser.evalStackDepth(subBasicBlock)) {
                            subBasicBlock = (BasicBlock)predecessors.iterator().next();
                        }
                        ControlFlowGraphReducer.removePredecessors(subBasicBlock);
                        return subBasicBlock;
                    }
                } else if (basicBlock.getFirstLineNumber() > subBasicBlock.getFirstLineNumber()) {
                    ControlFlowGraphReducer.removePredecessors(subBasicBlock);
                    return subBasicBlock;
                }
            }
            return ControlFlowGraphReducer.searchUpdateBlockAndCreateContinueLoop(visited, subBasicBlock);
        }
        return null;
    }

    protected static void removePredecessors(BasicBlock basicBlock) {
        HashSet<BasicBlock> predecessors = basicBlock.getPredecessors();
        Iterator iterator = predecessors.iterator();
        while (iterator.hasNext()) {
            ((BasicBlock)iterator.next()).replace(basicBlock, BasicBlock.LOOP_CONTINUE);
        }
        predecessors.clear();
    }

    protected static void changeEndLoopToJump(BitSet visited, BasicBlock target, BasicBlock basicBlock) {
        if (!basicBlock.matchType(1266696506) && !visited.get(basicBlock.getIndex())) {
            visited.set(basicBlock.getIndex());
            switch (basicBlock.getType()) {
                case 8192: 
                case 32768: 
                case 262144: {
                    if (basicBlock.getBranch() == BasicBlock.LOOP_END) {
                        basicBlock.setBranch(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getBranch());
                    }
                }
                case 1: 
                case 4: 
                case 0x400000: 
                case 0x4000000: 
                case 0x10000000: {
                    if (basicBlock.getNext() == BasicBlock.LOOP_END) {
                        basicBlock.setNext(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                        break;
                    }
                    ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getNext());
                    break;
                }
                case 1024: 
                case 2048: 
                case 4096: {
                    if (basicBlock.getSub1() == BasicBlock.LOOP_END) {
                        basicBlock.setSub1(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getSub1());
                    }
                }
                case 512: {
                    for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                        if (exceptionHandler.getBasicBlock() == BasicBlock.LOOP_END) {
                            exceptionHandler.setBasicBlock(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                            continue;
                        }
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, exceptionHandler.getBasicBlock());
                    }
                    break;
                }
                case 131072: 
                case 0x20000000: {
                    if (basicBlock.getSub2() == BasicBlock.LOOP_END) {
                        basicBlock.setSub2(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getSub2());
                    }
                }
                case 65536: {
                    if (basicBlock.getSub1() == BasicBlock.LOOP_END) {
                        basicBlock.setSub1(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getSub1());
                    }
                    if (basicBlock.getNext() == BasicBlock.LOOP_END) {
                        basicBlock.setNext(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                        break;
                    }
                    ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getNext());
                    break;
                }
                case 524288: 
                case 0x100000: {
                    if (basicBlock.getSub1() == BasicBlock.LOOP_END) {
                        basicBlock.setSub1(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getSub1());
                    }
                    if (basicBlock.getSub2() == BasicBlock.LOOP_END) {
                        basicBlock.setSub2(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                        break;
                    }
                    ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getSub2());
                    break;
                }
                case 128: {
                    if (basicBlock.getNext() == BasicBlock.LOOP_END) {
                        basicBlock.setNext(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                    } else {
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, basicBlock.getNext());
                    }
                }
                case 64: {
                    for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                        if (switchCase.getBasicBlock() == BasicBlock.LOOP_END) {
                            switchCase.setBasicBlock(ControlFlowGraphReducer.newJumpBasicBlock(basicBlock, target));
                            continue;
                        }
                        ControlFlowGraphReducer.changeEndLoopToJump(visited, target, switchCase.getBasicBlock());
                    }
                    break;
                }
            }
        }
    }

    protected static BasicBlock newJumpBasicBlock(BasicBlock bb, BasicBlock target) {
        HashSet<BasicBlock> predecessors = new HashSet<BasicBlock>();
        predecessors.add(bb);
        target.getPredecessors().remove(bb);
        return bb.getControlFlowGraph().newBasicBlock(0x40000000, bb.getFromOffset(), target.getFromOffset(), predecessors);
    }

    protected static BasicBlock clone(BasicBlock bb, BasicBlock next) {
        BasicBlock clone = next.getControlFlowGraph().newBasicBlock(next.getType(), next.getFromOffset(), next.getToOffset());
        clone.setNext(BasicBlock.END);
        clone.getPredecessors().add(bb);
        next.getPredecessors().remove(bb);
        bb.setNext(clone);
        return clone;
    }
}
