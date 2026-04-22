/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-13 - Ported from JD-Core.
 *
 * Natural-loop descriptor: start header + member blocks + end (exit) block.
 * Produced by ControlFlowGraphLoopReducer.identifyNaturalLoops from the
 * dominator tree.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.HashSet;
import java.util.Iterator;

public class Loop {
    protected BasicBlock start;
    protected HashSet<BasicBlock> members;
    protected BasicBlock end;

    public Loop(BasicBlock start, HashSet<BasicBlock> members, BasicBlock end) {
        this.start = start;
        this.members = members;
        this.end = end;
    }

    public BasicBlock getStart() { return start; }
    public void setStart(BasicBlock start) { this.start = start; }
    public HashSet<BasicBlock> getMembers() { return members; }
    public BasicBlock getEnd() { return end; }
    public void setEnd(BasicBlock end) { this.end = end; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Loop)) return false;
        Loop loop = (Loop) o;
        if (!start.equals(loop.start)) return false;
        if (!members.equals(loop.members)) return false;
        return end == null ? loop.end == null : end.equals(loop.end);
    }

    @Override
    public int hashCode() {
        int result = 258190310 + start.hashCode();
        result = 31 * result + members.hashCode();
        result = 31 * result + (end != null ? end.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Loop{start=").append(start.getIndex()).append(", members=[");
        if (members != null && !members.isEmpty()) {
            Iterator<BasicBlock> it = members.iterator();
            sb.append(it.next().getIndex());
            while (it.hasNext()) sb.append(", ").append(it.next().getIndex());
        }
        sb.append("], end=").append(end == null ? "" : Integer.valueOf(end.getIndex())).append("}");
        return sb.toString();
    }
}
