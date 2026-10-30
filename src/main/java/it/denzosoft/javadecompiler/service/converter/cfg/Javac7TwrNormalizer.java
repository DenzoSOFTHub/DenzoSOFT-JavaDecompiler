/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// START_CHANGE: BUG-2026-0180-20261018-1 - Bytecode pre-pass for the javac 7/8 try-with-resources
// desugaring.
/**
 * Rewrites the javac 7/8 desugaring of {@code try (R r = init) body} into the plain
 * try/finally shape {@code R r = init; try { body } finally { r.close(); }}, which the try/finally
 * reconstruction already handles and LIM-0008 folds back into a try-with-resources header.
 *
 * <p>javac 7/8 emit, per resource (javac 9+ use a different, smaller shape that this class does
 * not touch):
 * <pre>
 *   init; astore R; aconst_null; astore P            // Throwable primary = null
 *   [body]                                           // ranges -> H1 (Throwable), H2 (any)
 *   COPY                                             // before every normal exit of the body
 *   H1: astore t; aload t; astore P; aload t; athrow // catch (Throwable t) { primary = t; throw t; }
 *   H2: astore e; COPY; aload e; athrow              // finally, exceptional path
 * COPY = aload R; ifnull X; aload P; ifnull L; aload R; close; goto X;
 *        S: astore t2; aload P; aload t2; addSuppressed; goto X;   // [aload R; close] -> S Throwable
 *        L: aload R; close; [goto X]
 * </pre>
 * Every COPY becomes {@code aload R; close} (+ goto X), H1 disappears and H2 becomes the
 * finally handler {@code astore e; aload R; close; aload e; athrow}. The exact semantics
 * (null check, addSuppressed) are restored by the try-with-resources header the writer emits;
 * the handler pcs of rewritten regions are reported so the reconstructor folds them
 * unconditionally. Anything that deviates from the shape above is left untouched.
 */
public final class Javac7TwrNormalizer {
    private static final int ACONST_NULL = 0x01, ATHROW = 0xbf, IFNULL = 0xc6, GOTO = 0xa7, NOP = 0x00;

    private final byte[] c;
    private final ConstantPool cp;
    private final boolean[] isStart;

    /** Handler pcs of the try/finally regions produced by the rewrite. */
    private final Set<Integer> twrHandlers = new HashSet<Integer>();

    private Javac7TwrNormalizer(byte[] c, ConstantPool cp, boolean[] isStart) {
        this.c = c;
        this.cp = cp;
        this.isStart = isStart;
    }

    /** Result of a normalization: the (possibly) rewritten code and the folded handler pcs. */
    public static final class Result {
        public final CodeAttribute code;
        public final Set<Integer> twrHandlerPcs;

        Result(CodeAttribute code, Set<Integer> twrHandlerPcs) {
            this.code = code;
            this.twrHandlerPcs = twrHandlerPcs;
        }
    }

    public static Result normalize(CodeAttribute ca, ConstantPool cp) {
        Result unchanged = new Result(ca, new HashSet<Integer>());
        if (ca == null) return unchanged;
        CodeAttribute.ExceptionEntry[] table = ca.getExceptionTable();
        if (table == null || table.length < 3) return unchanged;
        byte[] code = ca.getCode();
        boolean[] starts = new boolean[code.length + 1];
        for (int pc = 0; pc < code.length; ) {
            starts[pc] = true;
            int len = opLength(code, pc);
            if (len <= 0) return unchanged;
            pc += len;
        }
        starts[code.length] = true;
        Javac7TwrNormalizer n = new Javac7TwrNormalizer(code, cp, starts);
        try {
            byte[] out = code.clone();
            List<CodeAttribute.ExceptionEntry> entries = new ArrayList<CodeAttribute.ExceptionEntry>();
            for (CodeAttribute.ExceptionEntry e : table) entries.add(e);
            boolean changed = false;
            for (CodeAttribute.ExceptionEntry e : table) {
                if (!entries.contains(e)) continue;
                if (n.rewrite(e, entries, out)) changed = true;
            }
            if (!changed) return unchanged;
            CodeAttribute rewritten = new CodeAttribute(ca.getName(), ca.getLength(), ca.getMaxStack(),
                ca.getMaxLocals(), out, entries.toArray(new CodeAttribute.ExceptionEntry[entries.size()]),
                ca.getAttributes());
            return new Result(rewritten, n.twrHandlers);
        } catch (RuntimeException ex) {
            return unchanged; // malformed or unexpected shape: never fail the method because of a pre-pass
        }
    }

    /** One finally copy (COPY above). */
    private static final class Copy {
        int start, end, x, closeStart, closeEnd, s, r;
    }

    private boolean rewrite(CodeAttribute.ExceptionEntry e1, List<CodeAttribute.ExceptionEntry> entries, byte[] out) {
        if (e1.catchType <= 0 || !"java/lang/Throwable".equals(cp.getClassName(e1.catchType))) return false;
        int h1 = e1.handlerPc;
        // H1: astore t; aload t; astore P; aload t; athrow
        int p = h1;
        int t = storeSlot(p);
        if (t < 0) return false;
        p += len(p);
        if (loadSlot(p) != t) return false;
        p += len(p);
        int prim = storeSlot(p);
        if (prim < 0 || prim == t) return false;
        p += len(p);
        if (loadSlot(p) != t) return false;
        p += len(p);
        if (op(p) != ATHROW) return false;
        int h2 = p + 1;
        // H2: astore e; COPY; aload e; athrow
        int ex = storeSlot(h2);
        if (ex < 0) return false;
        Copy hc = parseCopy(h2 + len(h2), -1, prim);
        if (hc == null || hc.end != hc.x || loadSlot(hc.x) != ex) return false;
        int hEnd = hc.x + len(hc.x);
        if (op(hEnd) != ATHROW) return false;
        hEnd++;
        int res = hc.r;

        // exception-table shape
        List<CodeAttribute.ExceptionEntry> g1 = new ArrayList<CodeAttribute.ExceptionEntry>();
        List<CodeAttribute.ExceptionEntry> g2 = new ArrayList<CodeAttribute.ExceptionEntry>();
        List<CodeAttribute.ExceptionEntry> drop = new ArrayList<CodeAttribute.ExceptionEntry>();
        int sMin = Integer.MAX_VALUE;
        for (CodeAttribute.ExceptionEntry e : entries) {
            if (e.handlerPc == h1) {
                if (e.catchType != e1.catchType) return false;
                g1.add(e);
                sMin = Math.min(sMin, e.startPc);
            } else if (e.handlerPc == h2) {
                if (e.catchType != 0) return false;
                g2.add(e);
            }
        }
        for (CodeAttribute.ExceptionEntry e : g2) {
            if (e.startPc >= h1 && e.startPc < hEnd) { drop.add(e); continue; } // covers H1 itself
            boolean same = false;
            for (CodeAttribute.ExceptionEntry f : g1) if (f.startPc == e.startPc && f.endPc == e.endPc) same = true;
            if (!same) return false;
        }
        if (g2.size() - drop.size() != g1.size()) return false;
        CodeAttribute.ExceptionEntry hInner = innerEntry(entries, hc);
        if (hInner == null) return false;
        drop.add(hInner);
        drop.addAll(g1);

        // prologue: astore R; aconst_null; astore P; ending exactly where the body starts
        int ip = prevStart(sMin);
        if (ip < 0 || storeSlot(ip) != prim) return false;
        ip = prevStart(ip);
        if (ip < 0 || op(ip) != ACONST_NULL) return false;
        int rp = prevStart(ip);
        if (rp < 0 || storeSlot(rp) != res) return false;

        // normal-path copies: javac places them between the body start and H1 (after the body
        // and before every exit inside it). A later resource try may reuse the same slots, so
        // nothing outside [sMin, h1) is ours; the close call must also be the very same method.
        int handlerClose = hc.closeStart + len(hc.closeStart);
        List<Copy> copies = new ArrayList<Copy>();
        for (int pc = sMin; pc < h1; pc++) {
            if (!isStart[pc]) continue;
            if (loadSlot(pc) != res || op(pc + len(pc)) != IFNULL) continue;
            Copy cp2 = parseCopy(pc, res, prim);
            if (cp2 == null) continue;
            int ci = cp2.closeStart + len(cp2.closeStart);
            if (len(ci) != len(handlerClose) || !sameBytes(ci, handlerClose, len(ci))) return false;
            CodeAttribute.ExceptionEntry in = innerEntry(entries, cp2);
            if (in == null) return false;
            drop.add(in);
            copies.add(cp2);
            pc = cp2.end - 1;
        }

        // rewrite
        for (int i = ip; i < sMin; i++) out[i] = NOP;
        byte[] loadR = slice(hc.closeStart, len(hc.closeStart));
        int inv = hc.closeStart + loadR.length;
        byte[] close = slice(inv, len(inv));
        byte[] storeE = slice(h2, len(h2));
        byte[] loadE = slice(hc.x, len(hc.x));
        for (int i = h1; i < hEnd; i++) out[i] = NOP;
        System.arraycopy(storeE, 0, out, h1, storeE.length);
        int tail = hEnd - 1 - loadE.length - close.length - loadR.length;
        System.arraycopy(loadR, 0, out, tail, loadR.length);
        System.arraycopy(close, 0, out, tail + loadR.length, close.length);
        System.arraycopy(loadE, 0, out, tail + loadR.length + close.length, loadE.length);
        out[hEnd - 1] = (byte) ATHROW;
        for (Copy cp2 : copies) {
            for (int i = cp2.start; i < cp2.end; i++) out[i] = NOP;
            boolean needGoto = cp2.x != cp2.end;
            int at = cp2.end - loadR.length - close.length - (needGoto ? 3 : 0);
            System.arraycopy(loadR, 0, out, at, loadR.length);
            System.arraycopy(close, 0, out, at + loadR.length, close.length);
            if (needGoto) {
                int g = at + loadR.length + close.length;
                int off = cp2.x - g;
                out[g] = (byte) GOTO;
                out[g + 1] = (byte) (off >> 8);
                out[g + 2] = (byte) off;
            }
        }
        List<CodeAttribute.ExceptionEntry> next = new ArrayList<CodeAttribute.ExceptionEntry>();
        for (CodeAttribute.ExceptionEntry e : entries) {
            if (drop.contains(e)) continue;
            if (g2.contains(e)) next.add(new CodeAttribute.ExceptionEntry(e.startPc, e.endPc, h1, 0));
            else next.add(e);
        }
        entries.clear();
        entries.addAll(next);
        twrHandlers.add(Integer.valueOf(h1));
        return true;
    }

    private CodeAttribute.ExceptionEntry innerEntry(List<CodeAttribute.ExceptionEntry> entries, Copy cp2) {
        for (CodeAttribute.ExceptionEntry e : entries) {
            if (e.startPc == cp2.closeStart && e.endPc == cp2.closeEnd && e.handlerPc == cp2.s && e.catchType > 0
                    && "java/lang/Throwable".equals(cp.getClassName(e.catchType))) return e;
        }
        return null;
    }

    private Copy parseCopy(int pc, int res, int prim) {
        Copy k = new Copy();
        k.start = pc;
        int p = pc;
        int r = loadSlot(p);
        if (r < 0 || (res >= 0 && r != res) || r == prim) return null;
        k.r = r;
        p += len(p);
        if (op(p) != IFNULL) return null;
        k.x = target(p);
        p += 3;
        if (loadSlot(p) != prim) return null;
        p += len(p);
        if (op(p) != IFNULL) return null;
        int l = target(p);
        p += 3;
        if (loadSlot(p) != r) return null;
        k.closeStart = p;
        p += len(p);
        if (!isClose(p)) return null;
        int close1 = p;
        p += len(p);
        k.closeEnd = p;
        if (op(p) != GOTO || target(p) != k.x) return null;
        p += 3;
        k.s = p;
        int t2 = storeSlot(p);
        if (t2 < 0) return null;
        p += len(p);
        if (loadSlot(p) != prim) return null;
        p += len(p);
        if (loadSlot(p) != t2) return null;
        p += len(p);
        if (!isAddSuppressed(p)) return null;
        p += len(p);
        if (op(p) != GOTO || target(p) != k.x) return null;
        p += 3;
        if (p != l || loadSlot(p) != r) return null;
        p += len(p);
        if (!isClose(p) || !sameBytes(p, close1, len(p))) return null;
        p += len(p);
        if (op(p) == GOTO && target(p) == k.x) p += 3;
        k.end = p;
        return k;
    }

    private boolean isClose(int p) {
        int o = op(p);
        if (o != 0xb6 && o != 0xb9) return false;
        int idx = u2(p + 1);
        return "close".equals(cp.getMemberName(idx)) && "()V".equals(cp.getMemberDescriptor(idx));
    }

    private boolean isAddSuppressed(int p) {
        if (op(p) != 0xb6) return false;
        int idx = u2(p + 1);
        return "addSuppressed".equals(cp.getMemberName(idx))
            && "(Ljava/lang/Throwable;)V".equals(cp.getMemberDescriptor(idx));
    }

    private int loadSlot(int p) {
        if (p < 0 || p >= c.length || !isStart[p]) return -1;
        int o = op(p);
        if (o == 0x19) return c[p + 1] & 0xff;
        if (o >= 0x2a && o <= 0x2d) return o - 0x2a;
        if (o == 0xc4 && (c[p + 1] & 0xff) == 0x19) return u2(p + 2);
        return -1;
    }

    private int storeSlot(int p) {
        if (p < 0 || p >= c.length || !isStart[p]) return -1;
        int o = op(p);
        if (o == 0x3a) return c[p + 1] & 0xff;
        if (o >= 0x4b && o <= 0x4e) return o - 0x4b;
        if (o == 0xc4 && (c[p + 1] & 0xff) == 0x3a) return u2(p + 2);
        return -1;
    }

    private int prevStart(int pc) {
        for (int p = pc - 1; p >= 0 && p >= pc - 6; p--) {
            if (isStart[p]) return p + len(p) == pc ? p : -1;
        }
        return -1;
    }

    private boolean sameBytes(int a, int b, int n) {
        for (int i = 0; i < n; i++) if (c[a + i] != c[b + i]) return false;
        return true;
    }

    private byte[] slice(int from, int n) {
        byte[] b = new byte[n];
        System.arraycopy(c, from, b, 0, n);
        return b;
    }

    private int op(int p) {
        return p >= 0 && p < c.length ? c[p] & 0xff : -1;
    }

    private int u2(int p) {
        return ((c[p] & 0xff) << 8) | (c[p + 1] & 0xff);
    }

    private int target(int p) {
        return p + (short) u2(p + 1);
    }

    private int len(int p) {
        return opLength(c, p);
    }

    /** Length in bytes of the instruction at {@code pc}, or -1 for an unknown opcode. */
    private static int opLength(byte[] c, int pc) {
        int op = c[pc] & 0xff;
        if (op <= 0x0f) return 1;
        if (op == 0x10 || op == 0x12) return 2;
        if (op == 0x11 || op == 0x13 || op == 0x14) return 3;
        if (op >= 0x15 && op <= 0x19) return 2;
        if (op >= 0x1a && op <= 0x35) return 1;
        if (op >= 0x36 && op <= 0x3a) return 2;
        if (op >= 0x3b && op <= 0x83) return 1;
        if (op == 0x84) return 3;
        if (op >= 0x85 && op <= 0x98) return 1;
        if (op >= 0x99 && op <= 0xa8) return 3;
        if (op == 0xa9) return 2;
        if (op == 0xaa || op == 0xab) {
            int p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
            if (p + 12 > c.length) return -1;
            if (op == 0xaa) {
                int low = readInt(c, p + 4), high = readInt(c, p + 8);
                return p + 12 + (high - low + 1) * 4 - pc;
            }
            int n = readInt(c, p + 4);
            return p + 8 + n * 8 - pc;
        }
        if (op >= 0xac && op <= 0xb1) return 1;
        if (op >= 0xb2 && op <= 0xb8) return 3;
        if (op == 0xb9 || op == 0xba) return 5;
        if (op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1) return 3;
        if (op == 0xbc) return 2;
        if (op == 0xbe || op == 0xbf || op == 0xc2 || op == 0xc3) return 1;
        if (op == 0xc4) return pc + 1 < c.length && (c[pc + 1] & 0xff) == 0x84 ? 6 : 4;
        if (op == 0xc5) return 4;
        if (op == 0xc6 || op == 0xc7) return 3;
        if (op == 0xc8 || op == 0xc9) return 5;
        return -1;
    }

    private static int readInt(byte[] c, int p) {
        return ((c[p] & 0xff) << 24) | ((c[p + 1] & 0xff) << 16) | ((c[p + 2] & 0xff) << 8) | (c[p + 3] & 0xff);
    }
}
// END_CHANGE: BUG-2026-0180-1
