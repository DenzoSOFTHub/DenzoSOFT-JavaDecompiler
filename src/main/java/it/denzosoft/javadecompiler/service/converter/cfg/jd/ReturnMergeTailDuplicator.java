/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: BUG-2026-0065-20260609-3 - Tail-duplicate the shared `*return`
 * merge block of a switch EXPRESSION so each arm keeps its own value.
 *
 * A Java switch-expression desugars each arm to push a value and `goto` a SHARED
 * `ireturn`/`areturn`/... merge block. The JD pipeline decodes every BasicBlock
 * exactly once and seeds the operand stack of the merge block from ONE
 * predecessor's exitStack, so the merge bakes in the first arm's value and the
 * other arms lose theirs (`return var7+var8+var9+var10` printed for every case).
 *
 * Fix: give every predecessor its own private copy of the return block. The
 * per-block decode in JdFlowBuilder then seeds each copy from that arm's own
 * exitStack, so each arm returns its own value.
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ReturnMergeTailDuplicator {

    /** Tail-duplicate every multi-predecessor RETURN_VALUE / RETURN block. */
    public static void duplicate(ControlFlowGraph cfg) {
        if (cfg == null) return;
        // Snapshot the block list: we append clones while iterating.
        List<BasicBlock> snapshot = new ArrayList<BasicBlock>(cfg.getBasicBlocks());
        for (BasicBlock merge : snapshot) {
            int type = merge.getType();
            if (type != BasicBlock.TYPE_RETURN_VALUE && type != BasicBlock.TYPE_RETURN) {
                continue;
            }
            HashSet<BasicBlock> preds = merge.getPredecessors();
            if (preds.size() <= 1) continue;

            // START_CHANGE: BUG-2026-0065-20260609-4 - Only tail-duplicate the
            // switch-EXPRESSION merge, never an ordinary multi-return method.
            //
            // A switch-expression arm desugars to "push value; goto SHARED_RETURN";
            // Pass 5 of ControlFlowGraphMaker coalesces that pair into a single
            // GOTO_IN_TERNARY_OP predecessor (a block that leaves a value on the
            // operand stack and unconditionally jumps to the return). So the merge of
            // a switch-expression has EVERY predecessor of type GOTO_IN_TERNARY_OP (or
            // a plain GOTO that carries a stack value).
            //
            // By contrast an ordinary `if (c) return a; else return b;` reaches its
            // shared `*return` block by FALL-THROUGH from a STATEMENTS / CONDITIONAL
            // block, which the ControlFlowGraphReducer needs to keep intact to form
            // the IF_ELSE. Duplicating that return splits the join the reducer is
            // looking for and the method collapses to nothing. We therefore require
            // ALL predecessors to be value-carrying gotos before duplicating.
            if (!allPredecessorsAreValueGoto(preds)) continue;
            // END_CHANGE: BUG-2026-0065-4

            // Keep the original block bound to ONE predecessor; clone for the rest.
            List<BasicBlock> predList = new ArrayList<BasicBlock>(preds);
            // Leave predList.get(0) on the original `merge`; clone for indices 1..n.
            for (int i = 1; i < predList.size(); i++) {
                BasicBlock pred = predList.get(i);
                BasicBlock clone = cfg.newBasicBlock(merge);
                // Fresh predecessor set: only this one arm reaches the clone.
                HashSet<BasicBlock> clonePreds = clone.getPredecessors();
                clonePreds.clear();
                clonePreds.add(pred);
                // Rewire the predecessor's edge(s) that pointed at `merge` to `clone`.
                pred.replace(merge, clone);
                // `replace` also fixes pred.predecessors membership of merge/clone,
                // but the merge's predecessor set still lists `pred`; drop it.
                preds.remove(pred);
            }
        }
    }

    // START_CHANGE: BUG-2026-0065-20260609-5 - Switch-expression-arm signature test.
    /** True only when EVERY predecessor unconditionally jumps to the merge while
     *  leaving its arm's value on the operand stack: the GOTO_IN_TERNARY_OP form
     *  produced by ControlFlowGraphMaker Pass 5 for switch-expression arms (a plain
     *  GOTO is accepted too for robustness). This excludes ordinary multi-return
     *  methods whose returns are reached by STATEMENTS / CONDITIONAL fall-through. */
    private static boolean allPredecessorsAreValueGoto(HashSet<BasicBlock> preds) {
        for (BasicBlock pred : preds) {
            int t = pred.getType();
            if (t != BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR && t != BasicBlock.TYPE_GOTO) {
                return false;
            }
        }
        return true;
    }
    // END_CHANGE: BUG-2026-0065-5
}
