/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-12 - Ported ControlFlowGraphLoopReducer from JD-Core.
 * Decompiled with CFR 0.152.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
// BasicBlock is in same package
// ControlFlowGraph is in same package
// Loop is in same package
// ByteCodeParser is in same package
import java.util.List; import java.util.ArrayList;

public class ControlFlowGraphLoopReducer {
    protected static final LoopComparator LOOP_COMPARATOR = new LoopComparator();

    public static BitSet[] buildDominatorIndexes(ControlFlowGraph cfg) {
        boolean change;
        List<BasicBlock> list = cfg.getBasicBlocks();
        int length = list.size();
        BitSet[] arrayOfDominatorIndexes = new BitSet[length];
        BitSet initial = new BitSet(length);
        initial.set(0);
        arrayOfDominatorIndexes[0] = initial;
        for (int i = 0; i < length; ++i) {
            initial = new BitSet(length);
            initial.flip(0, length);
            arrayOfDominatorIndexes[i] = initial;
        }
        initial = arrayOfDominatorIndexes[0];
        initial.clear();
        initial.set(0);
        do {
            change = false;
            for (BasicBlock basicBlock : list) {
                int index = basicBlock.getIndex();
                BitSet dominatorIndexes = arrayOfDominatorIndexes[index];
                initial = (BitSet)dominatorIndexes.clone();
                for (BasicBlock predecessorBB : basicBlock.getPredecessors()) {
                    dominatorIndexes.and(arrayOfDominatorIndexes[predecessorBB.getIndex()]);
                }
                dominatorIndexes.set(index);
                change |= !initial.equals(dominatorIndexes);
            }
        } while (change);
        return arrayOfDominatorIndexes;
    }

    public static List<Loop> identifyNaturalLoops(ControlFlowGraph cfg, BitSet[] arrayOfDominatorIndexes) {
        BitSet startDominatorIndexes;
        int i;
        List<BasicBlock> list = cfg.getBasicBlocks();
        int length = list.size();
        BitSet[] arrayOfMemberIndexes = new BitSet[length];
        block5: for (i = 0; i < length; ++i) {
            BasicBlock current = (BasicBlock)list.get(i);
            BitSet dominatorIndexes = arrayOfDominatorIndexes[i];
            switch (current.getType()) {
                case 32768: {
                    int index = current.getBranch().getIndex();
                    if (index >= 0 && dominatorIndexes.get(index)) {
                        arrayOfMemberIndexes[index] = ControlFlowGraphLoopReducer.searchLoopMemberIndexes(length, arrayOfMemberIndexes[index], current, current.getBranch());
                    }
                }
                case 4: 
                case 0x4000000: {
                    int index = current.getNext().getIndex();
                    if (index < 0 || !dominatorIndexes.get(index)) continue block5;
                    arrayOfMemberIndexes[index] = ControlFlowGraphLoopReducer.searchLoopMemberIndexes(length, arrayOfMemberIndexes[index], current, current.getNext());
                    continue block5;
                }
                case 64: {
                    int index;
                    for (SwitchCase switchCase : current.getSwitchCases()) {
                        index = switchCase.getBasicBlock().getIndex();
                        if (index < 0 || !dominatorIndexes.get(index)) continue;
                        arrayOfMemberIndexes[index] = ControlFlowGraphLoopReducer.searchLoopMemberIndexes(length, arrayOfMemberIndexes[index], current, switchCase.getBasicBlock());
                    }
                    continue block5;
                }
            }
        }
        for (i = 0; i < length; ++i) {
            if (arrayOfMemberIndexes[i] == null) continue;
            BitSet memberIndexes = arrayOfMemberIndexes[i];
            int maxOffset = -1;
            for (int j = 0; j < length; ++j) {
                int offset;
                if (!memberIndexes.get(j) || maxOffset >= (offset = ((BasicBlock)list.get(j)).getFromOffset())) continue;
                maxOffset = offset;
            }
            BasicBlock start = (BasicBlock)list.get(i);
            startDominatorIndexes = arrayOfDominatorIndexes[i];
            if (start.getType() != 512 || maxOffset == start.getFromOffset() || maxOffset >= start.getExceptionHandlers().get(0).getBasicBlock().getFromOffset()) continue;
            BasicBlock newStart = start.getNext();
            HashSet<BasicBlock> newStartPredecessors = newStart.getPredecessors();
            Iterator<BasicBlock> iterator = start.getPredecessors().iterator();
            while (iterator.hasNext()) {
                BasicBlock predecessor = iterator.next();
                if (startDominatorIndexes.get(predecessor.getIndex())) continue;
                iterator.remove();
                predecessor.replace(start, newStart);
                newStartPredecessors.add(predecessor);
            }
            memberIndexes.clear(start.getIndex());
            arrayOfMemberIndexes[newStart.getIndex()] = memberIndexes;
            arrayOfMemberIndexes[i] = null;
        }
        List<Loop> loops = new ArrayList<Loop>();
        for (int i2 = 0; i2 < length; ++i2) {
            if (arrayOfMemberIndexes[i2] == null) continue;
            BitSet memberIndexes = arrayOfMemberIndexes[i2];
            BasicBlock start = (BasicBlock)list.get(i2);
            startDominatorIndexes = arrayOfDominatorIndexes[i2];
            BitSet searchZoneIndexes = new BitSet(length);
            searchZoneIndexes.or(startDominatorIndexes);
            searchZoneIndexes.flip(0, length);
            searchZoneIndexes.set(start.getIndex());
            if (start.getType() == 32768) {
                if (start.getNext() != start && start.getBranch() != start && memberIndexes.get(start.getNext().getIndex()) && memberIndexes.get(start.getBranch().getIndex())) {
                    BitSet nextIndexes = new BitSet(length);
                    BitSet branchIndexes = new BitSet(length);
                    ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(nextIndexes, memberIndexes, start.getNext(), start);
                    ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(branchIndexes, memberIndexes, start.getBranch(), start);
                    BitSet commonMemberIndexes = (BitSet)nextIndexes.clone();
                    commonMemberIndexes.and(branchIndexes);
                    BitSet onlyLoopHeaderIndex = new BitSet(length);
                    onlyLoopHeaderIndex.set(i2);
                    if (commonMemberIndexes.equals(onlyLoopHeaderIndex)) {
                        loops.add(ControlFlowGraphLoopReducer.makeLoop(list, start, searchZoneIndexes, memberIndexes));
                        branchIndexes.flip(0, length);
                        searchZoneIndexes.and(branchIndexes);
                        searchZoneIndexes.set(start.getIndex());
                        loops.add(ControlFlowGraphLoopReducer.makeLoop(list, start, searchZoneIndexes, nextIndexes));
                        continue;
                    }
                    loops.add(ControlFlowGraphLoopReducer.makeLoop(list, start, searchZoneIndexes, memberIndexes));
                    continue;
                }
                loops.add(ControlFlowGraphLoopReducer.makeLoop(list, start, searchZoneIndexes, memberIndexes));
                continue;
            }
            loops.add(ControlFlowGraphLoopReducer.makeLoop(list, start, searchZoneIndexes, memberIndexes));
        }
        loops.sort(LOOP_COMPARATOR);
        return loops;
    }

    protected static BitSet searchLoopMemberIndexes(int length, BitSet memberIndexes, BasicBlock current, BasicBlock start) {
        BitSet visited = new BitSet(length);
        ControlFlowGraphLoopReducer.recursiveBackwardSearchLoopMemberIndexes(visited, current, start);
        if (memberIndexes == null) {
            return visited;
        }
        memberIndexes.or(visited);
        return memberIndexes;
    }

    protected static void recursiveBackwardSearchLoopMemberIndexes(BitSet visited, BasicBlock current, BasicBlock start) {
        if (!visited.get(current.getIndex())) {
            visited.set(current.getIndex());
            if (current != start) {
                for (BasicBlock predecessor : current.getPredecessors()) {
                    ControlFlowGraphLoopReducer.recursiveBackwardSearchLoopMemberIndexes(visited, predecessor, start);
                }
            }
        }
    }

    protected static Loop makeLoop(List<BasicBlock> list, BasicBlock start, BitSet searchZoneIndexes, BitSet memberIndexes) {
        HashSet<BasicBlock> set;
        int length = list.size();
        int maxOffset = -1;
        for (int i = 0; i < length; ++i) {
            int offset;
            if (!memberIndexes.get(i) || maxOffset >= (offset = ControlFlowGraphLoopReducer.checkMaxOffset(list.get(i)))) continue;
            maxOffset = offset;
        }
        memberIndexes.clear();
        ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(memberIndexes, searchZoneIndexes, start, maxOffset);
        HashSet<BasicBlock> members = new HashSet<BasicBlock>(memberIndexes.cardinality());
        for (int i = 0; i < length; ++i) {
            if (!memberIndexes.get(i)) continue;
            members.add(list.get(i));
        }
        BasicBlock end = BasicBlock.END;
        if (start.getType() == 32768) {
            int index = start.getBranch().getIndex();
            if (!memberIndexes.get(index)) {
                end = start.getBranch();
            } else {
                index = start.getNext().getIndex();
                if (!memberIndexes.get(index)) {
                    end = start.getNext();
                }
            }
        }
        if (end == BasicBlock.END && !(end = ControlFlowGraphLoopReducer.searchEndBasicBlock(memberIndexes, maxOffset, members)).matchType(58720274) && end.getPredecessors().size() == 1 && end.getPredecessors().iterator().next().getLastLineNumber() + 1 >= end.getFirstLineNumber() && ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set = new HashSet<BasicBlock>(), end, null)) {
            members.addAll(set);
            for (BasicBlock member : set) {
                if (member.getIndex() < 0) continue;
                memberIndexes.set(member.getIndex());
            }
            end = ControlFlowGraphLoopReducer.searchEndBasicBlock(memberIndexes, maxOffset, set);
        }
        if (end != BasicBlock.END) {
            HashSet<BasicBlock> m = new HashSet<BasicBlock>(members);
            HashSet<BasicBlock> set2 = new HashSet<BasicBlock>();
            for (BasicBlock member : m) {
                if (member.getType() != 32768 || member == start) continue;
                set2.clear();
                if (ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set2, member.getNext(), end)) {
                    members.addAll(set2);
                }
                set2.clear();
                if (!ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set2, member.getBranch(), end)) continue;
                members.addAll(set2);
            }
        }
        return new Loop(start, members, end);
    }

    private static BasicBlock searchEndBasicBlock(BitSet memberIndexes, int maxOffset, Set<BasicBlock> members) {
        BasicBlock end = BasicBlock.END;
        block6: for (BasicBlock member : members) {
            switch (member.getType()) {
                case 32768: {
                    BasicBlock bb = member.getBranch();
                    if (!memberIndexes.get(bb.getIndex()) && maxOffset < bb.getFromOffset()) {
                        end = bb;
                        maxOffset = bb.getFromOffset();
                        break;
                    }
                }
                case 4: 
                case 0x4000000: {
                    BasicBlock bb = member.getNext();
                    if (memberIndexes.get(bb.getIndex()) || maxOffset >= bb.getFromOffset()) break;
                    end = bb;
                    maxOffset = bb.getFromOffset();
                    break;
                }
                case 64: {
                    BasicBlock bb;
                    for (SwitchCase switchCase : member.getSwitchCases()) {
                        bb = switchCase.getBasicBlock();
                        if (memberIndexes.get(bb.getIndex()) || maxOffset >= bb.getFromOffset()) continue;
                        end = bb;
                        maxOffset = bb.getFromOffset();
                    }
                    continue block6;
                }
                case 512: {
                    BasicBlock bb = member.getNext();
                    if (!memberIndexes.get(bb.getIndex()) && maxOffset < bb.getFromOffset()) {
                        end = bb;
                        maxOffset = bb.getFromOffset();
                    }
                    for (ExceptionHandler exceptionHandler : member.getExceptionHandlers()) {
                        bb = exceptionHandler.getBasicBlock();
                        if (memberIndexes.get(bb.getIndex()) || maxOffset >= bb.getFromOffset()) continue;
                        end = bb;
                        maxOffset = bb.getFromOffset();
                    }
                    break;
                }
            }
        }
        return end;
    }

    private static int checkMaxOffset(BasicBlock basicBlock) {
        int maxOffset = basicBlock.getFromOffset();
        if (basicBlock.getType() == 512) {
            for (ExceptionHandler exceptionHandler : basicBlock.getExceptionHandlers()) {
                int offset = exceptionHandler.getInternalThrowableName() == null ? ControlFlowGraphLoopReducer.checkThrowBlockOffset(exceptionHandler.getBasicBlock()) : ControlFlowGraphLoopReducer.checkSynchronizedBlockOffset(exceptionHandler.getBasicBlock());
                if (maxOffset >= offset) continue;
                maxOffset = offset;
            }
        } else if (basicBlock.getType() == 64) {
            int offset;
            BasicBlock lastBB = null;
            BasicBlock previousBB = null;
            for (SwitchCase switchCase : basicBlock.getSwitchCases()) {
                BasicBlock bb = switchCase.getBasicBlock();
                if (lastBB != null && lastBB.getFromOffset() >= bb.getFromOffset()) continue;
                previousBB = lastBB;
                lastBB = bb;
            }
            if (previousBB != null && maxOffset < (offset = ControlFlowGraphLoopReducer.checkSynchronizedBlockOffset(previousBB))) {
                maxOffset = offset;
            }
        }
        return maxOffset;
    }

    private static int checkSynchronizedBlockOffset(BasicBlock basicBlock) {
        if (basicBlock.getNext().getType() == 512 && ByteCodeParser.getLastOpcode(basicBlock) == 194) {
            return ControlFlowGraphLoopReducer.checkThrowBlockOffset(basicBlock.getNext().getExceptionHandlers().get(0).getBasicBlock());
        }
        return basicBlock.getFromOffset();
    }

    private static int checkThrowBlockOffset(BasicBlock basicBlock) {
        int offset = basicBlock.getFromOffset();
        BitSet watchdog = new BitSet();
        while (!basicBlock.matchType(1266696506) && !watchdog.get(basicBlock.getIndex())) {
            watchdog.set(basicBlock.getIndex());
            basicBlock = basicBlock.getNext();
        }
        if (basicBlock.getType() == 8) {
            return basicBlock.getFromOffset();
        }
        return offset;
    }

    protected static void recursiveForwardSearchLoopMemberIndexes(BitSet visited, BitSet searchZoneIndexes, BasicBlock current, BasicBlock target) {
        if (!current.matchType(1266696506) && !visited.get(current.getIndex()) && searchZoneIndexes.get(current.getIndex())) {
            visited.set(current.getIndex());
            if (current != target) {
                ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, current.getNext(), target);
                ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, current.getBranch(), target);
                for (SwitchCase switchCase : current.getSwitchCases()) {
                    ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, switchCase.getBasicBlock(), target);
                }
                for (ExceptionHandler exceptionHandler : current.getExceptionHandlers()) {
                    ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, exceptionHandler.getBasicBlock(), target);
                }
                if (current.getType() == 0x10000000) {
                    visited.set(current.getNext().getIndex());
                }
            }
        }
    }

    protected static void recursiveForwardSearchLoopMemberIndexes(BitSet visited, BitSet searchZoneIndexes, BasicBlock current, int maxOffset) {
        if (!current.matchType(58720514) && !visited.get(current.getIndex()) && searchZoneIndexes.get(current.getIndex()) && current.getFromOffset() <= maxOffset) {
            visited.set(current.getIndex());
            ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, current.getNext(), maxOffset);
            ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, current.getBranch(), maxOffset);
            for (SwitchCase switchCase : current.getSwitchCases()) {
                ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, switchCase.getBasicBlock(), maxOffset);
            }
            for (ExceptionHandler exceptionHandler : current.getExceptionHandlers()) {
                ControlFlowGraphLoopReducer.recursiveForwardSearchLoopMemberIndexes(visited, searchZoneIndexes, exceptionHandler.getBasicBlock(), maxOffset);
            }
            if (current.getType() == 0x10000000) {
                visited.set(current.getNext().getIndex());
            }
        }
    }

    protected static boolean recursiveForwardSearchLastLoopMemberIndexes(HashSet<BasicBlock> members, BitSet searchZoneIndexes, HashSet<BasicBlock> set, BasicBlock current, BasicBlock end) {
        if (current == end || members.contains(current) || set.contains(current)) {
            return true;
        }
        if (current.matchType(876822149)) {
            if (!ControlFlowGraphLoopReducer.inSearchZone(current.getNext(), searchZoneIndexes) || !ControlFlowGraphLoopReducer.predecessorsInSearchZone(current, searchZoneIndexes)) {
                searchZoneIndexes.clear(current.getIndex());
                return true;
            }
            set.add(current);
            return ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set, current.getNext(), end);
        }
        if (current.getType() == 32768) {
            if (!(ControlFlowGraphLoopReducer.inSearchZone(current.getNext(), searchZoneIndexes) && ControlFlowGraphLoopReducer.inSearchZone(current.getBranch(), searchZoneIndexes) && ControlFlowGraphLoopReducer.predecessorsInSearchZone(current, searchZoneIndexes))) {
                searchZoneIndexes.clear(current.getIndex());
                return true;
            }
            set.add(current);
            return ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set, current.getNext(), end) | ControlFlowGraphLoopReducer.recursiveForwardSearchLastLoopMemberIndexes(members, searchZoneIndexes, set, current.getBranch(), end);
        }
        if (current.matchType(1266696506)) {
            if (!ControlFlowGraphLoopReducer.predecessorsInSearchZone(current, searchZoneIndexes)) {
                if (current.getIndex() >= 0) {
                    searchZoneIndexes.clear(current.getIndex());
                }
            } else {
                set.add(current);
            }
            return true;
        }
        return false;
    }

    protected static boolean predecessorsInSearchZone(BasicBlock basicBlock, BitSet searchZoneIndexes) {
        HashSet<BasicBlock> predecessors = basicBlock.getPredecessors();
        for (BasicBlock predecessor : predecessors) {
            if (ControlFlowGraphLoopReducer.inSearchZone(predecessor, searchZoneIndexes)) continue;
            return false;
        }
        return true;
    }

    protected static boolean inSearchZone(BasicBlock basicBlock, BitSet searchZoneIndexes) {
        return basicBlock.matchType(1249918994) || searchZoneIndexes.get(basicBlock.getIndex());
    }

    protected static BasicBlock recheckEndBlock(Set<BasicBlock> members, BasicBlock end) {
        boolean flag = false;
        for (BasicBlock predecessor : end.getPredecessors()) {
            if (members.contains(predecessor)) continue;
            flag = true;
            break;
        }
        if (!flag) {
            BasicBlock newEnd = null;
            for (BasicBlock member : members) {
                BasicBlock bb;
                if (member.matchType(876822149)) {
                    bb = member.getNext();
                    if (bb == end || members.contains(bb)) continue;
                    newEnd = bb;
                    break;
                }
                if (member.getType() != 32768) continue;
                bb = member.getNext();
                if (bb != end && !members.contains(bb)) {
                    newEnd = bb;
                    break;
                }
                bb = member.getBranch();
                if (bb == end || members.contains(bb)) continue;
                newEnd = bb;
                break;
            }
            if (newEnd != null && end.getFromOffset() < newEnd.getFromOffset()) {
                if (end.matchType(56)) {
                    members.add(end);
                    end = newEnd;
                } else if (end.matchType(876822149) && end.getNext() == newEnd) {
                    members.add(end);
                    end = newEnd;
                }
            }
        }
        return end;
    }

    protected static BasicBlock reduceLoop(Loop loop) {
        BasicBlock start = loop.getStart();
        HashSet<BasicBlock> members = loop.getMembers();
        BasicBlock end = loop.getEnd();
        int toOffset = start.getToOffset();
        end = ControlFlowGraphLoopReducer.recheckEndBlock(members, end);
        BasicBlock loopBB = start.getControlFlowGraph().newBasicBlock(0x400000, start.getFromOffset(), start.getToOffset());
        Iterator<BasicBlock> startPredecessorIterator = start.getPredecessors().iterator();
        while (startPredecessorIterator.hasNext()) {
            BasicBlock predecessor = startPredecessorIterator.next();
            if (members.contains(predecessor)) continue;
            predecessor.replace(start, loopBB);
            loopBB.getPredecessors().add(predecessor);
            startPredecessorIterator.remove();
        }
        loopBB.setSub1(start);
        for (BasicBlock member : members) {
            block14: {
                block16: {
                    BasicBlock bb;
                    block18: {
                        block17: {
                            block12: {
                                block15: {
                                    block13: {
                                        if (!member.matchType(876822149)) break block12;
                                        bb = member.getNext();
                                        if (bb != start) break block13;
                                        member.setNext(BasicBlock.LOOP_START);
                                        break block14;
                                    }
                                    if (bb != end) break block15;
                                    member.setNext(BasicBlock.LOOP_END);
                                    break block14;
                                }
                                if (members.contains(bb) || bb.getPredecessors().size() <= 1) break block14;
                                member.setNext(ControlFlowGraphLoopReducer.newJumpBasicBlock(member, bb));
                                break block14;
                            }
                            if (member.getType() != 32768) break block16;
                            bb = member.getNext();
                            if (bb == start) {
                                member.setNext(BasicBlock.LOOP_START);
                            } else if (bb == end) {
                                member.setNext(BasicBlock.LOOP_END);
                            } else if (!members.contains(bb) && bb.getPredecessors().size() > 1) {
                                member.setNext(ControlFlowGraphLoopReducer.newJumpBasicBlock(member, bb));
                            }
                            bb = member.getBranch();
                            if (bb != start) break block17;
                            member.setBranch(BasicBlock.LOOP_START);
                            break block14;
                        }
                        if (bb != end) break block18;
                        member.setBranch(BasicBlock.LOOP_END);
                        break block14;
                    }
                    if (members.contains(bb) || bb.getPredecessors().size() <= 1) break block14;
                    member.setBranch(ControlFlowGraphLoopReducer.newJumpBasicBlock(member, bb));
                    break block14;
                }
                if (member.getType() == 64) {
                    for (SwitchCase switchCase : member.getSwitchCases()) {
                        BasicBlock bb = switchCase.getBasicBlock();
                        if (bb == start) {
                            switchCase.setBasicBlock(BasicBlock.LOOP_START);
                            continue;
                        }
                        if (bb == end) {
                            switchCase.setBasicBlock(BasicBlock.LOOP_END);
                            continue;
                        }
                        if (members.contains(bb) || bb.getPredecessors().size() <= 1) continue;
                        switchCase.setBasicBlock(ControlFlowGraphLoopReducer.newJumpBasicBlock(member, bb));
                    }
                }
            }
            if (toOffset >= member.getToOffset()) continue;
            toOffset = member.getToOffset();
        }
        if (end != null) {
            loopBB.setNext(end);
            end.replace(members, loopBB);
        }
        start.getPredecessors().clear();
        loopBB.setToOffset(toOffset);
        return loopBB;
    }

    protected static BasicBlock newJumpBasicBlock(BasicBlock bb, BasicBlock target) {
        HashSet<BasicBlock> predecessors = new HashSet<BasicBlock>();
        predecessors.add(bb);
        target.getPredecessors().remove(bb);
        return bb.getControlFlowGraph().newBasicBlock(0x40000000, bb.getFromOffset(), target.getFromOffset(), predecessors);
    }

    public static void reduce(ControlFlowGraph cfg) {
        BitSet[] arrayOfDominatorIndexes = ControlFlowGraphLoopReducer.buildDominatorIndexes(cfg);
        List<Loop> loops = ControlFlowGraphLoopReducer.identifyNaturalLoops(cfg, arrayOfDominatorIndexes);
        int loopsLength = loops.size();
        for (int i = 0; i < loopsLength; ++i) {
            Loop loop = loops.get(i);
            BasicBlock startBB = loop.getStart();
            BasicBlock loopBB = ControlFlowGraphLoopReducer.reduceLoop(loop);
            for (int j = loopsLength - 1; j > i; --j) {
                Loop otherLoop = loops.get(j);
                if (otherLoop.getStart() == startBB) {
                    otherLoop.setStart(loopBB);
                }
                if (otherLoop.getMembers().contains(startBB)) {
                    otherLoop.getMembers().removeAll(loop.getMembers());
                    otherLoop.getMembers().add(loopBB);
                }
                if (otherLoop.getEnd() != startBB) continue;
                otherLoop.setEnd(loopBB);
            }
        }
    }

    public static class LoopComparator
    implements Comparator<Loop> {
        @Override
        public int compare(Loop loop1, Loop loop2) {
            return loop1.getMembers().size() - loop2.getMembers().size();
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }
    }
}
