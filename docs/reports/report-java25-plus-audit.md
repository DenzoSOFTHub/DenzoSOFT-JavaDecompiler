# Report: Java 25+ decompilation audit (v1.10.0 baseline)

Evidence-based audit performed on 2026-09-05 against the v1.10.0 tree. It measured the
construct matrix in three debug modes, 3,376 top-level classes of JDK 25 `java.base`, and
purpose-built Java 21-25 probe fixtures. It is the source of the v1.11.0 backlog
(BUG-2026-0100..0122, IMP-2026-0070..0074, OPT-0007..0012, LIM-0009, REQ-2026-0001).

Paths starting with SCRATCH refer to the throwaway audit workspace and are not in the repo.

---


Date: 2026-09-05. Build analysed: v1.10.0 working tree (git clean), compiled with JDK 8.
Method: read pipeline + knowledge base, then MEASURED: (a) repo construct matrix (55 top-level classes) round-tripped in three debug modes x two pipelines; (b) 7 new Java 21-25 probe fixtures + 1 --enable-preview fixture; (c) JDK 25 java.base (7,406 class files, 3,376 top-level) batch-decompiled with both pipelines, marker-clustered, bytecode-drilled; (d) single-thread latency, thread scaling, JFR profile; (e) class-file census; (f) hand-patched class files at major 70/99.
Scratch artefacts (SCRATCH = this directory): matrix.sh, mx_{g,nog,none}*/, probe/, probe2/, jbase*/, harness/, rec.jfr, samples.txt. Nothing in the repository was modified.

## 0. Executive summary

The headline claims (57/57 recompile-clean, 0 known silent miscompilations, 99.7% marker-clean) hold ONLY for javac's default -g:source,lines and ONLY for the recompile metric.

| Gate | Result |
|---|---|
| Matrix, default debug (-g:source,lines) | 55/55 legacy, 36/55 JD (repo has 55 classes, not 57 - IMP-2026-0071) |
| Matrix, -g (full LVT = Maven default, production jars) | 44/55 legacy, 36/55 JD; 10 of 11 failures are one bug (BUG-2026-0106) |
| Matrix, -g:none (stripped/obfuscated) | 53/55 recompile, BUT 0 catch clauses survive (source 15), 1 finally of 6, 0 try-with-resources: every handler silently deleted, output compiles (BUG-2026-0100) |
| java.base (3,376 top-level, HAS LVT) | 0 crashes, 0 empty, 16 STACK_UNDERFLOW files (0.47%), 0 real DECODE_ERROR (the "23" were Alert.DECODE_ERROR source strings) |
| java.base silent defects (compile-clean shapes) | 98 classes (2.9%) with a dropped synchronized; 36 classes with /* inline stmt */ lambda truncation (66 sites); 1,008 classes (29.9%) with a same-scope duplicate declaration (5,410 sites) |
| Pattern switch on real code | 27 of 31 java.base classes with typeSwitch sites still emit raw SwitchBootstraps.typeSwitch (87%) |
| Java 26 class files (major 70) | hard-rejected, zero output |
| Throughput | 906 classes/s single-thread (p50 0.3 ms, p99 12 ms, max 83 ms); 664 -> 2,306/s from 1 -> 4 threads (no contention); 12% of CPU is the disassembler running for every method without --show-bytecode |

Ranking principle: silent compilable miscompilation > truncation with diagnostic > recompile failure > crash.

## 1. IMPLEMENT FIRST

| # | ID | Title | Sev | Effort |
|---|---|---|---|---|
| 1 | BUG-2026-0106 | Duplicate local declaration with LVT (int sum; int sum = 0;) | HIGH | LOW (3 lines) |
| 2 | BUG-2026-0101 | synchronized nested in if/loop/try silently dropped | CRITICAL | LOW |
| 3 | BUG-2026-0102 | Lambda body for/switch/throw/try/synchronized -> /* inline stmt */; (compiles) | CRITICAL | MEDIUM |
| 4 | BUG-2026-0104 | dup2/dup2_x1/dup2_x2/dup_x2 ignore category-2 values (dup2_x1/x2 are no-ops) | HIGH | LOW |
| 5 | BUG-2026-0100 | try/catch/finally assigned by source line; no LineNumberTable => all handlers vanish | CRITICAL | MEDIUM-HIGH |
| 6 | BUG-2026-0103 | LVT scope ranges ignored (slot -> one name/type) | CRITICAL | MEDIUM |
| 7 | BUG-2026-0118 | Class-file major > 69 hard-rejected (Java 26+) | HIGH | LOW |
| 8 | BUG-2026-0108 | Pattern-switch labels stored as CONSTANT_Dynamic print `case  _` (qualified enum constants Java 21 GA; JEP 507) | HIGH | MEDIUM |
| 9 | OPT-0007 | BytecodeDisassembler runs unconditionally for every method (12% CPU) | - | LOW |
| 10 | BUG-2026-0110 | Anonymous class: fields/initializers dropped, generic supertype lost | HIGH | MEDIUM |

## 2. Measurement detail

### 2.1 Construct matrix (SCRATCH/matrix.sh <g|nog|none> [jvm flags])
| Mode | javac | legacy | JD | failing (legacy) |
|---|---|---|---|---|
| nog | -g:source,lines | 55/55 | 36/55 | - |
| g | -g | 44/55 | 36/55 | C_Arrays, C_ControlFlow(5), C_EnhancedFor(2), C_Exceptions, C_Generics, C_OverloadDemo, C_RecordPattern, C_Strings, C_VarargsBoxing, C_VarInference(3), C_Wildcards |
| none | -g:none | 53/55 | n/a | C_Java7MultiCatch, C_Java7TryResources |
-g failures: 10 classes = "variable X is already defined" (0106); C_RecordPattern: pre-declared `C_RecordPattern.Point a;` collides with `case Line(Point a, Point b)` (0107); C_VarInference also "package Map does not exist" (0116).

### 2.2 Semantic loss invisible to recompilation (-g:none), keyword census over all decompiled matrix classes
| Mode | catch ( | finally | synchronized ( | try ( |
|---|---|---|---|---|
| source | 15 | 6 | 5 | 5 |
| g / nog | 14 | 3 | 5 | 5 |
| none | 0 | 1 | 5 | 0 |
C_Exceptions.tryCatchFinally at -g:none: `int var3 = 0; var3 = arg0[arg1]; var3 += 1000; return var3;` (compiles).

### 2.3 Java 21-25 probes (SCRATCH/probe/src, -g; probe/preview with --enable-preview --release 25)
| Fixture | Result | Clusters |
|---|---|---|
| P_FlexibleCtor (JEP 513) | PASS | - |
| P_RecordPatterns (nested record patterns, guards, case null, generic records, enum switch, qualified enum constant case) | FAIL 1 | `case  _ -> "mon"` for `case DayOfWeek.MONDAY` (0108) |
| P_Unnamed (`_` everywhere, `case Point(int x, _)`, `case Box(_)`, `case Integer _, Long _`) | FAIL 10 | LVT flattening (0103/0106) + pat()/multi() residual raw typeSwitch with ALL returns lost and `break; break;` (0109) - reproduces without -g too |
| P_ModernApis (ScopedValue, virtual threads, sequenced collections, gatherers, text block, string/long/char switch, VarHandle) | FAIL 1 | `try (ExecutorService ex = ...)` + body local -> `throw f;` (0103 + 0111) |
| P_Structured (classic control flow, TWR combos, nested try, labeled loops, boolean ternary, compound ops) | FAIL 15 | 5x dup decl (0106), `try (r1; r2)` (ROADMAP known), try(a;b) catch finally mangled (0111/0103), `boolean ok = a > b ? 1 : 0` (0112) |
| P_Lambdas (nested/curried lambdas, all method-ref kinds, lambda with try, anon inside lambda, recursive ref) | FAIL 3 | lambda try body -> /* inline stmt */ (0102), `new P_Lambdas._1(k)` anon-in-lambda (0110b), Optional<CAP#1> (LIM-0002 permanent) |
| P_Generics (bounded/recursive generics, wildcards, generic arrays, raw/diamond, enum bodies, @interface defaults, anon with field, local class, intersection-cast lambda, private interface methods) | FAIL 9 | `(Number)` cast for R, `best = e.getKey()` no cast, `(Object[])` for T[] (0113); anon `cnt` field dropped + raw `new Comparator()` (0110); `(Runnable) (Serializable) () -> {}` (0114); 2x dup decl |
| P_Preview (JEP 507 primitive/long/double/boolean patterns, StableValue, StructuredTaskScope) | FAIL 2 | `case  _` for case true/false (0108); `double x` switch residual |
Deserializer accepts minor 0xFFFF silently (IMP-2026-0073).

### 2.4 java.base
Census (harness/Scan.java): 0 deserialize failures; majors {52:1, 69:7405}; 6,825/6,861 code-bearing classes have LocalVariableTable (java.base IS a -g corpus); 12,445 LVTT; 0 CONSTANT_Dynamic; bootstraps: LambdaMetafactory.metafactory 1,563, ObjectMethods 178, SwitchBootstraps.typeSwitch 63, altMetafactory 15; attributes seen incl. ModuleHashes, ModuleTarget (unknown -> skipped).
Batch (legacy): 3,376/3,376 ok, 0 errors, 2.56 s (startup-dominated).
| Signal | files |
|---|---|
| `//   - STACK_UNDERFLOW` | 16 (30 sites) |
| DECODE_ERROR/CFG/STRUCTURED_FLOW/JD failures, `/* condition */`, `/* expr */` | 0 |
| `/* __MONITORENTER__ */` (lost synchronized) | 98 (+55 with only cosmetic __MONITOREXIT__ on early exits) |
| `/* inline stmt */` | 36 (66 sites) e.g. java/lang/Module.java:1365, ProcessBuilder.java:1257, reflect/Proxy.java:1349 |
| same-scope duplicate declaration (heuristic: bare `T x;` then `T x = ...` at identical indent in same block) | 1,008 (29.9%), 5,410 sites; Pattern 79, BigInteger 71, ConcurrentHashMap 62 |
| raw SwitchBootstraps.typeSwitch | 27 of 31 classes using it |
| `(X) (Serializable) lambda` | ChronoLocalDate:260, ChronoZonedDateTime:141, TreeMap:3383 |
| `Object varN = null` retype failures | 10 |
STACK_UNDERFLOW clusters (javap-verified): (1) category-2 dup family, 6 files: StampedLock.releaseWrite pc 9 (`dup2_x1; putfield state:J; lstore_3` -> `long nextState = null;`), Spliterators$IteratorSpliterator.trySplit pc 39, FdLibm$RemPio2 pc 465 (`dup2_x2; dastore; dastore`), CompletableFuture$Signaller / LinkedBlockingDeque / LinkedBlockingQueue / Phaser (lcmp after dup2-based `(nanos = ...) <= 0L`), Exchanger -> BUG-2026-0104. (2) switch-expression/ternary arm values merging into aastore or a call with stack-carried operands, 8 files: StackMapDecoder.initFrameLocals pc 315 (arms `goto 315` into a shared aastore; array+index pushed before the switch), AnnotationReader 910/397, ClassPrinterImpl 320-324/1292-1293, VMSupport 481-484, ObjectStreamClass 88, keytool/Main 203, ParserVerifier 186 -> BUG-2026-0115. (3) ConstantPoolBuilder 164 (invokeinterface after ternary argument) -> same family.
JD pipeline on java.base: same 16 underflow files (shared decoder), monitor leak 146 vs 153, residual typeSwitch 30 vs 27, 1,974/3,376 outputs differ.

### 2.5 Performance
Latency.java (single thread, warm): 906 classes/s; p50 300 us, p90 2.5 ms, p99 12.1 ms, max 83 ms (jdk/internal/classfile/impl/AnnotationReader, 22 KB); top-20 = 14% of time (no pathological outliers).
ParLatency.java: 1 thread 664/s, 2: 1,186, 4: 2,306, 6: 2,006 (4 physical cores) -> no lock contention.
JFR (profile, 4 passes, 955 samples) self: decodeOpcode 18%, HashMap.getNode 4.5%, writeExpression 2.8%, StringBuilder.append 2.7%, BytecodeDisassembler.decodeInstruction 2.4% (inclusive disassemble 12.1%), StringLatin1.replace 2.1% (ObjectType(String) -> internalToQualified), Integer.parseInt 1.7% (TryCatchReconstructor comparator split/parseInt), StructuredFlowBuilder.hasDirectBackEdge 1.3% self / 8.3% inclusive, collectImports family ~4%.

### 2.6 Class-file headroom probes
- major 70 / 99 -> "Unsupported class file version" at ClassFileDeserializer.java:57-62; --batch counts an error, no output.
- Unknown attribute -> skipped (AttributeParser.java:117-120); any exception inside an attribute swallowed and skipped (:33-48) - tolerant but silent.
- Unknown CP tag -> IllegalArgumentException (ConstantPool.parse default) - correct (size unknown), surfaced per class.
- ldc CONSTANT_Dynamic -> string literal "/* constant:N */" (Converter:4446) - silent wrong value (0117).
- Any indy bootstrap with a MethodHandle at args[1] is decoded as lambda/method-ref regardless of owner (Converter:3150-3260) (0119).

## 3. Prioritized backlog
ID allocation: taken = BUG-2026-0001..0099, LIM-0001..0008, OPT-0001..0006, IMP-2026-0001/0002/0004/0009/0010/0062/0063, no REQ. New: BUG-2026-0100+, LIM-0009+, OPT-0007+, IMP-2026-0070+, REQ-2026-0001+.

### BUG-2026-0100 - try/catch/finally membership decided by source line; all handlers deleted without LineNumberTable; statements displaced with it
Type Bug. Severity CRITICAL (silent). NEW.
Evidence: matrix -g:none 0 catch across 55 classes (source 15); mx_none/dec/C_Exceptions.java tryCatchFinally reduced to the happy path; probe2/none S_Sync.slotReuse and P_Structured.nestedTry identical. With lines (probe2/g) S_Sync.slotReuse: `r = f == null ? 1 : 2;` (inside exception range [2,16) per javap) emitted AFTER the catch.
Root cause: transform/TryCatchReconstructor.java:172-230 applyGroup: tryStartLine = findLineForPc(tryStartPc); `if (tryStartLine < 0) return null;` (:181); membership `sLine >= tryStartLine && sLine <= tryEndLine` (:213); computeAfterTryStartLine (:1308). Handler blocks are unreachable from the entry walk, so a rejected group loses its catch bodies entirely; the duplicated finally on the normal path is what remains. Statements carry only lineNumber.
Approach: give statements a bytecode position: in decodeBasicBlock record IdentityHashMap<Statement,int[]>{startPc,endPc} per emitted top-level statement; pass it to TryCatchReconstructor; classify by PC (startPc in [tryStart,tryEnd)); fall back to lines only for transform-synthesised statements. Add the -g:none matrix mode with a keyword-census assertion (catch count == source).
Effort MEDIUM-HIGH. Impact: every stripped/obfuscated jar; also the displacement class on -g code.

### BUG-2026-0101 - synchronized nested inside any compound statement silently dropped
Severity CRITICAL (silent; lock removed; compiles). NEW.
Evidence: probe2/S_Sync.nestedInIf/nestedInLoop in -g and no-LVT -> `Object var2 = this.lock; /* __MONITORENTER__ */ try { this.counter += 1; } finally { /* __MONITOREXIT__ */ }`; top-level synchronized in the same class is fine. java.base 98/3,376 outputs contain __MONITORENTER__ (java/util/Date.java:590, String, ConcurrentHashMap, WeakHashMap, Pattern, ResourceBundle...); javap of Date pc 806-846 confirms a real monitor region inside an if.
Root cause: Converter.java:4776 reconstructSynchronized walks only the given list; stripMonitorFromTryFinally (:4958) recurses into BlockStatement only; neither descends into If/IfElse/While/DoWhile/For/ForEach/Try/Switch/Label/Synchronized bodies; the monitor try/finally is built by TryCatchReconstructor inside the enclosing structure.
Approach: move to transform/SynchronizedReconstructor; add recurse(Statement) like BranchVarHoister.recurse rebuilding every compound body; drop leftover __MONITOREXIT__ comments whose enclosing SynchronizedStatement was built. Tests: S_Sync + grep gate __MONITOR == 0 on self-sweep and java.base sample.
Effort LOW.

### BUG-2026-0102 - lambda block bodies: for/while/do/switch/throw/try/synchronized/labeled emitted as `/* inline stmt */;`
Severity CRITICAL (statement loss; usually compiles). NEW.
Evidence: probe2/S_Sync lambdaLoop -> `() -> { /* inline stmt */; }`, lambdaSwitch, lambdaSync same, lambdaThrow -> `if (p == null) { /* inline stmt */; }`; P_Lambdas.lambdaWithTry -> `() -> { FileInputStream in = null; /* inline stmt */; /* inline stmt */; }`. java.base 36 files / 66 sites (java/lang/reflect/Proxy.java:1349 `assert () -> { /* inline stmt */; }.getAsBoolean()`).
Root cause: JavaSourceWriter.java:3843-3888 writeInlineLambdaStatement handles Expression/Return/If/IfElse only; else writeInlineStatement (:3781-3839) handles Expression/VarDecl/Block and prints the placeholder at :3838.
Approach: when the body contains anything else, emit a multi-line block via writeStatement (the anonymous-class inliner at :3290-3312 already does this inside an expression); never print a placeholder - record a diagnostic instead. Gate: `/* inline stmt */` == 0 on java.base.
Effort MEDIUM.

### BUG-2026-0103 - LocalVariableTable scope ranges ignored: one name/descriptor/signature per slot
Severity CRITICAL (variable identity/type corruption on -g code; often compiles). NEW (ISS-2026-0005 special-cased catch slots only).
Evidence (-g): P_Unnamed.useUnnamed -> `catch (NumberFormatException f)` then `f = (p0, p1) -> a;` (BiFunction declaration lost); P_ModernApis.virtualThreads -> `throw f;` (f is the Future sharing the Throwable slot); S_Sync.slotReuse -> `for (Integer y : xs)` over List<String> plus `Integer y;` pre-declared; P_Structured.twrCatchFinally -> `buf = "io"`. None occurs without LVT.
Root cause: Converter.java:1236-1247 flattens the LVT (`localVarNames.put(lv.index, lv.name)` :1244, same for descriptors/signatures) - last wins; pushLocal (:3539)/storeLocal (:3568)/decodeIinc (:2208) look up by slot only although currentDecodePc is known; start_pc/length parsed and discarded.
Approach: LocalVariableResolver.resolve(slot, pc) -> {name, descriptor, signature} from the LVT entry whose [start_pc, start_pc+length) contains pc (treat the store immediately before start_pc as belonging to that entry); two entries sharing a slot with different names are distinct variables declared at their first store; feed per-entry declaration points into the pre-declaration logic instead of the slot-only varAssignBlocks scan. Also the seam for 0106/0107 and BUG-2026-0096's slot-split state.
Effort MEDIUM. Impact: all Maven/Gradle-built jars, java.base.

### BUG-2026-0104 - dup2, dup2_x1, dup2_x2, dup_x2 mis-model category-2 (long/double) values
Severity HIGH (diagnosed underflow today; silent wrong duplication when the stack happens to be deep enough). NEW.
Evidence: jbase_out_legacy/java/util/concurrent/locks/StampedLock.java:470 (releaseWrite: `dup2_x1; putfield state:J; lstore_3` -> `long nextState = null;`), Spliterators$IteratorSpliterator.trySplit pc 35, FdLibm$RemPio2 pc 463 `dup2_x2` -> underflow at dastore; 6/16 underflow files.
Root cause: Converter.java:2565 `case 0x5D: case 0x5E: break;` (no-ops); :2552 dup2 pops two Expressions even when the top is one category-2 expression; :2534 dup_x2 form 1 only. The modeled stack holds one Expression per value regardless of category.
Approach: implement all JVMS 6.5 forms keyed on expr.getType() category (LONG/DOUBLE = 2); reuse the BUG-2026-0087 alias handling so duplicates become variable references. Tests: `long l = this.f = expr;`, `a[i] = b[j] = d;`, `(nanos = deadline - now()) <= 0L`.
Effort LOW.

### BUG-2026-0106 - LVT-driven pre-declarations duplicated by promoteUndeclaredAssignments
Severity HIGH (recompile failure; extremely broad). NEW (report-coverage-assurance.md mentions "-g surfaces a few LVTT-handling bugs, e.g. C_ControlFlow, tracked separately" - nothing was tracked).
Evidence: matrix -g 10/11 failures; mx_g/dec/C_ControlFlow.java:19-21 `int sum; int sum = 0;`. java.base 1,008/3,376 outputs (29.9%), 5,410 sites.
Root cause: decompileMethodBody builds preDeclarations for LVT-named slots stored in >= 2 blocks (Converter.java:1352-1400, add at :1394) and marks them declaredVars, so the decoder emits `sum = 0` as an assignment; promoteUndeclaredAssignments(result, paramNames077) (:1601, impl :4133) knows only params + AST declarations, sees `sum` as undeclared and promotes it; preDeclarations are prepended afterwards (:1645); mergeDeclarationsWithAssignments (:4326) cannot merge two declarations.
Approach: seed promoteUndeclaredAssignments' declared set with the pre-declared names (or prepend before promoting); merge then yields `int sum = 0;`. Add the -g matrix mode to the gate.
Effort LOW (3 lines).

### BUG-2026-0107 - pre-declaration collides with pattern bindings / for-each / catch variables
Severity HIGH. NEW (sibling of 0106).
Evidence: mx_g/dec/C_RecordPattern.java:44-46 `C_RecordPattern.Point a;` then `case Line(Point a, Point b)`; probe2/g S_Sync.slotReuse `Integer y;` then `for (Integer y : ...)`.
Root cause: the slot/block-based pre-declaration scan (:1352-1400); later transforms turn the slot's stores into a binding (TypeSwitchRecordFolder, RecordDeconstructionFolder) or a ForEachStatement variable, leaving the hoisted declaration dead and illegal.
Approach: after the transform chain drop pre-declarations whose name is bound by a pattern/for-each/catch and not read at top level before it; longer term replace eager pre-declaration with the demand-driven hoisting of BranchVarHoister/SwitchVarHoister. Effort LOW-MEDIUM.

### BUG-2026-0108 - typeSwitch labels stored as CONSTANT_Dynamic render as `case  _`
Severity HIGH (Java 21 GA feature). NEW.
Evidence: probe/dec/P_RecordPatterns.java:236 `case  _ -> "mon"` for `case java.time.DayOfWeek.MONDAY` (javap: bootstrap arg `Dynamic invoke:Ljava/lang/Enum$EnumDesc;` via ConstantBootstraps.invoke + EnumDesc.of); probe/dec/P_Preview.java:121 `case  _ -> "t"; case  _ -> "f"` (Dynamic getStaticFinal Boolean.TRUE/FALSE); primSwitch/longSwitch/doubleSwitch labels `0`, `0l`, `1.0d`, `Dynamic primitiveClass I/J/D` -> residual raw switch.
Root cause: Converter.java:3111-3131 accepts only CONSTANT_Class/String/Integer bootstrap args; other tags -> pool.getUtf8 (null for tag 17) -> "/* case N */"; arm builders in StructuredFlowBuilder (:2052-2160) synthesise `_` for unparsable labels.
Approach: CondyLabelResolver in the indy decoder: tag 17 -> read its bootstrap: ConstantBootstraps.invoke with EnumDesc.of -> `Enum.CONSTANT` (+import); getStaticFinal Boolean.TRUE/FALSE -> true/false; primitiveClass -> primitive type pattern (`int i`); tags 5/4/6 -> 0L/1.0f/1.0 literals; keep the String label list format.
Effort MEDIUM.

### BUG-2026-0109 - pattern-switch shapes still falling back to raw typeSwitch (+ lost returns)
Severity HIGH (truncation; pat() has no return at all). CONFIRMS/REFINES ROADMAP 2 "J21SwitchPattern statement-form fallback", BUG-2026-0067 residual.
Evidence: probe/dec/P_Unnamed.java pat() (`case Point(int x, _)`, `case Box(_)`, `case String _`) and multi() (`case Integer _, Long _`) -> `while (true) { switch (SwitchBootstraps.typeSwitch(var1, var2)) { case 0: ... break; break; ... } }` with every arm value gone; identical without -g. java.base: 27 of 31 classes with typeSwitch sites keep raw output (java/io/FilePermission.java:168).
Root cause: StructuredFlowBuilder.tryBuildSwitchExpression (:1662-1893) matches only arms merging into a common *return/store; statement-form switches (arms break to a merge), unnamed record components (dead temporaries `int var6 = var5;`), and multi-label type arms (`case 0: case 1:` sharing an instanceof chain) are not matched; the selective JD route (methodHasTypeSwitch :1916) covers only typeSwitch+MatchException methods where the fold succeeds.
Approach: transform/PatternSwitchStatementReconstructor on the statement form: recognise the `while(true){switch(typeSwitch(sel, idx)){...}}` scaffold, map case index -> label (patternSwitchLabels), fold `Type b = (Type) sel;` heads (RecordDeconstructionFolder.foldArm), treat `idx = N; continue` as guard failure (when), merge `case i: case j:` bodies into `case A _, B _`, preserve arm bodies verbatim.
Effort HIGH. Where the 87% real-world failure lives.

### BUG-2026-0110 - anonymous classes: fields (+ initializers) dropped; generic supertype arguments lost; anon inside a lambda not inlined
Severity HIGH. NEW (0044/0097 covered methods/captures).
Evidence: probe/dec/P_Generics.java:118-124 `new Comparator() { public int compare(String a, String b) { this.cnt += 1; ... } }` - `int cnt = cap;` gone, `<String>` gone (javap P_Generics$1: `int cnt;`, Signature attribute present, putfield cnt in <init>); P_Lambdas.lambdaWithAnon -> `() -> new P_Lambdas._1(k)`.
Root cause: JavaSourceWriter.java:3290-3312 inlines anonResult.getMethods() only (loop :3302), skipping <init> (field initialisers); display name from anonymousClassDisplayNames (:97-110) uses the erased supertype name, ignoring inner.getSignature(); lambda bodies use the inline path (0102) which never reaches the NewExpression anon branch.
Approach: emit non-synthetic non-val$ fields with initialisers recovered from <init> (same extraction as writeCompilationUnit's instance-initialiser logic :330-600); header from SignatureParser.parseClassSuperType/parseClassInterfaceTypes(anonResult.getSignature()); lambda bodies via writeStatement (0102).
Effort MEDIUM.

### BUG-2026-0111 - try-with-resources combinations (multi-resource effectively-final; TWR + catch + finally; TWR body locals)
Severity HIGH. CONFIRMS/REFINES ROADMAP 2 "Multi-resource effectively-final TWR", BUG-2026-0068.
Evidence: probe/dec/P_Structured.java:30-56 (`try (r1; r2)`: var3 used before declared, nested try with `throw e`, post-try `Reader var3 = r2;`), :58-80 (`try (in; buf) {...} catch (IOException) {...} finally {...}`: buf undeclared, `buf = "io"`), P_ModernApis.java:24-40 (`if (ex == null) {...; return;} throw f;`).
Root cause: transform/ModernTwrReconstructor matches typed resources in the `R r = init; try {body} catch (Throwable t) { r.close(); throw t; } r.close();` shape only; LVT flattening (0103) renames the Throwable slot; TryCatchReconstructor.isHandlerProtectionEntry coalesces only identical (handlerPc, catchType).
Approach (after 0103): accept the effectively-final alias form (`Object v = r; ... if (v != null) v.close();`), recognise the nested resource ladder recursively from innermost outward, and re-attach the enclosing user catch/finally group as clauses of the same TryCatchStatement.
Effort MEDIUM-HIGH.

### BUG-2026-0112 - `boolean ok = a > b ? 1 : 0;`
Severity MEDIUM (recompile failure at -g). NEW. Evidence: probe/dec/P_Structured.java:214. Root cause: BooleanSimplifier handles ternaries for boolean methods (:172), assignment RHS (:220) and declarations (:277) only when the variable is already known boolean; here the LVT says Z but the initializer is int and storeLocal does not reconcile. Approach: in storeLocal / BooleanSimplifier declaration branch, when declared type is boolean and initializer is `c ? 1 : 0` / `c ? 0 : 1` / 0|1 -> `c` / `!c` / false|true. Effort LOW.

### BUG-2026-0113 - erased casts rendered instead of the generic target; erased-to-type-variable assignments without cast
Severity MEDIUM. REFINES BUG-2026-0069 / LIM-0002.
Evidence: probe/dec/P_Generics.java:22 `return (Number) k.cast(...)` for `<R extends Number> R first(Class<R>)`; :36-37 `best = e.getKey();`, `bv = (Comparable) e.getValue();` for K/V locals typed from LVTT; :49 `T[] out = (Object[]) Array.newInstance(...)`.
Root cause: checkcast decode (Converter.java:3388) always builds CastExpression(new ObjectType(className)); addGenericReturnCasts (:1147) only wraps when no cast exists; no substitution of the declared LVTT/Signature type when the checkcast target is its erasure; no unchecked-cast insertion for Object-typed RHS into type-variable locals.
Approach: (a) if the target's generic type G erases to the checkcast class, render `(G)`; (b) if RHS is erased Object and target is a type variable/parameterized type, insert `(G)`; (c) same for Array.newInstance. Effort LOW-MEDIUM. The Optional<CAP#1> case in P_Lambdas.maxOf is permanent.

### BUG-2026-0114 - LambdaMetafactory.altMetafactory (serializable / intersection lambdas) rendered as chained casts
Severity MEDIUM (recompile failure: Serializable is not a functional interface). NEW.
Evidence: probe/dec/P_Generics.java:181 `(Runnable) (Serializable) () -> { return; }`; java.base ChronoLocalDate.java:260, ChronoZonedDateTime.java:141, TreeMap.java:3383 `(Comparator) (Serializable) (a, b) -> ...` (8 classes use altMetafactory).
Root cause: Converter.java:3150-3260 treats altMetafactory like metafactory; flags/marker interfaces in bootstrapArguments[3..] ignored.
Approach: parse flags (FLAG_SERIALIZABLE=1, FLAG_MARKERS=2, FLAG_BRIDGES=4), collect markers, emit one intersection cast `(Iface & Marker) lambda`. Effort LOW-MEDIUM.

### BUG-2026-0115 - switch-expression / ternary values consumed by aastore or a call with stack-carried operands -> STACK_UNDERFLOW
Severity MEDIUM (diagnosed, not silent; 8/16 java.base marker files). NEW (README lists only Panama classes).
Evidence: jbase_out_legacy/jdk/internal/classfile/impl/StackMapDecoder.java:75-77 (initFrameLocals; javap pc 260-315: each arm `getstatic ...; goto 315`, `315: aastore`), AnnotationReader 910/397, ClassPrinterImpl 320-324/1292-1293, VMSupport 481-484, ObjectStreamClass 88, keytool/Main 203, ParserVerifier 186, ConstantPoolBuilder 164.
Root cause: the merge block's stack must be seeded from the arm's exit stack plus the pre-switch stack (array, index); predecessor exit-stack inheritance (BUG-2026-0051) picks one predecessor; tryBuildSwitchExpression requires a *return/store merge.
Approach: generalise the switch-expression producer to any merge whose single consumer pops the arm value (aastore, putfield, invoke arg, athrow, areturn): build the SwitchExpression and substitute into the consumer (in-place substitution machinery of BUG-2026-0066); seed the merge decode with the switch block's exit stack minus the selector. Effort MEDIUM-HIGH.

### BUG-2026-0116 - import of the outer class lost for nested generic type arguments from LVTT (Map.Entry)
Severity LOW-MEDIUM. NEW. Evidence: mx_g/dec/C_VarInference.java:68 `ArrayList<Map.Entry<String, Integer>> entries` without `import java.util.Map`. Root cause: JavaSourceWriter.collectStatementImports (:1633-1680) imports vds.getType() (erased) but never runs collectSignatureImports (:1774) on vds.getGenericSignature(), which is what gets printed (~:2206). Approach: call collectSignatureImports for declaration generic signatures (and LambdaExpression.getInterfaceGenericSignature, for-each element signatures). Effort LOW.

### BUG-2026-0117 - ldc/ldc_w of CONSTANT_Dynamic emits the string literal "/* constant:N */"
Severity MEDIUM (silent wrong value; forward-looking: javac emits no condy in ldc position today, 0 in java.base; other compilers/generators do). NEW. Root cause: Converter.java:4437-4447 default branch. Approach: build a pseudo static call on the bootstrap owner/name with rendered static args and record CONDY_UNSUPPORTED; never emit a string literal for a non-string constant. Effort LOW.

### BUG-2026-0118 - class-file major > 69 (Java 26+) hard-rejected; no output for any such class
Severity HIGH. NEW.
Evidence: ver/V70.class -> "Unsupported class file version: 70.0 (max supported: 69.0 / Java 25)"; --batch counts an error. Java 26 files are format-identical (no new CP tags/opcodes needed by the parser; the exact Java 26 feature list could not be verified from this machine).
Root cause: ClassFileDeserializer.java:55-62 throws when major > StringConstants.MAX_SUPPORTED_MAJOR_VERSION (util/StringConstants.java:84 = 69); DenzoDecompiler.getMaxSupportedJavaVersion() = 25.
Approach: extend the table through 71; for major > MAX do NOT throw - parse best-effort and put a `// WARNING: class file version N (Java M) newer than supported` header into the unit (JavaSyntaxResult carries major/minor); hard error only for bad magic / major < 45; surface minor 0xFFFF as `// compiled with --enable-preview` (IMP-2026-0073). Effort LOW.

### BUG-2026-0119 - unknown invokedynamic bootstraps decoded as lambdas/method references
Severity MEDIUM (silent misrender; code-reading finding). NEW. Root cause: Converter.java:3150-3260: any bootstrap with >= 3 args and a MethodHandle at args[1] is treated as a lambda/method-ref; bsmOwner == LambdaMetafactory (:3231) only gates SAM inference. Approach: gate the lambda branch on owner LambdaMetafactory; otherwise emit `<Owner>.<bsmName>(args...)` + INDY_UNSUPPORTED diagnostic. Effort LOW.

### BUG-2026-0120 - StructuredFlowBuilder recursion cap silently truncates
Severity MEDIUM (silent, rare). NEW. Root cause: StructuredFlowBuilder.java:247-259 returns without emitting when recursionDepth > DecompilerLimits.MAX_RECURSION_DEPTH (200); no diagnostic. Approach: throw a FlowDepthExceededException caught in decompileMethodBody -> STRUCTURED_FLOW_FAILED + linear fallback (:1660-1668), or make buildFromBlock0 iterative (CONFIRMS OPT-0006). Effort LOW for the diagnostic.

### IMP-2026-0070 - DenzoDecompiler is not thread-safe (shared JavaSourceWriter with per-call state)
Severity MEDIUM (API contract; internal callers create one instance per class). NEW. Evidence: DenzoDecompiler.java:31 `private final JavaSourceWriter writer`; JavaSourceWriter.java:33-54,272 mutable per-call fields (currentResult, anonymousClassDisplayNames, inlinedStaticFieldNames, capturedArgSubstitutions, unitTypeIndex). CLAUDE.md calls the orchestrator thread-safe. Approach: new writer per decompile() like the converter. Effort LOW.

### IMP-2026-0071 - test-gate blind spots: missing fixtures, single debug mode, recompile-only oracle
Severity HIGH (process). CONFIRMS/REFINES ROADMAP 3 "Automated semantic-diff harness".
Evidence: release notes cite C_FlexibleCtor, C_ImplicitMain and 57/57, but the repo matrix has 48 files / 55 classes and neither fixture (only StructuredFlowBuilder comments and docs mention them); README recipe compiles with default debug only; recompilation cannot see deleted handlers/locks/lambda statements.
Approach: add the two fixtures; run the matrix in 3 modes (-g, default, -g:none) from DecompilerTest; add a structural oracle (per class count catch/finally/synchronized/try (/->/switch in source vs output, fail on decrease; grep-gate /* inline stmt */, __MONITOR, SwitchBootstraps.typeSwitch == 0); copy SCRATCH/probe/src/*.java into the matrix as C_J25_*; promote the runtime original-vs-recompiled comparison to a standing stage. Effort MEDIUM.

### IMP-2026-0072 - dual pipeline decision (section 5): freeze cfg/jd as a narrowly scoped fallback, port the record-switch fold to legacy, then delete.

### IMP-2026-0073 - preview/version provenance in the output header (pairs with 0118). Effort LOW.

### IMP-2026-0074 - single TransformPipeline for both flow paths
Evidence: Converter.java JD path ~1440-1480 vs legacy ~1560-1650: JD lacks ModernTwrReconstructor, promoteUndeclaredAssignments, ForEachDetector.backPropagateSignatures, SwitchVarHoister, BranchVarHoister; legacy lacks TypeSwitchRecordFolder. The JD path is selectively active for every typeSwitch+MatchException method even with the flag off (:1420-1428), so those methods silently get the shorter chain. Approach: transform/TransformPipeline.run(List<Statement>, PipelineContext) with one ordered list; both call sites use it. Effort LOW-MEDIUM.

### OPT-0007 - bytecode disassembly computed for every method regardless of --show-bytecode
Evidence: Converter.java:760 unconditional `md.bytecodeInstructions = BytecodeDisassembler.disassemble(...)`; the writer reads it only when showBytecode (JavaSourceWriter.java:2513); JFR inclusive 116/955 = 12.1%. Approach: pass the configuration map into the converter (message header "configuration") and disassemble only on request, or let the writer call it lazily. Expected ~10-12% throughput. Effort LOW.

### OPT-0008 - ObjectType(String) recomputes qualified/simple names per construction
Evidence: model/javasyntax/type/ObjectType.java:15-18 -> TypeNameUtil.internalToQualified (String.replace) + simpleNameFromInternal; JFR StringLatin1.replace 20 + ObjectType.<init> 20 samples (~4%); constructed on every checkcast/new/getfield/invoke decode. Approach: per-converter HashMap<String,ObjectType> cache (thread-confined) or static ConcurrentHashMap. Expected 2-4%. Effort LOW.

### OPT-0009 - back-edge search is a repeated DFS per conditional block
Evidence: StructuredFlowBuilder.hasBackEdgeTo/hasDirectBackEdge (:1587-1660) called from the whileTrueHeaders pre-scan (:129-150) and matchConditionalPattern; each call O(blocks) with a fresh HashSet; JFR inclusive 8.3%; O(B^2) per method. Approach: compute loop headers and per-header body BitSets once per CFG (ControlFlowGraph.build already knows branchTargetPc <= startPc); answer from the precomputed sets. Expected 5-8%, removes a quadratic term. Effort MEDIUM.

### OPT-0010 - redundant full bytecode scans per method (CONFIRMS OPT-0001 with anchors)
ControlFlowGraph.build (leaders), the pre-declaration store scan (Converter.java:1352-1380 re-reading every block with skipOpcodeOperands), methodHasTypeSwitch (:1916, a third linear scan), BytecodeDisassembler.disassemble (:760), plus the decode. Approach: one InstructionIndex (pc -> opcode/operands/length, store-slot list, indy sites) built once and shared. Expected 5-10% with OPT-0007. Effort MEDIUM.

### OPT-0011 - small allocation/boxing churn (LOW)
TryCatchReconstructor.java:120-130 sorts group keys by Integer.parseInt(a.split("-")[0]) inside the comparator; localVarNames HashMap<Integer,String> lookups on every load/store (HashMap.getNode 43 samples) -> slot-indexed arrays (maxLocals known); collectImports re-walks the whole AST (~4%) -> collect during writing into a deferred buffer or during conversion.

### OPT-0012 - converter seams (CONFIRMS ROADMAP 3 with cut lines)
ClassFileToJavaSyntaxConverter.java is 5,041 lines; decodeOpcode spans :2270-3538. Extract: (1) LocalVariableResolver (:1210-1400, :3539-3740, slot-split state :1707-1723) hosting 0103/0106/0107; (2) InvokeDynamicDecoder (:2967-3260 + SAM inference :3897-4130) hosting 0108/0114/0119; (3) transform/SynchronizedReconstructor (:4776-5041) hosting 0101; (4) transform/AssertReconstructor (:4562-4775); (5) TransformPipeline (IMP-2026-0074). Writer (4,391 lines): LambdaBodyWriter/AnonymousClassWriter (:3195-3400, :3781-3914) hosting 0102/0110.

### LIM-0009 - real-world pattern-switch coverage: track the 27/31 java.base residual as a measurable limitation until 0109/0115 close; add the ratio to the release gate.

### REQ-2026-0001 - --semantic-check CLI (CONFIRMS ROADMAP 4): decompile -> recompile -> compare structural census (catch/finally/synchronized/lambda statements) and exception-table sizes between original and recompiled class files using the project's own deserializer; cheap, catches the 0100/0101/0102 classes. Effort MEDIUM.

## 4. PERMANENT / impossible (do not track as bugs)
- Generic types of locals/expressions without LVTT (LIM-0002): P_Lambdas.maxOf -> Optional<CAP#1>; the (T) cast is not in the bytecode. 0113 can add unchecked casts but cannot recover type arguments.
- @Override (LIM-0003). Text block vs string (README). `var` vs explicit type; unnamed `_` vs named-but-unused (no distinguishing LVT entry; not re-verified) - any name compiles.
- JEP 512 compact source files / instance main: normal `final class Name`; only the explicit form can be emitted. JEP 511 import module: source-level only.
- Cross-module sealed permits (README). Which duplicated finally copy is "the" finally when copies differ after optimisation (rare).
- NOT permanent: lambda parameter names without LVT (pN), local class placement (EnclosingMethod exists).

## 5. Dual pipeline - firm recommendation: retire; keep only the record-switch route as a frozen fallback until ported
| Corpus | legacy | JD |
|---|---|---|
| matrix -g:source,lines | 55/55 | 36/55 (C_ControlFlow alone 16 errors) |
| matrix -g | 44/55 | 36/55 |
| java.base underflow files | 16 | 16 (shared decoder) |
| java.base lost/leaked monitor files | 153 | 146 |
| java.base raw typeSwitch files | 27 | 30 |
| identical outputs | - | 1,402 / 3,376 |
Structural reasons: JdFlowBuilder.emit renders every TYPE_LOOP as `while (true)` ("Minimal loop emission... extend in future"), so for/while(cond)/do-while/for-each depend on transforms written for legacy shapes; the JD path skips five transforms (IMP-2026-0074). Parity means re-implementing loop-condition placement, switch break/continue semantics (BUG-2026-0085/0086) and TWR on a second engine (~3,842 lines in cfg/jd plus runJdPipeline :935-1064). The one capability legacy lacks (switch-form record-pattern fold, BUG-2026-0079) is a local CFG transform (ReturnMergeTailDuplicator + TypeSwitchRecordFolder) applicable to the legacy ControlFlowGraph before StructuredFlowBuilder runs (duplicate the shared return block per predecessor when the switch is a typeSwitch); after that, delete cfg/jd and the flag. Until then keep the selective route as is; do not invest in JD parity.

## 6. Java 25 and beyond - readiness matrix
| Feature | Shape | Status | Item |
|---|---|---|---|
| Records, compact ctors, generic records | Record attr, ObjectMethods | OK | - |
| Sealed/permits/non-sealed | PermittedSubclasses | OK | - |
| Record patterns, instanceof patterns, guards, case null | typeSwitch + MatchException scaffold | OK in expression form on fixtures; 87% residual on java.base; statement form / unnamed components / multi-label arms fail | 0109 |
| Qualified enum constants in pattern switch (Java 21 GA) | CONSTANT_Dynamic EnumDesc labels | broken | 0108 |
| Unnamed `_` | no LVT entry; dead temporaries | for-each/catch/lambda OK without LVT; record `_` components fail | 0103, 0109 |
| Flexible constructor bodies (JEP 513) | statements before invokespecial <init> | OK (P_FlexibleCtor passes) | - |
| Compact source files (JEP 512), module imports (JEP 511) | ordinary class files | OK by construction | permanent (form) |
| Virtual threads, ScopedValue, sequenced collections, gatherers | ordinary calls | OK except TWR-with-locals | 0111/0103 |
| Switch expressions (yield blocks, string/enum/long/char) | switch + merge | OK on fixtures; value -> aastore/call-arg merges fail | 0115 |
| --enable-preview (minor 0xFFFF) | accepted | OK, provenance missing | IMP-0073 |
| JEP 507 primitive/boolean patterns (preview) | typeSwitch with Dynamic primitiveClass / Boolean.TRUE / long-float-double constants | broken (`case  _`, residual) | 0108 |
| Stable values / structured concurrency (preview APIs) | ordinary calls | OK | - |
| Class-file major 70 (Java 26) / 71 | identical format | hard-rejected | 0118 |
| Unknown attributes (ModuleHashes, ModuleTarget, future LoadableDescriptors) | skipped | OK (silent) | - |
| Unknown CP tags | throw | correct, surfaced per class | - |
| Valhalla (JEP 401-family): ACC_IDENTITY reuses 0x0020, ACC_STRICT_INIT 0x0800 on fields, LoadableDescriptors | parser tolerant; writer prints `class` (no `value`) | acceptable degradation; writer branch needed when finalised | future LIM |
| ldc CONSTANT_Dynamic | string placeholder | silent wrong value | 0117 |
| Non-javac / future bootstraps | decoded as lambdas | silent misrender | 0119 |
| Type annotations on type arguments | type_path discarded (AttributeParser.java:456-460) | known ROADMAP 2 | - |

## 7. Structural observations
- StructuredFlowBuilder correctness is spread over six interacting stacks (switchMergeStack, whileTrueExitStack, loopContinueTargets, outerLoopMergePoints, labeledBreakLabels, doWhileHeaders/whileTrueHeaders) plus a `visited` set used as both claim tracker and emission guard. The BUG-2026-0086-2 "never drop both successors" fallback (:690-745) is the right policy; extend it to the two remaining silent returns: the depth cap (0120) and the backward-goto `return;` (~:640).
- TryCatchReconstructor (2,142 lines) is the only transform reasoning in source lines; 0100 makes it PC-based and removes the computeAfterTryStartLine heuristics (:1308-1420) and the nested-candidate retry (:135-148).
- The writer mixes three emission modes (line-aligned statements, single-line inline for lambdas/for-headers, expression-embedded class bodies); 0102 collapses the second into the first for lambdas.
- Diagnostics vocabulary should gain transform-level markers (SYNC_UNRECONSTRUCTED, LAMBDA_BODY_UNSUPPORTED, TRY_UNANCHORED, INDY_UNSUPPORTED, CONDY_UNSUPPORTED, FLOW_DEPTH_EXCEEDED) so "marker-clean %" becomes truthful; today all three CRITICAL silent classes are invisible to it.

## 8. Reproduction
SCRATCH/matrix.sh g|nog|none [-Ddenzo.jd.pipeline=true]; SCRATCH/probe/src/*.java, probe/preview/P_Preview.java; SCRATCH/probe2/S_Sync.java (g/, nog/, none/); java -cp target/classes it.denzosoft.javadecompiler.Main --batch SCRATCH/jbase/java.base <out>; java -cp target/classes:SCRATCH/harness/cls Latency|ParLatency|Scan SCRATCH/jbase/java.base; jfr print --events jdk.ExecutionSample SCRATCH/rec.jfr.
