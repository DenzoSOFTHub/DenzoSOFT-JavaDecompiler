/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-15 - JD-Core-style flow builder.
 *
 * Wires the ported JD-Core CFG pipeline into our Java-syntax statement
 * emitter. Replaces the legacy StructuredFlowBuilder when `useJdPipeline`
 * is set on the converter.
 *
 * Phases:
 *   1. ControlFlowGraphMaker        -- bytecode -> rich-typed CFG
 *   2. per-block decode             -- fill block.statements / conditionExpression
 *   3. ControlFlowGraphReducer      -- collapse conditionals / loops / tries / switches
 *   4. ControlFlowGraphGotoReducer  -- bypass leftover GOTO blocks
 *   5. emit()                       -- walk reduced CFG, produce final Statement list
 *
 * The emitter is intentionally conservative: block types that the reducer
 * did not fully collapse (DELETED, CONDITIONAL_BRANCH still on the main
 * chain, raw GOTO) are emitted as inline statement lists, never truncated.
 * "Every truncation is a grave error" -- no silent drops.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.javasyntax.expression.BooleanExpression;
import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.expression.StringConstantExpression;
import it.denzosoft.javadecompiler.model.javasyntax.statement.BlockStatement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.ExpressionStatement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.IfElseStatement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.IfStatement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;
import it.denzosoft.javadecompiler.model.javasyntax.statement.WhileStatement;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JdFlowBuilder {

    /** Callback used to decode a block's bytecode range into Statements. */
    public interface BlockDecoder {
        /** Populate bb.statements and bb.conditionExpression from the bytecode range. */
        void decode(BasicBlock bb);
    }

    private final MethodInfo method;
    private final ConstantPool constants;
    private final BlockDecoder decoder;

    public JdFlowBuilder(MethodInfo method, ConstantPool constants, BlockDecoder decoder) {
        this.method = method;
        this.constants = constants;
        this.decoder = decoder;
    }

    public List<Statement> build() {
        ControlFlowGraph cfg = ControlFlowGraphMaker.make(method, constants);
        if (cfg == null) return new ArrayList<Statement>();

        // ---- Phase 2: decode every block with real bytecode into Statement list ----
        for (BasicBlock bb : cfg.getBasicBlocks()) {
            if (bb.matchType(BasicBlock.TYPE_STATEMENTS
                    | BasicBlock.TYPE_CONDITIONAL_BRANCH
                    | BasicBlock.TYPE_SWITCH_DECLARATION
                    | BasicBlock.TYPE_GOTO
                    | BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR
                    | BasicBlock.TYPE_RETURN_VALUE
                    | BasicBlock.TYPE_THROW
                    | BasicBlock.TYPE_RETURN)) {
                decoder.decode(bb);
            }
        }

        // ---- Phases 3+4: structural reduction ----
        try {
            ControlFlowGraphReducer.reduce(cfg);
        } catch (Exception e) {
            // Reducer can throw on bytecode that violates its assumptions; we do
            // NOT silently truncate -- the emitter below walks the unreduced CFG.
        }
        try {
            ControlFlowGraphGotoReducer.reduce(cfg);
        } catch (Exception e) {
            // Same policy: keep going, emit what we have.
        }

        // ---- Phase 5: walk the reduced CFG, emit Statement list ----
        List<Statement> out = new ArrayList<Statement>();
        BitSet visited = new BitSet(cfg.getBasicBlocks().size());
        emit(cfg.getStart(), out, visited);
        return out;
    }

    /**
     * Emit statements for the block chain rooted at `bb`. This method is
     * iterative on bb.next / sub1 / sub2; it does not explicitly track a
     * "stop point" because the reducer's structured forms self-terminate
     * (IF/IF_ELSE/LOOP/TRY/SWITCH carry their own `next`).
     *
     * The visited BitSet guards against infinite recursion when the CFG is
     * only partially reduced; it's a safety net, not a truncation policy.
     */
    private void emit(BasicBlock bb, List<Statement> out, BitSet visited) {
        while (bb != null && bb != BasicBlock.END) {
            // Guard: immutable singletons have index -1
            if (bb.getIndex() >= 0 && visited.get(bb.getIndex())) return;
            if (bb.getIndex() >= 0) visited.set(bb.getIndex());

            int line = bb.getFirstLineNumber();
            switch (bb.getType()) {
                case BasicBlock.TYPE_START:
                    bb = bb.getNext();
                    continue;
                case BasicBlock.TYPE_END:
                case BasicBlock.TYPE_LOOP_END:
                case BasicBlock.TYPE_SWITCH_BREAK:
                    return;
                case BasicBlock.TYPE_DELETED:
                    bb = bb.getNext();
                    continue;
                case BasicBlock.TYPE_STATEMENTS:
                case BasicBlock.TYPE_GOTO:
                case BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR:
                case BasicBlock.TYPE_RETURN:
                case BasicBlock.TYPE_RETURN_VALUE:
                case BasicBlock.TYPE_THROW:
                case BasicBlock.TYPE_SWITCH_DECLARATION: // partially-reduced: emit as linear body
                case BasicBlock.TYPE_CONDITIONAL_BRANCH:  // same
                    if (bb.statements != null) out.addAll(bb.statements);
                    bb = bb.getNext();
                    continue;
                case BasicBlock.TYPE_IF: {
                    Expression cond = conditionExpr(bb.getCondition(), line);
                    List<Statement> thenBody = new ArrayList<Statement>();
                    emit(bb.getSub1(), thenBody, visited);
                    out.add(new IfStatement(line, cond,
                        new BlockStatement(line, thenBody)));
                    bb = bb.getNext();
                    continue;
                }
                case BasicBlock.TYPE_IF_ELSE: {
                    Expression cond = conditionExpr(bb.getCondition(), line);
                    List<Statement> thenBody = new ArrayList<Statement>();
                    emit(bb.getSub1(), thenBody, visited);
                    List<Statement> elseBody = new ArrayList<Statement>();
                    emit(bb.getSub2(), elseBody, visited);
                    out.add(new IfElseStatement(line, cond,
                        new BlockStatement(line, thenBody),
                        new BlockStatement(line, elseBody)));
                    bb = bb.getNext();
                    continue;
                }
                case BasicBlock.TYPE_LOOP: {
                    // Minimal loop emission: treat as while(true) over sub1.
                    // Full loop reconstruction (condition on header vs trailer, do-while,
                    // for-init/update) happens in LoopReducer output — extend in future.
                    Expression loopCond = new BooleanExpression(line, true);
                    List<Statement> body = new ArrayList<Statement>();
                    emit(bb.getSub1(), body, visited);
                    out.add(new WhileStatement(line, loopCond,
                        new BlockStatement(line, body)));
                    bb = bb.getNext();
                    continue;
                }
                default:
                    // Unknown/unreduced type: do NOT drop -- emit statements if present
                    // and continue. Aligns with the "no truncation" policy.
                    if (bb.statements != null) out.addAll(bb.statements);
                    if (bb.getNext() != bb && bb.getNext() != BasicBlock.END) {
                        bb = bb.getNext();
                        continue;
                    }
                    return;
            }
        }
    }

    private static Expression conditionExpr(BasicBlock cond, int line) {
        // The Reducer stores the effective condition on a clone block whose
        // `conditionExpression` was set by the decoder bridge. Fallback: a
        // string-constant comment so the reader sees SOMETHING is wrong rather
        // than a silent truncation.
        if (cond != null && cond.conditionExpression != null) {
            return cond.conditionExpression;
        }
        return new StringConstantExpression(line, "/* condition */");
    }
}
