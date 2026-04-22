/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-11 - Ported from JD-Core.
 *
 * Final reducer pass: for every remaining TYPE_GOTO block, if it jumps to a
 * unique successor, rewire the predecessors to bypass the goto block entirely.
 * Self-loops become TYPE_INFINITE_GOTO (LoopReducer handles these).
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.HashSet;

public class ControlFlowGraphGotoReducer {
    public static void reduce(ControlFlowGraph cfg) {
        for (BasicBlock basicBlock : cfg.getBasicBlocks()) {
            if (basicBlock.getType() != BasicBlock.TYPE_GOTO) continue;
            BasicBlock successor = basicBlock.getNext();
            if (basicBlock == successor) {
                // Self-loop: `while (true) ;` pattern.
                basicBlock.getPredecessors().remove(basicBlock);
                basicBlock.setType(BasicBlock.TYPE_INFINITE_GOTO);
                continue;
            }
            HashSet<BasicBlock> successorPredecessors = successor.getPredecessors();
            successorPredecessors.remove(basicBlock);
            for (BasicBlock predecessor : basicBlock.getPredecessors()) {
                predecessor.replace(basicBlock, successor);
                successorPredecessors.add(predecessor);
            }
            basicBlock.setType(BasicBlock.TYPE_DELETED);
        }
    }
}
