/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a basic block in the control flow graph.
 * A basic block is a sequence of instructions with no branches except
 * at the entry (first instruction) and exit (last instruction).
 */
public class BasicBlock {
    /** Start PC of this block (inclusive) */
    public final int startPc;
    /** End PC of this block (exclusive - first PC of next block) */
    public int endPc;

    /** Block type */
    public int type;
    public static final int NORMAL = 0;
    public static final int CONDITIONAL = 1;  // ends with if
    public static final int GOTO = 2;         // ends with goto
    public static final int RETURN = 3;       // ends with return
    public static final int THROW = 4;        // ends with throw
    public static final int SWITCH = 5;       // ends with tableswitch/lookupswitch
    public static final int FALL_THROUGH = 6; // falls through to next block

    /** Branch target PC (for conditional/goto) */
    public int branchTargetPc = -1;
    /** Fall-through target PC (for conditional) */
    public int fallThroughPc = -1;

    /** Branch condition opcode (0x99-0xA6, 0xC6-0xC7) */
    public int branchOpcode = -1;

    /** Successor blocks */
    public BasicBlock trueSuccessor;   // branch target (for conditional), or sole successor (for goto)
    public BasicBlock falseSuccessor;  // fall-through (for conditional)

    /** Predecessor blocks */
    public List<BasicBlock> predecessors = new ArrayList<BasicBlock>();

    /** Decoded statements for this block */
    public List<Statement> statements = new ArrayList<Statement>();

    /** Condition expression (for conditional blocks) */
    public Expression condition;

    /** Whether this block has been visited during structured analysis */
    public boolean visited = false;

    /** Structured statement produced from this block (if/while/etc.) */
    public Statement structuredStatement;

    /** Switch targets (for switch blocks) */
    public int[] switchKeys;
    public int[] switchTargets;
    public int switchDefaultTarget = -1;

    /** Line number at start of block */
    public int lineNumber;

    /** Switch selector expression (for switch blocks) */
    public Expression selectorExpression;

    /** Stack expression remaining after block decode (for ternary detection) */
    public Expression stackTopExpression;

    public BasicBlock(int startPc) {
        this.startPc = startPc;
        this.endPc = startPc;
        this.type = NORMAL;
    }

    public boolean isConditional() { return type == CONDITIONAL; }
    public boolean isGoto() { return type == GOTO; }
    public boolean isReturn() { return type == RETURN; }
    public boolean isThrow() { return type == THROW; }
    public boolean isSwitch() { return type == SWITCH; }

    /** Check if this block branches backward (loop back-edge) */
    public boolean isBackwardBranch() {
        return branchTargetPc >= 0 && branchTargetPc <= startPc;
    }

    public String toString() {
        return "BB[pc=" + startPc + "-" + endPc + ", type=" + type +
               (branchTargetPc >= 0 ? ", target=" + branchTargetPc : "") + "]";
    }
}
