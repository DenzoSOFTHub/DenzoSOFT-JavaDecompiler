# Report: Java 25 and beyond — decompilation analysis (v1.13.0 tree)

Evidence-based analysis performed on 2026-09-07 against the v1.13.0 working tree (`target/classes`
current, commit e032be5 plus the uncommitted v1.13.0 close-out edits). It measured JDK 25 `java.base`
with both flow pipelines, the construct matrix in three debug modes with both pipelines, a **full
recompilation of the decompiled `java.base`** as a patched module, a bytecode-vs-output construct
census, twelve purpose-built Java 21-25 probe fixtures, eight pathological generated classes, and
single-thread in-process throughput. Nothing under `src/` was modified; one transform was compiled
into the scratchpad with trace prints to explain a classification decision.

Paths starting with SCRATCH refer to the throwaway workspace
`/tmp/claude-1000/-media-sf-Cloud-Lavoro-Docker-ClaudeCode-workspace-DenzoSOFT-JavaDecompiler/dad871ac-911a-46d8-8464-fc1b2d31c9c3/scratchpad`
and are not in the repository. Section 9 lists what is there and how to regenerate it.

Item IDs proposed below (`BUG-2026-0129` onward) are **proposals**: BUG-2026-0125..0128 were
allocated during this session by the v1.13.0 close-out, so re-check `docs/tracking/track-bugs.md`
before filing.

---

## Addendum — 2026-09-07, later the same day

The report's findings were acted on the same day. What follows is what LATER MEASUREMENT changed. The
body below is left as written, so the two can be compared; where a claim did not survive, the section
carries a pointer to here.

**Items are filed.** Q1-Q5 are `BUG-2026-0129` … `BUG-2026-0133`; §3 (retire the JD pipeline) is
tracked as **IMP-2026-0072**, which already existed as the open "dual pipeline decision" — no new ID
was created for it. The §3 retirement was **approved by the project owner on 2026-09-07**.

**§3 — two claims did not survive measurement.** A freeze fixture with the four record-switch shapes
the section names was compiled `-g` with JDK 25, decompiled with the v1.13.0 build and recompiled:

| Shape | Recompiles |
|---|---|
| `return switch` + flat deconstruction + `default` | yes |
| `return switch` + unnamed component (`Point _`) | yes |
| `return switch` + sealed exhaustive, no `default` | **NO** — hoisted `Point a;` collides with the arm bindings |
| `return switch` + nested deconstruction | **NO** — emits `while (true) { switch … }` with no `return` at all |

1. The section lists sealed-exhaustive and nested among the shapes the selective route "gets right".
   Both are already broken, so they cannot regress — the freeze to protect is **two** shapes, not four.
2. The section states `C_RecordPattern`/`C_Sealed` in the matrix depend on the route. **`C_Sealed.java`
   contains zero `switch` statements** and cannot depend on a record-switch fold. `C_RecordPattern`
   does — and its known `-g` matrix failure is exactly the hoisted-binding collision above
   (`C_RecordPattern.java:49: variable a is already defined`), so one of the two remaining `-g`
   failures in the 53/55 baseline is this defect rather than a separate one.

**§3 step 1 (freeze) is done and runtime-verified.** Fixtures at `src/test/resources/jd-freeze/`.
The two working shapes were run before and after a decompile→recompile round trip and produced
identical output on every branch (345 / -1 / 42 / -2). Steps 2-4 remain; step 2 is BUG-2026-0128.

**Q1 reproducers: pick the fixture carefully.** The obvious `try { if (n>0) return n*2; return -1; }
finally { unlock(); }` does reproduce the doubled `unlock()`, but its baseline output does not compile
at all for unrelated reasons — the try is split in two and `var1` escapes its scope. The `break`
variant is worse: the whole `while` loop is emitted AFTER the try/finally that returns, unreachable,
with a wrong return value. Both defeat the A/B runtime oracle before it can see Q1. Since §2 records
that 30 of the 82 affected `java.base` files recompile clean, usable shapes exist — but they must be
chosen, not assumed. Whether the loop displacement is distinct from Q3/Q4/BUG-2026-0121 is unresolved.

**The verification standard in §7 is now a project rule.** CLAUDE.md's "Verifying a decompiler fix"
was amended: the oracle is the ORIGINAL SOURCE, not the compiler. "It recompiles" is a syntax check
and is no longer accepted as a verification on its own; where a fixture can be executed the standing
gate is `original → run → A` vs `decompile → recompile → run → B`, with A == B, and fixtures are to
carry a `main` printing observable state. Where running is impossible the strongest proxy is used and
must be NAMED in the result. This generalises the last row of the §7 table into the default.

---

## 0. Executive summary

The headline gates are green and mostly meaningless for correctness: `java.base` decompiles with
0 errors, the construct matrix is 55/53/55, and every diagnostic marker CLAUDE.md names is at or near
zero. Underneath, **25.1% of the decompiled `java.base` (846 of 3,376 files, 13,685 javac errors)
does not recompile**, and a bytecode-vs-output census plus manual reads found five classes of
**silent, compile-clean miscompilation** that no existing marker reports:

| # | Silent defect class (compiles, means something else) | `java.base` scale (measured) |
|---|---|---|
| 1 | Inlined `finally` copy survives before a nested `return`/`break` -> action executes twice (`lock.unlock()` twice = `IllegalMonitorStateException`) | 498 sites / 82 files; 42 files are `unlock()`; 30 files recompile clean |
| 2 | try-with-resources flattened to open/use/close with **no try at all** (resource never closed on exception) | 0/9 shapes survive when the TWR is on one source line (`-g`, default); 7/9 flattened at `-g:none` regardless of layout; runtime-proven (`closed=1` -> `closed=0`) |
| 3 | Loop synthesised by the flow builder pushed **out of** its `try` (exceptions in the loop no longer caught; method tail after the try lost) | 59 sites / 41 files (strict scanner); `java/io/Console.instantiateConsole` loses `c = new ProxyingConsole(jc)` and the whole tail |
| 4 | `break`/`continue` out of a `synchronized` block inside a loop deletes the block body | `java/util/concurrent/Phaser.doRegister` (6 statements gone), `java/util/TimerThread.mainLoop`; reproduces in all three debug modes |
| 5 | `synchronized` dropped when the monitor temp is consumed as an `instanceof` binding | 16 classes with `synchronized (` != `monitorenter` (`java/lang/String.contentEquals`, `javax/crypto/Cipher`, `LoginContext`); 3/3 lost in the probe, all modes |

None of these leaves a `__MONITORENTER__`, `/* inline stmt */`, `STACK_UNDERFLOW` or any other
marker. The marker table CLAUDE.md step 6 prescribes is necessary and was checked (section 1.2); it is
not sufficient, and this report adds the census gates that would have caught all five (section 7).

The **JD pipeline should be retired**: with the flag on it deletes essentially every loop in
`java.base` (`while (`: 19 vs 4,707; `for (`: 8 vs 2,819) and scores 36/55 on the matrix in all three
debug modes. Its one live contribution — the `return switch` record-deconstruction fold that the
selective route provides — must be ported to the legacy path first, and that port is the same work
as fixing record-pattern switches in every other position (section 2, Q6), which are broken today.

Measurement table (all numbers this session, `java.base` = 3,376 top-level classes, javac `-g`):

| Gate | Legacy (default) | JD (`-Ddenzo.jd.pipeline=true`) |
|---|---|---|
| Batch errors | 0 | 0 |
| `__MONITORENTER__` files / `__MONITOREXIT__` files (sites) | 0 / 12 (14) | 0 / 2 (2) |
| `/* inline stmt */` | 0 | 0 |
| `STACK_UNDERFLOW` files (sites) | 7 (19) | 7 (19) |
| `DECODE_ERROR pc=` / `CFG_BUILD_FAILED` / `STRUCTURED_FLOW_FAILED` / `JD_PIPELINE_FAILED` | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `PATTERN_SWITCH_NOT_RECONSTRUCTED` files (sites) | 5 (9) | 7 (11) |
| raw `SwitchBootstraps.typeSwitch` files (sites) | 5 (18) | 7 (22) |
| `$exception` leaked into source, files (sites) | 46 (85) | 258 (1,165) |
| `boolean x = c ? 1 : 0;` sites | 269 | - |
| `catch (` / `} finally {` / `synchronized (` / `try (` emitted | 3,742 / 1,753 / 933 / 22 | 3,378 / 1,166 / 890 / 2 |
| bytecode: real catch handlers / `monitorenter` | 3,698 / 951 | same |
| `while (` / `for (` / `do {` emitted | 4,707 / 2,819 / 711 | **19 / 8 / 10** |
| files differing from legacy | - | 1,944 |
| Recompile of the decompiled module: files with errors / errors | **846 / 13,685** | not run (loops are gone) |
| Construct matrix default / `-g` / `-g:none` (of 55) | 55 / 53 / 55 | 36 / 36 / 36 |
| Single-thread in-process throughput (warm) | 1,460-1,640 classes/s; p50 0.18 ms, p99 6.3-7.4 ms, max 20 ms | - |

---

## 1. Method

### 1.1 What was run

- `java.base` extracted with `jmod extract` (7,390 class files, 3,376 top-level, all major 69, LVT
  present: this IS a `-g` corpus). Batch-decompiled twice (legacy, JD) into SCRATCH/jbase/{legacy,jd}.
- **Recompilation of the decompiled module**: `javac --patch-module java.base=SCRATCH/jbase/legacy
  -Xmaxerrs 200000 -proc:none @files` (14 s, 1 GB). Diagnostics land on stderr (SCRATCH/jbase/re_legacy.time).
- **Bytecode census** (SCRATCH/harness/Census.java, built on the project's own `ClassFileDeserializer`):
  per top-level class, distinct typed exception handlers (excluding the two javac-synthetic kinds:
  `NoSuchFieldError` handlers of `$SwitchMap$` initialisers and `Throwable` handlers whose body reaches
  `addSuppressed`, i.e. try-with-resources), `any` handlers, `monitorenter` count, indy kinds, and the
  set of method names invoked from non-synthetic, non-bridge methods. Joined with counts from the
  output (SCRATCH/join.py, comments/strings/char literals stripped — the first version of this scanner
  was wrong for files containing `'"'` and reported `javax/crypto/Cipher` at 0 catches; fixed, per
  CLAUDE.md step 7).
- **Shape scanners** over the output, each printing its sites so they could be read:
  surviving-finally-copy (SCRATCH/dupfinally2.py), loop-displaced-after-try (SCRATCH/loopaftertry.py),
  same-block duplicate declaration (SCRATCH/dupdecl.py).
- Construct matrix, three modes x two pipelines (SCRATCH/mx/matrix.sh, the CLAUDE.md loop).
- Probe fixtures (SCRATCH/probe/src, SCRATCH/j25/src, SCRATCH/rt/src), each compiled in the mode(s)
  stated, decompiled with `--compact`, recompiled, and where possible **run** against the original.
- Pathological generated classes (SCRATCH/patho): 400-deep nested `if`, 2,000-arm else-if chain,
  3,000-case tableswitch + 1,500-case lookupswitch, 6,000-statement method, 600-operand concat,
  300-deep ternary, 30 nested labelled loops, 60 nested try/finally, 40 nested synchronized, 25 nested
  lambdas. Run with `-Xss1m`.
- Timing: `--batch` wall time (noisy VirtualBox guest, 8 vCPU), `taskset -c 0`, `-Xmx256m`, and an
  in-process single-thread harness (SCRATCH/harness/Timing.java) over the 3,376 top-level classes.
- One instrumented copy of `TryCatchReconstructor` (SCRATCH/instr) to trace `applyGroup`
  classification; run with `-cp SCRATCH/instr/cls:target/classes`, source tree untouched.

### 1.2 Marker table, read against the sites

Every marker count above was expanded to its sites and a sample read:
- The 59 textual `DECODE_ERROR` hits are all `Alert.DECODE_ERROR` in `sun/security/ssl`; zero real
  `DECODE_ERROR pc=` diagnostics.
- The 12 `__MONITOREXIT__` files are NOT cosmetic: `TimerThread.mainLoop`, `Phaser.doRegister`,
  `BufferedReader.readLine(boolean,boolean[])` are severely truncated around them (section 2, Q4).
- The 7 `STACK_UNDERFLOW` files are the BUG-2026-0115 family (switch/ternary value into `aastore` /
  call argument): `StackMapDecoder:315`, `AnnotationReader:910/397`, `ClassPrinterImpl:320-324,
  1292-1293`, `VMSupport:481-484`, `ObjectStreamClass:88`, `ParserVerifier:186`,
  `ConstantPoolBuilder:164`. Unchanged since the audit.
- `$exception` (46 files) marks a mis-shaped handler every time it appears
  (`java/nio/file/Files.mismatch`: `totalRead = $exception;` inside a mangled two-resource TWR;
  `BufferedReader.readLine`: `try { e = $exception; } finally { /* __MONITOREXIT__ */ }`). It is
  emitted by `ClassFileToJavaSyntaxConverter.decodeBasicBlock` (:2117) as the handler seed and should
  never reach the writer; it currently records no diagnostic.

### 1.3 Recompile error histogram (`java.base`, legacy)

| javac message (normalised) | count | maps to |
|---|---|---|
| cannot find symbol | 6,265 | out-of-scope locals from displaced statements, synthetic names, `$exception`, cascades |
| missing return statement | 885 | tail lost after try/loop (Q3, BUG-2026-0121), do-while + return |
| incomparable types: boolean and int / int cannot be converted to boolean / bad operand type int for `!` / boolean cannot be converted to int | 545 + 524 + 352 + 33 = **1,454** | Q8 (BooleanSimplifier gaps) |
| variable X might not have been initialized | 429 | hoisted declarations, displaced assignments |
| possible lossy conversion from int to byte/short/char | 296 + 36 + 35 = 367 | Q18 (int constant to narrow parameter) |
| unreported exception `<any>` | 257 | cascade |
| cannot assign a value to static final variable | 175 | Q9 (nested-class `<clinit>` not inlined) |
| unreachable statement / break outside switch or loop | 131 / 112 | lost loop structure (legacy too, not only JD) |
| no suitable constructor found for KnownOIDs(...) / String cannot be converted to TemporalUnit / ... | 103 + 34 + ... = 137+ | Q7 (enum constructor parameter types) |
| variable X is already defined | 100 | LVT slot sharing (BUG-2026-0103 family) |
| CoderResult <-> int | 111 | slot re-typing in `sun/nio/cs` coders |
| int cannot be converted to ChronoField / Set<Location> | 32 + 23 | Q10 (enum switch labels) |
| an enum annotation value must be an enum constant | 18 | Q19 |

Caveat: cascades inflate "cannot find symbol"; the rows below it are individually verified shapes.

---

## 2. Prioritised work queue

Ordered by (correctness impact x confidence) / effort. "Silent" means the output recompiles and
behaves differently. For every item the debug mode that reproduces it is stated; several reproduce in
exactly one.

### Q1. Inlined `finally` copies survive before nested exits (double execution) — CRITICAL, Bug, silent

Proposed `BUG-2026-0129`.

**Evidence.** javac inlines the finally body before every exit from the try. `TryCatchReconstructor`
removes the copy only at the top level of the try/catch body (trailing statements, or the statements
immediately before a top-level Return/Throw). A copy inside an `if` or loop nested in the try body is
kept, so it executes and then the real `finally` executes again:

```java
// java.base legacy output, java/util/concurrent/LinkedBlockingDeque.pollFirst  (bytecode: unlock at
// pc 38-40 is OUTSIDE the protected ranges [18,38) and [46,66) -- it is the inlined copy)
try {
    E x;
    while ((x = this.unlinkFirst()) == null) {
        if (nanos <= 0L) {
            Object var8 = null;
            lock.unlock();          // inlined finally copy, kept
            return var8;
        }
        nanos = this.notEmpty.awaitNanos(nanos);
    }
} finally {
    lock.unlock();                  // runs again -> IllegalMonitorStateException
}
```

Scanner (SCRATCH/dupfinally2.py — finally body of 1-4 simple statements, same consecutive lines inside
the try body immediately followed by `return`/`break`/`continue`/`throw`): **498 sites in 82 files**
(449 before `return`, 49 before `break`); 42 files are the `unlock()` family
(`SSLSessionImpl.getPacketBufferSize` has three, `SSLSocketOutputRecord` six, `EphemeralKeyManager`,
`TrustStoreManager`, `UnixSecureDirectoryStream`, ...); others are `Reference.reachabilityFence(this)`
(`DESKey`, `PBEKey`), `src.position(mark)` (`sun/nio/cs/*`), `this.updateState(xcert)`
(`RevocationChecker`), `UnixNativeDispatcher.close(fd, ...)`. **30 of the 82 files recompile clean**
(18 of them `unlock()` files: `ChunkedInputStream`, `HttpClient`, `KeepAliveCache`,
`AuthenticationInfo`, `ModuleReferences`, ...), so the defect ships silently. Reproduces at `-g`,
`-g:source,lines` and `-g:none`.

**Root cause.** `TryCatchReconstructor.removeDuplicatedFinally` (:1983-2041): Case 1 matches the
trailing N statements of the body list, Case 2 the N statements before a top-level terminator, Case 3
a plain-expression tail. None recurses into `IfStatement`/`IfElseStatement`/`WhileStatement`/
`DoWhileStatement`/`ForStatement`/`LabelStatement`/`SwitchStatement` bodies, where javac places the
copy before a nested `return`/`break`/`continue`. `stripLeadingFinallyDuplicate` (:2046) handles only
the normal-completion copy.

**Approach.** Make the dedup structural and recursive: walk every compound statement inside the try
body and each catch body; whenever the N statements immediately preceding a `Return`/`Throw`/`Break`/
`Continue` structurally match the finally body (`statementsMatchFinally` with local-name
normalisation, which already exists), delete them. Also handle the loop-tail position (copy as the
last statements of a loop body before an implicit continue). Do this only when a finally body was
actually reconstructed for the group. Do NOT extend the count-based Case 3 to nested bodies (that is
the shape BUG-2026-0056-8 had to tighten).

**Effort / risk.** LOW-MEDIUM. Risk: deleting a genuine user statement that happens to equal the
finally body immediately before a nested exit — unlikely and already accepted at top level.

**Guard test.** `DecompilerTest` fixture = `LinkedBlockingDeque.pollFirst` shape (generic `E`, loop,
early `return null` under `if`) at `-g`; assert the try body contains exactly one `lock.unlock()`
occurrence and it is in the `finally`. Verify the guard: on today's build the assertion fails (two
`unlock()`). Census gate for the release: SCRATCH/dupfinally2.py over `java.base` must report 0.

### Q2. try-with-resources silently flattened to open/use/close — CRITICAL, Bug, silent

Proposed `BUG-2026-0130` (supersedes the "multi-resource" framing of BUG-2026-0111; overlaps
BUG-2026-0126 and the residue of BUG-2026-0100).

**Evidence.** Probe SCRATCH/probe/src/P_Twr2.java (9 TWR shapes, each written on ONE line) and
P_Twr3.java (same shapes, try/body/close on separate lines):

| javac mode | P_Twr2 (one-line) `try (` reconstructed | P_Twr3 (multi-line) |
|---|---|---|
| `-g:source,lines` | **0 / 9** | 8 / 9 |
| `-g` | **0 / 9** | 8 / 9 |
| `-g:none` | 2 / 9 | 2 / 9 |

The bytecode of `P_Twr2.a_publicStatic_InputStream` is byte-identical in shape to the matrix's
`C_Java7TryResources.readFirst` (exception table `[9,14)->20 Throwable`, `[21,25)->28 Throwable`);
the only difference is the LineNumberTable. The flattened output is not a garbled try — it is **no
try**:

```java
static void h_void_StringReader(String s, StringBuilder sb) throws IOException {
    StringReader r = new StringReader(s);
    sb.append((char) r.read());
    r.close();                                   // not executed if read() throws
}
```

Runtime proof (SCRATCH/rt/R_Twr.java, `-g`): original prints `closed=1`, the recompiled decompilation
prints `closed=0`. `P_Twr.java` (10 shapes incl. TWR + catch, + finally, in a loop, nested, with body
locals): 0/10, 0/10, 1/10. `java.base` emits 22 `try (` in 18 files, while the census counts 1,235 javac-synthetic typed
handlers (4,933 typed - 3,698 real; this figure mixes TWR `Throwable` handlers with `$SwitchMap$`
`NoSuchFieldError` handlers, so it is an upper bound on TWR sites) — either way almost every TWR in the
JDK is flattened or rendered as the raw desugar.

**Root cause.** Two paths, both in `TryCatchReconstructor.applyGroup`:
1. With lines: statements are classified by `sLine in [tryStartLine, tryEndLine]` (:395). A one-line
   TWR puts the resource init, body, the inlined `close()` and the `return` all on the same line, so
   everything is "in the try", the group then looks like the TWR compiler pattern with a trivial
   body, and `isTryBodyTrivial(tryBody) && isTryWithResourcesPattern(...)` (:586) **returns null:
   "skip this exception group - emit original statements unchanged"**. The handler is deleted. The
   same branch fires at `-g:none` for the pc-classified groups whose body is only a declaration.
2. `ModernTwrReconstructor` is "tuned against the line-based shape" (BUG-2026-0122 notes) and does not
   match the pc-grouped shape, which is why `-g:none` stays at 2/9 even with correct grouping.

**Approach.**
- Never drop a group whose handler is the TWR desugar. Replace the `return null` at :586 with a
  reconstruction attempt; if the attempt fails, emit the desugared `try { } catch (Throwable t) {
  r.close(); throw t; }` (correct, compilable, ugly) and record a diagnostic `TWR_UNRECONSTRUCTED`.
- Classify TWR groups by bytecode pc even when lines exist (the pc map and `minPcOf` exist;
  BUG-2026-0125 already uses them). This is narrower than the wholesale switch that was tried and
  rejected in BUG-2026-0122/0126 (it lost a real handler in `SunEC$ProviderService`): restrict the pc
  criterion to groups whose handler type is `Throwable` and whose handler body reaches `addSuppressed`
  or `close()`.
- Make `ModernTwrReconstructor` match on structure (`R r = init; try { body } catch (Throwable t) {
  [if (r != null)] r.close(); throw t; } [if (r != null)] r.close();`) independently of which list the
  pieces landed in, and handle the body-`return` variant (the `return var` after the close is the
  common case; today it yields `unreachable statement` as in `P_Structured.both`).

**What not to try.** Enabling `usePc100` for all line-bearing groups (tried; -756 `catch (`, at least
one real handler lost). Keep BUG-2026-0126's advice: split the population first.

**Effort / risk.** MEDIUM-HIGH. Risk: `ModernTwrReconstructor` changes ripple into the `TwrT` test
and the 18 files that currently emit `try (`; run the three-mode matrix and diff `java.base` `try (`
counts per file.

**Guard test.** P_Twr3's nine shapes at `-g` AND P_Twr2's at `-g` (one-line) AND both at `-g:none`,
asserting `try (` per method and, for `R_Twr`, run-and-compare `closed=1`. All fail today.

### Q3. Flow-builder-synthesised compound statements (`line=0`) pushed out of the try region — HIGH, Bug, silent when it compiles

Proposed `BUG-2026-0131` (a precisely delimited slice of BUG-2026-0126; also the mechanism behind the
`Console` half of BUG-2026-0121's family).

**Evidence.** Probe SCRATCH/probe/src/P_TryTail.java at `-g` and `-g:source,lines`:

```java
// source                                    // output (-g)
try {                                        try {
    for (int i = 0; i < a.length; i++) {         int i = 0;
        if (a[i] < 0) { r = i; break; }      } catch (RuntimeException e) {
    }                                        }
} catch (RuntimeException e) { }             outer: while (i < a.length) { ... }   // outside
```

`consoleShape` (the `java/io/Console.instantiateConsole` shape) additionally loses
`c = new ProxyingConsole(jc)` and everything after the try (`if (tty() && c == null) ...; return c;`).
`java.base`: strict scanner SCRATCH/loopaftertry.py (try body <= 3 assignments, next statement a loop
over a variable assigned in that body) finds **59 sites in 41 files** (`Console:597`,
`ProcessBuilder:1227`, `Inet6Address:700`, `Charset:415`, `JarFile:722`, `JceSecurity:347`,
`SubmissionPublisher` x5, `PriorityBlockingQueue` x2, ...). At `-g:none` the same fixture is correct.
With a for-each the hoisted iterator is typed with the catch variable's slot type
(`IllegalStateException e = xs.iterator();`, `RuntimeException unused = this.entrySet().iterator()` in
`AbstractMap:462`, `Hashtable:823`) — those fail to compile; the `int i`/`Iterator var2 = null` forms
compile and silently remove exception coverage.

**Root cause (traced).** Instrumented `applyGroup` on `P_TryTail` (`-g`):

```
[applyGroup] try=[2,27) lines=[26,26] usePc=false
[stmt] VariableDeclarationStatement line=26 pc=3      -> tryBody
[stmt] LabelStatement                line=0  pc=17    -> afterTry
```

`TryCatchReconstructor.applyGroup` :395-412: when the class has lines (`usePc100 == false`) a
statement with `line == 0` fails `sLine > 0 && ...`, is not `beforeTry` (a try statement was already
seen), and falls to the final `afterTry.add(s)`. Loops, labelled blocks and merged if/else built by
`StructuredFlowBuilder` carry `line 0`. `minPcOf(s)` (:91-131) already descends into the body and
returns 17, inside `[2,27)`, but it is only consulted when `usePc100` is true. BUG-2026-0125's
"unpositioned statement inherits its predecessor" rule is likewise gated on `usePc100`.

**Approach.** In the line branch, for `sLine <= 0` statements use `minPcOf(s)` against the group's
pc union (exactly the `inAnyRange122` computation two lines above), falling back to the predecessor
rule. This touches only statements that today have NO position information, so it cannot move a
line-positioned statement and does not reopen the SunEC handler loss.

**Effort / risk.** LOW. Risk: none of the line-positioned classification changes.

**Guard test.** P_TryTail `consoleShape`/`minimal`/`voidTail` at `-g`: assert the loop is inside the
try and `return c;` survives (fails today on all three). Keep BUG-2026-0121's `ifTry` shape as a
separate expectation — it is a flow-builder inversion, not this classification, and still fails at
`-g:none` too.

### Q4. `break`/`continue` out of `synchronized` inside a loop deletes statements — CRITICAL, Bug, silent when locals are declared

Proposed `BUG-2026-0132` (generalises BUG-2026-0121; explains the 12 residual `__MONITOREXIT__` files).

**Evidence.** `java/util/concurrent/Phaser.doRegister` (javap pc 185-284: `monitorenter`; `if (state ==
s) { phase = parent.doRegister(1); if (phase < 0) { monitorexit; goto 284 } ... weakCompareAndSet
...; monitorexit; goto 284 }`) decompiles to

```java
synchronized (this) {
    if (this.state != s) {
        try { /* __MONITOREXIT__ */ } finally { /* __MONITOREXIT__ */ }
    }
}
```

— condition inverted, `parent.doRegister(1)`, the `break`s, `next = ...` and the CAS loop gone; the
file recompiles (only comments remain). `TimerThread.mainLoop` loses `queue.removeMin(); continue;`,
`if (!taskFired) queue.wait(...)` and `if (taskFired) task.run()`, and gains a fabricated
`try { ... } catch (InterruptedException e) {}`. Probe SCRATCH/probe/src/P_SyncBreak.java reproduces
all of it in **all three debug modes** (`f(1)`, `cas(...)`, `remove(0)`, `wait(10L)`, `f(2)` absent from
the output). The census "invoked method names absent from the output" lists 541 classes / 1,447
names; it is an upper bound (bridges and `$deserializeLambda$` were excluded, some noise remains) but
every one of the six classes read (`Phaser`, `TimerThread`, `Console`, `FileTime`, `BufferedReader`,
`Files`) was a real loss.

**Root cause.** A `monitorexit` followed by an unconditional jump out of the enclosing loop (`break`)
or back to its header (`continue`) is javac's exit-path unlock. `reconstructSynchronized`
(`ClassFileToJavaSyntaxConverter` :5272-5430) pairs `__MONITORENTER__`/`__MONITOREXIT__` markers as
balanced parentheses in statement order and terminates the region at the first depth-matched exit
marker (`collectSynchronizedRegion` :5430-5480). The break-path exit marker comes first, so the
region closes there and whatever the flow builder placed between that exit and the real region end
is dropped by the surrounding try/finally reconstruction (the `any` handler covering the region is
turned into `try { } finally { /* __MONITOREXIT__ */ }` and its body discarded). BUG-2026-0121's
notes established the if/else inversion comes from `StructuredFlowBuilder`, not the reconstructor,
and that a reconstructor-only fix dropped the handler; do not repeat that.

**Approach.** Model exit-path monitorexits explicitly at the flow-builder level: an `__MONITOREXIT__`
immediately followed by a `break`/`continue`/`return`/`throw` (or by a `goto` that leaves the
monitor's `any` range) is an *exit copy*, not the region end. Mark such markers (e.g. a distinct
`__MONITOREXIT_EXIT__` marker emitted by the decoder when the next instruction is a jump/return
leaving the protected range of the enclosing `any` entry) and have `collectSynchronizedRegion` skip
them; the region ends at the exit marker whose successor is the fall-through merge. Pair this with a
block-coverage check (Q15) so any residual loss is reported instead of silent.

**Effort / risk.** MEDIUM-HIGH (flow builder + reconstructor). Risk: nested synchronized (BUG-2026-0092
balanced pairing) — keep `C_Concurrency` and the nested-synchronized matrix cases in the gate.

**Guard test.** P_SyncBreak at all three modes: assert `f(1)`, `cas(`, `remove(0)`, `wait(10L)`,
`f(2)` present and `__MONITOREXIT__` absent. Release gate: census `synchronized (` == `monitorenter`
per class (16 classes differ today) plus zero `__MONITOREXIT__` in `java.base`.

### Q5. `synchronized` dropped when its monitor temp is consumed as an instanceof binding — CRITICAL, Bug, silent

Proposed `BUG-2026-0133`.

**Evidence.** `java/lang/String.contentEquals`:

```java
// bytecode pc 14-28: aload_1; dup; astore_2; monitorenter; ... monitorexit; ireturn
if (cs instanceof StringBuffer n) {                 // output: lock gone, bogus binding `n`
    return this.nonSyncContentEquals((AbstractStringBuilder) cs);
}
```

Probe SCRATCH/probe/src/P_SyncSlot.java: `if (x instanceof T) { synchronized (x) { return f(x); } }`
loses the lock in **3/3 methods in all three debug modes**, with no marker left. The census lists 16
`java.base` classes whose `synchronized (` count differs from their `monitorenter` count
(`String` 0/1, `Cipher` 0/2, `LoginContext` 0/1, `DefaultProxySelector` 0/1, `Subject` 9/12,
`VirtualThread` 7/9, `JarFileFactory` 5/7, ... and `UnixAsynchronousSocketChannelImpl` 13/11, a
fabrication). BUG-2026-0101 was closed on "`__MONITORENTER__` markers 98 -> 0 files"; the locks are
still lost, the marker is not.

**Root cause.** `InstanceOfPatternReconstructor.tryBind` (:200-235) accepts a leading bare alias
`v = operand;` in the then-body as the pattern binding (`bareAlias`, added for BUG-2026-0067). The
monitor temp store (`dup; astore_2` -> `var2 = cs;`) is exactly that shape, so the store is consumed
into `cs instanceof StringBuffer var2` and removed. `reconstructSynchronized` then finds a
`__MONITORENTER__` with no preceding `varX = lockExpr` (`extractLockFromTail` returns null, :5306)
and skips the marker; the exit markers are stripped as leftovers. The reconstructors run in the wrong
order for this shape: `reconstructSynchronized` runs after `InstanceOfPatternReconstructor`.

**Approach.** In `tryBind`, refuse a bare alias whose next statement is a `__MONITORENTER__` marker
(or, more generally, whose alias variable is never referenced in the then-body — a pattern binding
that is never read is not what javac emits). Alternatively run `reconstructSynchronized` before the
pattern reconstructors in both chains. Prefer the first (local, no ordering change).

**Effort / risk.** LOW. Risk: none observed; `C_InstanceofPattern` and BUG-2026-0067's fixture guard
the binding forms.

**Guard test.** P_SyncSlot at `-g`: three `synchronized (` expected, zero `instanceof ... var`. Fails
today (0/3). Release gate: the per-class `monitorenter` census (Q4).

### Q6. Record-pattern `switch` works only in `return switch` position — HIGH, Bug (Java 21 GA)

Proposed `BUG-2026-0134` (ties to LIM-0009, BUG-2026-0128, BUG-2026-0115 and the JD retirement).

**Evidence.** Probe SCRATCH/probe/src/P_RecSw.java, default path, `-g`:

| Shape | Result |
|---|---|
| `return switch (o) { case Circle(double r) -> ...; default -> k; }` | OK (JD selective route) |
| `return switch (o) { case Integer i -> ...; case String s -> ...; default -> ... }` | OK |
| `double v = switch (sh) { case Circle(double r) -> r; case Square(double s) -> s; };` | arm values lost, `while (true) { switch ... }`, `double v = r;` (r out of scope), **no diagnostic** |
| `System.out.println(switch (sh) {...})` | same, `this.println(r)` |
| statement form `switch (o) { case Circle(double r) -> println(r); ... }` | `break;` followed by dead `System.out.println(s);` |
| `for (...) k += i; return switch (sh) {...}` (loop before) | arm values lost |
| `t += switch (sh) {...}` inside a for-each | `STACK_UNDERFLOW pc=176` + garbage |

**Root cause.** The legacy `StructuredFlowBuilder.tryBuildSwitchExpression` cannot rebuild the value
merge of the `typeSwitch` restart scaffold; the JD route (`ClassFileToJavaSyntaxConverter` :1495) is
kept only when `foldedRecordPatternSwitch(jdResult)` finds a `return switch` (:1553), so every other
position falls back to legacy, which produces the shapes above. `PatternSwitchStatementReconstructor`
(v1.12.0) handles type patterns but not deconstruction arms. No diagnostic is recorded because the
raw `typeSwitch` call is folded into `switch (var)` before `containsRawTypeSwitch` runs.

**Approach.** This is the same flow-builder work BUG-2026-0128 needs (model the
`while (true) { switch (typeSwitch(sel, idx)) }` restart loop and take arm values from the per-block
exit-stack snapshots of BUG-2026-0051), generalised to any consumer of the merged value (store,
call argument, `aastore`, compound assignment — BUG-2026-0115's list). Doing it in the legacy builder
removes the last reason for the JD route (section 3). Until then, at minimum record
`PATTERN_SWITCH_NOT_RECONSTRUCTED` whenever a `switch` over a `typeSwitch` selector is emitted with a
`while (true)` wrapper and no value merge — today `assignForm`/`callArg`/`stmtForm` ship with no
diagnostic.

**What not to try.** Accepting the bare-assignment binding form in `guardedPatternArm` (tried for
BUG-2026-0123: no additional switch reconstructed, 170 files changed). Rewriting these shapes in
`PatternSwitchStatementReconstructor` into plausible syntax without the values (the item file is
explicit that it must keep declining).

**Effort / risk.** HIGH. Risk: the switch-expression builder is shared with classic switch
expressions; gate on `C_SwitchExpr`, `C_PatternSwitch`, `C_RecordPattern`, `C_Sealed`, `C_Unnamed`.

**Guard test.** P_RecSw's six methods at `-g` (recompile + run against the original). Four fail today.

### Q7. Enum constructor parameter types shifted for 3+ parameters — HIGH, Bug (compile failure)

Proposed `BUG-2026-0135`.

**Evidence.** Probe SCRATCH/probe/src/P_EnumCtor.java, **all three modes**:

```java
// source                                              // output
P_EnumCtor(String s, int n, boolean f)                 private P_EnumCtor(boolean s, int n, boolean f)
P_EnumCtor(String s, ChronoLike u1, ChronoLike u2, long l)   private P_EnumCtor(P_EnumCtor.ChronoLike s, long u1, P_EnumCtor.ChronoLike u2, long l)
```

One- and two-parameter constructors are correct. `java.base`: `sun/security/util/KnownOIDs`
(`private KnownOIDs(boolean oid, String stdName, boolean registerNames)`, 103 javac errors from its
constants), `java/time/temporal/ChronoField` (34), `ChronoUnit`, `sun/security/ssl/SSLCipher`, ...
The observed mapping is consistent with a slot-indexed type lookup that starts at slot 1 for the
stripped parameter list while the real parameters of an enum constructor start at slot 3 (after the
synthetic `String name, int ordinal`): parameter at real slot k gets the type of stripped index
(k - 1), falling back to the positional type when that index is out of range.

**Root cause (to pin).** `ClassFileToJavaSyntaxConverter.convertMethod` (:596-630) builds `paramTypes`
from the full descriptor and `paramNames` by walking slots from `method.isStatic() ? 0 : 1`; the
enum-specific stripping happens later (writer, `JavaSourceWriter` :705-710 suppresses the <=2-param
constructor; the header writer strips the first two). Bisect with P_EnumCtor: the wrong types appear
with and without an LVT, so the culprit is the descriptor-based slot/type pairing, not the
LocalVariableTable.

**Approach.** For `<init>` of an enum, offset the parameter slot walk by 2 (or strip the two
synthetic descriptors before building both lists) in one place, and have the writer consume already
stripped lists. Add a 3- and a 4-parameter enum constructor to `C_EnumAdvanced`.

**Effort / risk.** LOW. Risk: none (two-parameter constructors already take this path correctly).

**Guard test.** P_EnumCtor recompiles at all three modes; fails today (`incompatible types`).

### Q8. boolean/int confusion — HIGH aggregate, Bug (compile failure), BUG-2026-0112 at scale

**Evidence.** 1,454 javac errors (10.6% of all) in `java.base`: `boolean taskFired = executionTime <=
currentTime ? 1 : 0;` (269 sites of `boolean x = c ? 1 : 0;`), `while (var2.hasNext() != 0)`,
`if (s.isEmpty() != 0)` (121 sites of `boolean-call() != 0`), `eol = 1;` / `term[0] = 0;` for boolean
targets, `!` applied to int. Shows in every mode; worst at `-g:none` (no LVT type to fall back on).

**Root cause.** `BooleanSimplifier` reconciles ternaries only when the target is already known
boolean (:172, :220, :277); `storeLocal` does not use the LVT descriptor `Z` to retype the RHS; method
invocations whose descriptor returns `Z` are compared with `!= 0` when the condition was built from
`ifeq/ifne` on an int-typed expression.

**Approach.** Use the three type sources that are always available: the LVT descriptor (`Z`) of the
target slot, the callee descriptor's return type (`()Z`) for invocation results, and the field
descriptor for `putfield`/`putstatic`; when any of them is boolean, fold `c ? 1 : 0` -> `c`,
`x != 0` -> `x`, `x == 0` -> `!x`, `= 1/0` -> `= true/false`. Array stores (`term[0] = 0` into
`boolean[]`) use the `bastore` on a `[Z`-typed array.

**Effort / risk.** LOW-MEDIUM. Risk: `byte`/`boolean` share `bastore`; use the array's declared type.

**Guard test.** Extend `testBooleanTernary` with the `taskFired` shape at `-g:none` and a
`while (it.hasNext())` at `-g:none`; both fail today.

### Q9. Nested-class `static final` fields assigned in `static {}` — MEDIUM, Bug (compile failure)

**Evidence.** 175 `cannot assign a value to static final variable` errors:
`java/lang/Thread$ThreadNumbering` -> `static { U = Unsafe.getUnsafe(); try { ... } }`;
`sun/util/resources/LocaleData$LocaleDataStrategy` -> `static { INSTANCE = new ...; JAVA_BASE_LOCALES
= Set.of(...); }`.

**Root cause.** `JavaSourceWriter` builds `staticInits` (:584-600) only from a `<clinit>` whose
statements are all plain field assignments; when the initialiser also contains control flow (a
`try` after the assignments), nothing is inlined and the assignments are emitted verbatim.

**Approach.** Inline the leading run of `putstatic` assignments to `final` fields as field
initialisers and keep the remainder as the `static {}` block (order is preserved because javac emits
them in source order). Same for the inner-class path at :1130.

**Effort / risk.** LOW. Risk: a `final` field assigned after a side-effecting statement must stay in
the block — only the leading run is safe.

**Guard test.** Fixture with `static final X U = ...; static { try { ... } catch ... }` in a nested
class; recompile. Fails today.

### Q10. Enum `switch` via `$SwitchMap$` mis-folded — MEDIUM, Bug

**Evidence.** `switch ((ChronoField) field.ordinal())` (3 files, compile error: the cast was moved
onto the ordinal), `switch (x.ordinal())` with integer labels (154 sites: compiles, constants lost —
ROADMAP §2 calls this "compilable, semantically correct", which is true only when the selector stays
the `ordinal()` int; with the cast fold it is not), raw `$SwitchMap$` array reads (48 sites). The
`LocalDate$1` switch-map class is correctly hidden but its mapping is not used.

**Approach.** Resolve `$SwitchMap$Enum[expr.ordinal()]` by reading the anonymous class's `<clinit>`
(index -> constant) — the loader already provides nested classes for the writer — and emit
`switch (expr) { case CONSTANT -> ... }`. When the mapping class is unavailable (single-class
decompilation from a jar entry) keep `switch (expr.ordinal())` with int labels and the cast on the
receiver, never on the ordinal.

**Effort / risk.** MEDIUM. Risk: label collisions when two enums share a switch-map class (javac
numbers them per enum; keep per-array maps).

### Q11. Nested lambdas deeper than two levels leak `Class::lambda$m$n` — MEDIUM, Bug (compile failure)

**Evidence.** SCRATCH/probe/src/P_Lam.java: `d3()` -> `() -> () -> P_Lam::lambda$d3$2`, curried
`a -> b -> c -> ...` -> `a -> b -> P_Lam::lambda$curried$2`; `java.base`: 5 sites in 3 files
(`java/lang/module/Resolver:532`, `jdk/internal/foreign/abi/BindingSpecializer:205`,
`sun/util/locale/provider/LocaleResources:674`).

**Root cause.** `ClassFileToJavaSyntaxConverter` :425-440: lambda bodies are registered in two passes
("on the first pass that nested body is not yet registered, so it leaks as `Class::lambda$x$n`. The
second pass re-decodes"); depth three needs a third pass.

**Approach.** Iterate to a fixed point (re-decode until no `lambda$` method reference remains, with a
bound of, say, 8) or decode `lambda$` methods in dependency order (a body referencing a later
`lambda$` is decoded after it).

**Effort / risk.** LOW. Risk: decode time on classes with hundreds of lambdas — bound the passes.

### Q12. `$exception` and other handler-shape garbage should record a diagnostic — MEDIUM, Improvement

**Evidence.** 85 `$exception` in 46 files; `Files.mismatch` (two-resource TWR: `totalRead =
$exception;`, `in2` undeclared, both resources' bodies interleaved), `BufferedReader.readLine`
(`try { e = $exception; } finally { /* __MONITOREXIT__ */ }`). The decoder pushes `$exception` as the
handler seed (:2117); it survives only when the handler's leading `astore` was not decoded as the
catch variable, i.e. the handler was split or merged into the wrong region. No diagnostic is recorded.

**Approach.** When the writer is about to print a `LocalVariableExpression` named `$exception`, or the
converter detects a surviving seed after the transform chain, record `HANDLER_UNANCHORED` in the
method diagnostics. This turns 46 silent files into flagged ones and gives Q2/Q3 a regression signal.

**Effort.** LOW.

### Q13. JEP 513 flexible constructor bodies: inner-class outer instance read before `super()` exposes `this$0` — MEDIUM, Bug

**Evidence.** SCRATCH/j25/src/P_FlexCtor.java: `Inner(int v) { outerHash = P_FlexCtor.this.hashCode();
super(v); }` decompiles to `Inner(P_FlexCtor this$0, int v) { this.outerHash = this$0.hashCode();
super(v); }` — compiles, but changes the constructor's signature (every `new Inner(v)` call site
breaks) and leaks the synthetic parameter. Static nested and top-level flexible constructors, enum and
record variants are correct (P_FlexCtor recompiles otherwise).

**Root cause.** The outer-instance parameter is stripped only when it is consumed by the
`this.this$0 = param` prologue before `super()`; in a flexible body javac reads the parameter
directly for statements before `super()`.

**Approach.** For inner-class constructors always strip parameter 0 (the `Lo/Outer;` synthetic,
identified via `InnerClasses`/`EnclosingMethod`) and substitute `Outer.this` for its reads.

**Effort.** LOW-MEDIUM.

### Q14. Primitive types in patterns (JEP 455/507, preview in 25; JEP 530 in 26) — MEDIUM, Limitation until final, then Bug

**Evidence.** SCRATCH/j25/src/P_PrimPat.java (`--enable-preview --release 25`, `-g`): boolean and
double selectors with type patterns reconstruct; every guarded primitive arm (`case int i when i >
100`) is the BUG-2026-0128 restart shape (raw `typeSwitch`); `o instanceof int i` on a `long` renders
as `ExactConversionsSupport.isLongToIntExact(var4)` + `(int) l` — an internal class that does not
compile from user code; nested record patterns with primitive narrowing are raw.

**Approach.** Fold `java.lang.runtime.ExactConversionsSupport.isXToYExact(e)` followed by
`(Y) e` into `e instanceof Y v`; treat the `typeSwitch` labels `Dynamic primitiveClass` (already
parsed by BUG-2026-0108) as primitive type patterns in the Q6/0128 value-merge work. Track as a
limitation until the JEP is final; the class-file shape is stable across the previews.

### Q15. Silent truncation without a diagnostic — HIGH (process), Improvement, LOW effort

**Evidence.** `java/nio/file/attribute/FileTime.toString`: 39 invocations in bytecode, ~5 in the
output, the rest replaced by `while (var7a % 10 == 0) { int var7a = var7a / 10; ... }`; **no
diagnostic**. `Phaser.doRegister`, `TimerThread.mainLoop`, `Console.instantiateConsole`: same. The
flow builder has two silent returns — the depth cap (`StructuredFlowBuilder` :249-253, BUG-2026-0120)
and `return; // End of flow` (:653) — and no check that every basic block was emitted.

**Approach.** After structuring, compare the set of decoded blocks against the set of blocks whose
statements were emitted (an identity set filled by `buildFromBlock0` when it appends a block's
statements); any decoded, reachable block missing from the output records `FLOW_TRUNCATED pc=N`. Also
promote the depth cap to a diagnostic (0120). This is the cheapest single change in this queue and it
makes Q3/Q4/Q6 measurable in `java.base` rather than by hand.

### Q16. Fabricated and lost `catch` clauses — the numbers BUG-2026-0126 asked for — HIGH, Bug

**Evidence.** Per class, emitted `catch (` vs bytecode handlers:
- **Fabrication (certain)**: 84 classes emit MORE `catch (` than the class has typed handlers of any
  kind; +209 clauses. `sun/security/ec/SunEC` 19 vs 1, `sun/security/provider/DSAKeyFactory` 14 vs 4,
  `com/sun/crypto/provider/DHKeyFactory` 10 vs 3, `sun/security/timestamp/TSResponse` 8 vs 1,
  `java/security/KeyRep` 8 vs 2, `java/text/SimpleDateFormat` 11 vs 5.
- **Loss (upper bound, synthetic handlers already excluded)**: 151 classes emit FEWER than their real
  handlers; -316. `sun/security/tools/keytool/Main` 29 vs 47, `sun/nio/fs/UnixFileSystem` 24 vs 38,
  `java/lang/invoke/MethodHandleImpl` 16 vs 26, `AbstractQueuedSynchronizer` 4 vs 11,
  `java/util/Scanner` 15 vs 20, `StampedLock` 3 vs 8, `ForkJoinWorkerThread` 0 vs 4.
Full lists: run SCRATCH/join.py. This is the per-class before/after instrument the item file says
decides whether the pc criterion can ship: a candidate change is acceptable when the fabrication list
shrinks and the loss list does not grow for any class.

### Q17. Lambda parameter names when one parameter is unnamed — LOW, Bug (compile failure)

`m.forEach((_, v) -> s[0] += v)` -> `(p0, p1) -> s[0] = s[0] + v.intValue()`: the header falls back
to `pN` for all parameters when any LVT name is missing, the body keeps the LVT name `v`. Use the LVT
name where present and `_`/`pN` only for the missing one (SCRATCH/j25/src/P_Unnamed2.java, `-g`).

### Q18. Integer constants passed to `byte`/`short`/`char` parameters — LOW, Bug (367 compile errors)

`this.resolveOrFail(6, refc, name, type)` where the parameter is `byte`: the callee descriptor is
known at the `invoke*`; render `(byte) 6` (or the named constant) when the argument is an int
literal and the descriptor says `B`/`S`/`C`. `java/lang/invoke/MethodHandles` alone has dozens.

### Q19. Nested enum constants in annotation values — LOW, Bug (18 errors)

`@PreviewFeature(feature = Feature.PEM_API)` must be `PreviewFeature.Feature.PEM_API` (or imported).
`JavaSourceWriter` annotation value rendering drops the outer class of a nested enum.

### Q20. Observed, not analysed

- `$assertionsDisabled` appears 643 times in `java.base` output: either many `assert` statements are
  not folded by `reconstructAsserts`, or the field reference is printed in folded form; not read.
- `this$0` appears 132 times (mostly legitimate `Outer.this` should have been printed).
- `PerformanceBenchmark` (test harness) dies with `StackOverflowError` in
  `ClassFileToJavaSyntaxConverter.loadAndAddInnerClass` (:160) when driven over all 7,390 class files
  as main classes; the CLI on `HashMap$Node`, `ConcurrentHashMap$TreeBin`, `MethodHandleImpl$1` is
  fine, so the cycle needs a specific nested class as entry — undetermined which.
- Same-block duplicate declarations: 3 sites in 3 files (`JapaneseImperialCalendar:1670`,
  `CurrencyNameProvider:58`, `CodeStackTrackerImpl:158`) — BUG-2026-0106 residue, low.

---

## 3. The two flow pipelines — retire the JD pipeline

| Corpus / signal | Legacy | JD (`-Ddenzo.jd.pipeline=true`) |
|---|---|---|
| Matrix `-g:source,lines` / `-g` / `-g:none` | 55 / 53 / 55 | **36 / 36 / 36** (same 19 failures each mode) |
| `java.base` `while (` / `for (` / `do {` | 4,707 / 2,819 / 711 | **19 / 8 / 10** |
| `java.base` `catch (` / `finally` / `synchronized (` / `try (` | 3,742 / 1,753 / 933 / 22 | 3,378 / 1,166 / 890 / 2 |
| `java.base` `$exception` leaks (files / sites) | 46 / 85 | **258 / 1,165** |
| raw `typeSwitch` bodies | 5 | 7 |
| files differing | - | 1,944 / 3,376 |
| batch wall (same machine, same run pair) | 3.4-8.0 s | 2.1-4.1 s (it does less) |

What "loops gone" looks like (SCRATCH/probe/out/P_Loops.jd.java, `sumTo`):

```java
int s = 0; int i = 0;
if (i < n) { s += i; i++; } else { return s; }     // was: for (int i = 0; i < n; i++) s += i;
```

This compiles in a `void` method and is silently wrong. The audit's reading that JD "renders every
TYPE_LOOP as `while (true)`" does not survive measurement: the port emits the loop body once and drops
the back-edge. The per-method quality gate (`containsPlaceholders`, "no new diagnostics") cannot see
it.

**What JD wins today.** Exactly one thing, and only through the selective route that is active with
the flag OFF: the `return switch (sel) { case R(comps) -> ... }` record-deconstruction fold
(`TypeSwitchRecordFolder`, kept only when `foldedRecordPatternSwitch` succeeds). `withDefault` and
`areaOne` in P_RecSw/P_Loops pass because of it; `C_RecordPattern`/`C_Sealed` in the matrix depend on
it. Everything else — assignment, argument, statement, loop-adjacent — falls back to legacy and breaks
(Q6).

> **Superseded in part — see the Addendum at the top.** Measurement showed only two of the four shapes
> named above actually work today, and `C_Sealed` does not depend on the route at all (it contains no
> `switch`). The freeze in step 1 is done and runtime-verified.

**Recommendation.** Retire, in this order:
1. Freeze: add a regression fixture that captures every shape the selective route currently gets right
   (`return switch` with deconstruction, with/without `default`, sealed exhaustive with
   `MatchException`) — those must not regress when the route is removed.
2. Implement the value merge for the `typeSwitch` restart loop in the legacy builder (Q6 = BUG-2026-0128
   generalised), consuming values at any merge consumer.
3. Route record-switch methods to legacy behind a flag, verify the fixture, three-mode matrix, and the
   `java.base` raw-`typeSwitch` count (must stay <= 5 and fall as step 2 lands).
4. Delete `cfg/jd/` (~3,800 lines), `runJdPipeline`, the JD half of the transform chain
   (`ClassFileToJavaSyntaxConverter` :1478-1600) and the snapshot/restore of per-method decode state that
   exists only for the fall-through. IMP-2026-0074 (single transform pipeline) becomes a one-call
   refactor instead of a merge of two diverged chains.

Do not invest in parity: parity would mean re-implementing loop reconstruction, switch break/continue
semantics, TWR and the five transforms the JD chain skips, on an engine that today loses loops.

---

## 4. Java 21-25 and what is coming — readiness, with what the class file can and cannot tell

| Feature | What the class file carries | Status (default path, `-g`) | Item |
|---|---|---|---|
| Record patterns in `switch` (JEP 440/441, GA 21) | `typeSwitch` indy + restart loop + `MatchException` | `return switch` only; assignment / argument / statement / loop-adjacent broken | Q6, BUG-2026-0128 |
| Guarded arms, multi-label arms | index restart | raw dispatch, flagged (5 `java.base` bodies) | BUG-2026-0128 |
| Unnamed variables/patterns (JEP 456, GA 22) | no LVT entry; `Box(_, int n)` becomes an instanceof-guarded arm | for-each / catch / TWR `_` fine; lambda `(_, v)` header/body mismatch; record `_` components hit 0128 | Q17, Q6 |
| Flexible constructor bodies (JEP 513, GA 25) | statements before `invokespecial <init>` | correct, including `throw` before `super()`, `this(...)` delegation, enum and record; inner class reads `this$0` parameter | Q13 |
| Module import declarations (JEP 511, GA 25) | nothing — source-level | output uses explicit imports; **not recoverable, permanent** | LIM |
| Compact source files / instance `main` (JEP 512, GA 25) | `final class Hello25` with `ACC_FINAL|ACC_SUPER`, no attribute (verified with `javap -v`) | ordinary class emitted; the compact form is **not recoverable, permanent**. `IO.println` fine | LIM |
| Scoped values (JEP 506, GA 25), stable values (JEP 502, preview) | ordinary calls + lambdas | fine (redundant casts `(String) USER.get()`) | - |
| Structured concurrency (JEP 505, preview) | TWR over `StructuredTaskScope` + generics | TWR with `return` in body breaks (`unreachable statement`) | Q2 |
| Primitive types in patterns (JEP 455/507/530, preview) | `typeSwitch` labels `Dynamic primitiveClass`, `ExactConversionsSupport.isXToYExact` calls | boolean/double type-pattern switch OK; guarded arms raw; `instanceof int i` leaks internal API | Q14 |
| String templates (JEP 430/459/465, withdrawn) | `TemplateRuntime` bootstraps in 21/22 preview class files only; not producible here | untested; such files run only on their own JDK; BUG-2026-0119's owner gate is the generic defence | permanent-ish |
| `--enable-preview` provenance | minor 0xFFFF | header comment `// Compiled with preview features (Java 25)` present | done |
| Valhalla value classes (JEP 401, not in 25; not compilable on this machine) | per the JEP: `ACC_IDENTITY` reuses 0x0020 (a value class LACKS it), `ACC_STRICT_INIT` 0x0800 on fields, `LoadableDescriptors` attribute; no new opcodes in the current draft | unverified: parser skips unknown attributes; writer ignores 0x0020 on classes (`JavaSourceWriter` :1932) so a value class prints as `class`; 0x0800 on a field would need to NOT print `strictfp`. Gate any `value` emission on class-file major >= the Valhalla release, because pre-Java-1.1 files also lack ACC_SUPER | future LIM |
| Class-file major 70+ | best-effort since v1.11.0 | works (BUG-2026-0118) | - |

Permanent limitations confirmed this session: module imports, compact source form, `var` vs explicit
type, `@Override` (LIM-0003), generic type arguments without LVTT (LIM-0002), `_` vs an unused name.

---

## 5. Performance and scale

- In-process, single thread, warm (SCRATCH/harness/Timing.java, 3,375 top-level classes, one
  `DenzoDecompiler` instance): **1,463 and 1,643 classes/s** in rounds 2-3 (round 1 cold: 736/s);
  p50 0.18-0.21 ms, p90 1.5-1.7 ms, p99 6.3-7.4 ms, max 20-21 ms
  (`jdk/internal/util/regex/IndicConjunctBreak`, `ConcurrentHashMap`); cold max 143 ms
  (`AnnotationReader`). Top-10 slowest are all 12-20 ms; no pathological outlier. 18.8 MB of source.
- `--batch` (8 vCPU VirtualBox guest, includes JVM start): 3.4-8.0 s across five runs — the spread is
  the VM, not the decompiler (the `-Xmx256m` run was the fastest at 3.4 s). RSS 350-590 MB with the
  default heap; fits in `-Xmx256m`. `taskset -c 0` (all JVM threads on one core): 19.6 s.
- Pathological classes, `-Xss1m`, all correct or degraded-without-crash and all <= 0.55 s: 400-deep
  nested `if` folded to one `&&` chain (correct), 2,000-arm else-if, 3,000-case tableswitch,
  6,000-statement method, 600-operand concat, 300-deep ternary, 30 nested labelled loops, 60 nested
  try/finally, 40 nested synchronized. The 2 MB batch stack is not needed for these; deep recursion in
  `StructuredFlowBuilder` is bounded by `MAX_RECURSION_DEPTH = 200`, which truncates silently (Q15).
- Cost of the JD selective route in the default path: every method containing a `typeSwitch` (69
  sites in `java.base`) is decoded twice. Negligible.
- No performance item in this queue outranks a correctness item; OPT-0008..0011 stand as filed.

---

## 6. Architectural debt that demonstrably costs correctness

1. **`TryCatchReconstructor` (2,311 lines) reasons in source lines with a pc criterion bolted on.**
   Q2, Q3 and Q16 are one root: `applyGroup` has two classification regimes (`usePc100` true/false)
   with different rules for unpositioned statements, and `ModernTwrReconstructor` is tuned to the line
   regime. The fix path is one regime — pc for membership, lines only for cosmetics — introduced group
   by group (TWR groups first) with the per-class fabrication/loss lists of Q16 as the gate.
2. **The transform chain exists twice** (`ClassFileToJavaSyntaxConverter` :1497-1560 JD vs the legacy
   chain after :1600). The JD chain lacks `ModernTwrReconstructor`, `promoteUndeclaredAssignments`,
   `ForEachDetector.backPropagateSignatures`, `SwitchVarHoister`, `BranchVarHoister`; because the JD
   route is live by default for every `typeSwitch` method, record-switch methods get the shorter chain
   today. Retiring JD (section 3) removes the duplicate instead of merging it.
3. **Reconstructors compete for the same statements with no ordering contract.**
   `InstanceOfPatternReconstructor` consumes the monitor temp that `reconstructSynchronized` needs
   (Q5); `TryCatchReconstructor` builds the monitor `try/finally` that `reconstructSynchronized`
   unwraps (Q4). A small "claimed statement" set, or emitting distinct decoder markers for monitor
   temps and handler seeds, would make these collisions explicit.
4. **Silent returns in `StructuredFlowBuilder`** (depth cap :249, `return; // End of flow` :653) and no
   block-coverage invariant (Q15). "Every truncation is a grave error" is the stated policy; today the
   truncations in `FileTime.toString` and `Phaser.doRegister` are invisible.
5. **The marker vocabulary measures the decoder, not the transforms.** Add `TWR_UNRECONSTRUCTED`,
   `HANDLER_UNANCHORED`, `FLOW_TRUNCATED`, `SYNC_UNRECONSTRUCTED` so "marker-clean" means something
   (audit §7 asked for this; nothing was added).

---

## 7. Gates to add (what would have caught this report's findings)

All cheap, all runnable from `DecompilerTest` or a shell step, all fail on today's build:

| Gate | Oracle | Today |
|---|---|---|
| `synchronized (` count == `monitorenter` count, per class | census over `java.base` | 16 classes differ |
| emitted `catch (` <= typed handlers, per class | census | 84 classes over |
| `try (` count per class vs TWR handler groups (Throwable handler reaching `addSuppressed`); zero handler groups dropped | census + `TWR_UNRECONSTRUCTED` | 22 `try (` in 18 files vs <= 1,235 synthetic handlers |
| surviving finally copy before an exit | SCRATCH/dupfinally2.py == 0 | 498 |
| loop directly after a try whose body only initialises the loop's variables | SCRATCH/loopaftertry.py == 0 | 59 |
| `$exception`, `::lambda$`, `$SwitchMap`, `boolean x = c ? 1 : 0` in output | grep == 0 | 85 / 5 / 48 / 269 |
| decompiled `java.base` recompiles as a patched module: error count and per-message histogram must not grow | `javac --patch-module` (14 s) | 13,685 errors / 846 files |
| decompile -> recompile -> **run** for the reproducers in SCRATCH/rt | output identical | `R_Twr` differs |

REQ-2026-0001 (`--semantic-check`) is the natural home for the first three.

---

## 8. What could not be determined

- The exact line of the enum-constructor slot/type pairing (Q7): the mapping is characterised and the
  fixture bisects it in minutes; I did not want to guess a line number.
- Which nested class makes `PerformanceBenchmark` recurse forever in `loadAndAddInnerClass`; the CLI
  and batch paths do not hit it.
- How many of the 1,447 "invoked name absent from output" hits are real losses. Six of six read were
  real; treat the metric as an upper bound until Q15's `FLOW_TRUNCATED` replaces it.
- Valhalla and Java 26 class-file behaviour: no JDK 26 on this machine; the table in section 4 is from
  the JEP texts and the parser's code, not from running it.
- Whether the `-g:none` TWR residue (7/9 flattened) shares Q2's second cause entirely or also involves
  `isHandlerProtectionEntry` coalescing (BUG-2026-0068); the trace was taken only for the line regime.

---

## 9. Reproduction

All under SCRATCH (session-local; regenerate with the commands in section 1):

- `jbase/x/classes` (extracted `java.base`), `jbase/legacy`, `jbase/jd` (outputs), `jbase/re_legacy.time`
  (javac diagnostics of the recompile), `jbase/legacy.log` / `jd.log` (batch summaries).
- `harness/Census.java` + `census.tsv` + `invoked.tsv`; `join.py` (per-class comparison, prints the
  fabrication/loss/sync/missing-name lists); `dupfinally.py`, `dupfinally2.py`, `loopaftertry.py`,
  `dupdecl.py`; `harness/Timing.java`.
- `mx/matrix.sh <tag> <javac-flag> [jvm flags]` with `L_*` (legacy) and `J_*` (JD) result trees.
- `probe/src`: P_Loops, P_RecSw, P_SyncBreak, P_SyncRet, P_SyncSlot, P_TryTail, P_Twr, P_Twr2,
  P_Twr3, P_EnumCtor, P_Lam; outputs in `probe/out` per mode (`cls_-g`, `cls_-gsourcelines`, `cls_-gnone`).
- `j25/src`: P_PrimPat, P_Structured (preview), P_FlexCtor, P_Unnamed2, P_ModuleImport, Hello25;
  outputs and recompile errors in `j25/out`, `j25/re`.
- `rt/src`: R_Finally, R_Finally2, R_Finally3, R_Twr, R_TryLoop, R_TryLoop2, R_TryLoop3 with
  `dec/` and `re/` (decompile -> recompile -> run).
- `patho/`: generated pathological classes and outputs.
- `instr/`: the traced copy of `TryCatchReconstructor` (`-cp SCRATCH/instr/cls:target/classes`).

Commands worth keeping in the project (proposed for `docs/guides/`): the patched-module recompile,
the census harness, and the three scanners; together they run in under a minute after a batch.
