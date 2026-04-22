/*
 * This project is distributed under the GPLv3 license.
 *
 * START_CHANGE: IMP-2026-0062-20260422-1 - JD-Core-style CFG model.
 *
 * Ported from JD-Core (GPLv3), originally by Emmanuel Dupuy. Adapted to
 * DenzoSOFT's package layout and simplified where DefaultList is used upstream
 * (replaced with java.util.ArrayList). The type-flag design, sub1/sub2/condition
 * navigation and matchType/replace algebra are load-bearing for the reducer
 * pipeline; these stay close to the original.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * CFG block with a rich type-flag field representing its structured form
 * (IF, IF_ELSE, CONDITION_AND, LOOP, TRY, SWITCH, ...). The reducer collapses
 * subgraphs by changing a block's type and rewiring sub1/sub2/condition/next/branch.
 *
 * Group constants (GROUP_*) are bitmasks for `matchType` membership tests.
 */
public class BasicBlock {
    public static final int TYPE_DELETED = 0;
    public static final int TYPE_START = 1;
    public static final int TYPE_END = 2;
    public static final int TYPE_STATEMENTS = 4;
    public static final int TYPE_THROW = 8;
    public static final int TYPE_RETURN = 16;
    public static final int TYPE_RETURN_VALUE = 32;
    public static final int TYPE_SWITCH_DECLARATION = 64;
    public static final int TYPE_SWITCH = 128;
    public static final int TYPE_SWITCH_BREAK = 256;
    public static final int TYPE_TRY_DECLARATION = 512;
    public static final int TYPE_TRY = 1024;
    public static final int TYPE_TRY_JSR = 2048;
    public static final int TYPE_TRY_ECLIPSE = 4096;
    public static final int TYPE_JSR = 8192;
    public static final int TYPE_RET = 16384;
    public static final int TYPE_CONDITIONAL_BRANCH = 32768;
    public static final int TYPE_IF = 65536;
    public static final int TYPE_IF_ELSE = 131072;
    public static final int TYPE_CONDITION = 262144;
    public static final int TYPE_CONDITION_OR = 524288;
    public static final int TYPE_CONDITION_AND = 0x100000;
    public static final int TYPE_CONDITION_TERNARY_OPERATOR = 0x200000;
    public static final int TYPE_LOOP = 0x400000;
    public static final int TYPE_LOOP_START = 0x800000;
    public static final int TYPE_LOOP_CONTINUE = 0x1000000;
    public static final int TYPE_LOOP_END = 0x2000000;
    public static final int TYPE_GOTO = 0x4000000;
    public static final int TYPE_INFINITE_GOTO = 0x8000000;
    public static final int TYPE_GOTO_IN_TERNARY_OPERATOR = 0x10000000;
    public static final int TYPE_TERNARY_OPERATOR = 0x20000000;
    public static final int TYPE_JUMP = 0x40000000;

    /** A successor-exit block: single path out (STATEMENTS, GOTO, ...). */
    public static final int GROUP_SINGLE_SUCCESSOR = 876822149;
    /** Synthetic / marker types (JSR, LOOP_START, LOOP_CONTINUE, etc.). */
    public static final int GROUP_SYNTHETIC = 1140900419;
    /** Types that correspond to bytecode blocks (as opposed to synthetic reducer output). */
    public static final int GROUP_CODE = 472178940;
    /** Terminal types: END, RETURN, RETURN_VALUE, THROW, SWITCH_BREAK, LOOP_END, ... */
    public static final int GROUP_END = 1266696506;
    /** Condition-bearing types: CONDITION, CONDITION_OR, CONDITION_AND, CONDITION_TERNARY_OPERATOR. */
    public static final int GROUP_CONDITION = 0x3C0000;

    protected static final String[] TYPE_NAMES = new String[]{
        "DELETED", "START", "END", "STATEMENTS", "THROW", "RETURN", "RETURN_VALUE",
        "SWITCH_DECLARATION", "SWITCH", "SWITCH_BREAK", "TRY_DECLARATION", "TRY",
        "TRY_JSR", "TYPE_TRY_ECLIPSE", "JSR", "RET", "CONDITIONAL_BRANCH", "IF",
        "IF_ELSE", "CONDITION", "CONDITION_OR", "CONDITION_AND",
        "CONDITION_TERNARY_OPERATOR", "LOOP", "LOOP_START", "LOOP_CONTINUE",
        "LOOP_END", "GOTO", "INFINITE_GOTO", "GOTO_IN_TERNARY_OP", "TERNARY_OP", "JUMP"
    };

    protected static final List<ExceptionHandler> EMPTY_EXCEPTION_HANDLERS = new ArrayList<ExceptionHandler>(0);
    protected static final List<SwitchCase> EMPTY_SWITCH_CASES = new ArrayList<SwitchCase>(0);

    public static final BasicBlock SWITCH_BREAK = new ImmutableBasicBlock(TYPE_SWITCH_BREAK);
    public static final BasicBlock LOOP_START = new ImmutableBasicBlock(TYPE_LOOP_START);
    public static final BasicBlock LOOP_CONTINUE = new ImmutableBasicBlock(TYPE_LOOP_CONTINUE);
    public static final BasicBlock LOOP_END = new ImmutableBasicBlock(TYPE_LOOP_END);
    public static final BasicBlock END = new ImmutableBasicBlock(TYPE_END);
    public static final BasicBlock RETURN = new ImmutableBasicBlock(TYPE_RETURN);

    protected ControlFlowGraph controlFlowGraph;
    protected int index;
    protected int type;
    protected int fromOffset;
    protected int toOffset;
    protected BasicBlock next;
    protected BasicBlock branch;
    protected BasicBlock condition;
    protected boolean inverseCondition;
    protected BasicBlock sub1;
    protected BasicBlock sub2;
    protected List<ExceptionHandler> exceptionHandlers = EMPTY_EXCEPTION_HANDLERS;
    protected List<SwitchCase> switchCases = EMPTY_SWITCH_CASES;
    protected HashSet<BasicBlock> predecessors;

    public BasicBlock(ControlFlowGraph controlFlowGraph, int index, BasicBlock original) {
        this(controlFlowGraph, index, original, new HashSet<BasicBlock>());
    }

    public BasicBlock(ControlFlowGraph controlFlowGraph, int index, BasicBlock original, HashSet<BasicBlock> predecessors) {
        this.controlFlowGraph = controlFlowGraph;
        this.index = index;
        this.type = original.type;
        this.fromOffset = original.fromOffset;
        this.toOffset = original.toOffset;
        this.next = original.next;
        this.branch = original.branch;
        this.condition = original.condition;
        this.inverseCondition = original.inverseCondition;
        this.sub1 = original.sub1;
        this.sub2 = original.sub2;
        this.exceptionHandlers = original.exceptionHandlers;
        this.switchCases = original.switchCases;
        this.predecessors = predecessors;
    }

    public BasicBlock(ControlFlowGraph controlFlowGraph, int index, int type,
                      int fromOffset, int toOffset, boolean inverseCondition) {
        this(controlFlowGraph, index, type, fromOffset, toOffset, inverseCondition, new HashSet<BasicBlock>());
    }

    public BasicBlock(ControlFlowGraph controlFlowGraph, int index, int type,
                      int fromOffset, int toOffset, boolean inverseCondition,
                      HashSet<BasicBlock> predecessors) {
        this.controlFlowGraph = controlFlowGraph;
        this.index = index;
        this.type = type;
        this.fromOffset = fromOffset;
        this.toOffset = toOffset;
        this.sub1 = END;
        this.sub2 = END;
        this.condition = END;
        this.branch = END;
        this.next = END;
        this.predecessors = predecessors;
        this.inverseCondition = inverseCondition;
    }

    public ControlFlowGraph getControlFlowGraph() { return controlFlowGraph; }
    public int getIndex() { return index; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public int getFromOffset() { return fromOffset; }
    public void setFromOffset(int fromOffset) { this.fromOffset = fromOffset; }
    public int getToOffset() { return toOffset; }
    public void setToOffset(int toOffset) { this.toOffset = toOffset; }
    public int getFirstLineNumber() {
        return controlFlowGraph.getLineNumber(fromOffset);
    }
    public int getLastLineNumber() {
        return controlFlowGraph.getLineNumber(toOffset - 1);
    }
    public BasicBlock getNext() { return next; }
    public void setNext(BasicBlock next) { this.next = next; }
    public BasicBlock getBranch() { return branch; }
    public void setBranch(BasicBlock branch) { this.branch = branch; }
    public List<ExceptionHandler> getExceptionHandlers() { return exceptionHandlers; }
    public List<SwitchCase> getSwitchCases() { return switchCases; }
    public void setSwitchCases(List<SwitchCase> switchCases) { this.switchCases = switchCases; }
    public BasicBlock getCondition() { return condition; }
    public void setCondition(BasicBlock condition) { this.condition = condition; }
    public BasicBlock getSub1() { return sub1; }
    public void setSub1(BasicBlock sub1) { this.sub1 = sub1; }
    public BasicBlock getSub2() { return sub2; }
    public void setSub2(BasicBlock sub2) { this.sub2 = sub2; }
    public HashSet<BasicBlock> getPredecessors() { return predecessors; }
    public boolean mustInverseCondition() { return inverseCondition; }
    public void setInverseCondition(boolean inverseCondition) { this.inverseCondition = inverseCondition; }

    public boolean contains(BasicBlock basicBlock) {
        if (next == basicBlock) return true;
        if (branch == basicBlock) return true;
        for (int i = 0; i < exceptionHandlers.size(); i++) {
            if (exceptionHandlers.get(i).getBasicBlock() == basicBlock) return true;
        }
        for (int i = 0; i < switchCases.size(); i++) {
            if (switchCases.get(i).getBasicBlock() == basicBlock) return true;
        }
        if (sub1 == basicBlock) return true;
        return sub2 == basicBlock;
    }

    public void replace(BasicBlock old, BasicBlock nevv) {
        if (next == old) next = nevv;
        if (branch == old) branch = nevv;
        for (int i = 0; i < exceptionHandlers.size(); i++) {
            exceptionHandlers.get(i).replace(old, nevv);
        }
        for (int i = 0; i < switchCases.size(); i++) {
            switchCases.get(i).replace(old, nevv);
        }
        if (sub1 == old) sub1 = nevv;
        if (sub2 == old) sub2 = nevv;
        if (predecessors.contains(old)) {
            predecessors.remove(old);
            if (nevv != END) {
                predecessors.add(nevv);
            }
        }
    }

    public void replace(HashSet<BasicBlock> olds, BasicBlock nevv) {
        if (olds.contains(next)) next = nevv;
        if (olds.contains(branch)) branch = nevv;
        for (int i = 0; i < exceptionHandlers.size(); i++) {
            exceptionHandlers.get(i).replace(olds, nevv);
        }
        for (int i = 0; i < switchCases.size(); i++) {
            switchCases.get(i).replace(olds, nevv);
        }
        if (olds.contains(sub1)) sub1 = nevv;
        if (olds.contains(sub2)) sub2 = nevv;
        predecessors.removeAll(olds);
        predecessors.add(nevv);
    }

    public void addExceptionHandler(String internalThrowableName, BasicBlock basicBlock) {
        if (exceptionHandlers == EMPTY_EXCEPTION_HANDLERS) {
            exceptionHandlers = new ArrayList<ExceptionHandler>();
            exceptionHandlers.add(new ExceptionHandler(internalThrowableName, basicBlock));
        } else {
            for (int i = 0; i < exceptionHandlers.size(); i++) {
                ExceptionHandler eh = exceptionHandlers.get(i);
                if (eh.getBasicBlock() == basicBlock) {
                    eh.addInternalThrowableName(internalThrowableName);
                    return;
                }
            }
            exceptionHandlers.add(new ExceptionHandler(internalThrowableName, basicBlock));
        }
    }

    /** Flip the condition's sense (negate it). Only valid on CONDITION* types. */
    public void inverseCondition() {
        switch (type) {
            case TYPE_CONDITION:
            case TYPE_CONDITION_TERNARY_OPERATOR:
            case TYPE_GOTO_IN_TERNARY_OPERATOR:
                inverseCondition ^= true;
                break;
            case TYPE_CONDITION_AND:
                type = TYPE_CONDITION_OR;
                sub1.inverseCondition();
                sub2.inverseCondition();
                break;
            case TYPE_CONDITION_OR:
                type = TYPE_CONDITION_AND;
                sub1.inverseCondition();
                sub2.inverseCondition();
                break;
            default:
                // Ignore silently; caller guarantees appropriate type.
                break;
        }
    }

    /** Bit-AND membership test against a group constant. */
    public boolean matchType(int types) {
        return (this.type & types) != 0;
    }

    public String getTypeName() {
        return TYPE_NAMES[type == 0 ? 0 : Integer.numberOfTrailingZeros(type) + 1];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BasicBlock{index=").append(index)
            .append(", from=").append(fromOffset)
            .append(", to=").append(toOffset)
            .append(", type=").append(getTypeName())
            .append(", inverseCondition=").append(inverseCondition);
        if (!predecessors.isEmpty()) {
            sb.append(", predecessors=[");
            Iterator<BasicBlock> it = predecessors.iterator();
            if (it.hasNext()) {
                sb.append(it.next().getIndex());
                while (it.hasNext()) sb.append(", ").append(it.next().getIndex());
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public int hashCode() { return 378887654 + index; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BasicBlock)) return false;
        return index == ((BasicBlock) other).index;
    }

    /** Immutable singletons for END, LOOP_START, etc. Cannot have predecessors. */
    protected static class ImmutableBasicBlock extends BasicBlock {
        public ImmutableBasicBlock(int type) {
            super(null, -1, type, 0, 0, true, new HashSet<BasicBlock>() {
                @Override
                public boolean add(BasicBlock e) { return false; }
            });
        }

        @Override public int getFirstLineNumber() { return 0; }
        @Override public int getLastLineNumber() { return 0; }
    }
}
