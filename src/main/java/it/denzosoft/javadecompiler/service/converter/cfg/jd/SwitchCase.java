/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-3 - Ported from JD-Core
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.HashSet;

/** Single switch arm: integer label + target block; defaultCase flag for the default arm. */
public class SwitchCase {
    protected int value;
    protected int offset;
    protected BasicBlock basicBlock;
    protected boolean defaultCase;

    public SwitchCase(BasicBlock basicBlock) {
        this.offset = basicBlock.getFromOffset();
        this.basicBlock = basicBlock;
        this.defaultCase = true;
    }

    public SwitchCase(int value, BasicBlock basicBlock) {
        this.value = value;
        this.offset = basicBlock.getFromOffset();
        this.basicBlock = basicBlock;
        this.defaultCase = false;
    }

    public int getValue() { return value; }
    public int getOffset() { return offset; }
    public BasicBlock getBasicBlock() { return basicBlock; }
    public void setBasicBlock(BasicBlock basicBlock) { this.basicBlock = basicBlock; }
    public boolean isDefaultCase() { return defaultCase; }

    public void replace(BasicBlock old, BasicBlock nevv) {
        if (basicBlock == old) basicBlock = nevv;
    }

    public void replace(HashSet<BasicBlock> olds, BasicBlock nevv) {
        if (olds.contains(basicBlock)) basicBlock = nevv;
    }

    @Override
    public String toString() {
        if (defaultCase) return "SwitchCase{default: " + basicBlock + "}";
        return "SwitchCase{'" + value + "': " + basicBlock + "}";
    }
}
