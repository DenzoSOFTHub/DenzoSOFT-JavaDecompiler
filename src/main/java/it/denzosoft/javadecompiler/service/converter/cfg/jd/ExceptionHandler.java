/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-2 - Ported from JD-Core
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Exception handler edge on a BasicBlock: a typed throwable class name plus the
 * handler's entry block. Multi-catch handlers share one entry block and chain
 * type names via otherInternalThrowableNames.
 */
public class ExceptionHandler {
    protected String internalThrowableName;
    protected List<String> otherInternalThrowableNames;
    protected BasicBlock basicBlock;

    public ExceptionHandler(String internalThrowableName, BasicBlock basicBlock) {
        this.internalThrowableName = internalThrowableName;
        this.basicBlock = basicBlock;
    }

    public String getInternalThrowableName() { return internalThrowableName; }
    public List<String> getOtherInternalThrowableNames() { return otherInternalThrowableNames; }
    public BasicBlock getBasicBlock() { return basicBlock; }
    public void setBasicBlock(BasicBlock basicBlock) { this.basicBlock = basicBlock; }

    public void addInternalThrowableName(String internalThrowableName) {
        if (otherInternalThrowableNames == null) {
            otherInternalThrowableNames = new ArrayList<String>();
        }
        otherInternalThrowableNames.add(internalThrowableName);
    }

    public void replace(BasicBlock old, BasicBlock nevv) {
        if (basicBlock == old) basicBlock = nevv;
    }

    public void replace(HashSet<BasicBlock> olds, BasicBlock nevv) {
        if (olds.contains(basicBlock)) basicBlock = nevv;
    }

    @Override
    public String toString() {
        if (otherInternalThrowableNames == null) {
            return "ExceptionHandler{" + internalThrowableName + " -> " + basicBlock + "}";
        }
        return "ExceptionHandler{" + internalThrowableName + ", " + otherInternalThrowableNames + " -> " + basicBlock + "}";
    }
}
