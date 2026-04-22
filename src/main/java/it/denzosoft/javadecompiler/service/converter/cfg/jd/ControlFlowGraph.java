/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-4 - Ported from JD-Core
 *
 * Owns the block list and maps PC offsets to source line numbers.
 * Unlike our legacy `cfg.ControlFlowGraph`, this one is MUTABLE under the
 * reducer: `newBasicBlock` creates synthetic IF/IF_ELSE/LOOP/... blocks as the
 * CFG is reduced.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import it.denzosoft.javadecompiler.model.classfile.MethodInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ControlFlowGraph {
    protected MethodInfo method;
    /** All blocks (original + synthetic). Indices are stable across reductions. */
    protected List<BasicBlock> list = new ArrayList<BasicBlock>();
    /** offsetToLineNumbers[pc] -> source line; 0 if unknown. */
    protected int[] offsetToLineNumbers = null;

    public ControlFlowGraph(MethodInfo method) {
        this.method = method;
    }

    public MethodInfo getMethod() { return method; }
    public List<BasicBlock> getBasicBlocks() { return list; }
    public BasicBlock getStart() { return list.get(0); }

    public BasicBlock newBasicBlock(BasicBlock original) {
        BasicBlock b = new BasicBlock(this, list.size(), original);
        list.add(b);
        return b;
    }

    public BasicBlock newBasicBlock(int fromOffset, int toOffset) {
        return newBasicBlock(0, fromOffset, toOffset);
    }

    public BasicBlock newBasicBlock(int type, int fromOffset, int toOffset) {
        BasicBlock b = new BasicBlock(this, list.size(), type, fromOffset, toOffset, true);
        list.add(b);
        return b;
    }

    public BasicBlock newBasicBlock(int type, int fromOffset, int toOffset, boolean inverseCondition) {
        BasicBlock b = new BasicBlock(this, list.size(), type, fromOffset, toOffset, inverseCondition);
        list.add(b);
        return b;
    }

    public BasicBlock newBasicBlock(int type, int fromOffset, int toOffset, HashSet<BasicBlock> predecessors) {
        BasicBlock b = new BasicBlock(this, list.size(), type, fromOffset, toOffset, true, predecessors);
        list.add(b);
        return b;
    }

    public void setOffsetToLineNumbers(int[] offsetToLineNumbers) {
        this.offsetToLineNumbers = offsetToLineNumbers;
    }

    public int getLineNumber(int offset) {
        if (offsetToLineNumbers == null) return 0;
        if (offset < 0 || offset >= offsetToLineNumbers.length) return 0;
        return offsetToLineNumbers[offset];
    }
}
