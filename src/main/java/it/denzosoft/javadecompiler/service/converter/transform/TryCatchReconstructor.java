/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;
import it.denzosoft.javadecompiler.service.converter.cfg.BasicBlock;
import it.denzosoft.javadecompiler.service.converter.cfg.ControlFlowGraph;
// START_CHANGE: BUG-2026-0056-20260610-2 - Structured handler-body decoding
import it.denzosoft.javadecompiler.service.converter.cfg.StructuredFlowBuilder;
// END_CHANGE: BUG-2026-0056-2

import java.util.*;

/**
 * Wraps decompiled statement lists with try-catch-finally blocks
 * by analysing the exception table from a Code attribute.
 */
public class TryCatchReconstructor {

    private final ControlFlowGraph cfg;
    private final Map<Integer, Integer> pcToLine;
    private final Map<Integer, String> localVarNames;
    private final byte[] bytecode;
    private final ConstantPool pool;
    // START_CHANGE: ISS-2026-0005-20260324-6 - Handler PC to exception variable name map
    private final Map<Integer, String> handlerVarNames;
    // END_CHANGE: ISS-2026-0005-6
    // START_CHANGE: BUG-2026-0056-20260610-3 - Optional structured flow builder used to decode
    // handler bodies that contain internal control flow (conditionals/switches). When unset, the
    // legacy linear walk is used for all handlers.
    private StructuredFlowBuilder flowBuilder;

    public void setFlowBuilder(StructuredFlowBuilder flowBuilder) {
        this.flowBuilder = flowBuilder;
    }
    // END_CHANGE: BUG-2026-0056-3

    // START_CHANGE: BUG-2026-0100-20260905-5 - Bytecode positions of decoded statements.
    // Try-region membership is decided on these when available; source lines are only the
    // fallback for statements a transform synthesised. Without this, a class compiled with
    // -g:none (no LineNumberTable) lost EVERY catch clause: `tryStartLine < 0` rejected the
    // group and handler blocks, unreachable from the entry walk, were never emitted.
    private IdentityHashMap<Statement, Integer> statementPcs;

    public void setStatementPcs(IdentityHashMap<Statement, Integer> statementPcs) {
        this.statementPcs = statementPcs;
    }

    // START_CHANGE: BUG-2026-0180-20261018-4 - Handler pcs of the try/finally regions that
    // Javac7TwrNormalizer produced from a javac 7/8 try-with-resources desugaring. Such a group
    // is a resource try by construction: it must never be dropped as "trivial".
    private Set<Integer> twrHandlerPcs = Collections.<Integer>emptySet();

    // START_CHANGE: BUG-2026-0182-20261018-3 - class-file major version (-1 = unknown)
    private int classMajorVersion182 = -1;

    public void setClassMajorVersion(int major) {
        this.classMajorVersion182 = major;
    }
    // END_CHANGE: BUG-2026-0182-3

    public void setTwrHandlerPcs(Set<Integer> pcs) {
        this.twrHandlerPcs = pcs == null ? Collections.<Integer>emptySet() : pcs;
    }
    // END_CHANGE: BUG-2026-0180-4

    /**
     * Lowest bytecode pc contained in {@code s}, or -1 when unknown. Compound statements built
     * by the flow builder are not themselves in the map, so their position is the minimum of the
     * decoded statements they wrap.
     */
    // START_CHANGE: BUG-2026-0122-20260907-2 - Reverse index of the LineNumberTable.
    private Map<Integer, Integer> lineToLowestPc;

    /** Lowest bytecode pc mapping to {@code line}, or -1 when unknown. */
    private int lowestPcForLine(int line) {
        if (line <= 0 || pcToLine == null || pcToLine.isEmpty()) return -1;
        if (lineToLowestPc == null) {
            lineToLowestPc = new HashMap<Integer, Integer>();
            for (Map.Entry<Integer, Integer> e : pcToLine.entrySet()) {
                Integer prev = lineToLowestPc.get(e.getValue());
                if (prev == null || e.getKey().intValue() < prev.intValue()) {
                    lineToLowestPc.put(e.getValue(), e.getKey());
                }
            }
        }
        Integer pc = lineToLowestPc.get(Integer.valueOf(line));
        return pc == null ? -1 : pc.intValue();
    }

    /** True when the statement wraps other statements (its position is their minimum). */
    private boolean hasChildStatements(Statement s) {
        return s instanceof BlockStatement || s instanceof IfStatement || s instanceof IfElseStatement
            || s instanceof WhileStatement || s instanceof DoWhileStatement || s instanceof ForStatement
            || s instanceof ForEachStatement || s instanceof LabelStatement
            || s instanceof SynchronizedStatement || s instanceof TryCatchStatement
            || s instanceof SwitchStatement;
    }
    // END_CHANGE: BUG-2026-0122-2

    private int minPcOf(Statement s) {
        if (s == null || statementPcs == null) return -1;
        Integer direct = statementPcs.get(s);
        if (direct != null) return direct.intValue();
        // START_CHANGE: BUG-2026-0122-20260907-1 - Statements SYNTHESISED by the flow builder (a
        // ternary merge, a folded condition) are new objects that never passed through the decoder,
        // so they carry no recorded pc and used to fall back to the line comparison -- which is the
        // very thing the pc criterion exists to replace. Recover a position from the statement's
        // line instead, taking the lowest pc that maps to it.
        int viaLine = lowestPcForLine(s.getLineNumber());
        if (viaLine >= 0 && !hasChildStatements(s)) return viaLine;
        int best = -1;
        if (s instanceof BlockStatement) best = minPcOf(((BlockStatement) s).getStatements());
        else if (s instanceof IfStatement) best = minPcOf(((IfStatement) s).getThenBody());
        else if (s instanceof IfElseStatement) {
            best = minOf(minPcOf(((IfElseStatement) s).getThenBody()),
                         minPcOf(((IfElseStatement) s).getElseBody()));
        }
        else if (s instanceof WhileStatement) best = minPcOf(((WhileStatement) s).getBody());
        else if (s instanceof DoWhileStatement) best = minPcOf(((DoWhileStatement) s).getBody());
        else if (s instanceof ForStatement) {
            best = minOf(minPcOf(((ForStatement) s).getInit()), minPcOf(((ForStatement) s).getBody()));
        }
        else if (s instanceof ForEachStatement) best = minPcOf(((ForEachStatement) s).getBody());
        else if (s instanceof LabelStatement) best = minPcOf(((LabelStatement) s).getBody());
        else if (s instanceof SynchronizedStatement) best = minPcOf(((SynchronizedStatement) s).getBody());
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            best = minPcOf(t.getTryBody());
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) best = minOf(best, minPcOf(cc.body));
            best = minOf(best, minPcOf(t.getFinallyBody()));
        }
        else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                best = minOf(best, minPcOf(c.getStatements()));
            }
        }
        return best;
    }

    // START_CHANGE: BUG-2026-0189-20261018-2 - Highest recorded pc inside {@code s} (-1 = none).
    private int maxPcOf(Statement s) {
        if (s == null || statementPcs == null) return -1;
        Integer direct = statementPcs.get(s);
        int best = direct != null ? direct.intValue() : -1;
        if (s instanceof BlockStatement) best = Math.max(best, maxPcOf(((BlockStatement) s).getStatements()));
        else if (s instanceof IfStatement) best = Math.max(best, maxPcOf(((IfStatement) s).getThenBody()));
        else if (s instanceof IfElseStatement) {
            best = Math.max(best, Math.max(maxPcOf(((IfElseStatement) s).getThenBody()),
                                           maxPcOf(((IfElseStatement) s).getElseBody())));
        }
        else if (s instanceof WhileStatement) best = Math.max(best, maxPcOf(((WhileStatement) s).getBody()));
        else if (s instanceof DoWhileStatement) best = Math.max(best, maxPcOf(((DoWhileStatement) s).getBody()));
        else if (s instanceof ForStatement) {
            best = Math.max(best, Math.max(maxPcOf(((ForStatement) s).getBody()), maxPcOf(((ForStatement) s).getUpdate())));
        }
        else if (s instanceof ForEachStatement) best = Math.max(best, maxPcOf(((ForEachStatement) s).getBody()));
        else if (s instanceof LabelStatement) best = Math.max(best, maxPcOf(((LabelStatement) s).getBody()));
        else if (s instanceof SynchronizedStatement) best = Math.max(best, maxPcOf(((SynchronizedStatement) s).getBody()));
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            best = Math.max(best, maxPcOf(t.getTryBody()));
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) best = Math.max(best, maxPcOf(cc.body));
            best = Math.max(best, maxPcOf(t.getFinallyBody()));
        }
        else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) best = Math.max(best, maxPcOf(c.getStatements()));
        }
        return best;
    }

    private int maxPcOf(List<Statement> list) {
        int best = -1;
        if (list != null) for (Statement s : list) best = Math.max(best, maxPcOf(s));
        return best;
    }
    // END_CHANGE: BUG-2026-0189-2

    private int minPcOf(List<Statement> list) {
        if (list == null) return -1;
        int best = -1;
        for (int i = 0; i < list.size(); i++) best = minOf(best, minPcOf(list.get(i)));
        return best;
    }

    private static int minOf(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return a < b ? a : b;
    }
    // END_CHANGE: BUG-2026-0100-5

    public TryCatchReconstructor(ControlFlowGraph cfg,
                                  Map<Integer, Integer> pcToLine,
                                  Map<Integer, String> localVarNames,
                                  byte[] bytecode,
                                  ConstantPool pool) {
        this(cfg, pcToLine, localVarNames, bytecode, pool, new HashMap<Integer, String>());
    }

    // START_CHANGE: ISS-2026-0005-20260324-7 - Constructor with handler var name map
    public TryCatchReconstructor(ControlFlowGraph cfg,
                                  Map<Integer, Integer> pcToLine,
                                  Map<Integer, String> localVarNames,
                                  byte[] bytecode,
                                  ConstantPool pool,
                                  Map<Integer, String> handlerVarNames) {
        this.cfg = cfg;
        this.pcToLine = pcToLine;
        this.localVarNames = localVarNames;
        this.bytecode = bytecode;
        this.pool = pool;
        this.handlerVarNames = handlerVarNames;
    }
    // END_CHANGE: ISS-2026-0005-7

    public List<Statement> reconstruct(List<Statement> statements,
                                        CodeAttribute.ExceptionEntry[] exceptionTable) {
        return wrapWithTryCatch(statements, exceptionTable);
    }

    private List<Statement> wrapWithTryCatch(List<Statement> statements,
                                              CodeAttribute.ExceptionEntry[] exceptionTable) {
        if (exceptionTable == null || exceptionTable.length == 0) {
            return statements;
        }

        fullTable198 = exceptionTable; // BUG-2026-0198: before coalescing
        // START_CHANGE: BUG-2026-0068-20260610-1 - Coalesce compiler-generated handler-protection
        // entries before grouping. The try-with-resources desugar re-protects the synthetic
        // Throwable close-and-rethrow handler region with the SAME user handler, e.g.
        // readOrDefault: [20,38)->38 IOException alongside [0,18)->38 IOException, where 20 is
        // the handlerPc of [9,14)->20 Throwable. Grouping such an entry by its own
        // (startPc, endPc) used to build a spurious inner try around the resource declaration,
        // pushing the resource variable out of scope. An entry is dropped ONLY when
        // (a) its startPc is exactly the handlerPc of another entry (it starts AT a handler),
        // and (b) another entry routes to the identical (handlerPc, catchType) - i.e. it merely
        // extends an existing handler's protected range over compiler-generated handler code.
        // Genuine nested user try regions never satisfy both conditions at once.
        List<CodeAttribute.ExceptionEntry> coalesced =
            new ArrayList<CodeAttribute.ExceptionEntry>();
        for (int i = 0; i < exceptionTable.length; i++) {
            if (!isHandlerProtectionEntry(exceptionTable[i], exceptionTable)) {
                coalesced.add(exceptionTable[i]);
            }
        }
        if (coalesced.size() < exceptionTable.length) {
            exceptionTable = coalesced.toArray(
                new CodeAttribute.ExceptionEntry[coalesced.size()]);
        }
        // END_CHANGE: BUG-2026-0068-1

        // Group exception entries by (startPc, endPc) - same try region
        Map<String, List<CodeAttribute.ExceptionEntry>> groups =
            new LinkedHashMap<String, List<CodeAttribute.ExceptionEntry>>();
        for (int i = 0; i < exceptionTable.length; i++) {
            CodeAttribute.ExceptionEntry entry = exceptionTable[i];
            String key = entry.startPc + "-" + entry.endPc;
            List<CodeAttribute.ExceptionEntry> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<CodeAttribute.ExceptionEntry>();
                groups.put(key, list);
            }
            list.add(entry);
        }
        // START_CHANGE: BUG-2026-0137-20261015-2 - One source `try` can own SEVERAL protected
        // ranges: javac splits the range around code it excludes (e.g. the `areturn` of a
        // `return` inside the try: [26,46)->56 and [47,56)->56). Grouping by (startPc, endPc)
        // made each fragment its own try, so the body was wrapped twice in nested try/catch with
        // the same clause and the code of the second fragment (`throw new ISE(s)`) ended up
        // OUTSIDE both - an exception the source caught escaped the method. A handler pc belongs
        // to exactly one source try statement, so regions whose (handlerPc, catchType) sets are
        // identical are fragments of the same try: merge them into one group keyed by the
        // union range.
        groups = mergeSplitRegions(groups);
        // END_CHANGE: BUG-2026-0137-2

        // Build a sorted array of all PCs that have line numbers
        List<Integer> sortedPcs = new ArrayList<Integer>(pcToLine.keySet());
        Collections.sort(sortedPcs);

        // Process groups in reverse order of startPc so inner try-catch is wrapped first
        List<String> groupKeys = new ArrayList<String>(groups.keySet());
        Collections.sort(groupKeys, new Comparator<String>() {
            public int compare(String a, String b) {
                int aStart = Integer.parseInt(a.split("-")[0]);
                int bStart = Integer.parseInt(b.split("-")[0]);
                return bStart - aStart;
            }
        });

        // START_CHANGE: BUG-2026-0056-20260610-20 - The per-group wrapping is factored out into
        // applyGroup so it can recurse into structured statement bodies (a try region inside a
        // loop built by the flow builder) and into decoded handler bodies (a nested try inside a
        // catch). Groups that found no statements anywhere are kept as pending candidates: when
        // an OUTER group later decodes its handler body, the pending groups are offered that
        // body (the nested try's statements only exist there).
        pendingNestedGroups = new ArrayList<List<CodeAttribute.ExceptionEntry>>();
        for (String key : groupKeys) {
            List<CodeAttribute.ExceptionEntry> groupEntries = groups.get(key);
            nestedGroupCandidate = false;
            List<Statement> replaced = applyGroup(statements, groupEntries, sortedPcs);
            if (replaced != null) {
                statements = replaced;
            } else if (nestedGroupCandidate) {
                pendingNestedGroups.add(groupEntries);
            }
        }

        // START_CHANGE: BUG-2026-0056-20260610-12 - Retype `Object v = null` declarations whose
        // every subsequent assignment has one single concrete reference type (e.g. the variable
        // assigned inside the try from a String-returning call and returned after the
        // try-catch-finally). The decoder types a slot initialised by aconst_null as Object;
        // without retyping, `return v;` in a non-Object method does not recompile.
        retypeNullObjectDeclarations(statements);
        // END_CHANGE: BUG-2026-0056-12

        return statements;
    }

    // START_CHANGE: BUG-2026-0137-20261015-3 - Merge range fragments of one source try.
    private static String handlerSignature(List<CodeAttribute.ExceptionEntry> entries) {
        List<String> parts = new ArrayList<String>();
        for (CodeAttribute.ExceptionEntry e : entries) parts.add(e.handlerPc + ":" + e.catchType);
        Collections.sort(parts);
        return parts.toString();
    }

    private static Map<String, List<CodeAttribute.ExceptionEntry>> mergeSplitRegions(
            Map<String, List<CodeAttribute.ExceptionEntry>> groups) {
        Map<String, List<List<CodeAttribute.ExceptionEntry>>> bySig =
            new LinkedHashMap<String, List<List<CodeAttribute.ExceptionEntry>>>();
        for (List<CodeAttribute.ExceptionEntry> g : groups.values()) {
            String sig = handlerSignature(g);
            // Only ranges that lie entirely BEFORE their handlers are try-body fragments. A range
            // covering handler code is compiler protection (synchronized's monitorexit handler
            // re-protects itself: [11,23)->26 and [26,29)->26) and must stay a group of its own.
            for (CodeAttribute.ExceptionEntry e : g) {
                if (e.endPc > e.handlerPc) { sig = "self@" + e.startPc + "-" + e.endPc; break; }
            }
            List<List<CodeAttribute.ExceptionEntry>> l = bySig.get(sig);
            if (l == null) {
                l = new ArrayList<List<CodeAttribute.ExceptionEntry>>();
                bySig.put(sig, l);
            }
            l.add(g);
        }
        if (bySig.size() == groups.size()) return groups;
        Map<String, List<CodeAttribute.ExceptionEntry>> out =
            new LinkedHashMap<String, List<CodeAttribute.ExceptionEntry>>();
        for (List<List<CodeAttribute.ExceptionEntry>> frags : bySig.values()) {
            int start = Integer.MAX_VALUE, end = -1;
            List<CodeAttribute.ExceptionEntry> all = new ArrayList<CodeAttribute.ExceptionEntry>();
            for (List<CodeAttribute.ExceptionEntry> f : frags) {
                for (CodeAttribute.ExceptionEntry e : f) {
                    if (e.startPc < start) start = e.startPc;
                    if (e.endPc > end) end = e.endPc;
                }
            }
            // The fragment holding the lowest startPc goes first: applyGroup reads the region
            // start from groupEntries.get(0).
            Collections.sort(frags, new Comparator<List<CodeAttribute.ExceptionEntry>>() {
                public int compare(List<CodeAttribute.ExceptionEntry> a, List<CodeAttribute.ExceptionEntry> b) {
                    return a.get(0).startPc - b.get(0).startPc;
                }
            });
            for (List<CodeAttribute.ExceptionEntry> f : frags) all.addAll(f);
            String key = start + "-" + end;
            List<CodeAttribute.ExceptionEntry> prev = out.get(key);
            if (prev != null) prev.addAll(all); else out.put(key, all);
        }
        return out;
    }
    // END_CHANGE: BUG-2026-0137-3

    // START_CHANGE: BUG-2026-0198-20261018-1 - The exception table before BUG-2026-0068 drops
    // handler-protection entries. Such an entry can also be the second FRAGMENT of an outer try
    // that encloses an inner try/catch: [62,102)->114 and [103,111)->114 where 103 is the inner
    // handler (javax.sound.sampled.AudioSystem.getMixer). Dropping it shrank the outer span to
    // 102, and the BUG-2026-0189 rule then pushed the inner loop (last pc 108) out of the try.
    private CodeAttribute.ExceptionEntry[] fullTable198;

    /** End of the protected span of the group, counting dropped fragments with the same handler. */
    private int fullSpanEnd198(List<CodeAttribute.ExceptionEntry> groupEntries, int end) {
        if (fullTable198 == null) return end;
        int start = Integer.MAX_VALUE;
        for (CodeAttribute.ExceptionEntry g : groupEntries) start = Math.min(start, g.startPc);
        for (CodeAttribute.ExceptionEntry e : fullTable198) {
            if (e.endPc <= end || e.startPc < start || e.endPc > e.handlerPc) continue;
            for (CodeAttribute.ExceptionEntry g : groupEntries) {
                if (g.handlerPc == e.handlerPc && g.catchType == e.catchType) { end = e.endPc; break; }
            }
        }
        return end;
    }
    // END_CHANGE: BUG-2026-0198-1

    /** Groups that found no statements at any level yet (likely nested inside a handler). */
    private List<List<CodeAttribute.ExceptionEntry>> pendingNestedGroups;
    /** Set by applyGroup when the group failed ONLY because no statements were found. */
    private boolean nestedGroupCandidate;

    /**
     * Apply one exception-table group to a statement list. Returns the new statement list, or
     * null when the group's try region does not match any statements in the list.
     */
    private List<Statement> applyGroup(List<Statement> statements,
                                        List<CodeAttribute.ExceptionEntry> groupEntries,
                                        List<Integer> sortedPcs) {
            CodeAttribute.ExceptionEntry firstEntry = groupEntries.get(0);
            int tryStartPc = firstEntry.startPc;
            int tryEndPc = firstEntry.endPc;
            // START_CHANGE: BUG-2026-0137-20261015-4 - A merged group spans all its fragments.
            for (int gi = 1; gi < groupEntries.size(); gi++) {
                if (groupEntries.get(gi).startPc < tryStartPc) tryStartPc = groupEntries.get(gi).startPc;
                if (groupEntries.get(gi).endPc > tryEndPc) tryEndPc = groupEntries.get(gi).endPc;
            }
            // END_CHANGE: BUG-2026-0137-4
            // BUG-2026-0198: span including dropped handler-protection fragments of this try; it
            // also decides the merge point (the goto ending the LAST fragment targets it)
            int spanEnd198 = fullSpanEnd198(groupEntries, tryEndPc);

            int tryStartLine = findLineForPc(tryStartPc, sortedPcs);
            int tryEndLine = findLineBeforePc(tryEndPc, sortedPcs);

            // START_CHANGE: BUG-2026-0100-20260905-6 - Bytecode positions make the region decision
            // possible without a LineNumberTable. They are used ONLY when the region has no line
            // information: with lines present the established line-based grouping is what the
            // downstream reconstructors (notably ModernTwrReconstructor) are tuned against, and
            // switching it wholesale regressed try-with-resources collapse. The displacement class
            // of line-based defects is tracked separately as BUG-2026-0122.
            // BUG-2026-0122: the pc criterion stays restricted to regions WITHOUT line information.
            // Enabling it for line-bearing classes fixes the displacement it was meant to fix (and
            // takes the -g:none matrix to 55/55), but on JDK 25 java.base it also changes the emitted
            // `catch (` count by 756. Part of that is removing genuine FABRICATIONS -- v1.12.0 emitted
            // 19 catch clauses for sun/security/ec/SunEC$ProviderService, which has exactly ONE
            // exception region -- but at least one real handler is lost with them, and losing a
            // handler is the gravest defect class here. Not shipped until the two can be separated.
            boolean usePc100 = tryStartLine < 0 && statementPcs != null && !statementPcs.isEmpty();
            if (tryStartLine < 0 && !usePc100) return null;
            // END_CHANGE: BUG-2026-0100-6

            List<Statement> tryBody = new ArrayList<Statement>();
            List<Statement> beforeTry = new ArrayList<Statement>();
            List<Statement> afterTry = new ArrayList<Statement>();

            int firstHandlerPc = Integer.MAX_VALUE;
            int lastHandlerPc = -1;
            for (CodeAttribute.ExceptionEntry entry : groupEntries) {
                if (entry.handlerPc < firstHandlerPc) {
                    firstHandlerPc = entry.handlerPc;
                }
                if (entry.handlerPc > lastHandlerPc) {
                    lastHandlerPc = entry.handlerPc;
                }
            }
            int firstHandlerLine = findLineForPc(firstHandlerPc, sortedPcs);

            // START_CHANGE: BUG-2026-0056-20260421-1 - Compute the line at which the try-catch-finally
            // region ENDS (i.e. the "merge point" after all handlers). Statements whose line falls
            // inside the handler region are part of a handler body (decoded separately) and must
            // be dropped; statements with a line strictly past the merge point are plain
            // after-try code and must be preserved. Previously any statement at/after
            // firstHandlerLine was dropped, which lost the trailing `log.info(...); return resp;`
            // that appears AFTER the catch/finally in non-void methods.
            int afterTryStartLine = computeAfterTryStartLine(
                firstHandlerPc, lastHandlerPc, spanEnd198, sortedPcs); // BUG-2026-0198: was tryEndPc
            // END_CHANGE: BUG-2026-0056-1

            boolean inTryRegion = false;
            // BUG-2026-0122: set once a positioned statement past the try region is seen, so a later
            // unpositioned statement is not pulled back into the try.
            boolean leftTryRegion122 = false;
            boolean leftTryLines175 = false; // BUG-2026-0175
            int groupEndPc122 = tryEndPc;
            for (int gi = 0; gi < groupEntries.size(); gi++) {
                if (groupEntries.get(gi).endPc > groupEndPc122) groupEndPc122 = groupEntries.get(gi).endPc;
            }
            for (Statement s : statements) {
                int sLine = s.getLineNumber();
                // START_CHANGE: BUG-2026-0100-20260905-7 - Prefer the exact bytecode range.
                // A statement decoded at pc P belongs to the try region iff P is inside
                // [startPc, endPc) of the exception entry -- exactly the JVM's own rule.
                int sPc100 = usePc100 ? minPcOf(s) : -1;
                // BUG-2026-0189: a compound reaching past the protected span is not try-body code
                if (sPc100 >= 0 && hasChildStatements(s) && maxPcOf(s) >= spanEnd198) sPc100 = Integer.MAX_VALUE;
                // START_CHANGE: BUG-2026-0122-20260907-6 - A group can hold SEVERAL protected
                // ranges sharing one handler (javac splits a single source `try` whose body has
                // gaps: sun/security/ec/SunEC$ProviderService.newInstance has three, [46,71)
                // [72,88) [89,105), all targeting handler 817). Testing membership against
                // firstEntry alone shrank the try body to the first fragment and the group was then
                // rejected, dropping the handler entirely. Membership is the UNION of the group's
                // ranges.
                boolean inAnyRange122 = false;
                if (sPc100 >= 0) {
                    for (int gi = 0; gi < groupEntries.size(); gi++) {
                        CodeAttribute.ExceptionEntry ge = groupEntries.get(gi);
                        if (sPc100 >= ge.startPc && sPc100 < ge.endPc) { inAnyRange122 = true; break; }
                        // START_CHANGE: BUG-2026-0199-20261018-1 - javac ends a protected range
                        // just BEFORE the return instruction of a `return expr;` that closes the try
                        // body ([31,43) with areturn at 43), and a return statement is positioned at
                        // that instruction. Without lines the return then fell outside the region:
                        // with nothing else in the body the group was rejected and the catch clause
                        // vanished (`try { return get(x); } catch (IAE e) {}` lost its handler).
                        if (sPc100 == ge.endPc && sPc100 > ge.startPc && s instanceof ReturnStatement) {
                            inAnyRange122 = true;
                            break;
                        }
                        // END_CHANGE: BUG-2026-0199-1
                    }
                    // START_CHANGE: BUG-2026-0137-20261015-5 - The gap between two fragments of one
                    // source try is code javac excluded from the protected range (the `areturn` of
                    // a `return` inside the try, an inlined finally copy) - still try-body code.
                    // `if (s.equals(n)) return s.toUpperCase();` (position = its areturn pc, in the
                    // gap) was otherwise moved after the catch.
                    if (!inAnyRange122 && (groupEntries.size() > 1 || spanEnd198 > groupEndPc122)
                            && sPc100 >= tryStartPc && sPc100 < spanEnd198) { // BUG-2026-0198: dropped fragments too
                        inAnyRange122 = true;
                    }
                    // END_CHANGE: BUG-2026-0137-5
                }
                // END_CHANGE: BUG-2026-0122-6
                // START_CHANGE: BUG-2026-0122-20260907-3 - A statement the flow builder SYNTHESISED
                // (a merged ternary is the common one) carries neither a recorded pc nor a line: it
                // is a new object built from several blocks, so `pc=-1 line=0`. Classifying it by
                // line then always failed and it was pushed past the catch -- `r = o == null ? 1 : 2;`
                // whose bytecode sits at pc 5..15, inside the try range [2,16), was emitted after the
                // handler. Statement ORDER is preserved from the decode, so an unpositioned statement
                // belongs wherever its predecessor went.
                if (usePc100 && sPc100 < 0 && sLine <= 0 && inTryRegion && !leftTryRegion122) {
                    tryBody.add(s);
                    continue;
                }
                if (sPc100 >= 0 && !inAnyRange122 && sPc100 >= groupEndPc122) leftTryRegion122 = true;
                if (sPc100 >= 0) {
                    if (inAnyRange122) {
                        inTryRegion = true;
                        tryBody.add(s);
                    } else if (!inTryRegion) {
                        beforeTry.add(s);
                    } else if (firstHandlerPc != Integer.MAX_VALUE && sPc100 >= firstHandlerPc
                               && afterTryStartLine > 0 && sLine > 0 && sLine < afterTryStartLine) {
                        // Decoded from inside a handler body and not yet past the merge point:
                        // the handler is emitted separately as a catch clause.
                        continue;
                    } else {
                        afterTry.add(s);
                    }
                    continue;
                }
                // END_CHANGE: BUG-2026-0100-7
                // START_CHANGE: BUG-2026-0137-20261015-6 - A statement of a block that starts
                // between two LineNumberTable entries carries no line (0): the code after
                // `if (c) return x;` written on the same source line. The line test cannot place
                // it, and it was pushed out of the try (`throw new ISE(s)` escaped its catch).
                // Its bytecode position can: a recorded pc inside the group's protected span is
                // try-body code.
                // START_CHANGE: BUG-2026-0175-20261018-1 - The BUG-2026-0122-3 rule for line-bearing
                // classes: a statement the flow builder synthesised (the boolean merge of
                // `flag = s != null && check(s);`) has neither pc nor line. Classified by line it
                // always failed and was moved after the catch, so an exception thrown by check()
                // escaped the handler. Decode order is preserved: it belongs with its predecessor.
                if (!usePc100 && sLine <= 0 && inTryRegion && !leftTryLines175 && minPcOf(s) < 0) {
                    tryBody.add(s);
                    continue;
                }
                // END_CHANGE: BUG-2026-0175-1
                boolean pcInTry137 = false;
                if (sLine <= 0 && statementPcs != null) {
                    int pc137 = minPcOf(s);
                    // START_CHANGE: BUG-2026-0189-20261018-1 - A compound statement's minimum pc
                    // is its first CHILD's: for a loop that is the first body statement, past the
                    // header. A loop whose body merely STARTS with the protected code then looked
                    // like try-body code and the try was wrapped around the whole loop: the first
                    // exception left the loop (`for (..) { try { f(); } catch (E e) {..} print(); }`).
                    // A compound joins the try only when its last pc is inside the span too.
                    if (pc137 >= 0 && hasChildStatements(s)) {
                        int max189 = maxPcOf(s);
                        if (max189 >= spanEnd198) pc137 = -1;
                    }
                    // END_CHANGE: BUG-2026-0189-1
                    if (pc137 >= 0) {
                        for (int gi = 0; gi < groupEntries.size(); gi++) {
                            CodeAttribute.ExceptionEntry ge = groupEntries.get(gi);
                            // Self-protecting handler ranges (synchronized's monitorexit) are not
                            // source try bodies.
                            if (ge.endPc > ge.handlerPc) continue;
                            if (pc137 >= ge.startPc && pc137 < ge.endPc) { pcInTry137 = true; break; }
                        }
                        if (!pcInTry137 && (groupEntries.size() > 1 || spanEnd198 > groupEndPc122)
                                && pc137 >= tryStartPc && pc137 < spanEnd198) { // BUG-2026-0198: dropped fragments too
                            pcInTry137 = true;
                        }
                    }
                }
                if (pcInTry137) {
                    inTryRegion = true;
                    tryBody.add(s);
                } else
                // END_CHANGE: BUG-2026-0137-6
                if (sLine > 0 && tryStartLine >= 0 && sLine >= tryStartLine && sLine <= tryEndLine) {
                    inTryRegion = true;
                    tryBody.add(s);
                } else if (!inTryRegion) {
                    beforeTry.add(s);
                } else {
                    // START_CHANGE: BUG-2026-0056-20260421-2 - Restore after-try statements.
                    // Drop statements that fall inside any handler body's line range, but keep
                    // statements past the merge point (they are plain after-try code).
                    leftTryLines175 = true; // BUG-2026-0175
                    if (afterTryStartLine > 0 && sLine >= afterTryStartLine) {
                        afterTry.add(s);
                        continue;
                    }
                    if (firstHandlerLine > 0 && sLine >= firstHandlerLine) {
                        continue;
                    }
                    afterTry.add(s);
                    // END_CHANGE: BUG-2026-0056-2
                }
            }

            // START_CHANGE: BUG-2026-0056-20260610-21 - No top-level statement falls in the try
            // region: the region may live inside a structured statement the flow builder already
            // built (try-catch inside a loop / if branch). Recurse into compound bodies before
            // giving up; if that fails too, mark the group as a nested-handler candidate.
            if (tryBody.isEmpty()) {
                List<Statement> nested = applyGroupInsideCompound(
                    statements, groupEntries, sortedPcs, tryStartLine);
                if (nested != null) return nested;
                nestedGroupCandidate = true;
                return null;
            }
            // END_CHANGE: BUG-2026-0056-21

            List<TryCatchStatement.CatchClause> catchClauses =
                new ArrayList<TryCatchStatement.CatchClause>();
            Statement finallyBody = null;
            boolean scaffold182 = false; // BUG-2026-0182: a handler held try-with-resources addSuppressed scaffolding

            List<CodeAttribute.ExceptionEntry> sortedEntries =
                new ArrayList<CodeAttribute.ExceptionEntry>(groupEntries);
            Collections.sort(sortedEntries, new Comparator<CodeAttribute.ExceptionEntry>() {
                public int compare(CodeAttribute.ExceptionEntry a, CodeAttribute.ExceptionEntry b) {
                    return a.handlerPc - b.handlerPc;
                }
            });

            int mergePc = findTryCatchMergePc(spanEnd198); // BUG-2026-0198: was tryEndPc

            // Group entries by handlerPc to merge multi-catch (same handler, different exception types)
            Map<Integer, List<CodeAttribute.ExceptionEntry>> handlerGroups =
                new LinkedHashMap<Integer, List<CodeAttribute.ExceptionEntry>>();
            for (int hi = 0; hi < sortedEntries.size(); hi++) {
                CodeAttribute.ExceptionEntry entry = sortedEntries.get(hi);
                Integer hpc = Integer.valueOf(entry.handlerPc);
                List<CodeAttribute.ExceptionEntry> group = handlerGroups.get(hpc);
                if (group == null) {
                    group = new ArrayList<CodeAttribute.ExceptionEntry>();
                    handlerGroups.put(hpc, group);
                }
                group.add(entry);
            }

            for (Map.Entry<Integer, List<CodeAttribute.ExceptionEntry>> hgEntry : handlerGroups.entrySet()) {
                int handlerPc = hgEntry.getKey().intValue();
                List<CodeAttribute.ExceptionEntry> hgEntries = hgEntry.getValue();
                CodeAttribute.ExceptionEntry firstHEntry = hgEntries.get(0);

                // Decode handler body once for the shared handlerPc
                // Find the index in sortedEntries for the first entry of this group
                int hiFirst = 0;
                for (int hi = 0; hi < sortedEntries.size(); hi++) {
                    if (sortedEntries.get(hi) == firstHEntry) {
                        hiFirst = hi;
                        break;
                    }
                }
                List<Statement> handlerBody = decodeHandlerBlocks(
                    handlerPc, sortedEntries, hiFirst, mergePc);

                String varName = findExceptionVarName(handlerPc);

                // START_CHANGE: ISS-2026-0005-20260324-12 - Rename misnamed exception variable in handler body
                // If the handler's first astore uses a slot with a different name in localVarNames,
                // rename all references in the handler body to the correct exception variable name.
                String slotName = findSlotNameAtHandler(handlerPc);
                if (slotName != null && !slotName.equals(varName)) {
                    handlerBody = renameVarInStatements(handlerBody, slotName, varName);
                }
                // Also rename synthetic var names (e.g., "var3") that come from unnamed slots
                if (slotName == null) {
                    int handlerSlot = findSlotAtHandler(handlerPc);
                    if (handlerSlot >= 0) {
                        String syntheticName = "var" + handlerSlot;
                        if (!syntheticName.equals(varName)) {
                            handlerBody = renameVarInStatements(handlerBody, syntheticName, varName);
                        }
                    }
                }
                // END_CHANGE: ISS-2026-0005-12

                // START_CHANGE: BUG-2026-0091-20260610-1 - Track whether the initial exception
                // store was removed here, so filterFinallyBody does not strip a SECOND statement
                // (a real finally statement) under the assumption it is the exception store.
                int handlerSizeBeforeStoreStrip = handlerBody.size();
                handlerBody = removeInitialExceptionStore(handlerBody, varName);
                boolean exceptionStoreRemoved = handlerBody.size() < handlerSizeBeforeStoreStrip;
                // END_CHANGE: BUG-2026-0091-1

                // START_CHANGE: BUG-2026-0056-20260610-24 - A nested try-catch reconstructed
                // INSIDE this handler body may use the same exception variable name (both
                // default to "e" when no LVT is present). Rename the nested clause and its
                // body so the inner declaration does not collide with the outer catch's.
                handlerBody = disambiguateNestedCatchVars(handlerBody, varName);
                // END_CHANGE: BUG-2026-0056-24

                int handlerLine = findLineForPc(handlerPc, sortedPcs);
                if (handlerLine < 0) handlerLine = tryStartLine;

                // Check if all entries in this group are typed (catch) or untyped (finally)
                boolean allTyped = true;
                boolean anyUntyped = false;
                for (CodeAttribute.ExceptionEntry he : hgEntries) {
                    if (he.catchType <= 0) {
                        anyUntyped = true;
                        allTyped = false;
                    }
                }

                if (allTyped) {
                    // Strip addSuppressed boilerplate from catch bodies
                    int beforeStrip182 = handlerBody.size();
                    handlerBody = stripAddSuppressed(handlerBody);
                    if (handlerBody.size() < beforeStrip182) scaffold182 = true; // BUG-2026-0182
                    // Merge all exception types into a single multi-catch clause
                    List<Type> types = new ArrayList<Type>();
                    // BUG-2026-0137: fragments of a split region repeat the same catch type.
                    Set<Integer> seenCatchTypes137 = new HashSet<Integer>();
                    for (CodeAttribute.ExceptionEntry he : hgEntries) {
                        if (!seenCatchTypes137.add(Integer.valueOf(he.catchType))) continue;
                        String typeName = pool.getClassName(he.catchType);
                        if (typeName == null) {
                            typeName = "java/lang/Exception";
                        }
                        types.add(new ObjectType(typeName));
                    }
                    Statement catchBody = new BlockStatement(handlerLine,
                        handlerBody.isEmpty() ? new ArrayList<Statement>() : handlerBody);
                    catchClauses.add(new TryCatchStatement.CatchClause(
                        types, varName, catchBody));
                } else if (anyUntyped) {
                    // START_CHANGE: BUG-2026-0091-20260610-2 - Tell filterFinallyBody whether the
                    // exception store has already been stripped from the handler body.
                    List<Statement> filteredFinally =
                        filterFinallyBody(handlerBody, exceptionStoreRemoved);
                    // END_CHANGE: BUG-2026-0091-2
                    if (!filteredFinally.isEmpty()) {
                        finallyBody = new BlockStatement(handlerLine, filteredFinally);
                    }
                }
            }

            if (catchClauses.isEmpty() && finallyBody == null) return null;

            if (finallyBody != null && finallyBody instanceof BlockStatement) {
                // START_CHANGE: BUG-2026-0091-20260610-3 - Pass the actual finally statements
                // (not just their count) so dedup only removes a structurally matching tail.
                // The previous count-based truncation deleted real trailing returns (e.g. the
                // synchronized desugar, where finally = [__MONITOREXIT__ marker] and the inlined
                // monitorexit PRECEDES the return).
                List<Statement> finallyStmts = ((BlockStatement) finallyBody).getStatements();
                if (!finallyStmts.isEmpty()) {
                    tryBody = removeDuplicatedFinally(tryBody, finallyStmts);

                    List<TryCatchStatement.CatchClause> cleanedClauses =
                        new ArrayList<TryCatchStatement.CatchClause>();
                    for (TryCatchStatement.CatchClause cc : catchClauses) {
                        if (cc.body instanceof BlockStatement) {
                            List<Statement> cleanedBody = removeDuplicatedFinally(
                                ((BlockStatement) cc.body).getStatements(), finallyStmts);
                            cleanedClauses.add(new TryCatchStatement.CatchClause(
                                cc.exceptionTypes, cc.variableName,
                                new BlockStatement(cc.body.getLineNumber(), cleanedBody)));
                        } else {
                            cleanedClauses.add(cc);
                        }
                    }
                    catchClauses = cleanedClauses;

                    // javac also inlines the finally body on the normal exit path, i.e. at the
                    // START of the code following the try region. Strip a structurally matching
                    // leading duplicate from afterTry. Then, if the finally body itself ends in
                    // a Return/Throw, the whole try statement completes abruptly and anything
                    // left in afterTry is unreachable (javac rejects it) - drop it.
                    afterTry = stripLeadingFinallyDuplicate(afterTry, finallyStmts);
                    Statement lastFinallyStmt = finallyStmts.get(finallyStmts.size() - 1);
                    if ((lastFinallyStmt instanceof ReturnStatement
                            || lastFinallyStmt instanceof ThrowStatement)
                            && !afterTry.isEmpty()) {
                        afterTry = new ArrayList<Statement>();
                    }
                }
                // END_CHANGE: BUG-2026-0091-3
            }

            // Validate try-catch quality: if the try body is empty or only contains
            // trivial statements (variable declarations/assignments), skip the try-catch
            // wrapper. This handles try-with-resources patterns where the compiler generates
            // complex nested try-catch for resource management but the actual body is lost
            // during decompilation. Emitting the original linear statements is more compilable.
            // START_CHANGE: BUG-2026-0180-20261018-5 - never for a normalized javac 7/8 resource try
            boolean forcedTwr180 = false;
            for (CodeAttribute.ExceptionEntry ge180 : groupEntries) {
                if (twrHandlerPcs.contains(Integer.valueOf(ge180.handlerPc))) forcedTwr180 = true;
            }
            // END_CHANGE: BUG-2026-0180-5
            // START_CHANGE: BUG-2026-0182-20261018-1 - A body of plain assignments (`r =
            // Integer.parseInt(s);`, `v = "y" + in.read();`) is real code: skipping it deleted a
            // genuine `catch (Throwable)` / `finally { in.close(); }` and the output still compiled.
            // Skip only an EMPTY body, or a trivial one whose handler was try-with-resources
            // scaffolding (an addSuppressed call, stripped above) - the javac 9+ shape needs that.
            if (!forcedTwr180 && (tryBody.isEmpty() || (scaffold182 && isTryBodyTrivial(tryBody)))
                    && isTryWithResourcesPattern(catchClauses, finallyBody)) {
            // END_CHANGE: BUG-2026-0182-1
                return null; // skip this exception group - emit original statements unchanged
            }

            // START_CHANGE: LIM-0008-20260326-1 - Try-with-resources resource extraction
            List<Statement> resources = null;
            // START_CHANGE: BUG-2026-0182-20261018-2 - The heuristic fold (a try whose finally calls
            // close() on a variable assigned just before it) also captured hand-written
            // `try { .. } finally { ctx.close(); }` - illegal when the type is not AutoCloseable
            // (javax.naming.Context) and different in meaning anyway. javac 7/8 resource tries are
            // now recognised exactly (BUG-2026-0180) and classes older than 51 cannot contain one,
            // so the heuristic is kept only for javac 9+ class files.
            if ((forcedTwr180 || classMajorVersion182 >= 53) && isTryWithResourcesPattern(catchClauses, finallyBody)) {
            // END_CHANGE: BUG-2026-0182-2
                // Extract resource variable names from finally close() calls
                List<String> closeVarNames = extractCloseVariableNames(finallyBody);
                if (!closeVarNames.isEmpty()) {
                    resources = new ArrayList<Statement>();
                    List<Statement> remainingBefore = new ArrayList<Statement>();
                    // START_CHANGE: BUG-2026-0180-20261018-8 - For a normalized javac 7/8 resource
                    // try the resource is exactly the store right before the region: take only that
                    // statement (not every earlier assignment of the slot) and make it a declaration
                    // with the most precise type available.
                    if (forcedTwr180) {
                        remainingBefore.addAll(beforeTry);
                        Statement last180 = beforeTry.isEmpty() ? null : beforeTry.get(beforeTry.size() - 1);
                        String av180 = last180 == null ? null : getAssignedVarName(last180);
                        if (av180 != null && closeVarNames.contains(av180)) {
                            Statement decl180 = asResourceDeclaration180(last180, beforeTry);
                            if (decl180 != null && !(last180 instanceof VariableDeclarationStatement)) {
                                // A plain assignment: the slot is declared elsewhere (an earlier
                                // use or a method-top pre-declaration), so the header needs a
                                // fresh name, used throughout the try body.
                                String fresh180 = av180 + "_r";
                                while (statementsReferenceVar(tryBody, fresh180)
                                        || statementsReferenceVar(beforeTry, fresh180)
                                        || statementsDeclareVar(tryBody, fresh180)) {
                                    fresh180 = fresh180 + "r";
                                }
                                VariableDeclarationStatement d180 = (VariableDeclarationStatement) decl180;
                                decl180 = new VariableDeclarationStatement(d180.getLineNumber(), d180.getType(),
                                    fresh180, d180.getInitializer(), false, false);
                                tryBody = renameVarInStatements(tryBody, av180, fresh180);
                            }
                            if (decl180 != null) {
                                resources.add(decl180);
                                remainingBefore.remove(remainingBefore.size() - 1);
                            }
                        }
                    } else
                    // END_CHANGE: BUG-2026-0180-8
                    for (Statement bs : beforeTry) {
                        String assignedVar = getAssignedVarName(bs);
                        if (assignedVar != null && closeVarNames.contains(assignedVar)) {
                            resources.add(bs);
                        } else {
                            remainingBefore.add(bs);
                        }
                    }
                    if (resources.isEmpty()) {
                        // Also check the tryBody for resource declarations
                        List<Statement> remainingTry = new ArrayList<Statement>();
                        for (Statement ts : tryBody) {
                            String assignedVar = getAssignedVarName(ts);
                            if (assignedVar != null && closeVarNames.contains(assignedVar)) {
                                // BUG-2026-0180: a normalized resource is always a declaration
                                Statement asDecl180 = forcedTwr180 ? asResourceDeclaration180(ts, beforeTry) : null;
                                resources.add(asDecl180 != null ? asDecl180 : ts);
                            } else {
                                remainingTry.add(ts);
                            }
                        }
                        if (!resources.isEmpty()) {
                            tryBody = remainingTry;
                        }
                    } else {
                        beforeTry = remainingBefore;
                    }
                    if (resources.isEmpty()) {
                        resources = null;
                    } else {
                        // Remove compiler-generated Throwable catches and close() finally
                        catchClauses = filterTWRCatchClauses(catchClauses);
                        finallyBody = null;
                    }
                }
            }
            // END_CHANGE: LIM-0008-1

            // START_CHANGE: BUG-2026-0056-20260610-11 - Hoist a variable that is DECLARED inside
            // the try body but referenced in a catch clause, the finally body or the code after
            // the try (e.g. `String r = call();` in the try, `r = fallback;` in the catch,
            // `return r;` after). Leaving the declaration inside the try makes the recompiled
            // catch/after-try references unresolvable. The declaration is demoted to an
            // assignment and a definite-assignment-safe declaration (null / zero initialised)
            // is prepended before the try. Names redeclared in any other region (slot-reuse
            // scopes) are left untouched.
            for (int ti = 0; ti < tryBody.size(); ti++) {
                Statement ts = tryBody.get(ti);
                if (!(ts instanceof VariableDeclarationStatement)) continue;
                VariableDeclarationStatement vds = (VariableDeclarationStatement) ts;
                if (!vds.hasInitializer()) continue;
                String hoistName = vds.getName();
                boolean referencedOutside = statementsReferenceVar(afterTry, hoistName);
                if (!referencedOutside) {
                    for (TryCatchStatement.CatchClause cc : catchClauses) {
                        if (cc.body != null && statementReferencesVar(cc.body, hoistName)) {
                            referencedOutside = true;
                            break;
                        }
                    }
                }
                if (!referencedOutside && finallyBody != null
                        && statementReferencesVar(finallyBody, hoistName)) {
                    referencedOutside = true;
                }
                if (!referencedOutside) continue;
                boolean redeclared = statementsDeclareVar(beforeTry, hoistName)
                    || statementsDeclareVar(afterTry, hoistName);
                if (!redeclared) {
                    for (TryCatchStatement.CatchClause cc : catchClauses) {
                        if (hoistName.equals(cc.variableName)
                                || (cc.body != null && statementDeclaresVar(cc.body, hoistName))) {
                            redeclared = true;
                            break;
                        }
                    }
                }
                if (!redeclared && finallyBody != null
                        && statementDeclaresVar(finallyBody, hoistName)) {
                    redeclared = true;
                }
                if (redeclared) continue;
                Expression defaultInit = defaultInitializerFor(vds.getType(), vds.getLineNumber());
                if (defaultInit == null) continue; // cannot build a DA-safe initializer
                beforeTry.add(new VariableDeclarationStatement(vds.getLineNumber(),
                    vds.getType(), hoistName, defaultInit, false, false));
                tryBody.set(ti, new ExpressionStatement(new AssignmentExpression(
                    vds.getLineNumber(), vds.getType(),
                    new LocalVariableExpression(vds.getLineNumber(), vds.getType(), hoistName, -1),
                    "=", vds.getInitializer())));
            }
            // END_CHANGE: BUG-2026-0056-11

            TryCatchStatement tcs = new TryCatchStatement(
                tryStartLine,
                new BlockStatement(tryStartLine, tryBody),
                catchClauses,
                finallyBody,
                resources);

            List<Statement> newStatements = new ArrayList<Statement>();
            newStatements.addAll(beforeTry);
            newStatements.add(tcs);
            newStatements.addAll(afterTry);
            return newStatements;
    }

    /**
     * Try to apply the group inside the body of a top-level compound statement whose body's
     * line span contains the try region's start line (try-catch inside a loop, an if branch,
     * a synchronized block...). Returns the rebuilt statement list or null.
     */
    private List<Statement> applyGroupInsideCompound(List<Statement> statements,
                                                      List<CodeAttribute.ExceptionEntry> groupEntries,
                                                      List<Integer> sortedPcs,
                                                      int tryStartLine) {
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            List<List<Statement>> bodies = compoundBodies(s);
            if (bodies == null) continue;
            for (int bi = 0; bi < bodies.size(); bi++) {
                List<Statement> innerStmts = bodies.get(bi);
                // START_CHANGE: BUG-2026-0137-20261015-1 - Without a LineNumberTable the region has
                // no start line, so the line-span test rejected EVERY compound body and a try nested
                // in a loop or an if lost its catch clauses at -g:none. applyGroup decides region
                // membership by bytecode pc in that mode and returns null when the body holds no
                // statement of the region, so every body can simply be offered.
                if (tryStartLine >= 0 || statementPcs == null || statementPcs.isEmpty()) {
                    int[] span = lineSpan(innerStmts);
                    if (span == null || tryStartLine < span[0] || tryStartLine > span[1]) continue;
                }
                // END_CHANGE: BUG-2026-0137-1
                List<Statement> rebuilt = applyGroup(
                    new ArrayList<Statement>(innerStmts), groupEntries, sortedPcs);
                if (rebuilt == null) continue;
                Statement newCompound = rebuildCompound(s, bi, rebuilt);
                if (newCompound == null) continue;
                List<Statement> out = new ArrayList<Statement>(statements);
                out.set(i, newCompound);
                return out;
            }
        }
        return null;
    }

    /** The block bodies of a compound statement (or null when not a supported compound). */
    private static List<List<Statement>> compoundBodies(Statement s) {
        List<List<Statement>> bodies = null;
        Statement[] parts = compoundParts(s);
        if (parts == null) return null;
        for (int i = 0; i < parts.length; i++) {
            if (!(parts[i] instanceof BlockStatement)) return null;
            if (bodies == null) bodies = new ArrayList<List<Statement>>();
            bodies.add(((BlockStatement) parts[i]).getStatements());
        }
        return bodies;
    }

    private static Statement[] compoundParts(Statement s) {
        if (s instanceof WhileStatement) {
            return new Statement[] { ((WhileStatement) s).getBody() };
        }
        if (s instanceof DoWhileStatement) {
            return new Statement[] { ((DoWhileStatement) s).getBody() };
        }
        if (s instanceof ForStatement) {
            return new Statement[] { ((ForStatement) s).getBody() };
        }
        if (s instanceof ForEachStatement) {
            return new Statement[] { ((ForEachStatement) s).getBody() };
        }
        if (s instanceof SynchronizedStatement) {
            return new Statement[] { ((SynchronizedStatement) s).getBody() };
        }
        if (s instanceof LabelStatement) {
            return new Statement[] { ((LabelStatement) s).getBody() };
        }
        if (s instanceof IfStatement) {
            return new Statement[] { ((IfStatement) s).getThenBody() };
        }
        if (s instanceof IfElseStatement) {
            return new Statement[] { ((IfElseStatement) s).getThenBody(),
                                     ((IfElseStatement) s).getElseBody() };
        }
        return null;
    }

    /** Rebuild the compound statement with body index {@code bi} replaced. */
    private static Statement rebuildCompound(Statement s, int bi, List<Statement> newBody) {
        Statement[] parts = compoundParts(s);
        if (parts == null || bi >= parts.length) return null;
        BlockStatement nb = new BlockStatement(parts[bi].getLineNumber(), newBody);
        if (s instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) s;
            return new WhileStatement(ws.getLineNumber(), ws.getCondition(), nb);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) s;
            return new DoWhileStatement(dws.getLineNumber(), dws.getCondition(), nb);
        }
        if (s instanceof ForStatement) {
            ForStatement fs = (ForStatement) s;
            return new ForStatement(fs.getLineNumber(), fs.getInit(), fs.getCondition(),
                fs.getUpdate(), nb);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement fes = (ForEachStatement) s;
            return new ForEachStatement(fes.getLineNumber(), fes.getVariableType(),
                fes.getVariableName(), fes.getIterable(), nb);
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement ss = (SynchronizedStatement) s;
            return new SynchronizedStatement(ss.getLineNumber(), ss.getMonitor(), nb);
        }
        if (s instanceof LabelStatement) {
            LabelStatement ls = (LabelStatement) s;
            return new LabelStatement(ls.getLineNumber(), ls.getLabel(), nb);
        }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            return new IfStatement(is.getLineNumber(), is.getCondition(), nb);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) s;
            return bi == 0
                ? new IfElseStatement(ies.getLineNumber(), ies.getCondition(), nb, ies.getElseBody())
                : new IfElseStatement(ies.getLineNumber(), ies.getCondition(), ies.getThenBody(), nb);
        }
        return null;
    }

    /** Min/max source line over a statement list (recursive), or null when no line is known. */
    private static int[] lineSpan(List<Statement> stmts) {
        int[] span = new int[] { Integer.MAX_VALUE, -1 };
        collectLineSpan(stmts, span);
        if (span[1] < 0) return null;
        return span;
    }

    private static void collectLineSpan(List<Statement> stmts, int[] span) {
        if (stmts == null) return;
        for (Statement s : stmts) {
            collectLineSpan(s, span);
        }
    }

    private static void collectLineSpan(Statement s, int[] span) {
        if (s == null) return;
        int line = s.getLineNumber();
        if (line > 0) {
            if (line < span[0]) span[0] = line;
            if (line > span[1]) span[1] = line;
        }
        if (s instanceof BlockStatement) {
            collectLineSpan(((BlockStatement) s).getStatements(), span);
        } else if (s instanceof IfStatement) {
            collectLineSpan(((IfStatement) s).getThenBody(), span);
        } else if (s instanceof IfElseStatement) {
            collectLineSpan(((IfElseStatement) s).getThenBody(), span);
            collectLineSpan(((IfElseStatement) s).getElseBody(), span);
        } else if (s instanceof WhileStatement) {
            collectLineSpan(((WhileStatement) s).getBody(), span);
        } else if (s instanceof DoWhileStatement) {
            collectLineSpan(((DoWhileStatement) s).getBody(), span);
        } else if (s instanceof ForStatement) {
            collectLineSpan(((ForStatement) s).getInit(), span);
            collectLineSpan(((ForStatement) s).getBody(), span);
        } else if (s instanceof ForEachStatement) {
            collectLineSpan(((ForEachStatement) s).getBody(), span);
        } else if (s instanceof SynchronizedStatement) {
            collectLineSpan(((SynchronizedStatement) s).getBody(), span);
        } else if (s instanceof LabelStatement) {
            collectLineSpan(((LabelStatement) s).getBody(), span);
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            collectLineSpan(t.getTryBody(), span);
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                collectLineSpan(cc.body, span);
            }
            collectLineSpan(t.getFinallyBody(), span);
        } else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase sc : ((SwitchStatement) s).getCases()) {
                collectLineSpan(sc.getStatements(), span);
            }
        }
    }
    // END_CHANGE: BUG-2026-0056-20

    // START_CHANGE: BUG-2026-0056-20260610-25 - Rename nested catch clauses that collide with
    // the enclosing handler's exception variable name.
    private static List<Statement> disambiguateNestedCatchVars(List<Statement> stmts,
                                                                String outerName) {
        List<Statement> out = new ArrayList<Statement>(stmts.size());
        boolean changed = false;
        for (Statement s : stmts) {
            Statement r = disambiguateNestedCatchVars(s, outerName);
            if (r != s) changed = true;
            out.add(r);
        }
        return changed ? out : stmts;
    }

    private static Statement disambiguateNestedCatchVars(Statement s, String outerName) {
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            boolean changed = false;
            Statement tryB = t.getTryBody();
            if (tryB instanceof BlockStatement) {
                List<Statement> nb = disambiguateNestedCatchVars(
                    ((BlockStatement) tryB).getStatements(), outerName);
                if (nb != ((BlockStatement) tryB).getStatements()) {
                    tryB = new BlockStatement(tryB.getLineNumber(), nb);
                    changed = true;
                }
            }
            List<TryCatchStatement.CatchClause> ccs =
                new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                Statement body = cc.body;
                if (body instanceof BlockStatement) {
                    List<Statement> nb = disambiguateNestedCatchVars(
                        ((BlockStatement) body).getStatements(), outerName);
                    if (nb != ((BlockStatement) body).getStatements()) {
                        body = new BlockStatement(body.getLineNumber(), nb);
                    }
                }
                String vn = cc.variableName;
                if (outerName != null && outerName.equals(vn)) {
                    int suffix = 2;
                    String fresh = vn + suffix;
                    while (statementReferencesVar(body, fresh)) {
                        suffix++;
                        fresh = vn + suffix;
                    }
                    body = renameVarInStatement(body, vn, fresh);
                    vn = fresh;
                }
                if (body != cc.body || vn != cc.variableName) {
                    changed = true;
                    ccs.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, vn, body));
                } else {
                    ccs.add(cc);
                }
            }
            Statement fin = t.getFinallyBody();
            if (fin instanceof BlockStatement) {
                List<Statement> nb = disambiguateNestedCatchVars(
                    ((BlockStatement) fin).getStatements(), outerName);
                if (nb != ((BlockStatement) fin).getStatements()) {
                    fin = new BlockStatement(fin.getLineNumber(), nb);
                    changed = true;
                }
            }
            if (changed) {
                return new TryCatchStatement(t.getLineNumber(), tryB, ccs, fin, t.getResources());
            }
            return s;
        }
        if (s instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) s;
            List<Statement> nb = disambiguateNestedCatchVars(bs.getStatements(), outerName);
            if (nb != bs.getStatements()) {
                return new BlockStatement(bs.getLineNumber(), nb);
            }
            return s;
        }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            Statement then = disambiguateNestedCatchVars(is.getThenBody(), outerName);
            if (then != is.getThenBody()) {
                return new IfStatement(is.getLineNumber(), is.getCondition(), then);
            }
            return s;
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) s;
            Statement then = disambiguateNestedCatchVars(ies.getThenBody(), outerName);
            Statement els = disambiguateNestedCatchVars(ies.getElseBody(), outerName);
            if (then != ies.getThenBody() || els != ies.getElseBody()) {
                return new IfElseStatement(ies.getLineNumber(), ies.getCondition(), then, els);
            }
            return s;
        }
        if (s instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) s;
            Statement body = disambiguateNestedCatchVars(ws.getBody(), outerName);
            if (body != ws.getBody()) {
                return new WhileStatement(ws.getLineNumber(), ws.getCondition(), body);
            }
            return s;
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) s;
            Statement body = disambiguateNestedCatchVars(dws.getBody(), outerName);
            if (body != dws.getBody()) {
                return new DoWhileStatement(dws.getLineNumber(), dws.getCondition(), body);
            }
            return s;
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement ss = (SynchronizedStatement) s;
            Statement body = disambiguateNestedCatchVars(ss.getBody(), outerName);
            if (body != ss.getBody()) {
                return new SynchronizedStatement(ss.getLineNumber(), ss.getMonitor(), body);
            }
            return s;
        }
        return s;
    }
    // END_CHANGE: BUG-2026-0056-25

    // START_CHANGE: BUG-2026-0056-20260610-13 - Helpers for hoisting try-declared variables and
    // retyping null-initialised Object declarations.

    /** True when any statement of the list references (reads, writes or declares) the variable. */
    private static boolean statementsReferenceVar(List<Statement> stmts, String name) {
        if (stmts == null) return false;
        for (Statement s : stmts) {
            if (statementReferencesVar(s, name)) return true;
        }
        return false;
    }

    /**
     * True when the statement references the variable. Reuses the rename walker: renaming the
     * variable to itself returns a NEW node exactly when at least one reference was found.
     */
    private static boolean statementReferencesVar(Statement s, String name) {
        if (s == null) return false;
        return renameVarInStatement(s, name, name) != s;
    }

    /** True when any statement of the list declares the variable (at any nesting depth). */
    private static boolean statementsDeclareVar(List<Statement> stmts, String name) {
        if (stmts == null) return false;
        for (Statement s : stmts) {
            if (statementDeclaresVar(s, name)) return true;
        }
        return false;
    }

    private static boolean statementDeclaresVar(Statement s, String name) {
        if (s == null) return false;
        if (s instanceof VariableDeclarationStatement) {
            return name.equals(((VariableDeclarationStatement) s).getName());
        }
        if (s instanceof BlockStatement) {
            return statementsDeclareVar(((BlockStatement) s).getStatements(), name);
        }
        if (s instanceof IfStatement) {
            return statementDeclaresVar(((IfStatement) s).getThenBody(), name);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) s;
            return statementDeclaresVar(ies.getThenBody(), name)
                || statementDeclaresVar(ies.getElseBody(), name);
        }
        if (s instanceof WhileStatement) {
            return statementDeclaresVar(((WhileStatement) s).getBody(), name);
        }
        if (s instanceof DoWhileStatement) {
            return statementDeclaresVar(((DoWhileStatement) s).getBody(), name);
        }
        if (s instanceof ForStatement) {
            ForStatement fs = (ForStatement) s;
            return statementDeclaresVar(fs.getInit(), name)
                || statementDeclaresVar(fs.getBody(), name);
        }
        if (s instanceof SynchronizedStatement) {
            return statementDeclaresVar(((SynchronizedStatement) s).getBody(), name);
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (statementDeclaresVar(t.getTryBody(), name)) return true;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                if (name.equals(cc.variableName) || statementDeclaresVar(cc.body, name)) {
                    return true;
                }
            }
            return statementDeclaresVar(t.getFinallyBody(), name);
        }
        if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase sc : ((SwitchStatement) s).getCases()) {
                if (statementsDeclareVar(sc.getStatements(), name)) return true;
            }
        }
        return false;
    }

    /**
     * Definite-assignment-safe default initializer for a hoisted declaration:
     * null for reference types, zero/false for primitives, null (= skip) when unknown.
     */
    private static Expression defaultInitializerFor(Type type, int line) {
        if (type instanceof it.denzosoft.javadecompiler.model.javasyntax.type.PrimitiveType) {
            String desc = type.getDescriptor();
            if ("Z".equals(desc)) return new BooleanExpression(line, false);
            if ("J".equals(desc)) return new LongConstantExpression(line, 0L);
            if ("F".equals(desc)) return new FloatConstantExpression(line, 0.0f);
            if ("D".equals(desc)) return new DoubleConstantExpression(line, 0.0);
            return new IntegerConstantExpression(line, 0);
        }
        if (type instanceof it.denzosoft.javadecompiler.model.javasyntax.type.VoidType) {
            return null;
        }
        if (type != null) {
            return new NullExpression(type);
        }
        return null;
    }

    /**
     * Retype top-level `Object v = null` declarations when every assignment to v in the method
     * targets one single concrete reference type.
     */
    private static void retypeNullObjectDeclarations(List<Statement> statements) {
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (!(s instanceof VariableDeclarationStatement)) continue;
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (!vds.hasInitializer() || !(vds.getInitializer() instanceof NullExpression)) continue;
            if (!(vds.getType() instanceof ObjectType)) continue;
            if (!"java/lang/Object".equals(((ObjectType) vds.getType()).getInternalName())) continue;
            Set<String> assignedTypes = new HashSet<String>();
            boolean[] unknown = new boolean[1];
            for (Statement other : statements) {
                if (other == s) continue;
                collectAssignedTypes(other, vds.getName(), assignedTypes, unknown);
            }
            if (unknown[0] || assignedTypes.size() != 1) continue;
            String internalName = assignedTypes.iterator().next();
            if ("java/lang/Object".equals(internalName)) continue;
            statements.set(i, new VariableDeclarationStatement(vds.getLineNumber(),
                new ObjectType(internalName), vds.getName(), vds.getInitializer(),
                vds.isFinal(), vds.isVar()));
        }
    }

    private static void collectAssignedTypes(Statement s, String name,
                                              Set<String> out, boolean[] unknown) {
        if (s == null || unknown[0]) return;
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) e;
                if (ae.getLeft() instanceof LocalVariableExpression
                        && name.equals(((LocalVariableExpression) ae.getLeft()).getName())) {
                    if (!"=".equals(ae.getOperator())) {
                        unknown[0] = true;
                        return;
                    }
                    Expression rhs = ae.getRight();
                    if (rhs instanceof NullExpression) {
                        return; // null is compatible with any reference type
                    }
                    Type rhsType = rhs.getType();
                    if (rhsType instanceof ObjectType
                            && ((ObjectType) rhsType).getDimension() == 0) {
                        out.add(((ObjectType) rhsType).getInternalName());
                    } else {
                        unknown[0] = true;
                    }
                }
            }
        } else if (s instanceof VariableDeclarationStatement) {
            // a redeclaration of the same name means separate scopes - do not retype
            if (name.equals(((VariableDeclarationStatement) s).getName())) {
                unknown[0] = true;
            }
        } else if (s instanceof BlockStatement) {
            for (Statement c : ((BlockStatement) s).getStatements()) {
                collectAssignedTypes(c, name, out, unknown);
            }
        } else if (s instanceof IfStatement) {
            collectAssignedTypes(((IfStatement) s).getThenBody(), name, out, unknown);
        } else if (s instanceof IfElseStatement) {
            collectAssignedTypes(((IfElseStatement) s).getThenBody(), name, out, unknown);
            collectAssignedTypes(((IfElseStatement) s).getElseBody(), name, out, unknown);
        } else if (s instanceof WhileStatement) {
            collectAssignedTypes(((WhileStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof DoWhileStatement) {
            collectAssignedTypes(((DoWhileStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof ForStatement) {
            collectAssignedTypes(((ForStatement) s).getInit(), name, out, unknown);
            collectAssignedTypes(((ForStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof ForEachStatement) {
            collectAssignedTypes(((ForEachStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof SynchronizedStatement) {
            collectAssignedTypes(((SynchronizedStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof LabelStatement) {
            collectAssignedTypes(((LabelStatement) s).getBody(), name, out, unknown);
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            collectAssignedTypes(t.getTryBody(), name, out, unknown);
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                collectAssignedTypes(cc.body, name, out, unknown);
            }
            collectAssignedTypes(t.getFinallyBody(), name, out, unknown);
        } else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase sc : ((SwitchStatement) s).getCases()) {
                for (Statement c : sc.getStatements()) {
                    collectAssignedTypes(c, name, out, unknown);
                }
            }
        }
    }
    // END_CHANGE: BUG-2026-0056-13

    // START_CHANGE: BUG-2026-0068-20260610-2 - Detect a compiler-generated handler-protection
    // entry: it starts exactly at another entry's handlerPc AND a different entry already
    // routes to the identical (handlerPc, catchType). Both conditions are required so that
    // genuine nested user try regions (which start at user code, not at a handler, or which
    // have their own distinct handler) are never merged away.
    private static boolean isHandlerProtectionEntry(CodeAttribute.ExceptionEntry entry,
                                                     CodeAttribute.ExceptionEntry[] table) {
        boolean startsAtHandler = false;
        for (int i = 0; i < table.length; i++) {
            CodeAttribute.ExceptionEntry other = table[i];
            if (other != entry && other.handlerPc == entry.startPc) {
                startsAtHandler = true;
                break;
            }
        }
        if (!startsAtHandler) return false;
        for (int i = 0; i < table.length; i++) {
            CodeAttribute.ExceptionEntry other = table[i];
            if (other != entry && other.handlerPc == entry.handlerPc
                    && other.catchType == entry.catchType
                    && other.startPc != entry.startPc) {
                return true;
            }
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0068-2

    /**
     * Check if the try body is trivial (empty or only variable declarations/assignments).
     * A trivial try body means the decompiler failed to capture the real method logic
     * inside the try block.
     */
    private boolean isTryBodyTrivial(List<Statement> tryBody) {
        if (tryBody.isEmpty()) return true;
        for (Statement s : tryBody) {
            if (s instanceof VariableDeclarationStatement) {
                continue; // trivial
            }
            if (s instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) s).getExpression();
                if (expr instanceof AssignmentExpression) {
                    continue; // trivial assignment
                }
            }
            return false; // non-trivial statement found
        }
        return true;
    }

    /**
     * Check if this looks like a try-with-resources compiler pattern.
     * Indicators: catches Throwable, or has finally with close() call,
     * or catch body references addSuppressed.
     */
    private boolean isTryWithResourcesPattern(List<TryCatchStatement.CatchClause> catchClauses,
                                               Statement finallyBody) {
        // Check if any catch clause catches Throwable (compiler-generated)
        for (TryCatchStatement.CatchClause cc : catchClauses) {
            if (cc.exceptionTypes != null) {
                for (Type t : cc.exceptionTypes) {
                    if (t instanceof ObjectType) {
                        String name = ((ObjectType) t).getInternalName();
                        if ("java/lang/Throwable".equals(name) || "Throwable".equals(name)) {
                            return true;
                        }
                    }
                }
            }
        }
        // Check if finally body contains close() call (resource cleanup)
        if (finallyBody instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) finallyBody).getStatements()) {
                if (s instanceof ExpressionStatement) {
                    Expression expr = ((ExpressionStatement) s).getExpression();
                    if (expr instanceof MethodInvocationExpression) {
                        String methodName = ((MethodInvocationExpression) expr).getMethodName();
                        if ("close".equals(methodName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private List<Statement> decodeHandlerBlocks(int handlerPc,
                                                 List<CodeAttribute.ExceptionEntry> sortedEntries,
                                                 int currentIndex,
                                                 int mergePc) {
        List<Statement> result = new ArrayList<Statement>();
        BasicBlock handlerBlock = cfg.getBlockAtPc(handlerPc);
        if (handlerBlock == null) return result;

        Set<Integer> stopPcs = new HashSet<Integer>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            // Skip entries that share the same handlerPc as the current entry
            // (multi-catch handlers) - they should not be stop points
            if (sortedEntries.get(i).handlerPc != handlerPc) {
                stopPcs.add(sortedEntries.get(i).handlerPc);
            }
        }
        if (mergePc >= 0) {
            stopPcs.add(mergePc);
        }

        // START_CHANGE: BUG-2026-0056-20260610-4 - When the handler body contains internal
        // control flow (a conditional or a switch), the legacy linear walk cannot represent it:
        // it followed the fall-through edge only, emitting the then-branch unconditionally and
        // silently dropping the condition and the else-branch (catch composing an exception
        // message via if/else lost both). Delegate such handlers to the StructuredFlowBuilder,
        // which structures if/else, short-circuit conditions, switches and loops. The stop PC is
        // the try-catch merge point (the handler's terminator goto targets it); when no merge
        // point is known, fall back to the closest stop PC past the handler.
        if (flowBuilder != null && handlerRegionHasControlFlow(handlerBlock, stopPcs)) {
            int delegStopPc = (mergePc > handlerPc) ? mergePc : -1;
            if (delegStopPc < 0) {
                for (Integer sp : stopPcs) {
                    int spv = sp.intValue();
                    if (spv > handlerPc && (delegStopPc < 0 || spv < delegStopPc)) {
                        delegStopPc = spv;
                    }
                }
            }
            List<Statement> structured = flowBuilder.buildHandlerBody(handlerBlock, delegStopPc);
            if (structured != null && !structured.isEmpty()) {
                return structured;
            }
        }
        // END_CHANGE: BUG-2026-0056-4

        Set<Integer> visited = new HashSet<Integer>();
        collectHandlerStatements(handlerBlock, result, visited, stopPcs);

        // START_CHANGE: BUG-2026-0056-20260610-22 - A nested try-catch INSIDE this handler
        // (e.g. an inner try in a catch body) only materialises here: its statements are not in
        // the main statement list, so its group found no home earlier and was queued. Offer the
        // pending groups this handler body. A group is popped before the attempt so the
        // recursive handler decode cannot re-enter it.
        result = applyPendingGroups(result);
        // END_CHANGE: BUG-2026-0056-22

        return result;
    }

    // START_CHANGE: BUG-2026-0056-20260610-23 - Apply pending (nested) exception groups to a
    // freshly decoded handler body.
    private List<Statement> applyPendingGroups(List<Statement> handlerBody) {
        if (pendingNestedGroups == null || pendingNestedGroups.isEmpty()
                || handlerBody.isEmpty()) {
            return handlerBody;
        }
        List<Integer> sortedPcs = new ArrayList<Integer>(pcToLine.keySet());
        Collections.sort(sortedPcs);
        // Reverse startPc order: inner-most groups first, like the main loop.
        List<List<CodeAttribute.ExceptionEntry>> candidates =
            new ArrayList<List<CodeAttribute.ExceptionEntry>>(pendingNestedGroups);
        Collections.sort(candidates, new Comparator<List<CodeAttribute.ExceptionEntry>>() {
            public int compare(List<CodeAttribute.ExceptionEntry> a,
                               List<CodeAttribute.ExceptionEntry> b) {
                return b.get(0).startPc - a.get(0).startPc;
            }
        });
        for (List<CodeAttribute.ExceptionEntry> group : candidates) {
            pendingNestedGroups.remove(group);
            List<Statement> replaced = applyGroup(handlerBody, group, sortedPcs);
            if (replaced != null) {
                handlerBody = replaced;
            } else {
                pendingNestedGroups.add(group);
            }
        }
        return handlerBody;
    }
    // END_CHANGE: BUG-2026-0056-23

    // START_CHANGE: BUG-2026-0056-20260610-5 - Dry-run of the legacy linear handler walk that
    // reports whether a conditional or switch block is reachable inside the handler region.
    // Mirrors collectHandlerStatements' traversal so delegation triggers exactly for the
    // handlers the linear walk would mis-decode.
    private boolean handlerRegionHasControlFlow(BasicBlock start, Set<Integer> stopPcs) {
        Set<Integer> visited = new HashSet<Integer>();
        BasicBlock block = start;
        while (block != null) {
            if (visited.contains(block.startPc)) return false;
            if (stopPcs.contains(block.startPc)) return false;
            visited.add(block.startPc);

            if (block.type == BasicBlock.CONDITIONAL || block.type == BasicBlock.SWITCH) {
                return true;
            }
            if (block.isReturn() || block.isThrow()) {
                return false;
            } else if (block.isGoto()) {
                BasicBlock target = block.trueSuccessor;
                if (target != null && target.startPc > block.startPc
                    && !stopPcs.contains(target.startPc)) {
                    block = target;
                } else {
                    return false;
                }
            } else if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
                block = block.trueSuccessor;
            } else {
                return false;
            }
        }
        return false;
    }
    // END_CHANGE: BUG-2026-0056-5

    private int findTryCatchMergePc(int tryEndPc) {
        BasicBlock gotoBlock = cfg.getBlockAtPc(tryEndPc);
        if (gotoBlock != null && gotoBlock.isGoto()) {
            return gotoBlock.branchTargetPc;
        }

        for (BasicBlock block : cfg.getBlocks()) {
            if (block.endPc == tryEndPc && block.isGoto()) {
                return block.branchTargetPc;
            }
        }

        if (gotoBlock != null) {
            return tryEndPc;
        }
        return -1;
    }

    private void collectHandlerStatements(BasicBlock block, List<Statement> output,
                                           Set<Integer> visited, Set<Integer> stopPcs) {
        while (block != null) {
            if (visited.contains(block.startPc)) return;
            if (stopPcs.contains(block.startPc)) return;
            visited.add(block.startPc);

            if (block.statements != null && !block.statements.isEmpty()) {
                output.addAll(block.statements);
            }

            if (block.isReturn() || block.isThrow()) {
                return;
            } else if (block.isGoto()) {
                BasicBlock target = block.trueSuccessor;
                if (target != null && target.startPc > block.startPc
                    && !stopPcs.contains(target.startPc)) {
                    block = target;
                } else {
                    return;
                }
            } else if (block.type == BasicBlock.FALL_THROUGH || block.type == BasicBlock.NORMAL) {
                block = block.trueSuccessor;
            // START_CHANGE: BUG-2026-0056-20260421-4 - Walk through conditional blocks in the
            // handler body. The catch body may itself contain an if/else whose branches later
            // merge before the throw/return terminator. Previously we stopped at the first
            // conditional and produced a truncated catch (only the first assignment visible).
            // Follow the fall-through edge by default; if that ends up at a stop PC we try
            // the branch target instead.
            } else if (block.type == BasicBlock.CONDITIONAL) {
                BasicBlock fall = block.falseSuccessor;
                BasicBlock br = block.trueSuccessor;
                if (fall != null && !stopPcs.contains(fall.startPc) && !visited.contains(fall.startPc)) {
                    block = fall;
                } else if (br != null && !stopPcs.contains(br.startPc) && !visited.contains(br.startPc)) {
                    block = br;
                } else {
                    return;
                }
            // END_CHANGE: BUG-2026-0056-4
            } else {
                return;
            }
        }
    }

    // START_CHANGE: BUG-2026-0056-20260421-3 - Find the earliest line number AFTER every handler
    // block of the current try-region. Uses the handler exit / merge PC from the CFG when
    // available, falling back to the line of the PC that follows the last handler block.
    // Returns -1 if no "after-try" region can be identified (callers should then preserve the
    // legacy behaviour of dropping anything past firstHandlerLine).
    private int computeAfterTryStartLine(int firstHandlerPc, int lastHandlerPc,
                                          int tryEndPc, List<Integer> sortedPcs) {
        int mergePc = findTryCatchMergePc(tryEndPc);
        if (mergePc >= 0) {
            int mergeLine = findLineForPc(mergePc, sortedPcs);
            if (mergeLine > 0) return mergeLine;
        }
        // Fall back: take the end PC of the block that contains the last handler.
        if (lastHandlerPc >= 0 && cfg != null) {
            BasicBlock last = cfg.getBlockAtPc(lastHandlerPc);
            if (last != null && last.endPc > lastHandlerPc) {
                int afterLine = findLineForPc(last.endPc, sortedPcs);
                if (afterLine > 0) return afterLine;
            }
        }
        return -1;
    }
    // END_CHANGE: BUG-2026-0056-3

    public int findLineForPc(int pc, List<Integer> sortedPcs) {
        Integer line = pcToLine.get(pc);
        if (line != null) return line.intValue();

        int result = -1;
        for (int i = 0; i < sortedPcs.size(); i++) {
            int sortedPc = sortedPcs.get(i).intValue();
            if (sortedPc <= pc) {
                result = pcToLine.get(sortedPcs.get(i)).intValue();
            } else {
                break;
            }
        }
        return result;
    }

    public int findLineBeforePc(int pc, List<Integer> sortedPcs) {
        int result = -1;
        for (int i = 0; i < sortedPcs.size(); i++) {
            int sortedPc = sortedPcs.get(i).intValue();
            if (sortedPc < pc) {
                result = pcToLine.get(sortedPcs.get(i)).intValue();
            } else {
                break;
            }
        }
        return result;
    }

    public String findExceptionVarName(int handlerPc) {
        // START_CHANGE: ISS-2026-0005-20260324-8 - Prefer handler-specific var name from LVT
        if (handlerVarNames != null) {
            String hvName = handlerVarNames.get(handlerPc);
            if (hvName != null) return hvName;
        }
        // END_CHANGE: ISS-2026-0005-8
        if (bytecode == null || handlerPc >= bytecode.length) return "e";

        int opcode = bytecode[handlerPc] & 0xFF;
        int varIndex = -1;

        if (opcode == 0x3A) {
            if (handlerPc + 1 < bytecode.length) {
                varIndex = bytecode[handlerPc + 1] & 0xFF;
            }
        } else if (opcode >= 0x4B && opcode <= 0x4E) {
            varIndex = opcode - 0x4B;
        }

        if (varIndex >= 0) {
            String name = localVarNames.get(varIndex);
            if (name != null) return name;
            // Use the same auto-generated name format as the bytecode decoder
            return "var" + varIndex;
        }

        return "e";
    }

    public static List<Statement> removeInitialExceptionStore(List<Statement> handlerBody, String varName) {
        if (handlerBody.isEmpty()) return handlerBody;

        Statement first = handlerBody.get(0);
        boolean shouldRemove = false;

        if (first instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) first;
            // START_CHANGE: ISS-2026-0005-20260324-9 - Remove initial exception store by name match or slot reuse
            if (varName.equals(vds.getName()) || isExceptionStoreAssignment(first)) {
                shouldRemove = true;
            }
            // END_CHANGE: ISS-2026-0005-9
        } else if (first instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) first).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    String name = ((LocalVariableExpression) ae.getLeft()).getName();
                    // START_CHANGE: ISS-2026-0005-20260324-10 - Also remove if RHS is null (exception store with reused slot)
                    if (varName.equals(name) || ae.getRight() instanceof NullExpression) {
                        shouldRemove = true;
                    }
                    // END_CHANGE: ISS-2026-0005-10
                }
            }
        }

        if (shouldRemove) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 1; i < handlerBody.size(); i++) {
                filtered.add(handlerBody.get(i));
            }
            return filtered;
        }
        return handlerBody;
    }

    // START_CHANGE: ISS-2026-0005-20260324-11 - Check if a statement is a null-assignment (exception store artifact)
    private static boolean isExceptionStoreAssignment(Statement stmt) {
        if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            return vds.hasInitializer() && vds.getInitializer() instanceof NullExpression;
        }
        return false;
    }
    // END_CHANGE: ISS-2026-0005-11

    // START_CHANGE: ISS-2026-0005-20260324-13 - Find slot variable name at handler PC from localVarNames
    private String findSlotNameAtHandler(int handlerPc) {
        if (bytecode == null || handlerPc >= bytecode.length) return null;
        int varIndex = findSlotAtHandler(handlerPc);
        if (varIndex >= 0) {
            return localVarNames.get(varIndex);
        }
        return null;
    }

    private int findSlotAtHandler(int handlerPc) {
        if (bytecode == null || handlerPc >= bytecode.length) return -1;
        int opcode = bytecode[handlerPc] & 0xFF;
        if (opcode == 0x3A && handlerPc + 1 < bytecode.length) {
            return bytecode[handlerPc + 1] & 0xFF;
        } else if (opcode >= 0x4B && opcode <= 0x4E) {
            return opcode - 0x4B;
        }
        return -1;
    }

    private static List<Statement> renameVarInStatements(List<Statement> stmts, String oldName, String newName) {
        List<Statement> result = new ArrayList<Statement>(stmts.size());
        for (Statement s : stmts) {
            result.add(renameVarInStatement(s, oldName, newName));
        }
        return result;
    }

    private static Statement renameVarInStatement(Statement s, String oldName, String newName) {
        // START_CHANGE: BUG-2026-0143-20261017-1 - The hand-written walker below skipped for,
        // for-each, labels, lambdas, array initializers and more, so a catch body that used the
        // exception inside a loop kept the slot's synthetic name (`var1.getCause()` under
        // `catch (... e)`). The complete AstMapper.Renamer replaces it; the old walker is kept
        // unreachable for reference only.
        if (s != null) return new AstMapper.Renamer(oldName, newName).map(s);
        // END_CHANGE: BUG-2026-0143-1
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            Expression renamed = renameVarInExpression(expr, oldName, newName);
            if (renamed != expr) {
                return new ExpressionStatement(renamed);
            }
        } else if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (oldName.equals(vds.getName())) {
                return new VariableDeclarationStatement(vds.getLineNumber(), vds.getType(), newName,
                    vds.hasInitializer() ? renameVarInExpression(vds.getInitializer(), oldName, newName) : null,
                    vds.isFinal(), vds.isVar());
            }
        } else if (s instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) s;
            if (rs.hasExpression()) {
                Expression renamed = renameVarInExpression(rs.getExpression(), oldName, newName);
                if (renamed != rs.getExpression()) {
                    return new ReturnStatement(rs.getLineNumber(), renamed);
                }
            }
        } else if (s instanceof ThrowStatement) {
            ThrowStatement ts = (ThrowStatement) s;
            Expression renamed = renameVarInExpression(ts.getExpression(), oldName, newName);
            if (renamed != ts.getExpression()) {
                return new ThrowStatement(ts.getLineNumber(), renamed);
            }
        } else if (s instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) s;
            // START_CHANGE: BUG-2026-0056-20260610-26 - Preserve identity when nothing changed:
            // statementReferencesVar relies on `renamed != original` to detect references, and
            // the unconditional copy made every block look like it referenced every name
            // (spinning the fresh-name search in disambiguateNestedCatchVars forever).
            List<Statement> renamed = renameVarInStatements(bs.getStatements(), oldName, newName);
            boolean blockChanged = false;
            for (int i = 0; i < renamed.size(); i++) {
                if (renamed.get(i) != bs.getStatements().get(i)) {
                    blockChanged = true;
                    break;
                }
            }
            if (blockChanged) {
                return new BlockStatement(bs.getLineNumber(), renamed);
            }
            // END_CHANGE: BUG-2026-0056-26
        // START_CHANGE: BUG-2026-0056-20260610-6 - Recurse into structured statements. Handler
        // bodies are now decoded by the StructuredFlowBuilder and may contain if/else, loops,
        // switches and synchronized blocks whose conditions/bodies reference the exception slot
        // under its synthetic name; without this recursion the rename missed them and the
        // recompiled catch referenced an undeclared variable.
        } else if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            Expression cond = renameVarInExpression(is.getCondition(), oldName, newName);
            Statement then = renameVarInStatement(is.getThenBody(), oldName, newName);
            if (cond != is.getCondition() || then != is.getThenBody()) {
                return new IfStatement(is.getLineNumber(), cond, then);
            }
        } else if (s instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) s;
            Expression cond = renameVarInExpression(ies.getCondition(), oldName, newName);
            Statement then = renameVarInStatement(ies.getThenBody(), oldName, newName);
            Statement els = renameVarInStatement(ies.getElseBody(), oldName, newName);
            if (cond != ies.getCondition() || then != ies.getThenBody() || els != ies.getElseBody()) {
                return new IfElseStatement(ies.getLineNumber(), cond, then, els);
            }
        } else if (s instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) s;
            Expression cond = renameVarInExpression(ws.getCondition(), oldName, newName);
            Statement body = renameVarInStatement(ws.getBody(), oldName, newName);
            if (cond != ws.getCondition() || body != ws.getBody()) {
                return new WhileStatement(ws.getLineNumber(), cond, body);
            }
        } else if (s instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) s;
            Expression cond = renameVarInExpression(dws.getCondition(), oldName, newName);
            Statement body = renameVarInStatement(dws.getBody(), oldName, newName);
            if (cond != dws.getCondition() || body != dws.getBody()) {
                return new DoWhileStatement(dws.getLineNumber(), cond, body);
            }
        } else if (s instanceof SynchronizedStatement) {
            SynchronizedStatement ss = (SynchronizedStatement) s;
            Expression mon = renameVarInExpression(ss.getMonitor(), oldName, newName);
            Statement body = renameVarInStatement(ss.getBody(), oldName, newName);
            if (mon != ss.getMonitor() || body != ss.getBody()) {
                return new SynchronizedStatement(ss.getLineNumber(), mon, body);
            }
        } else if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            Expression sel = renameVarInExpression(sw.getSelector(), oldName, newName);
            boolean changed = sel != sw.getSelector();
            List<SwitchStatement.SwitchCase> cases =
                new ArrayList<SwitchStatement.SwitchCase>();
            for (SwitchStatement.SwitchCase sc : sw.getCases()) {
                List<Statement> renamedStmts = renameVarInStatements(sc.getStatements(), oldName, newName);
                boolean caseChanged = false;
                for (int ci = 0; ci < renamedStmts.size(); ci++) {
                    if (renamedStmts.get(ci) != sc.getStatements().get(ci)) {
                        caseChanged = true;
                        break;
                    }
                }
                if (caseChanged) {
                    changed = true;
                    cases.add(new SwitchStatement.SwitchCase(sc.getLabels(), renamedStmts));
                } else {
                    cases.add(sc);
                }
            }
            if (changed) {
                return new SwitchStatement(sw.getLineNumber(), sel, cases, sw.isArrowStyle());
            }
        } else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            Statement tryB = renameVarInStatement(t.getTryBody(), oldName, newName);
            boolean changed = tryB != t.getTryBody();
            List<TryCatchStatement.CatchClause> ccs =
                new ArrayList<TryCatchStatement.CatchClause>();
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                if (oldName.equals(cc.variableName)) {
                    ccs.add(cc); // shadowed inside this clause
                    continue;
                }
                Statement b = renameVarInStatement(cc.body, oldName, newName);
                if (b != cc.body) {
                    changed = true;
                    ccs.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, b));
                } else {
                    ccs.add(cc);
                }
            }
            Statement fin = t.getFinallyBody() == null
                ? null : renameVarInStatement(t.getFinallyBody(), oldName, newName);
            if (fin != t.getFinallyBody()) changed = true;
            if (changed) {
                return new TryCatchStatement(t.getLineNumber(), tryB, ccs, fin, t.getResources());
            }
        }
        // END_CHANGE: BUG-2026-0056-6
        return s;
    }

    private static Expression renameVarInExpression(Expression expr, String oldName, String newName) {
        // START_CHANGE: BUG-2026-0143-20261017-2 - See BUG-2026-0143-1.
        if (expr != null) return new AstMapper.Renamer(oldName, newName).map(expr);
        // END_CHANGE: BUG-2026-0143-2
        if (expr instanceof LocalVariableExpression) {
            LocalVariableExpression lve = (LocalVariableExpression) expr;
            if (oldName.equals(lve.getName())) {
                return new LocalVariableExpression(lve.getLineNumber(), lve.getType(), newName, lve.getIndex());
            }
        } else if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            Expression obj = renameVarInExpression(mie.getObject(), oldName, newName);
            List<Expression> args = renameVarInExpressions(mie.getArguments(), oldName, newName);
            if (obj != mie.getObject() || args != mie.getArguments()) {
                return new MethodInvocationExpression(mie.getLineNumber(), mie.getType(), obj,
                    mie.getOwnerInternalName(), mie.getMethodName(), mie.getDescriptor(), args);
            }
        } else if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            List<Expression> args = renameVarInExpressions(smie.getArguments(), oldName, newName);
            if (args != smie.getArguments()) {
                return new StaticMethodInvocationExpression(smie.getLineNumber(), smie.getType(),
                    smie.getOwnerInternalName(), smie.getMethodName(), smie.getDescriptor(), args);
            }
        } else if (expr instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) expr;
            Expression left = renameVarInExpression(ae.getLeft(), oldName, newName);
            Expression right = renameVarInExpression(ae.getRight(), oldName, newName);
            if (left != ae.getLeft() || right != ae.getRight()) {
                return new AssignmentExpression(ae.getLineNumber(), ae.getType(), left, ae.getOperator(), right);
            }
        } else if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            Expression left = renameVarInExpression(boe.getLeft(), oldName, newName);
            Expression right = renameVarInExpression(boe.getRight(), oldName, newName);
            if (left != boe.getLeft() || right != boe.getRight()) {
                return new BinaryOperatorExpression(boe.getLineNumber(), boe.getType(), left, boe.getOperator(), right);
            }
        } else if (expr instanceof CastExpression) {
            CastExpression ce = (CastExpression) expr;
            Expression inner = renameVarInExpression(ce.getExpression(), oldName, newName);
            if (inner != ce.getExpression()) {
                return new CastExpression(ce.getLineNumber(), ce.getType(), inner);
            }
        } else if (expr instanceof NewExpression) {
            // BUG-2026-0080 (RC-8): recurse into constructor args (e.g. `throw new RuntimeException(msg, e)`).
            NewExpression ne = (NewExpression) expr;
            List<Expression> args = renameVarInExpressions(ne.getArguments(), oldName, newName);
            if (args != ne.getArguments()) {
                return new NewExpression(ne.getLineNumber(), ne.getType(), ne.getInternalTypeName(), ne.getDescriptor(), args);
            }
        } else if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            Expression inner = renameVarInExpression(uoe.getExpression(), oldName, newName);
            if (inner != uoe.getExpression()) {
                return new UnaryOperatorExpression(uoe.getLineNumber(), uoe.getType(), uoe.getOperator(), inner, uoe.isPrefix());
            }
        } else if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            Expression c = renameVarInExpression(te.getCondition(), oldName, newName);
            Expression t = renameVarInExpression(te.getTrueExpression(), oldName, newName);
            Expression f = renameVarInExpression(te.getFalseExpression(), oldName, newName);
            if (c != te.getCondition() || t != te.getTrueExpression() || f != te.getFalseExpression()) {
                return new TernaryExpression(te.getLineNumber(), te.getType(), c, t, f);
            }
        } else if (expr instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) expr;
            Expression obj = renameVarInExpression(fae.getObject(), oldName, newName);
            if (obj != fae.getObject()) {
                return new FieldAccessExpression(fae.getLineNumber(), fae.getType(), obj,
                    fae.getOwnerInternalName(), fae.getName(), fae.getDescriptor());
            }
        // START_CHANGE: BUG-2026-0056-20260610-7 - Recurse into instanceof and array access:
        // structured catch bodies routinely test `e instanceof SomeException` in conditions.
        } else if (expr instanceof InstanceOfExpression) {
            InstanceOfExpression ioe = (InstanceOfExpression) expr;
            Expression inner = renameVarInExpression(ioe.getExpression(), oldName, newName);
            if (inner != ioe.getExpression()) {
                return new InstanceOfExpression(ioe.getLineNumber(), inner, ioe.getCheckType(),
                    ioe.getPatternVariableName());
            }
        } else if (expr instanceof ArrayAccessExpression) {
            ArrayAccessExpression aae = (ArrayAccessExpression) expr;
            Expression arr = renameVarInExpression(aae.getArray(), oldName, newName);
            Expression idx = renameVarInExpression(aae.getIndex(), oldName, newName);
            if (arr != aae.getArray() || idx != aae.getIndex()) {
                return new ArrayAccessExpression(aae.getLineNumber(), aae.getType(), arr, idx);
            }
        }
        // END_CHANGE: BUG-2026-0056-7
        return expr;
    }

    private static List<Expression> renameVarInExpressions(List<Expression> exprs, String oldName, String newName) {
        if (exprs == null) return exprs;
        boolean changed = false;
        List<Expression> result = new ArrayList<Expression>(exprs.size());
        for (Expression e : exprs) {
            Expression renamed = renameVarInExpression(e, oldName, newName);
            if (renamed != e) changed = true;
            result.add(renamed);
        }
        return changed ? result : exprs;
    }
    // END_CHANGE: ISS-2026-0005-13

    public static List<Statement> filterFinallyBody(List<Statement> handlerBody) {
        return filterFinallyBody(handlerBody, false);
    }

    // START_CHANGE: BUG-2026-0091-20260610-4 - Do not strip the first handler statement as an
    // exception store when removeInitialExceptionStore already removed it; otherwise a real
    // finally statement (declaration/assignment) is silently deleted.
    public static List<Statement> filterFinallyBody(List<Statement> handlerBody,
                                                     boolean exceptionStoreAlreadyRemoved) {
        if (handlerBody.isEmpty()) return handlerBody;

        List<Statement> filtered = new ArrayList<Statement>();
        int startIdx = 0;
        int endIdx = handlerBody.size();

        if (!exceptionStoreAlreadyRemoved) {
            Statement first = handlerBody.get(0);
            if (first instanceof VariableDeclarationStatement) {
                startIdx = 1;
            } else if (first instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) first).getExpression();
                if (expr instanceof AssignmentExpression) {
                    startIdx = 1;
                }
            }
        }
        // END_CHANGE: BUG-2026-0091-4

        if (endIdx > startIdx) {
            Statement last = handlerBody.get(endIdx - 1);
            if (last instanceof ThrowStatement) {
                endIdx--;
            }
        }

        for (int i = startIdx; i < endIdx; i++) {
            filtered.add(handlerBody.get(i));
        }

        // Strip addSuppressed boilerplate from try-with-resources
        filtered = stripAddSuppressed(filtered);

        return filtered;
    }

    /**
     * Strip addSuppressed boilerplate from try-with-resources compiler output.
     * Removes statements that reference addSuppressed and simplifies the pattern.
     */
    public static List<Statement> stripAddSuppressed(List<Statement> stmts) {
        List<Statement> result = new ArrayList<Statement>();
        for (Statement s : stmts) {
            if (!containsAddSuppressed(s)) {
                result.add(s);
            }
        }
        return result;
    }

    private static boolean containsAddSuppressed(Statement s) {
        if (s instanceof ExpressionStatement) {
            return expressionContainsAddSuppressed(((ExpressionStatement) s).getExpression());
        }
        if (s instanceof TryCatchStatement) {
            // If the entire try-catch is just wrapping addSuppressed, strip it
            TryCatchStatement tcs = (TryCatchStatement) s;
            if (tcs.getTryBody() instanceof BlockStatement) {
                List<Statement> tryStmts = ((BlockStatement) tcs.getTryBody()).getStatements();
                for (Statement ts : tryStmts) {
                    if (containsAddSuppressed(ts)) return true;
                }
            }
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                if (cc.body instanceof BlockStatement) {
                    List<Statement> catchStmts = ((BlockStatement) cc.body).getStatements();
                    for (Statement cs : catchStmts) {
                        if (containsAddSuppressed(cs)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean expressionContainsAddSuppressed(Expression expr) {
        if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            if ("addSuppressed".equals(mie.getMethodName())) return true;
        }
        return false;
    }

    // START_CHANGE: BUG-2026-0091-20260610-5 - Structural finally dedup (supersedes the
    // count-based truncation of BUG-2026-0021-1). The old code removed the LAST finallySize
    // statements by COUNT with a type-only whitelist that included ReturnStatement, so for the
    // synchronized desugar (finally = [__MONITOREXIT__ marker], inlined monitorexit PRECEDING
    // ireturn) it deleted the trailing `return` instead of the duplicated EXIT marker.
    // Now statements are removed ONLY when they structurally match the actual finally body:
    //   1. the trailing N statements match the finally statements -> remove them;
    //   2. otherwise, if the body ends in a Return/Throw, the N statements immediately BEFORE
    //      that terminator are compared (javac inlines finally before each exit point) and
    //      removed on match, keeping the terminator;
    //   3. fallback: if no structural match, the old count-based truncation is kept ONLY for
    //      tails made exclusively of plain ExpressionStatements (ReturnStatement is no longer
    //      whitelisted, so a trailing `return` can never be deleted by count);
    //   4. otherwise remove nothing.
    public static List<Statement> removeDuplicatedFinally(List<Statement> catchBody,
                                                           List<Statement> finallyStmts) {
        int finallySize = (finallyStmts == null) ? 0 : finallyStmts.size();
        if (finallySize <= 0) return catchBody;

        // START_CHANGE: BUG-2026-0129-20260907-1 - Strip the inlined finally copies that javac
        // places before a NESTED exit. Cases 1-3 below only ever looked at the top level of the
        // body list, so a copy inside an if/loop/label/switch/synchronized nested in the try
        // survived and the action ran twice - for the lock.unlock() family that is an
        // IllegalMonitorStateException at runtime, on output that compiles cleanly
        // (498 sites / 82 files in JDK 25 java.base).
        //
        // Only the shapes javac actually emits are removed: a copy IMMEDIATELY followed by a
        // return/break/continue, i.e. an exit that leaves the protected region normally. A
        // `throw` inside the region is covered by the `any` handler, so javac emits no copy
        // before it and a match there would always be a false positive - it is not accepted
        // here (Case 2 keeps its historical Throw handling at the top level, unchanged).
        catchBody = stripFinallyCopiesBeforeExits(catchBody, finallyStmts, true);
        if (catchBody.size() <= finallySize) return catchBody;
        // END_CHANGE: BUG-2026-0129-1

        // Case 1: the trailing N statements structurally match the finally body
        int tailStart = catchBody.size() - finallySize;
        if (statementsMatchFinally(catchBody, tailStart, finallyStmts)) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 0; i < tailStart; i++) {
                filtered.add(catchBody.get(i));
            }
            return filtered;
        }

        // Case 2: the body ends in a Return/Throw and the N statements immediately before
        // it match the finally body - remove those, keep the terminator
        Statement last = catchBody.get(catchBody.size() - 1);
        if (last instanceof ReturnStatement || last instanceof ThrowStatement) {
            int preStart = catchBody.size() - 1 - finallySize;
            if (preStart >= 0 && statementsMatchFinally(catchBody, preStart, finallyStmts)) {
                List<Statement> filtered = new ArrayList<Statement>();
                for (int i = 0; i < preStart; i++) {
                    filtered.add(catchBody.get(i));
                }
                filtered.add(last);
                return filtered;
            }
        }

        // START_CHANGE: BUG-2026-0056-20260610-8 - Tighten the Case 3 fallback. The old fallback
        // removed the trailing N statements by COUNT whenever they were all plain
        // ExpressionStatements, which deleted REAL trailing try-body statements that merely had
        // the same count as the finally body (e.g. `sb.append("ok;")` after an if/else inside
        // try+finally was swallowed because finally had one statement too). The tail is now
        // removed only when it also structurally matches the finally body with local variable
        // names normalized - this still dedups genuine inlined finally copies whose synthetic
        // variable names differ from the decoded handler body, but can no longer swallow
        // unrelated statements (different constants/targets/methods never match).
        boolean allPlainExpressions = true;
        for (int i = tailStart; i < catchBody.size(); i++) {
            if (!(catchBody.get(i) instanceof ExpressionStatement)) {
                allPlainExpressions = false;
                break;
            }
        }
        if (allPlainExpressions
                && statementsMatchFinally(catchBody, tailStart, finallyStmts, true)) {
            List<Statement> filtered = new ArrayList<Statement>();
            for (int i = 0; i < tailStart; i++) {
                filtered.add(catchBody.get(i));
            }
            return filtered;
        }
        // END_CHANGE: BUG-2026-0056-8

        return catchBody; // conservative: no match, remove nothing
    }

    // START_CHANGE: BUG-2026-0129-20260907-2 - Recursive removal of inlined finally copies
    // that precede a nested exit. The AST is immutable, so every compound statement that
    // contains a rewritten list is rebuilt. Nothing else about the statement changes.
    //
    // `topLevel` distinguishes the body list handed to removeDuplicatedFinally (where Cases 1-3
    // already handle the trailing copy and the copy before a trailing Return/Throw) from the
    // nested lists, which had no handling at all. At the top level only Break/Continue exits are
    // considered here, so the historical Return/Throw behaviour of Case 1/2/3 is untouched.
    private static List<Statement> stripFinallyCopiesBeforeExits(List<Statement> list,
                                                                  List<Statement> finallyStmts,
                                                                  boolean topLevel) {
        if (list == null || list.isEmpty()) return list;
        int n = finallyStmts.size();

        // 1. rebuild every compound statement with its inner lists processed
        List<Statement> mapped = new ArrayList<Statement>(list.size());
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Statement s = list.get(i);
            Statement r = rebuildWithFinallyCopiesStripped(s, finallyStmts);
            if (r != s) changed = true;
            mapped.add(r);
        }

        // 2. remove a run of N statements that matches the finally body and is immediately
        //    followed by an exit out of the protected region
        List<Statement> out = new ArrayList<Statement>(mapped.size());
        int i = 0;
        while (i < mapped.size()) {
            if (i + n < mapped.size()
                    && isFinallyCopyExit(mapped.get(i + n), topLevel)
                    && statementsMatchFinally(mapped, i, finallyStmts)) {
                i += n; // drop the copy, keep the exit
                changed = true;
                continue;
            }
            out.add(mapped.get(i));
            i++;
        }
        return changed ? out : list;
    }

    /** Exits for which javac inlines a finally copy. Throw is deliberately excluded. */
    private static boolean isFinallyCopyExit(Statement s, boolean topLevel) {
        if (s instanceof BreakStatement || s instanceof ContinueStatement) return true;
        // Return at the top level is Case 2's territory; only nested lists are handled here.
        return !topLevel && (s instanceof ReturnStatement);
    }

    /** Rebuild a compound statement with each of its statement lists stripped recursively. */
    private static Statement rebuildWithFinallyCopiesStripped(Statement s,
                                                               List<Statement> finallyStmts) {
        if (s instanceof BlockStatement) {
            BlockStatement b = (BlockStatement) s;
            List<Statement> body = stripFinallyCopiesBeforeExits(b.getStatements(), finallyStmts, false);
            return (body == b.getStatements()) ? s : new BlockStatement(b.getLineNumber(), body);
        }
        if (s instanceof IfStatement) {
            IfStatement f = (IfStatement) s;
            Statement t = rebuildWithFinallyCopiesStripped(f.getThenBody(), finallyStmts);
            return (t == f.getThenBody()) ? s
                : new IfStatement(f.getLineNumber(), f.getCondition(), t);
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement f = (IfElseStatement) s;
            Statement t = rebuildWithFinallyCopiesStripped(f.getThenBody(), finallyStmts);
            Statement e = rebuildWithFinallyCopiesStripped(f.getElseBody(), finallyStmts);
            return (t == f.getThenBody() && e == f.getElseBody()) ? s
                : new IfElseStatement(f.getLineNumber(), f.getCondition(), t, e);
        }
        if (s instanceof WhileStatement) {
            WhileStatement w = (WhileStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(w.getBody(), finallyStmts);
            return (b == w.getBody()) ? s
                : new WhileStatement(w.getLineNumber(), w.getCondition(), b);
        }
        if (s instanceof DoWhileStatement) {
            DoWhileStatement d = (DoWhileStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(d.getBody(), finallyStmts);
            return (b == d.getBody()) ? s
                : new DoWhileStatement(d.getLineNumber(), d.getCondition(), b);
        }
        if (s instanceof ForStatement) {
            ForStatement f = (ForStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(f.getBody(), finallyStmts);
            return (b == f.getBody()) ? s
                : new ForStatement(f.getLineNumber(), f.getInit(), f.getCondition(), f.getUpdate(), b);
        }
        if (s instanceof ForEachStatement) {
            ForEachStatement f = (ForEachStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(f.getBody(), finallyStmts);
            return (b == f.getBody()) ? s
                : new ForEachStatement(f.getLineNumber(), f.getVariableType(), f.getVariableName(),
                    f.getIterable(), b);
        }
        if (s instanceof LabelStatement) {
            LabelStatement l = (LabelStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(l.getBody(), finallyStmts);
            return (b == l.getBody()) ? s
                : new LabelStatement(l.getLineNumber(), l.getLabel(), b);
        }
        if (s instanceof SynchronizedStatement) {
            SynchronizedStatement y = (SynchronizedStatement) s;
            Statement b = rebuildWithFinallyCopiesStripped(y.getBody(), finallyStmts);
            return (b == y.getBody()) ? s
                : new SynchronizedStatement(y.getLineNumber(), y.getMonitor(), b);
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
            boolean ch = false;
            for (SwitchStatement.SwitchCase c : sw.getCases()) {
                List<Statement> b = stripFinallyCopiesBeforeExits(c.getStatements(), finallyStmts, false);
                if (b != c.getStatements()) {
                    ch = true;
                    cases.add(new SwitchStatement.SwitchCase(c.getLabels(), b));
                } else {
                    cases.add(c);
                }
            }
            return ch ? new SwitchStatement(sw.getLineNumber(), sw.getSelector(), cases, sw.isArrowStyle()) : s;
        }
        if (s instanceof TryCatchStatement) {
            // Recurse into a nested try's body and catch clauses (javac inlines the OUTER finally
            // before a return there too). The nested finally body is left alone: its own
            // statements are not copies of the outer finally and must never be touched.
            TryCatchStatement t = (TryCatchStatement) s;
            Statement tb = rebuildWithFinallyCopiesStripped(t.getTryBody(), finallyStmts);
            List<TryCatchStatement.CatchClause> catches = new ArrayList<TryCatchStatement.CatchClause>();
            boolean ch = (tb != t.getTryBody());
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                Statement cb = rebuildWithFinallyCopiesStripped(cc.body, finallyStmts);
                if (cb != cc.body) {
                    ch = true;
                    catches.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, cb));
                } else {
                    catches.add(cc);
                }
            }
            return ch ? new TryCatchStatement(t.getLineNumber(), tb, catches,
                t.hasFinally() ? t.getFinallyBody() : null, t.getResources()) : s;
        }
        return s;
    }
    // END_CHANGE: BUG-2026-0129-2

    /**
     * Strip a duplicated finally body inlined by javac at the START of the code that
     * follows the try region (the normal completion path).
     */
    private static List<Statement> stripLeadingFinallyDuplicate(List<Statement> afterTry,
                                                                 List<Statement> finallyStmts) {
        int finallySize = (finallyStmts == null) ? 0 : finallyStmts.size();
        if (finallySize <= 0 || afterTry.size() < finallySize) return afterTry;
        if (!statementsMatchFinally(afterTry, 0, finallyStmts)) return afterTry;
        List<Statement> result = new ArrayList<Statement>();
        for (int i = finallySize; i < afterTry.size(); i++) {
            result.add(afterTry.get(i));
        }
        return result;
    }

    private static boolean statementsMatchFinally(List<Statement> body, int offset,
                                                   List<Statement> finallyStmts) {
        // START_CHANGE: BUG-2026-0056-20260610-9 - Delegate to the parameterised variant
        return statementsMatchFinally(body, offset, finallyStmts, false);
    }

    private static boolean statementsMatchFinally(List<Statement> body, int offset,
                                                   List<Statement> finallyStmts,
                                                   boolean normalizeLocals) {
        for (int i = 0; i < finallyStmts.size(); i++) {
            if (!sameStatementShape(body.get(offset + i), finallyStmts.get(i), normalizeLocals)) {
                return false;
            }
        }
        return true;
    }
    // END_CHANGE: BUG-2026-0056-9

    /**
     * Two statements have the same shape when they are of the same class and their
     * line-independent signatures (expression/initializer rendering) are equal.
     * Compound statements (if/loops/blocks) are never considered equal: their content
     * cannot be compared reliably, so dedup conservatively keeps them.
     */
    private static boolean sameStatementShape(Statement a, Statement b,
                                               boolean normalizeLocals) {
        if (a == null || b == null) return false;
        if (!a.getClass().equals(b.getClass())) return false;
        String sigA = statementSignature(a, normalizeLocals);
        String sigB = statementSignature(b, normalizeLocals);
        if (sigA == null || sigB == null) return false;
        return sigA.equals(sigB);
    }

    /**
     * Line-independent signature of a simple statement, or null for statement types
     * that cannot be compared reliably.
     */
    private static String statementSignature(Statement s, boolean normalizeLocals) {
        if (s instanceof ExpressionStatement) {
            return "expr:" + expressionSignature(((ExpressionStatement) s).getExpression(), normalizeLocals);
        }
        if (s instanceof ReturnStatement) {
            return "return:" + expressionSignature(((ReturnStatement) s).getExpression(), normalizeLocals);
        }
        if (s instanceof ThrowStatement) {
            return "throw:" + expressionSignature(((ThrowStatement) s).getExpression(), normalizeLocals);
        }
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            return "decl:" + (normalizeLocals ? "$v" : vds.getName()) + "="
                + expressionSignature(vds.getInitializer(), normalizeLocals);
        }
        return null; // compound/unknown statement: never matches
    }

    /**
     * Render an expression for structural comparison. Method invocations include their
     * arguments (the plain toString elides them as "(...)").
     * START_CHANGE: BUG-2026-0056-20260610-10 - Recurse into the expression node types that
     * have no value-based toString (assignments, casts, unary/static calls, news...): they
     * previously fell back to the identity toString, so two structurally identical statements
     * decoded from different blocks could NEVER match and Case 1/2 dedup silently failed.
     * With normalizeLocals, local variable reads/writes render as "$v" so an inlined finally
     * copy that only differs by synthetic variable names still matches.
     */
    private static String expressionSignature(Expression e, boolean normalizeLocals) {
        if (e == null) return "null";
        if (e instanceof LocalVariableExpression) {
            return normalizeLocals ? "$v" : ((LocalVariableExpression) e).getName();
        }
        if (e instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) e;
            StringBuilder sb = new StringBuilder();
            sb.append(expressionSignature(mie.getObject(), normalizeLocals));
            sb.append('.').append(mie.getMethodName()).append('(');
            List<Expression> args = mie.getArguments();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(expressionSignature(args.get(i), normalizeLocals));
                }
            }
            sb.append(')');
            return sb.toString();
        }
        if (e instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) e;
            StringBuilder sb = new StringBuilder();
            sb.append(smie.getOwnerInternalName()).append('.')
              .append(smie.getMethodName()).append('(');
            List<Expression> args = smie.getArguments();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(expressionSignature(args.get(i), normalizeLocals));
                }
            }
            sb.append(')');
            return sb.toString();
        }
        if (e instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) e;
            return expressionSignature(ae.getLeft(), normalizeLocals) + ae.getOperator()
                + expressionSignature(ae.getRight(), normalizeLocals);
        }
        if (e instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) e;
            return "(" + expressionSignature(boe.getLeft(), normalizeLocals)
                + boe.getOperator()
                + expressionSignature(boe.getRight(), normalizeLocals) + ")";
        }
        if (e instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) e;
            return uoe.isPrefix()
                ? uoe.getOperator() + expressionSignature(uoe.getExpression(), normalizeLocals)
                : expressionSignature(uoe.getExpression(), normalizeLocals) + uoe.getOperator();
        }
        if (e instanceof CastExpression) {
            CastExpression ce = (CastExpression) e;
            return "cast(" + String.valueOf(ce.getType()) + ")"
                + expressionSignature(ce.getExpression(), normalizeLocals);
        }
        if (e instanceof NewExpression) {
            NewExpression ne = (NewExpression) e;
            StringBuilder sb = new StringBuilder();
            sb.append("new ").append(ne.getInternalTypeName()).append('(');
            List<Expression> args = ne.getArguments();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(expressionSignature(args.get(i), normalizeLocals));
                }
            }
            sb.append(')');
            return sb.toString();
        }
        if (e instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) e;
            return expressionSignature(fae.getObject(), normalizeLocals)
                + "." + fae.getName();
        }
        if (e instanceof ArrayAccessExpression) {
            ArrayAccessExpression aae = (ArrayAccessExpression) e;
            return expressionSignature(aae.getArray(), normalizeLocals)
                + "[" + expressionSignature(aae.getIndex(), normalizeLocals) + "]";
        }
        return String.valueOf(e);
    }
    // END_CHANGE: BUG-2026-0056-10
    // END_CHANGE: BUG-2026-0091-5

    // START_CHANGE: LIM-0008-20260326-2 - Helpers for try-with-resources resource extraction

    /**
     * Extract variable names that are closed in a finally block.
     * Looks for patterns like: varName.close() in the finally body.
     */
    private List<String> extractCloseVariableNames(Statement finallyBody) {
        List<String> names = new ArrayList<String>();
        if (finallyBody instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) finallyBody).getStatements()) {
                String name = extractCloseVarFromStatement(s);
                if (name != null) {
                    names.add(name);
                }
            }
        } else {
            String name = extractCloseVarFromStatement(finallyBody);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private String extractCloseVarFromStatement(Statement s) {
        // Direct close() call: var.close()
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            if (expr instanceof MethodInvocationExpression) {
                MethodInvocationExpression mie = (MethodInvocationExpression) expr;
                if ("close".equals(mie.getMethodName()) && mie.getObject() instanceof LocalVariableExpression) {
                    return ((LocalVariableExpression) mie.getObject()).getName();
                }
            }
        }
        // Guarded close: if (var != null) { var.close(); }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            List<Statement> body = null;
            if (is.getThenBody() instanceof BlockStatement) {
                body = ((BlockStatement) is.getThenBody()).getStatements();
            } else {
                body = new ArrayList<Statement>();
                body.add(is.getThenBody());
            }
            for (Statement bs : body) {
                String name = extractCloseVarFromStatement(bs);
                if (name != null) return name;
            }
        }
        return null;
    }

    /**
     * Get the variable name assigned by a statement (VarDecl or Assignment).
     */
    // START_CHANGE: BUG-2026-0180-20261018-9 - `x = init` / `T x = init` as a resource declaration.
    // The slot's decoded type can be Object (an alias `R r = other;` without a LocalVariableTable):
    // use the initializer's own type then, since a resource must be declared AutoCloseable or narrower.
    private static Statement asResourceDeclaration180(Statement s, List<Statement> before) {
        Type t;
        String name;
        Expression init;
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            t = v.getType();
            name = v.getName();
            init = v.getInitializer();
        } else {
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            LocalVariableExpression lve = (LocalVariableExpression) ae.getLeft();
            t = lve.getType();
            name = lve.getName();
            init = ae.getRight();
        }
        if (init == null || name == null) return null;
        if (t == null || isJavaLangObject180(t)) {
            Type it = init instanceof CastExpression ? ((CastExpression) init).getType() : init.getType();
            if (it != null && !isJavaLangObject180(it)) t = it;
        }
        if ((t == null || isJavaLangObject180(t)) && init instanceof LocalVariableExpression) {
            String src = ((LocalVariableExpression) init).getName();
            for (Statement b : before) {
                if (b instanceof VariableDeclarationStatement && src.equals(((VariableDeclarationStatement) b).getName())) {
                    Type bt = ((VariableDeclarationStatement) b).getType();
                    if (bt != null && !isJavaLangObject180(bt)) t = bt;
                }
            }
        }
        if (t == null) return null;
        return new VariableDeclarationStatement(s.getLineNumber(), t, name, init, false, false);
    }

    private static boolean isJavaLangObject180(Type t) {
        return t instanceof ObjectType && "java/lang/Object".equals(((ObjectType) t).getInternalName());
    }
    // END_CHANGE: BUG-2026-0180-9

    private String getAssignedVarName(Statement s) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) s;
            if (vds.hasInitializer()) {
                return vds.getName();
            }
        }
        if (s instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) s).getExpression();
            if (expr instanceof AssignmentExpression) {
                AssignmentExpression ae = (AssignmentExpression) expr;
                if (ae.getLeft() instanceof LocalVariableExpression) {
                    return ((LocalVariableExpression) ae.getLeft()).getName();
                }
            }
        }
        return null;
    }

    /**
     * Filter out compiler-generated catch clauses from try-with-resources.
     * Removes catches for Throwable and catches with only addSuppressed calls.
     */
    private List<TryCatchStatement.CatchClause> filterTWRCatchClauses(
            List<TryCatchStatement.CatchClause> catchClauses) {
        List<TryCatchStatement.CatchClause> filtered = new ArrayList<TryCatchStatement.CatchClause>();
        for (TryCatchStatement.CatchClause cc : catchClauses) {
            boolean isCompilerGenerated = false;
            if (cc.exceptionTypes != null) {
                for (Type t : cc.exceptionTypes) {
                    if (t instanceof ObjectType) {
                        String name = ((ObjectType) t).getInternalName();
                        if ("java/lang/Throwable".equals(name) || "Throwable".equals(name)) {
                            isCompilerGenerated = true;
                        }
                    }
                }
            }
            if (!isCompilerGenerated) {
                filtered.add(cc);
            }
        }
        return filtered;
    }
    // END_CHANGE: LIM-0008-2
}
