/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.model.classfile.ClassFile;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.ExceptionsAttribute;
import it.denzosoft.javadecompiler.service.deserializer.ClassFileDeserializer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Read-only view of the class hierarchy of the decompiled program (BUG-2026-0161).
 *
 * <p>Class headers (superclass, interfaces, the {@code throws} clause of each method) are read
 * from the decompilation's {@link Loader} and, for platform classes it does not contain, from
 * the running JVM's system class path. Results are cached per loader; the cache only ever holds
 * data read from immutable class files, so sharing it between threads keeps decompilation
 * deterministic. Every query answers "unknown" when a class cannot be found.</p>
 */
public final class ClassHierarchy {

    private static final class Info {
        String superName;
        String[] interfaces;
        boolean isInterface;
        final Map<String, String[]> throwsOf = new HashMap<String, String[]>();
        // BUG-2026-0166: javac enum-switch tables of this class, field name -> index -> {enum, constant}
        Map<String, Map<Integer, String[]>> switchMaps;
        // BUG-2026-0183: simple names of the non-private member types this class declares
        final java.util.Set<String> memberTypes = new java.util.HashSet<String>();
    }

    private static final Info MISSING = new Info();
    private static final Map<Object, Map<String, Info>> CACHE = new WeakHashMap<Object, Map<String, Info>>();
    private static final Object NO_LOADER = new Object();

    private final Loader loader;

    public ClassHierarchy(Loader loader) {
        this.loader = loader;
    }

    private Info info(String internalName) {
        if (internalName == null || internalName.startsWith("[")) return null;
        Map<String, Info> m;
        synchronized (CACHE) {
            Object key = loader != null ? (Object) loader : NO_LOADER;
            m = CACHE.get(key);
            if (m == null) {
                m = new HashMap<String, Info>();
                CACHE.put(key, m);
            }
            Info i = m.get(internalName);
            if (i != null) return i == MISSING ? null : i;
        }
        Info i = read(internalName);
        synchronized (CACHE) {
            m.put(internalName, i == null ? MISSING : i);
        }
        return i;
    }

    private Info read(String internalName) {
        byte[] data = null;
        try {
            if (loader != null && loader.canLoad(internalName)) data = loader.load(internalName);
        } catch (Exception e) {
            data = null;
        }
        if (data == null) {
            InputStream in = null;
            try {
                in = ClassLoader.getSystemResourceAsStream(internalName + ".class");
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
            } catch (Exception e) {
                data = null;
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignore) { }
                }
            }
        }
        if (data == null) return null;
        try {
            ClassFile cf = new ClassFileDeserializer().deserialize(data);
            Info i = new Info();
            i.superName = cf.getSuperClassName();
            i.isInterface = cf.isInterface();
            i.interfaces = cf.getInterfaces() != null ? cf.getInterfaces() : new String[0];
            // START_CHANGE: BUG-2026-0183-20261018-2 - Member types, for simple-name shadowing.
            it.denzosoft.javadecompiler.model.classfile.attribute.InnerClassesAttribute ica = cf.findAttribute("InnerClasses");
            if (ica != null && ica.getClasses() != null) {
                for (it.denzosoft.javadecompiler.model.classfile.attribute.InnerClassesAttribute.InnerClass ic : ica.getClasses()) {
                    if (internalName.equals(ic.outerClassName) && ic.innerName != null && (ic.accessFlags & 0x0002) == 0) {
                        i.memberTypes.add(ic.innerName);
                    }
                }
            }
            // END_CHANGE: BUG-2026-0183-2
            if (cf.getMethods() != null) {
                for (MethodInfo mi : cf.getMethods()) {
                    ExceptionsAttribute ex = mi.findAttribute("Exceptions");
                    String[] thr = ex != null ? ex.getExceptions() : new String[0];
                    it.denzosoft.javadecompiler.model.classfile.attribute.SignatureAttribute sig = mi.findAttribute("Signature");
                    if (sig != null && sig.getSignature() != null && sig.getSignature().indexOf("^T") >= 0) {
                        thr = new String[0]; // `throws X` with a type variable: decided by inference
                    }
                    i.throwsOf.put(mi.getName() + mi.getDescriptor(), thr);
                    // START_CHANGE: BUG-2026-0166-20261018-1 - Read the $SwitchMap$ tables
                    if ("<clinit>".equals(mi.getName()) && internalName.indexOf('$') > 0) {
                        i.switchMaps = readSwitchMaps(cf, mi, internalName);
                    }
                    // END_CHANGE: BUG-2026-0166-1
                }
            }
            return i;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // START_CHANGE: BUG-2026-0166-20261018-2 - Resolve a javac enum-switch table.
    /**
     * The enum constant each index of a javac {@code $SwitchMap$} table stands for, or null when
     * the table cannot be read.
     *
     * <p>javac compiles {@code switch (e)} on an enum into {@code switch ($SwitchMap$E[e.ordinal()])}
     * where the int array lives in a synthetic class (usually {@code Outer$1}) and is filled by its
     * static initializer: {@code $SwitchMap$E[E.CONST.ordinal()] = n;}. The case labels in the
     * bytecode are those {@code n}, not ordinals, so the source labels can only be recovered from
     * that initializer. Each value is {enum internal name, constant name}.</p>
     */
    public Map<Integer, String[]> switchMap(String owner, String field) {
        Info i = info(owner);
        if (i == null || i.switchMaps == null) return null;
        return i.switchMaps.get(field);
    }

    private static Map<String, Map<Integer, String[]>> readSwitchMaps(ClassFile cf, MethodInfo clinit, String self) {
        it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute ca = clinit.findAttribute("Code");
        if (ca == null || ca.getCode() == null) return null;
        it.denzosoft.javadecompiler.model.classfile.ConstantPool cp = cf.getConstantPool();
        byte[] c = ca.getCode();
        Map<String, Map<Integer, String[]>> out = null;
        // Matches: getstatic self.$SwitchMap$X; getstatic E.CONST:LE;; invokevirtual E.ordinal()I;
        //          <int constant n>; iastore
        int stage = 0;
        String table = null, enumOwner = null, constName = null;
        int value = 0;
        int pc = 0;
        while (pc < c.length) {
            int op = c[pc] & 0xff;
            int len = opLength(c, pc);
            if (len <= 0) return null;
            int next = 0;
            if (op == 0xb2 && pc + 2 < c.length) { // getstatic
                int idx = ((c[pc + 1] & 0xff) << 8) | (c[pc + 2] & 0xff);
                String mOwner = cp.getMemberClassName(idx);
                String mName = cp.getMemberName(idx);
                String mDesc = cp.getMemberDescriptor(idx);
                if (mName != null && mName.startsWith("$SwitchMap$") && self.equals(mOwner)) {
                    table = mName;
                    next = 1;
                } else if (stage == 1 && mDesc != null && mDesc.equals("L" + mOwner + ";")) {
                    enumOwner = mOwner;
                    constName = mName;
                    next = 2;
                }
            } else if (op == 0xb6 && stage == 2 && pc + 2 < c.length) { // invokevirtual
                int idx = ((c[pc + 1] & 0xff) << 8) | (c[pc + 2] & 0xff);
                if ("ordinal".equals(cp.getMemberName(idx)) && "()I".equals(cp.getMemberDescriptor(idx))) next = 3;
            } else if (stage == 3) {
                Integer v = null;
                if (op >= 0x02 && op <= 0x08) v = Integer.valueOf(op - 0x03);
                else if (op == 0x10) v = Integer.valueOf(c[pc + 1]);
                else if (op == 0x11) v = Integer.valueOf((short) (((c[pc + 1] & 0xff) << 8) | (c[pc + 2] & 0xff)));
                else if (op == 0x12 || op == 0x13) {
                    int idx = op == 0x12 ? (c[pc + 1] & 0xff) : (((c[pc + 1] & 0xff) << 8) | (c[pc + 2] & 0xff));
                    if (cp.getTag(idx) == it.denzosoft.javadecompiler.model.classfile.ConstantPool.CONSTANT_Integer) {
                        Object o = cp.getValue(idx);
                        if (o instanceof Integer) v = (Integer) o;
                    }
                }
                if (v != null) { value = v.intValue(); next = 4; }
            } else if (op == 0x4f && stage == 4) { // iastore
                if (out == null) out = new HashMap<String, Map<Integer, String[]>>();
                Map<Integer, String[]> m = out.get(table);
                if (m == null) { m = new HashMap<Integer, String[]>(); out.put(table, m); }
                String[] prev = m.get(Integer.valueOf(value));
                if (prev != null && !prev[1].equals(constName)) {
                    m.put(Integer.valueOf(value), new String[0]); // conflicting: unusable index
                } else {
                    m.put(Integer.valueOf(value), new String[] {enumOwner, constName});
                }
            }
            stage = next;
            pc += len;
        }
        if (out == null) return null;
        Map<String, Map<Integer, String[]>> clean = new HashMap<String, Map<Integer, String[]>>();
        for (Map.Entry<String, Map<Integer, String[]>> e : out.entrySet()) {
            boolean ok = true;
            for (String[] v : e.getValue().values()) if (v.length != 2) ok = false;
            if (ok) clean.put(e.getKey(), e.getValue());
        }
        return clean;
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
    // END_CHANGE: BUG-2026-0166-2

    // START_CHANGE: BUG-2026-0183-20261018-3
    /**
     * Simple names of the member types {@code internalName} inherits from its superclasses and
     * superinterfaces (as far as they can be loaded). Such a name shadows a same-named import or
     * java.lang type inside the class body: {@code Double} in a subclass of Rectangle2D is
     * Rectangle2D.Double.
     */
    public java.util.Set<String> inheritedMemberTypeNames(String internalName) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<String>();
        Info self = info(internalName);
        if (self == null) return out;
        if (self.superName != null) work.add(self.superName);
        for (String itf : self.interfaces) work.add(itf);
        while (!work.isEmpty() && seen.size() < 64) {
            String c = work.poll();
            if (!seen.add(c)) continue;
            Info i = info(c);
            if (i == null) continue;
            out.addAll(i.memberTypes);
            if (i.superName != null) work.add(i.superName);
            for (String itf : i.interfaces) work.add(itf);
        }
        return out;
    }
    // END_CHANGE: BUG-2026-0183-3

    /** TRUE / FALSE when known, null when some class on the way is missing. */
    public Boolean isSubtype(String sub, String sup) {
        if (sub == null || sup == null) return null;
        if (sub.equals(sup) || "java/lang/Object".equals(sup)) return Boolean.TRUE;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<String>();
        work.add(sub);
        boolean unknown = false;
        while (!work.isEmpty()) {
            String c = work.poll();
            if (!seen.add(c)) continue;
            if (c.equals(sup)) return Boolean.TRUE;
            Info i = info(c);
            if (i == null) { unknown = true; continue; }
            if (i.superName != null) work.add(i.superName);
            for (String s : i.interfaces) work.add(s);
        }
        return unknown ? null : Boolean.FALSE;
    }

    /**
     * Least common superclass of two classes (interfaces and unknown classes give
     * java/lang/Object unless one is a subtype of the other); null when nothing is known.
     */
    // START_CHANGE: BUG-2026-0192-20261018-1 - Least upper bound that also considers interfaces.
    /**
     * Like {@link #commonSuper} but, when the only common superCLASS is java/lang/Object, returns
     * the unique most specific interface both types implement (ECFieldFp / ECFieldF2m -> ECField).
     * Returns java/lang/Object when there is no such interface or it is ambiguous, null when
     * nothing is known.
     */
    public String leastUpperBound(String a, String b) {
        String c = commonSuper(a, b);
        if (c == null || !"java/lang/Object".equals(c)) return c;
        java.util.Set<String> sa = allSupertypes(a);
        java.util.Set<String> sb = allSupertypes(b);
        if (sa == null || sb == null) return c;
        sa.retainAll(sb);
        java.util.List<String> minimal = new java.util.ArrayList<String>();
        for (String x : sa) {
            Info ix = info(x);
            if (ix == null || !ix.isInterface) continue;
            boolean hasSub = false;
            for (String y : sa) {
                if (!y.equals(x) && Boolean.TRUE.equals(isSubtype(y, x))) { hasSub = true; break; }
            }
            if (!hasSub) minimal.add(x);
        }
        return minimal.size() == 1 ? minimal.get(0) : c;
    }

    /** Every supertype (classes and interfaces, including t), or null if any is unknown. */
    private java.util.Set<String> allSupertypes(String t) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<String>();
        work.add(t);
        while (!work.isEmpty()) {
            String c = work.poll();
            if (!seen.add(c)) continue;
            if ("java/lang/Object".equals(c)) continue;
            Info i = info(c);
            if (i == null) return null;
            if (i.superName != null) work.add(i.superName);
            for (String s : i.interfaces) work.add(s);
        }
        return seen;
    }
    // END_CHANGE: BUG-2026-0192-1

    public String commonSuper(String a, String b) {
        if (a == null || b == null) return null;
        if (a.equals(b)) return a;
        if (Boolean.TRUE.equals(isSubtype(a, b))) return b;
        if (Boolean.TRUE.equals(isSubtype(b, a))) return a;
        java.util.List<String> chainA = new java.util.ArrayList<String>();
        String c = a;
        int guard = 0;
        while (c != null && guard++ < 100) {
            chainA.add(c);
            Info i = info(c);
            if (i == null) return null;
            if (i.isInterface) { chainA.clear(); break; }
            c = i.superName;
        }
        c = b;
        guard = 0;
        while (c != null && guard++ < 100) {
            if (chainA.contains(c)) return c;
            Info i = info(c);
            if (i == null) return null;
            if (i.isInterface) break;
            c = i.superName;
        }
        return "java/lang/Object";
    }

    /** Checked = not a subtype of RuntimeException or Error (null when unknown). */
    public Boolean isChecked(String exception) {
        Boolean rt = isSubtype(exception, "java/lang/RuntimeException");
        if (rt == null) return null;
        if (rt.booleanValue()) return Boolean.FALSE;
        Boolean er = isSubtype(exception, "java/lang/Error");
        if (er == null) return null;
        return er.booleanValue() ? Boolean.FALSE : Boolean.TRUE;
    }

    /** The declared exceptions of a method found on owner or its supertypes; null when unknown. */
    public String[] throwsOf(String owner, String name, String desc) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<String>();
        work.add(owner);
        while (!work.isEmpty()) {
            String c = work.poll();
            if (!seen.add(c)) continue;
            Info i = info(c);
            if (i == null) return null;
            String[] t = i.throwsOf.get(name + desc);
            if (t != null) return t;
            if (i.superName != null) work.add(i.superName);
            for (String s : i.interfaces) work.add(s);
        }
        return null;
    }
}
