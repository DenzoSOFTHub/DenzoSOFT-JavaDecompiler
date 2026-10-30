/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;

import java.util.*;

/**
 * Guaranteed structuring of a method body (BUG-2026-0152).
 *
 * <p>The pattern-matching {@link StructuredFlowBuilder} produces natural Java but, on shapes it
 * does not recognize, it can drop blocks, misplace a merge point or leave a {@code break} with no
 * target. This class is the fallback the converter uses when the {@link ReachabilityChecker}
 * proves such a defect. It never guesses: it follows the algorithm of N. Ramsey, "Beyond
 * Relooper: recursive translation of unstructured control flow to structured control flow"
 * (ICFP 2022), which maps any reducible control-flow graph onto nested blocks and loops with
 * explicit exits. Java has exactly those constructs: a labeled block {@code L: { ... break L; }}
 * and a labeled {@code L: while (true) { ... continue L; }}.</p>
 *
 * <ol>
 *   <li>Every reachable basic block is decoded with an explicit entry stack.</li>
 *   <li>Value-carrying diamonds (conditional expressions and materialized booleans, whose result
 *       crosses a block boundary on the operand stack) are folded into one
 *       {@link TernaryExpression}, so no edge of the remaining graph carries a stack value.</li>
 *   <li>Exception-table regions become composite nodes, innermost first: the protected blocks
 *       form the {@code try} body, the blocks dominated by each handler its {@code catch}; a
 *       monitor region becomes {@code synchronized}. Every exit of a composite node is a
 *       labeled {@code break}/{@code continue}, so code after the region is never protected.</li>
 *   <li>The graph is emitted along the dominator tree: a node with several forward predecessors
 *       is placed after a labeled block that its predecessors leave with {@code break}; a loop
 *       header is wrapped in {@code while (true)} entered again with {@code continue}.</li>
 * </ol>
 *
 * <p>The output is semantically exact but deliberately plain. Anything the algorithm does not
 * fully understand (an irreducible graph, a stack value it cannot fold, a non-nested exception
 * region) raises {@link Failure}, and the caller keeps its previous result.</p>
 */
public final class SafeStructurer {

    /** Raised for any shape this structurer declines. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public Failure(String message) { super(message); }
    }

    private static final boolean DEBUG = System.getenv("DENZO_SAFE_DEBUG") != null;

    private static final int K_GOTO = 0;
    private static final int K_COND = 1;
    private static final int K_SWITCH = 2;
    private static final int K_EXIT = 3;
    private static final int K_TRY = 4;

    private static final class Handler {
        String type;          // internal name, null = any
        Node entry;
        Set<Node> nodes;
    }

    private final class Node {
        final int id;
        int pc;
        int line;
        List<Statement> stmts = new ArrayList<Statement>();
        int kind;
        Expression cond;               // K_COND: true -> succs[0], false -> succs[1]
        Expression selector;           // K_SWITCH
        List<List<Integer>> caseKeys;  // K_SWITCH: per group (default group last, keys empty)
        List<Node> succs = new ArrayList<Node>();
        List<Node> preds = new ArrayList<Node>();
        // K_TRY
        Set<Node> body;
        Node bodyEntry;
        List<Handler> handlers;
        Expression lock;               // non-null: synchronized region
        Node(int id) { this.id = id; }
        @Override public String toString() { return "N" + pc; }
    }

    /** Placeholder for an edge leaving the region being structured. */
    private static final class JumpMarker implements Statement {
        final Node target;
        final int line;
        JumpMarker(Node target, int line) { this.target = target; this.line = line; }
        public int getLineNumber() { return line; }
        public void accept(StatementVisitor visitor) { }
    }

    private final ControlFlowGraph cfg;
    private final CodeAttribute.ExceptionEntry[] table;
    private final StructuredFlowBuilder.BytecodeDecoder decoder;
    private final ConstantPool pool;
    private int nextId;
    private int catchCounter;

    private final byte[] bytecode;

    public SafeStructurer(ControlFlowGraph cfg, byte[] bytecode, CodeAttribute.ExceptionEntry[] table,
                          StructuredFlowBuilder.BytecodeDecoder decoder, ConstantPool pool) {
        this.cfg = cfg;
        this.bytecode = bytecode;
        this.table = table != null ? table : new CodeAttribute.ExceptionEntry[0];
        this.decoder = decoder;
        this.pool = pool;
    }

    // =================================================================== entry point

    public List<Statement> build() throws Failure {
        List<BasicBlock> blocks = cfg.getBlocks();
        if (blocks.isEmpty()) return new ArrayList<Statement>();
        List<CodeAttribute.ExceptionEntry> entries = usableEntries();
        Set<BasicBlock> reachable = reachable(blocks, entries);
        decodeAll(blocks, reachable);
        foldValues(blocks, reachable);
        spillAcrossRegions(blocks, reachable, entries);
        Map<BasicBlock, Node> nodeOf = buildNodes(blocks, reachable);
        Node entry = nodeOf.get(cfg.getEntryBlock());
        if (entry == null) throw new Failure("no entry");
        methodEntry = entry;
        Set<Node> all = new LinkedHashSet<Node>(nodeOf.values());
        buildTryRegions(entries, nodeOf, all);
        entry = find(rep, entry);
        Set<Node> top = reach(entry, all);
        List<Statement> out = structure(entry, top);
        for (Statement s : out) {
            if (containsMarker(s)) throw new Failure("unresolved exit");
        }
        return simplify(out);
    }

    // =================================================================== exception table

    private List<CodeAttribute.ExceptionEntry> usableEntries() {
        List<CodeAttribute.ExceptionEntry> out = new ArrayList<CodeAttribute.ExceptionEntry>();
        for (CodeAttribute.ExceptionEntry e : table) {
            // javac protects a finally/monitor handler with itself; that part of the range is not
            // a source try, but the part before the handler is still protected code
            if (e.handlerPc >= e.startPc && e.handlerPc < e.endPc) {
                if (e.handlerPc > e.startPc) {
                    out.add(new CodeAttribute.ExceptionEntry(e.startPc, e.handlerPc, e.handlerPc, e.catchType));
                }
                continue;
            }
            if (e.startPc >= e.endPc) continue;
            out.add(e);
        }
        return out;
    }

    private static List<BasicBlock> succBlocks(BasicBlock b, ControlFlowGraph cfg) {
        List<BasicBlock> out = new ArrayList<BasicBlock>(2);
        if (b.type == BasicBlock.SWITCH) {
            if (b.switchTargets != null) {
                for (int t : b.switchTargets) {
                    BasicBlock x = cfg.getBlockAtPc(t);
                    if (x != null && !out.contains(x)) out.add(x);
                }
            }
            BasicBlock d = cfg.getBlockAtPc(b.switchDefaultTarget);
            if (d != null && !out.contains(d)) out.add(d);
            return out;
        }
        if (b.type == BasicBlock.RETURN || b.type == BasicBlock.THROW) return out;
        if (b.type == BasicBlock.CONDITIONAL) {
            if (b.falseSuccessor != null) out.add(b.falseSuccessor);
            if (b.trueSuccessor != null && !out.contains(b.trueSuccessor)) out.add(b.trueSuccessor);
            return out;
        }
        if (b.trueSuccessor != null) out.add(b.trueSuccessor);
        return out;
    }

    private Set<BasicBlock> reachable(List<BasicBlock> blocks, List<CodeAttribute.ExceptionEntry> entries) {
        Set<BasicBlock> seen = new HashSet<BasicBlock>();
        Deque<BasicBlock> work = new ArrayDeque<BasicBlock>();
        work.add(cfg.getEntryBlock());
        seen.add(cfg.getEntryBlock());
        boolean grew = true;
        while (grew) {
            while (!work.isEmpty()) {
                BasicBlock b = work.poll();
                for (BasicBlock s : succBlocks(b, cfg)) {
                    if (seen.add(s)) work.add(s);
                }
            }
            grew = false;
            for (CodeAttribute.ExceptionEntry e : entries) {
                BasicBlock h = cfg.getBlockAtPc(e.handlerPc);
                if (h == null || seen.contains(h)) continue;
                for (BasicBlock b : seen) {
                    if (b.startPc >= e.startPc && b.startPc < e.endPc) {
                        seen.add(h);
                        work.add(h);
                        grew = true;
                        break;
                    }
                }
            }
        }
        return seen;
    }

    // =================================================================== decoding

    private final Map<BasicBlock, List<BasicBlock>> preds = new HashMap<BasicBlock, List<BasicBlock>>();
    private final Set<BasicBlock> decoded = new HashSet<BasicBlock>();
    private final Set<BasicBlock> removed = new HashSet<BasicBlock>();

    private void decodeAll(List<BasicBlock> blocks, Set<BasicBlock> reachable) throws Failure {
        for (BasicBlock b : blocks) {
            if (!reachable.contains(b)) continue;
            for (BasicBlock s : succBlocks(b, cfg)) {
                List<BasicBlock> l = preds.get(s);
                if (l == null) { l = new ArrayList<BasicBlock>(); preds.put(s, l); }
                l.add(b);
            }
        }
        List<BasicBlock> order = new ArrayList<BasicBlock>();
        for (BasicBlock b : blocks) if (reachable.contains(b)) order.add(b);
        int remaining = order.size();
        while (remaining > 0) {
            BasicBlock pick = null;
            for (BasicBlock b : order) {
                if (decoded.contains(b)) continue;
                if (b == cfg.getEntryBlock() || b.isExceptionHandler || hasDecodedPred(b)) { pick = b; break; }
            }
            if (pick == null) {
                for (BasicBlock b : order) {
                    if (!decoded.contains(b)) { pick = b; break; }
                }
            }
            decodeWithSeed(pick, seedFor(pick));
            remaining--;
        }
        // every edge must agree on the stack depth
        for (BasicBlock b : order) {
            int d = b.exitStack == null ? 0 : b.exitStack.size();
            for (BasicBlock s : succBlocks(b, cfg)) {
                if (s.isExceptionHandler) throw new Failure("normal edge into handler");
                int sd = s.forcedEntryStack == null ? 0 : s.forcedEntryStack.size();
                if (sd != d) throw new Failure("stack depth mismatch at pc " + s.startPc);
            }
        }
    }

    private boolean hasDecodedPred(BasicBlock b) {
        List<BasicBlock> l = preds.get(b);
        if (l == null) return false;
        for (BasicBlock p : l) if (decoded.contains(p)) return true;
        return false;
    }

    private List<Expression> seedFor(BasicBlock b) {
        if (b.isExceptionHandler) return null;
        List<BasicBlock> l = preds.get(b);
        if (l != null) {
            for (BasicBlock p : l) {
                if (decoded.contains(p) && !removed.contains(p)) {
                    return p.exitStack == null ? new ArrayList<Expression>() : new ArrayList<Expression>(p.exitStack);
                }
            }
        }
        return new ArrayList<Expression>();
    }

    private void decodeWithSeed(BasicBlock b, List<Expression> seed) {
        b.forcedEntryStack = b.isExceptionHandler ? null : (seed != null ? seed : new ArrayList<Expression>());
        b.exitStack = null;
        b.condition = null;
        b.selectorExpression = null;
        b.stackTopExpression = null;
        decoder.decodeBlock(b);
        if (b.isExceptionHandler) {
            // the handler seeds itself with $exception; record an empty explicit seed for checks
            b.forcedEntryStack = new ArrayList<Expression>();
        }
        decoded.add(b);
    }

    // =================================================================== value folding

    private void foldValues(List<BasicBlock> blocks, Set<BasicBlock> reachable) throws Failure {
        boolean changed = true;
        int guard = 0;
        while (changed) {
            if (++guard > 10000) throw new Failure("fold does not converge");
            changed = false;
            for (BasicBlock m : blocks) {
                if (!reachable.contains(m) || removed.contains(m)) continue;
                if (m.forcedEntryStack == null || m.forcedEntryStack.isEmpty()) continue;
                List<BasicBlock> mp = livePreds(m);
                if (mp.size() < 2) continue;
                if (sameExitStacks(mp)) continue; // a shared prefix only (e.g. a receiver)
                foldAt(m, mp);
                changed = true;
                break;
            }
        }
        for (BasicBlock b : blocks) {
            if (!reachable.contains(b) || removed.contains(b)) continue;
            if (b.exitStack != null && !b.exitStack.isEmpty() && !succBlocks(b, cfg).isEmpty()) {
                // a value still crosses an edge (e.g. a single-predecessor carry): only legal when
                // the successor is the sole consumer and was seeded with exactly these objects
                for (BasicBlock s : succBlocks(b, cfg)) {
                    if (livePreds(s).size() != 1 && !sameExitStacks(livePreds(s))) {
                        throw new Failure("unfolded stack value at pc " + b.startPc);
                    }
                }
            }
        }
    }

    private static boolean sameExitStacks(List<BasicBlock> bs) {
        List<Expression> first = bs.get(0).exitStack;
        for (int i = 1; i < bs.size(); i++) {
            if (!sameStack(first, bs.get(i).exitStack)) return false;
        }
        return true;
    }

    private List<BasicBlock> livePreds(BasicBlock b) {
        List<BasicBlock> out = new ArrayList<BasicBlock>();
        List<BasicBlock> l = preds.get(b);
        if (l == null) return out;
        for (BasicBlock p : l) if (!removed.contains(p) && !out.contains(p)) out.add(p);
        return out;
    }

    private void foldAt(BasicBlock m, List<BasicBlock> mp) throws Failure {
        // The condition block C is the nearest block from which every predecessor of M is
        // reached through value-only blocks. Walk up from the lowest-pc predecessor.
        BasicBlock c = findFoldHead(m, mp);
        if (c == null) throw new Failure("no fold head for merge at pc " + m.startPc);
        List<Expression> base = c.exitStack == null ? new ArrayList<Expression>() : c.exitStack;
        Set<BasicBlock> region = new HashSet<BasicBlock>();
        Expression t = arm(c.falseSuccessor, m, base, region, 0);
        Expression f = arm(c.trueSuccessor, m, base, region, 0);
        if (c.condition == null) throw new Failure("fold head without condition");
        Type ty = t.getType() != null ? t.getType() : f.getType();
        Expression value = new TernaryExpression(c.lineNumber, ty, c.condition, t, f);
        // every predecessor of M must be inside the folded region
        for (BasicBlock p : mp) {
            if (!region.contains(p) && p != c) throw new Failure("merge at pc " + m.startPc + " reached from outside its diamond");
        }
        for (BasicBlock r : region) removed.add(r);
        // C now flows straight into M
        c.type = BasicBlock.GOTO;
        c.condition = null;
        c.trueSuccessor = m;
        c.falseSuccessor = null;
        List<BasicBlock> mpl = preds.get(m);
        mpl.clear();
        mpl.add(c);
        List<Expression> seed = new ArrayList<Expression>(base);
        seed.add(value);
        c.exitStack = new ArrayList<Expression>(seed);
        redecodeFrom(m, seed);
    }

    private BasicBlock findFoldHead(BasicBlock m, List<BasicBlock> mp) {
        // candidate heads: conditional ancestors of the predecessors; take the one whose two arms
        // both reach M through value-only blocks, preferring the nearest (highest pc)
        Set<BasicBlock> cand = new LinkedHashSet<BasicBlock>();
        Deque<BasicBlock> work = new ArrayDeque<BasicBlock>(mp);
        Set<BasicBlock> seen = new HashSet<BasicBlock>(mp);
        int steps = 0;
        while (!work.isEmpty() && steps++ < 2000) {
            BasicBlock b = work.poll();
            if (b.type == BasicBlock.CONDITIONAL) cand.add(b);
            for (BasicBlock p : livePreds(b)) {
                if (seen.add(p)) work.add(p);
            }
        }
        BasicBlock best = null;
        for (BasicBlock c : cand) {
            if (c.type != BasicBlock.CONDITIONAL || c.condition == null) continue;
            List<Expression> base = c.exitStack == null ? new ArrayList<Expression>() : c.exitStack;
            Set<BasicBlock> region = new HashSet<BasicBlock>();
            try {
                arm(c.falseSuccessor, m, base, region, 0);
                arm(c.trueSuccessor, m, base, region, 0);
            } catch (Failure f) {
                if (DEBUG) System.err.println("  fold cand pc " + c.startPc + " for merge " + m.startPc + ": " + f.getMessage());
                continue;
            }
            boolean covers = true;
            for (BasicBlock p : mp) {
                if (!region.contains(p) && p != c) { covers = false; break; }
            }
            // closed region: an arm block shared with code outside the diamond cannot be folded
            for (BasicBlock rb : region) {
                if (!covers) break;
                for (BasicBlock p : livePreds(rb)) {
                    if (p != c && !region.contains(p)) { covers = false; break; }
                }
            }
            if (!covers) continue;
            if (best == null || c.startPc > best.startPc) best = c;
        }
        return best;
    }

    /** Value produced by the arm starting at b, ending at merge m, above the base stack. */
    private Expression arm(BasicBlock b, BasicBlock m, List<Expression> base, Set<BasicBlock> region, int depth)
            throws Failure {
        if (b == null || b == m || depth > 64) throw new Failure("bad arm");
        if (removed.contains(b)) throw new Failure("arm already folded");
        // START_CHANGE: BUG-2026-0152-20261017-8 - `dup; putstatic f` in an arm: the arm's value is
        // the assignment itself (`f == null ? (f = load()) : f`).
        Expression assignedValue = null;
        if (b.statements != null && b.statements.size() == 1 && b.type != BasicBlock.CONDITIONAL
                && b.exitStack != null && !b.exitStack.isEmpty()) {
            Statement only = b.statements.get(0);
            Expression top = b.exitStack.get(b.exitStack.size() - 1);
            if (only instanceof ExpressionStatement && ((ExpressionStatement) only).getExpression() instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) only).getExpression();
                if ("=".equals(ae.getOperator()) && ae.getRight() == top) assignedValue = ae;
            }
        }
        if (assignedValue == null && b.statements != null && !b.statements.isEmpty()) throw new Failure("arm has statements");
        // END_CHANGE: BUG-2026-0152-8
        if (b.type == BasicBlock.CONDITIONAL) {
            if (!sameStack(b.exitStack, base) || b.condition == null) throw new Failure("conditional arm stack");
            region.add(b);
            Expression t = arm(b.falseSuccessor, m, base, region, depth + 1);
            Expression f = arm(b.trueSuccessor, m, base, region, depth + 1);
            Type ty = t.getType() != null ? t.getType() : f.getType();
            return new TernaryExpression(b.lineNumber, ty, b.condition, t, f);
        }
        if (b.type != BasicBlock.GOTO && b.type != BasicBlock.FALL_THROUGH && b.type != BasicBlock.NORMAL) {
            throw new Failure("arm kind");
        }
        if (b.trueSuccessor != m && sameStack(b.exitStack, base) && b.trueSuccessor != null) {
            // an empty `goto` hop inside the arm (javac's `goto L` between short-circuit tests)
            region.add(b);
            return arm(b.trueSuccessor, m, base, region, depth + 1);
        }
        if (b.trueSuccessor != m) throw new Failure("arm does not reach merge");
        List<Expression> ex = b.exitStack;
        if (ex == null || ex.size() != base.size() + 1) throw new Failure("arm stack size");
        for (int i = 0; i < base.size(); i++) {
            if (ex.get(i) != base.get(i)) throw new Failure("arm stack prefix");
        }
        region.add(b);
        return assignedValue != null ? assignedValue : ex.get(ex.size() - 1);
    }

    private static boolean sameStack(List<Expression> a, List<Expression> b) {
        int as = a == null ? 0 : a.size();
        int bs = b == null ? 0 : b.size();
        if (as != bs) return false;
        for (int i = 0; i < as; i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private void redecodeFrom(BasicBlock m, List<Expression> seed) {
        Deque<BasicBlock> work = new ArrayDeque<BasicBlock>();
        Map<BasicBlock, List<Expression>> seeds = new HashMap<BasicBlock, List<Expression>>();
        work.add(m);
        seeds.put(m, seed);
        int guard = 0;
        while (!work.isEmpty() && guard++ < 100000) {
            BasicBlock b = work.poll();
            decodeWithSeed(b, seeds.get(b));
            if (b.exitStack == null || b.exitStack.isEmpty()) continue;
            for (BasicBlock s : succBlocks(b, cfg)) {
                if (removed.contains(s)) continue;
                if (s.startPc <= b.startPc) continue; // never re-seed a loop header
                seeds.put(s, new ArrayList<Expression>(b.exitStack));
                if (!work.contains(s)) work.add(s);
            }
        }
    }

    // =================================================================== region-crossing values

    /**
     * A value computed inside a protected region and consumed after it (javac's
     * {@code synchronized (l) { return this.x; }} loads x, unlocks, then returns) must still be
     * evaluated inside: it is stored into a fresh local at the end of the producing block and
     * the consumer reads the local.
     */
    private void spillAcrossRegions(List<BasicBlock> blocks, Set<BasicBlock> reachable,
                                    List<CodeAttribute.ExceptionEntry> entries) throws Failure {
        int counter = 0;
        for (BasicBlock b : blocks) {
            if (!reachable.contains(b) || removed.contains(b)) continue;
            if (b.exitStack == null || b.exitStack.isEmpty()) continue;
            for (BasicBlock s : succBlocks(b, cfg)) {
                if (removed.contains(s)) continue;
                if (coverage(b, entries).equals(coverage(s, entries))) continue;
                if (livePreds(s).size() != 1) throw new Failure("stack value into a shared block across a region");
                List<Expression> seed = new ArrayList<Expression>();
                for (Expression v : b.exitStack) {
                    if (isPure(v)) {
                        seed.add(v);
                        continue;
                    }
                    Type t = v.getType() != null && !(v.getType() instanceof VoidType) ? v.getType() : ObjectType.OBJECT;
                    String name = "stk$" + (counter++);
                    LocalVariableExpression tmp = new LocalVariableExpression(v.getLineNumber(), t, name, -1);
                    b.statements.add(new ExpressionStatement(new AssignmentExpression(v.getLineNumber(), t, tmp, "=", v)));
                    seed.add(tmp);
                }
                b.exitStack = new ArrayList<Expression>(seed);
                redecodeFrom(s, seed);
            }
        }
    }

    private static Set<Integer> coverage(BasicBlock b, List<CodeAttribute.ExceptionEntry> entries) {
        Set<Integer> out = new HashSet<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            CodeAttribute.ExceptionEntry e = entries.get(i);
            if (b.startPc >= e.startPc && b.startPc < e.endPc) out.add(Integer.valueOf(e.handlerPc));
        }
        return out;
    }

    private static boolean isPure(Expression v) {
        return v instanceof IntegerConstantExpression || v instanceof LongConstantExpression
            || v instanceof FloatConstantExpression || v instanceof DoubleConstantExpression
            || v instanceof StringConstantExpression || v instanceof BooleanExpression
            || v instanceof NullExpression || v instanceof ThisExpression || v instanceof ClassExpression;
    }

    // =================================================================== node graph

    private Map<BasicBlock, Node> buildNodes(List<BasicBlock> blocks, Set<BasicBlock> reachable) throws Failure {
        Map<BasicBlock, Node> map = new LinkedHashMap<BasicBlock, Node>();
        for (BasicBlock b : blocks) {
            if (!reachable.contains(b) || removed.contains(b)) continue;
            Node n = new Node(nextId++);
            n.pc = b.startPc;
            n.line = b.lineNumber;
            if (b.statements != null) n.stmts.addAll(b.statements);
            map.put(b, n);
        }
        for (Map.Entry<BasicBlock, Node> en : map.entrySet()) {
            BasicBlock b = en.getKey();
            Node n = en.getValue();
            if (b.type == BasicBlock.CONDITIONAL) {
                if (b.condition == null) throw new Failure("missing condition at pc " + b.startPc);
                n.kind = K_COND;
                n.cond = b.condition;
                n.succs.add(target(map, b.falseSuccessor));
                n.succs.add(target(map, b.trueSuccessor));
            } else if (b.type == BasicBlock.SWITCH) {
                if (b.selectorExpression == null) throw new Failure("missing selector at pc " + b.startPc);
                n.kind = K_SWITCH;
                n.selector = b.selectorExpression;
                n.caseKeys = new ArrayList<List<Integer>>();
                Map<Integer, Integer> groupOfTarget = new LinkedHashMap<Integer, Integer>();
                for (int i = 0; i < b.switchKeys.length; i++) {
                    int tgt = b.switchTargets[i];
                    if (tgt == b.switchDefaultTarget) continue; // folded into default
                    Integer g = groupOfTarget.get(Integer.valueOf(tgt));
                    if (g == null) {
                        g = Integer.valueOf(n.succs.size());
                        groupOfTarget.put(Integer.valueOf(tgt), g);
                        n.succs.add(target(map, cfg.getBlockAtPc(tgt)));
                        n.caseKeys.add(new ArrayList<Integer>());
                    }
                    n.caseKeys.get(g.intValue()).add(Integer.valueOf(b.switchKeys[i]));
                }
                n.succs.add(target(map, cfg.getBlockAtPc(b.switchDefaultTarget)));
                n.caseKeys.add(new ArrayList<Integer>());
            } else if (b.type == BasicBlock.RETURN || b.type == BasicBlock.THROW) {
                n.kind = K_EXIT;
            } else {
                if (b.trueSuccessor == null) {
                    if (!endsAbruptly(n.stmts)) throw new Failure("falls off the code at pc " + b.startPc);
                    n.kind = K_EXIT;
                } else {
                    n.kind = K_GOTO;
                    n.succs.add(target(map, b.trueSuccessor));
                }
            }
        }
        for (Node n : map.values()) {
            for (Node s : n.succs) s.preds.add(n);
        }
        return map;
    }

    private static Node target(Map<BasicBlock, Node> map, BasicBlock b) throws Failure {
        Node n = b == null ? null : map.get(b);
        if (n == null) throw new Failure("edge to unknown block " + (b == null ? "null" : "pc " + b.startPc));
        return n;
    }

    private static boolean endsAbruptly(List<Statement> st) {
        if (st.isEmpty()) return false;
        Statement last = st.get(st.size() - 1);
        return last instanceof ReturnStatement || last instanceof ThrowStatement;
    }

    // =================================================================== exception regions

    private void buildTryRegions(List<CodeAttribute.ExceptionEntry> entries, Map<BasicBlock, Node> nodeOf,
                                 Set<Node> all) throws Failure {
        if (entries.isEmpty()) return;
        // handler -> entries, in table order
        LinkedHashMap<Integer, List<CodeAttribute.ExceptionEntry>> byHandler =
            new LinkedHashMap<Integer, List<CodeAttribute.ExceptionEntry>>();
        for (CodeAttribute.ExceptionEntry e : entries) {
            List<CodeAttribute.ExceptionEntry> l = byHandler.get(Integer.valueOf(e.handlerPc));
            if (l == null) { l = new ArrayList<CodeAttribute.ExceptionEntry>(); byHandler.put(Integer.valueOf(e.handlerPc), l); }
            l.add(e);
        }
        // protected node set per handler (original nodes)
        List<Integer> handlers = new ArrayList<Integer>(byHandler.keySet());
        Map<Integer, Set<Node>> prot = new HashMap<Integer, Set<Node>>();
        for (Integer h : handlers) {
            Set<Node> p = new LinkedHashSet<Node>();
            for (CodeAttribute.ExceptionEntry e : byHandler.get(h)) {
                for (Node n : all) {
                    if (n.pc >= e.startPc && n.pc < e.endPc) p.add(n);
                }
            }
            if (p.isEmpty()) continue;
            prot.put(h, p);
        }
        // group handlers with the same protected set (one try, several catch clauses)
        List<List<Integer>> groups = new ArrayList<List<Integer>>();
        for (Integer h : handlers) {
            if (!prot.containsKey(h)) continue;
            List<Integer> g = null;
            for (List<Integer> gg : groups) {
                if (prot.get(gg.get(0)).equals(prot.get(h))) { g = gg; break; }
            }
            if (g == null) { g = new ArrayList<Integer>(); groups.add(g); }
            g.add(h);
        }
        // innermost first: a nested try (also one inside a catch or finally handler) always
        // spans fewer bytecode offsets than the region that contains it
        final Map<List<Integer>, Integer> size = new IdentityHashMap<List<Integer>, Integer>();
        final Map<List<Integer>, Integer> startOf = new IdentityHashMap<List<Integer>, Integer>();
        for (List<Integer> g : groups) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (Integer h : g) {
                for (CodeAttribute.ExceptionEntry e : byHandler.get(h)) {
                    lo = Math.min(lo, e.startPc);
                    hi = Math.max(hi, e.endPc);
                }
            }
            size.put(g, Integer.valueOf(hi - lo));
            startOf.put(g, Integer.valueOf(lo));
        }
        Collections.sort(groups, new Comparator<List<Integer>>() {
            public int compare(List<Integer> a, List<Integer> b) {
                int d = size.get(a).intValue() - size.get(b).intValue();
                if (d != 0) return d;
                // equal spans: the later one can sit in the earlier one's handler, never the reverse
                return startOf.get(b).intValue() - startOf.get(a).intValue();
            }
        });
        Map<BasicBlock, Node> handlerNode = new HashMap<BasicBlock, Node>();
        for (Integer h : handlers) {
            BasicBlock hb = cfg.getBlockAtPc(h.intValue());
            Node hn = hb == null ? null : nodeOf.get(hb);
            if (hn != null) handlerNode.put(hb, hn);
        }
        for (List<Integer> g : groups) {
            Set<Node> p = new LinkedHashSet<Node>();
            for (Node n : prot.get(g.get(0))) p.add(find(rep, n));
            List<Node> hentries = new ArrayList<Node>();
            for (Integer h : g) {
                Node hn = handlerNode.get(cfg.getBlockAtPc(h.intValue()));
                if (hn == null) throw new Failure("handler node missing");
                hn = find(rep, hn);
                if (p.contains(hn)) throw new Failure("handler inside its own region");
                hentries.add(hn);
            }
            // single entry into the protected set
            Node entry = null;
            for (Node n : p) {
                for (Node pr : n.preds) {
                    if (!p.contains(pr)) {
                        if (entry != null && entry != n) throw new Failure("try region with two entries");
                        entry = n;
                    }
                }
            }
            if (entry == null) {
                for (Node n : p) if (entry == null || n.pc < entry.pc) entry = n;
            }
            // handler regions: nodes dominated by the handler in the graph with exception edges
            Map<Node, Set<Node>> regions = handlerRegions(all, hentries, p, groups, prot, rep, handlerNode);
            Node comp = new Node(nextId++);
            comp.kind = K_TRY;
            comp.pc = entry.pc;
            comp.line = entry.line;
            comp.body = p;
            comp.bodyEntry = entry;
            comp.handlers = new ArrayList<Handler>();
            Set<Node> inside = new HashSet<Node>(p);
            for (int i = 0; i < g.size(); i++) {
                Handler hd = new Handler();
                hd.entry = hentries.get(i);
                hd.nodes = regions.get(hd.entry);
                for (Node x : hd.nodes) {
                    if (inside.contains(x)) throw new Failure("handler region overlaps try body");
                }
                inside.addAll(hd.nodes);
                CodeAttribute.ExceptionEntry ex = byHandler.get(g.get(i)).get(0);
                hd.type = ex.catchType == 0 ? null : pool.getClassName(ex.catchType);
                comp.handlers.add(hd);
            }
            // no edge from outside into the composite except to the try entry
            for (Node x : inside) {
                if (x == entry) continue;
                boolean isHandlerEntry = false;
                for (Handler hd : comp.handlers) if (hd.entry == x) isHandlerEntry = true;
                for (Node pr : x.preds) {
                    if (!inside.contains(pr)) {
                        throw new Failure("edge into the middle of a try region at pc " + x.pc);
                    }
                }
                if (isHandlerEntry && !x.preds.isEmpty()) throw new Failure("normal edge into a handler");
            }
            detectMonitor(comp);
            // rewire the graph
            boolean selfLoop = false;
            for (Node x : inside) {
                for (Node s : x.succs) {
                    if (!inside.contains(s) && !comp.succs.contains(s)) comp.succs.add(s);
                    // a handler that jumps back to the start of its try is a retry loop
                    if (s == entry && !p.contains(x)) selfLoop = true;
                    else if (inside.contains(s) && !p.contains(x) && p.contains(s)) {
                        throw new Failure("handler jumps into the middle of its try");
                    }
                }
            }
            if (selfLoop) {
                comp.succs.add(comp);
                comp.preds.add(comp);
            }
            for (Node pr : new ArrayList<Node>(entry.preds)) {
                if (inside.contains(pr)) continue;
                replaceSucc(pr, entry, comp);
                comp.preds.add(pr);
            }
            for (Node s : comp.succs) {
                List<Node> np = new ArrayList<Node>();
                for (Node pr : s.preds) {
                    if (inside.contains(pr)) {
                        if (!np.contains(comp)) np.add(comp);
                    } else {
                        np.add(pr);
                    }
                }
                s.preds.clear();
                s.preds.addAll(np);
            }
            all.removeAll(inside);
            all.add(comp);
            for (Node x : inside) rep.put(x, comp);
        }
    }

    private Node methodEntry;
    private final Map<Node, Node> rep = new HashMap<Node, Node>();

    private static Node find(Map<Node, Node> rep, Node n) {
        Node r = n;
        int guard = 0;
        while (rep.containsKey(r) && guard++ < 10000) r = rep.get(r);
        return r;
    }

    private static void replaceSucc(Node pr, Node from, Node to) {
        for (int i = 0; i < pr.succs.size(); i++) {
            if (pr.succs.get(i) == from) pr.succs.set(i, to);
        }
    }

    /** For each handler entry: the nodes it dominates, exception edges taken into account. */
    private Map<Node, Set<Node>> handlerRegions(Set<Node> all, List<Node> hentries, Set<Node> p,
            List<List<Integer>> groups, Map<Integer, Set<Node>> prot, Map<Node, Node> rep,
            Map<BasicBlock, Node> handlerNode) throws Failure {
        // Region of a handler: everything reachable from it that is not reachable from the
        // method entry without passing through it.
        Node root = find(rep, methodEntry);
        Map<Node, Set<Node>> out = new HashMap<Node, Set<Node>>();
        for (Node h : hentries) {
            Set<Node> fromH = reachWithin(h, all);
            // nodes reachable from the other roots (method entry and all other handlers) avoiding h
            Set<Node> others = new HashSet<Node>();
            List<Node> roots = new ArrayList<Node>();
            if (root != null) roots.add(root);
            for (Node hn : handlerNode.values()) {
                // handlers already folded into a composite are reached through that composite
                if (find(rep, hn) != hn) continue;
                if (hn != h && all.contains(hn)) roots.add(hn);
            }
            Deque<Node> work = new ArrayDeque<Node>();
            for (Node r : roots) if (r != h && others.add(r)) work.add(r);
            while (!work.isEmpty()) {
                Node x = work.poll();
                for (Node s : x.succs) {
                    if (s == h || !all.contains(s)) continue;
                    if (others.add(s)) work.add(s);
                }
            }
            Set<Node> region = new LinkedHashSet<Node>();
            for (Node x : fromH) if (!others.contains(x)) region.add(x);
            region.add(h);
            out.put(h, region);
        }
        return out;
    }

    private static boolean isHandlerEntryAnywhere(Node n, Map<BasicBlock, Node> handlerNode, Map<Node, Node> rep) {
        for (Node h : handlerNode.values()) if (find(rep, h) == n) return true;
        return false;
    }

    private static Set<Node> reachWithin(Node from, Set<Node> all) {
        Set<Node> seen = new LinkedHashSet<Node>();
        Deque<Node> work = new ArrayDeque<Node>();
        seen.add(from);
        work.add(from);
        while (!work.isEmpty()) {
            Node x = work.poll();
            for (Node s : x.succs) {
                if (all.contains(s) && seen.add(s)) work.add(s);
            }
        }
        return seen;
    }

    private static boolean isMarker(Statement s, String what) {
        if (!(s instanceof ExpressionStatement)) return false;
        Expression e = ((ExpressionStatement) s).getExpression();
        return e instanceof StringConstantExpression
            && ((StringConstantExpression) e).getValue() != null
            && ((StringConstantExpression) e).getValue().contains(what);
    }

    /** A catch-any region whose handler only unlocks and rethrows is a synchronized block. */
    private void detectMonitor(Node comp) {
        if (comp.handlers.size() != 1 || comp.handlers.get(0).type != null) return;
        Handler h = comp.handlers.get(0);
        List<Statement> hs = new ArrayList<Statement>();
        for (Node x : h.nodes) {
            if (x.kind == K_TRY) return;
            hs.addAll(x.stmts);
        }
        int exits = 0;
        for (Statement s : hs) {
            if (isMarker(s, "__MONITOREXIT__")) exits++;
            else if (s instanceof ThrowStatement) continue;
            else if (isSeedStore(s)) continue;
            else return;
        }
        if (exits != 1) return;
        Node entry = comp.bodyEntry;
        Node pre = null;
        int outside = 0;
        for (Node pr : entry.preds) {
            if (!comp.body.contains(pr)) { pre = pr; outside++; }
        }
        if (outside != 1 || pre.kind == K_TRY || pre.stmts.isEmpty()) return;
        Statement last = pre.stmts.get(pre.stmts.size() - 1);
        if (!isMarker(last, "__MONITORENTER__") || pre.stmts.size() < 2) return;
        Expression lock = storedLocal(pre.stmts.get(pre.stmts.size() - 2));
        if (lock == null) return;
        pre.stmts.remove(pre.stmts.size() - 1);
        comp.lock = lock;
        for (Node x : comp.body) {
            if (x.kind == K_TRY) continue;
            for (Iterator<Statement> it = x.stmts.iterator(); it.hasNext();) {
                if (isMarker(it.next(), "__MONITOREXIT__")) it.remove();
            }
        }
    }

    private static boolean isSeedStore(Statement s) {
        Expression rhs = null;
        if (s instanceof VariableDeclarationStatement) rhs = ((VariableDeclarationStatement) s).getInitializer();
        else if (s instanceof ExpressionStatement && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            rhs = ((AssignmentExpression) ((ExpressionStatement) s).getExpression()).getRight();
        }
        return rhs instanceof LocalVariableExpression && "$exception".equals(((LocalVariableExpression) rhs).getName());
    }

    private static Expression storedLocal(Statement s) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            return new LocalVariableExpression(v.getLineNumber(), v.getType(), v.getName(), -1);
        }
        if (s instanceof ExpressionStatement && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            if (a.getLeft() instanceof LocalVariableExpression) return a.getLeft();
        }
        return null;
    }

    // =================================================================== structuring (Ramsey)

    private static final class Region {
        final Node entry;
        final Set<Node> nodes;
        final Map<Node, Integer> rpo = new HashMap<Node, Integer>();
        final List<Node> order = new ArrayList<Node>();
        final Map<Node, Node> idom = new HashMap<Node, Node>();
        final Map<Node, List<Node>> children = new HashMap<Node, List<Node>>();
        final Set<Node> loopHeaders = new HashSet<Node>();
        final Set<Node> mergeNodes = new HashSet<Node>();
        Region(Node entry, Set<Node> nodes) { this.entry = entry; this.nodes = nodes; }
    }

    private static Set<Node> reach(Node entry, Set<Node> all) {
        return reachWithin(entry, all);
    }

    private List<Statement> structure(Node entry, Set<Node> nodes) throws Failure {
        Region r = analyze(entry, nodes);
        List<Statement> out = new ArrayList<Statement>();
        doTree(r, entry, out);
        return out;
    }

    private Region analyze(Node entry, Set<Node> nodes) throws Failure {
        Region r = new Region(entry, nodes);
        // reverse postorder (iterative DFS)
        Set<Node> seen = new HashSet<Node>();
        Deque<Object[]> stack = new ArrayDeque<Object[]>();
        List<Node> post = new ArrayList<Node>();
        seen.add(entry);
        stack.push(new Object[]{entry, Integer.valueOf(0)});
        while (!stack.isEmpty()) {
            Object[] top = stack.peek();
            Node n = (Node) top[0];
            int i = ((Integer) top[1]).intValue();
            if (i < n.succs.size()) {
                top[1] = Integer.valueOf(i + 1);
                Node s = n.succs.get(i);
                if (nodes.contains(s) && seen.add(s)) stack.push(new Object[]{s, Integer.valueOf(0)});
            } else {
                stack.pop();
                post.add(n);
            }
        }
        for (int i = post.size() - 1; i >= 0; i--) {
            r.rpo.put(post.get(i), Integer.valueOf(r.order.size()));
            r.order.add(post.get(i));
        }
        // dominators (Cooper, Harvey, Kennedy)
        r.idom.put(entry, entry);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < r.order.size(); i++) {
                Node b = r.order.get(i);
                Node nd = null;
                for (Node p : b.preds) {
                    if (!r.rpo.containsKey(p) || !r.idom.containsKey(p)) continue;
                    nd = nd == null ? p : intersect(r, p, nd);
                }
                if (nd != null && r.idom.get(b) != nd) {
                    r.idom.put(b, nd);
                    changed = true;
                }
            }
        }
        for (Node b : r.order) {
            if (b == entry) continue;
            Node d = r.idom.get(b);
            if (d == null) throw new Failure("unreachable node in region");
            List<Node> l = r.children.get(d);
            if (l == null) { l = new ArrayList<Node>(); r.children.put(d, l); }
            l.add(b);
        }
        // loops and merges
        for (Node b : r.order) {
            int forward = 0;
            for (Node p : b.preds) {
                if (!r.rpo.containsKey(p)) continue;
                int count = 0;
                for (Node s : p.succs) if (s == b) count++;
                if (r.rpo.get(p).intValue() >= r.rpo.get(b).intValue()) {
                    if (!dominates(r, b, p)) throw new Failure("irreducible control flow at pc " + b.pc);
                    r.loopHeaders.add(b);
                } else {
                    forward += count;
                    if (p.kind == K_TRY) forward += 2; // composite exits are always breaks
                }
            }
            if (forward >= 2) r.mergeNodes.add(b);
        }
        return r;
    }

    private static Node intersect(Region r, Node a, Node b) {
        Node x = a, y = b;
        int guard = 0;
        while (x != y && guard++ < 1000000) {
            while (r.rpo.get(x).intValue() > r.rpo.get(y).intValue()) x = r.idom.get(x);
            while (r.rpo.get(y).intValue() > r.rpo.get(x).intValue()) y = r.idom.get(y);
        }
        return x;
    }

    private static boolean dominates(Region r, Node a, Node b) {
        Node x = b;
        int guard = 0;
        while (guard++ < 1000000) {
            if (x == a) return true;
            Node d = r.idom.get(x);
            if (d == null || d == x) return x == a;
            x = d;
        }
        return false;
    }

    private static String blockLabel(Node n) { return "L" + n.id; }
    private static String loopLabel(Node n) { return "W" + n.id; }

    private void doTree(Region r, Node x, List<Statement> out) throws Failure {
        List<Node> ys = new ArrayList<Node>();
        List<Node> ch = r.children.get(x);
        if (ch != null) for (Node c : ch) if (r.mergeNodes.contains(c)) ys.add(c);
        final Region fr = r;
        Collections.sort(ys, new Comparator<Node>() {
            public int compare(Node a, Node b) {
                return fr.rpo.get(b).intValue() - fr.rpo.get(a).intValue(); // highest rpo first
            }
        });
        if (r.loopHeaders.contains(x)) {
            List<Statement> body = new ArrayList<Statement>();
            nodeWithin(r, x, ys, 0, body);
            out.add(new LabelStatement(x.line, loopLabel(x),
                new WhileStatement(x.line, BooleanExpression.TRUE, new BlockStatement(x.line, body))));
        } else {
            nodeWithin(r, x, ys, 0, out);
        }
    }

    private void nodeWithin(Region r, Node x, List<Node> ys, int k, List<Statement> out) throws Failure {
        if (k < ys.size()) {
            Node y = ys.get(k);
            List<Statement> inner = new ArrayList<Statement>();
            nodeWithin(r, x, ys, k + 1, inner);
            out.add(new LabelStatement(y.line, blockLabel(y), new BlockStatement(y.line, inner)));
            doTree(r, y, out);
            return;
        }
        emitNode(r, x, out);
    }

    private void emitNode(Region r, Node x, List<Statement> out) throws Failure {
        if (x.kind == K_TRY) {
            out.add(emitComposite(r, x));
            return;
        }
        out.addAll(x.stmts);
        int line = x.line;
        switch (x.kind) {
            case K_EXIT:
                return;
            case K_GOTO:
                branch(r, x, x.succs.get(0), out, line);
                return;
            case K_COND: {
                List<Statement> t = new ArrayList<Statement>();
                List<Statement> f = new ArrayList<Statement>();
                branch(r, x, x.succs.get(0), t, line);
                branch(r, x, x.succs.get(1), f, line);
                out.add(new IfElseStatement(line, x.cond, new BlockStatement(line, t), new BlockStatement(line, f)));
                return;
            }
            case K_SWITCH: {
                List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
                for (int i = 0; i < x.succs.size(); i++) {
                    List<Statement> body = new ArrayList<Statement>();
                    branch(r, x, x.succs.get(i), body, line);
                    List<Expression> labels = new ArrayList<Expression>();
                    for (Integer key : x.caseKeys.get(i)) labels.add(IntegerConstantExpression.valueOf(line, key.intValue()));
                    List<Statement> wrapped = new ArrayList<Statement>();
                    wrapped.add(new BlockStatement(line, body));
                    cases.add(new SwitchStatement.SwitchCase(labels, wrapped));
                }
                out.add(new SwitchStatement(line, x.selector, cases, false));
                return;
            }
            default:
                throw new Failure("unknown node kind");
        }
    }

    private void branch(Region r, Node from, Node to, List<Statement> out, int line) throws Failure {
        if (!r.nodes.contains(to) || !r.rpo.containsKey(to)) {
            out.add(new JumpMarker(to, line));
            return;
        }
        if (r.rpo.get(to).intValue() <= r.rpo.get(from).intValue()) {
            out.add(new ContinueStatement(line, loopLabel(to)));
            return;
        }
        if (r.mergeNodes.contains(to)) {
            out.add(new BreakStatement(line, blockLabel(to)));
            return;
        }
        doTree(r, to, out);
    }

    private Statement emitComposite(final Region r, final Node x) throws Failure {
        List<Statement> body = structure(x.bodyEntry, x.body);
        List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
        for (Handler h : x.handlers) {
            List<Statement> hb = structure(h.entry, h.nodes);
            String type = h.type != null ? h.type : "java/lang/Throwable";
            List<Type> types = new ArrayList<Type>();
            types.add(new ObjectType(type));
            final String var = "ex" + (catchCounter++);
            hb = bindCatchVariable(hb, h.entry.pc, var, new ObjectType(type));
            catches.add(new TryCatchStatement.CatchClause(types, var, new BlockStatement(x.line, hb)));
        }
        Statement s;
        if (x.lock != null) {
            s = new SynchronizedStatement(x.line, x.lock, new BlockStatement(x.line, body));
        } else {
            s = new TryCatchStatement(x.line, new BlockStatement(x.line, body), catches, null, null);
        }
        // exits of the composite: break/continue in this region, or propagate outward
        final Failure[] err = new Failure[1];
        Statement resolved = new it.denzosoft.javadecompiler.service.converter.transform.AstMapper() {
            @Override
            public Statement map(Statement st) {
                if (st instanceof JumpMarker) {
                    JumpMarker jm = (JumpMarker) st;
                    if (jm.target == x.bodyEntry) {
                        // a handler restarting its own try: the composite is a loop header
                        return new ContinueStatement(jm.line, loopLabel(x));
                    }
                    if (!r.nodes.contains(jm.target) || !r.rpo.containsKey(jm.target)) return st;
                    if (r.rpo.get(jm.target).intValue() <= r.rpo.get(x).intValue()) {
                        return new ContinueStatement(jm.line, loopLabel(jm.target));
                    }
                    if (!r.mergeNodes.contains(jm.target)) {
                        err[0] = new Failure("composite exit to a non-merge node");
                        return st;
                    }
                    return new BreakStatement(jm.line, blockLabel(jm.target));
                }
                return super.map(st);
            }
        }.map(s);
        if (err[0] != null) throw err[0];
        return resolved;
    }

    /**
     * The handler's opening {@code astore N} keeps the caught exception in slot N; every read of
     * that slot inside the handler is the catch parameter.
     */
    private List<Statement> bindCatchVariable(List<Statement> body, int handlerPc, final String var, final Type type) {
        int slot = -1;
        if (bytecode != null && handlerPc >= 0 && handlerPc < bytecode.length) {
            int op = bytecode[handlerPc] & 0xFF;
            if (op == 0x3A && handlerPc + 1 < bytecode.length) slot = bytecode[handlerPc + 1] & 0xFF;
            else if (op >= 0x4B && op <= 0x4E) slot = op - 0x4B;
        }
        final int fslot = slot;
        List<Statement> out = new ArrayList<Statement>(body);
        if (!out.isEmpty() && isSeedStore(out.get(0))) out.remove(0);
        return new it.denzosoft.javadecompiler.service.converter.transform.AstMapper() {
            @Override
            protected Expression mapLocal(LocalVariableExpression lv) {
                if ("$exception".equals(lv.getName()) || (fslot >= 0 && lv.getIndex() == fslot)) {
                    return new LocalVariableExpression(lv.getLineNumber(), type, var, fslot);
                }
                return lv;
            }
        }.mapList(out);
    }

    private static boolean containsMarker(Statement s) {
        final boolean[] found = new boolean[1];
        new it.denzosoft.javadecompiler.service.converter.transform.AstMapper() {
            @Override
            public Statement map(Statement st) {
                if (st instanceof JumpMarker) { found[0] = true; return st; }
                return super.map(st);
            }
        }.map(s);
        return found[0];
    }

    // =================================================================== simplification

    /** Cosmetic, meaning-preserving clean-up of the labeled-block output. */
    private static List<Statement> simplify(List<Statement> in) {
        List<Statement> out = in;
        for (int pass = 0; pass < 4; pass++) {
            Set<String> used = new HashSet<String>();
            collectJumps(out, used);
            out = simplifyList(out, used, null);
        }
        return out;
    }

    private static void collectJumps(List<Statement> l, final Set<String> used) {
        new it.denzosoft.javadecompiler.service.converter.transform.AstMapper() {
            @Override
            public Statement map(Statement st) {
                if (st instanceof BreakStatement && ((BreakStatement) st).getLabel() != null) used.add(((BreakStatement) st).getLabel());
                if (st instanceof ContinueStatement && ((ContinueStatement) st).getLabel() != null) used.add(((ContinueStatement) st).getLabel());
                return super.map(st);
            }
        }.mapList(l);
    }

    /**
     * @param tailLabel label of the block whose end immediately follows this list (a trailing
     *                  {@code break tailLabel} is redundant), or null
     */
    private static List<Statement> simplifyList(List<Statement> in, Set<String> used, String tailLabel) {
        List<Statement> out = new ArrayList<Statement>();
        for (int i = 0; i < in.size(); i++) {
            Statement s = in.get(i);
            boolean last = i == in.size() - 1;
            s = simplifyStmt(s, used, last ? tailLabel : null);
            if (s == null) continue;
            if (s instanceof LabelStatement && ((LabelStatement) s).getBody() instanceof BlockStatement
                    && !used.contains(((LabelStatement) s).getLabel())
                    && !declaresTopLevel(((BlockStatement) ((LabelStatement) s).getBody()).getStatements())) {
                // unused block label: splice its statements
                out.addAll(((BlockStatement) ((LabelStatement) s).getBody()).getStatements());
                continue;
            }
            out.add(s);
        }
        // drop a trailing `break tail;` (falls off into the same place)
        if (tailLabel != null && !out.isEmpty()) {
            Statement l = out.get(out.size() - 1);
            if (l instanceof BreakStatement && tailLabel.equals(((BreakStatement) l).getLabel())) {
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    private static boolean declaresTopLevel(List<Statement> l) {
        for (Statement s : l) if (s instanceof VariableDeclarationStatement) return true;
        return false;
    }

    private static Statement simplifyStmt(Statement s, Set<String> used, String tailLabel) {
        if (s instanceof BlockStatement) {
            return new BlockStatement(s.getLineNumber(), simplifyList(((BlockStatement) s).getStatements(), used, tailLabel));
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            if (l.getBody() instanceof BlockStatement) {
                // the end of a labeled block is where `break label` lands
                List<Statement> b = simplifyList(((BlockStatement) l.getBody()).getStatements(), used, l.getLabel());
                return new LabelStatement(l.getLineNumber(), l.getLabel(), new BlockStatement(l.getLineNumber(), b));
            }
            if (l.getBody() instanceof WhileStatement) {
                WhileStatement w = (WhileStatement) l.getBody();
                Statement b = simplifyStmt(w.getBody(), used, null);
                return new LabelStatement(l.getLineNumber(), l.getLabel(), new WhileStatement(w.getLineNumber(), w.getCondition(), b));
            }
            return s;
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement i = (IfElseStatement) s;
            Statement t = simplifyStmt(i.getThenBody(), used, tailLabel);
            Statement e = simplifyStmt(i.getElseBody(), used, tailLabel);
            boolean emptyE = e instanceof BlockStatement && ((BlockStatement) e).getStatements().isEmpty();
            boolean emptyT = t instanceof BlockStatement && ((BlockStatement) t).getStatements().isEmpty();
            if (emptyE) return new IfStatement(i.getLineNumber(), i.getCondition(), t);
            if (emptyT) return new IfStatement(i.getLineNumber(), negate(i.getCondition()), e);
            return new IfElseStatement(i.getLineNumber(), i.getCondition(), t, e);
        }
        if (s instanceof IfStatement) {
            IfStatement i = (IfStatement) s;
            return new IfStatement(i.getLineNumber(), i.getCondition(), simplifyStmt(i.getThenBody(), used, tailLabel));
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            List<TryCatchStatement.CatchClause> cs = new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                cs.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, simplifyStmt(cc.body, used, tailLabel)));
            }
            return new TryCatchStatement(t.getLineNumber(), simplifyStmt(t.getTryBody(), used, tailLabel), cs,
                t.getFinallyBody(), t.getResources());
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement sy = (SynchronizedStatement) s;
            return new SynchronizedStatement(sy.getLineNumber(), sy.getMonitor(), simplifyStmt(sy.getBody(), used, tailLabel));
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            List<SwitchStatement.SwitchCase> cs = new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                cs.add(new SwitchStatement.SwitchCase(c.getLabels(), simplifyList(c.getStatements(), used, null)));
            }
            return new SwitchStatement(sw.getLineNumber(), sw.getSelector(), cs, sw.isArrowStyle());
        }
        return s;
    }

    private static Expression negate(Expression e) {
        if (e instanceof UnaryOperatorExpression && "!".equals(((UnaryOperatorExpression) e).getOperator())) {
            return ((UnaryOperatorExpression) e).getExpression();
        }
        return new UnaryOperatorExpression(e.getLineNumber(), PrimitiveType.BOOLEAN, "!", e, true);
    }
}
