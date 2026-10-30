# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java bytecode decompiler supporting Java 1.0-25. Source compatibility: Java 1.6. Zero external dependencies.
Package: `it.denzosoft.javadecompiler`. Build: `mvn clean package`.

## Build & Test Commands

**Two JDKs are required**: the project targets `source/target 1.6`, which modern javac rejects
("Source option 6 is no longer supported"), so builds need JDK 8. The test suite compiles Java 25
fixtures, so it needs JDK 25.

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn clean compile   # Compile (plain `mvn` fails on JDK 25)
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn clean package   # Build target/java-decompiler-1.13.0.jar
```

`mvn test` runs **no tests** — there is no JUnit dependency and no surefire configuration. The real
suite is a plain `main()` runner:

```bash
JDK25=/usr/lib/jvm/java-25-openjdk-amd64
$JDK25/bin/javac -cp target/classes src/test/java/it/denzosoft/javadecompiler/DecompilerTest.java -d target/test-classes
java -cp target/classes:target/test-classes it.denzosoft.javadecompiler.DecompilerTest $JDK25/bin/javac
```

Each test compiles a source snippet with the given javac, decompiles the result, and asserts that
expected substrings appear in the output. To run a single case, comment out the other calls in
`DecompilerTest.main()` — there is no test selector. Current baseline: **80/81**; `BasicClass` fails
on a stale expectation (it asserts an explicit `super()` that the decompiler now correctly elides).
Do not treat that one as a regression, and do not "fix" it by re-emitting implicit `super()`.

Tests may pin a javac debug mode via the 5-argument `runTestFull(name, src, javacDebugFlag, must,
mustNot)`. Use it: several defect classes reproduce in exactly one mode.

**All-class self-decompilation** (must report 0 errors):
```bash
java -cp target/classes it.denzosoft.javadecompiler.Main --batch target/classes /tmp/selftest
```

**Construct matrix**: `src/test/resources/construct-matrix/` holds ~49 hand-written classes (55
top-level, despite older docs saying 57) densely covering Java 1.0-25 constructs. The verification is a
strict round trip — compile with JDK 25 → decompile → **recompile with JDK 25 → 0 errors**. There is no
committed runner; drive it with a shell loop:

```bash
JDK25=/usr/lib/jvm/java-25-openjdk-amd64; MODE="$1"; W=$(mktemp -d)   # MODE: -g:source,lines | -g | -g:none
$JDK25/bin/javac $MODE -d $W/cls src/test/resources/construct-matrix/*.java || exit 1
java -cp target/classes it.denzosoft.javadecompiler.Main --batch $W/cls $W/src >$W/decomp.log 2>&1
ok=0; bad=0
for f in $(cd $W/src && ls *.java); do
  if $JDK25/bin/javac -d $W/re -cp $W/src $W/src/$f 2>$W/e.$f; then ok=$((ok+1))
  else bad=$((bad+1)); echo "FAIL $f: $(head -1 $W/e.$f)"; fi
done
echo "$MODE : $ok ok / $bad fail"; echo "output kept in $W/src"
```

Recompiling each file separately with `-cp $W/src` (not all 55 in one javac call) is deliberate: it
keeps one bad file from masking or cascading into the others.

**Run it in all three debug modes — they are three different products**, and a defect usually lives in
exactly one of them:
- `-g:source,lines` (javac default) — the most forgiving; current 55/55
- `-g` (full LocalVariableTable; what Maven/Gradle emit, so every production jar) — current 53/55
- `-g:none` (no LineNumberTable; stripped and obfuscated jars) — current 55/55

The two known `-g` failures are `C_RecordPattern` (`variable a is already defined`) and
`C_VarInference` (`package Map does not exist`). They are the current baseline, not a regression you
introduced — but any *third* failure, or a different message on these two, is.

## Verifying a decompiler fix (read this before claiming one works)

**"It builds and nothing threw" proves nothing here.** The failure mode of a decompiler is output that
compiles perfectly and means something else: a deleted `catch`, a lock that no longer wraps anything,
a `for` counter typed `String[]`. Every one of those shipped in a release whose gates were green. Work
through the ladder below and stop at the first rung that shows a problem.

**The oracle is the ORIGINAL SOURCE, not the compiler.** A verification is only accurate if it
establishes that the output reconstructs what the original source actually said and did. "It
recompiles" is a syntax check — it says nothing about meaning, and every silent defect this project
has shipped recompiled cleanly. So never report a fix as verified on a recompile alone. Where the
fixture can be executed, the standing oracle is:

```
original source --javac--> run --> output A
      |
      decompile --> recompile --> run --> output B      A must equal B
```

Give fixtures a `main` that prints observable state (counters, flags, close/unlock order, the value
each branch returns) so a divergence in MEANING shows up as a diff instead of hiding behind a clean
compile. Where the fixture cannot be run — `java.base` classes, anything needing an environment —
substitute the strongest available proxy in this order: statement-by-statement reading against the
source (rung 1), a construct census (rung 5), and the bytecode itself (rung 2). Say which oracle you
used when you report the result; "recompiles clean" on its own is not a verification, and reporting it
as one is the specific failure this rule exists to prevent.

The runner. `CLASSES=<dir>` selects the build (point it at a baseline to prove a fixture reproduces a
defect BEFORE fixing it). Exit 0 = runtime-identical, 2 = does not recompile, 3 = **compiles but
behaves differently** — rung 3 is the one that catches the silent class:

```bash
JDK25=/usr/lib/jvm/java-25-openjdk-amd64; SRC="$1"; MODE="${2:--g}"; W=$(mktemp -d)
CLS=${CLASSES:-target/classes}; N=$(basename "$SRC" .java)
$JDK25/bin/javac $MODE -d $W/cls "$SRC" || { echo "fixture does not compile"; exit 1; }
$JDK25/bin/java -cp $W/cls $N > $W/A.txt 2>&1 || { echo "fixture does not RUN"; cat $W/A.txt; exit 1; }
java -cp $CLS it.denzosoft.javadecompiler.Main --batch $W/cls $W/dec > $W/decomp.log 2>&1
if ! $JDK25/bin/javac -d $W/re $W/dec/$N.java 2> $W/javac.err; then
  echo "FAIL($MODE) $N: does not recompile"; head -3 $W/javac.err; echo "output: $W/dec/$N.java"; exit 2; fi
$JDK25/bin/java -cp $W/re $N > $W/B.txt 2>&1
if diff -q $W/A.txt $W/B.txt >/dev/null; then echo "PASS($MODE) $N: runtime-identical"
else echo "FAIL($MODE) $N: BEHAVIOUR DIFFERS - compiles and means something else"
     diff $W/A.txt $W/B.txt; echo "output: $W/dec/$N.java"; exit 3; fi
```

**The gate is proven, not just plausible.** On commit `e032be5` (pre-release v1.13.0) it returns 3 for this fixture — the
output recompiles cleanly while the exception is no longer caught (original `open;close;caught;fin;`,
recompiled output an uncaught `RuntimeException`):

```java
static void run() {
    log.setLength(0);
    try (R r = new R()) { r.boom(); }
    catch (RuntimeException e) { log.append("caught;"); }
    finally { log.append("fin;"); }
}
```

Fixtures live in `src/test/resources/runtime-oracle/` (one shape each, with a table of today's exit
code per fixture — see its README) and `src/test/resources/jd-freeze/` (record-switch shapes frozen
against the JD retirement). Note that a plain `try (R r = new R()) { return r.use(); }` PASSES today
while multi-resource and TWR+catch+finally do not — never assume a whole construct is broken because
one shape is; pin the shape each fixture covers.

**1. Read the code you produced.** Decompile the fixture and actually read the method. Compare it to
the source it came from, statement by statement. This is the step that catches the silent class, and
it is the step easiest to skip.

**2. Ask the bytecode, not your intuition.** When output and expectation disagree, `javap -p -c -v`
settles it. A monitor region's real extent, whether an `iinc` sits inside or outside a lock, which
exception range covers which instruction — all of it is in the class file. Do not reason about what
javac "probably" emitted: several fixes this project has shipped were built on a shape that was read
from real bytecode and turned out to differ from the obvious guess.

**3. Build a baseline before you judge a diff.** `git stash` the change, build to a separate directory
(`cp -r target/classes <scratch>/base-classes`), restore, rebuild. Then run both builds over the same
input. Without it you cannot tell your regression from a defect that was already there — and both
happen. Twice in this project a "regression" turned out to be the fix exposing an older bug, and once
the reverse.

**4. Recompile the output, then RUN it.** The construct matrix in all three debug modes (above) gives
you the recompile half — necessary, never sufficient. For any fixture with observable behaviour,
finish the loop: run the original and the recompiled output and diff their output. A dropped `catch`,
a doubled `unlock()`, a lock that no longer wraps anything and a `finally` that runs twice all
recompile perfectly and all show up here.

**5. Census what recompilation cannot see.** Count the constructs that must survive and compare
against the source: `catch (`, `finally`, `synchronized (`, `try (`. At `-g:none` this project once
scored 53/55 on recompilation while emitting **zero** of the source's 15 `catch` clauses. A census
would have caught it years earlier; the recompile gate never could.

**6. Measure at scale.** Batch-decompile JDK 25 `java.base` (~3,376 top-level classes) and count
diagnostic markers and defect shapes: `__MONITORENTER__`, `/* inline stmt */`, `STACK_UNDERFLOW`,
`SwitchBootstraps.typeSwitch`, locals re-declared in scope. Compare against the baseline build from
step 3. Small fixtures cannot tell you whether a change helps or hurts across 3,376 classes; this is
where net-negative changes reveal themselves.

`java.base` CAN also be recompiled — as a patched module, in about 9 seconds — so treat the recompile
error count and its message histogram as a standing gate, not an impossibility. The procedure and the
current baseline (14,286 errors across 965 of 3,376 files, and what the top messages mean) are in
**`docs/guides/guide-corpus-gates.md`**. That guide also records a number from the analysis report that
did NOT reproduce under a different javac invocation: quote this metric only together with the exact
command that produced it, and always run baseline and fix through the identical procedure.

**7. Distrust your measuring instrument.** Three times in one session a hand-rolled scanner reported
numbers that were simply wrong — it counted `return x;` as a declaration, treated fields as locals,
and let `} else {` leak the then-branch's scope. Before quoting a number, print the individual sites
it is built from and read a few. If a metric moves in a way you cannot explain by the change you made,
the metric is the first suspect, not the last.

**8. Prove the test is a real guard.** A regression test that passes on the pre-fix build tests
nothing. Run every new test against the step-3 baseline and confirm it FAILS there, with the failure
message describing the defect you set out to fix.

**When a fix cannot be completed, say so in the item and leave the diagnostic in place.** Never make
degraded output *look* correct: a rewrite that produces plausible syntax while the body is still
missing its values, and that silences the diagnostic reporting it, is strictly worse than the raw
output it replaced. Transforms should decline shapes they do not fully understand.

Worked examples, with the numbers and the reasoning:
`docs/reports/report-java25-plus-audit.md` and the item files under `docs/releases/v1.11.0/` and
`docs/releases/v1.12.0/`.

**Running the decompiler:**
```bash
java -jar target/java-decompiler-1.13.0.jar                                   # GUI (default)
java -jar target/java-decompiler-1.13.0.jar <file.class>                      # CLI, line-aligned
java -jar target/java-decompiler-1.13.0.jar --compact <file.class>            # Compact output
java -jar target/java-decompiler-1.13.0.jar --show-bytecode <file.class>      # Inline bytecode instructions
java -jar target/java-decompiler-1.13.0.jar --show-native-info <file.class>   # JNI info on native methods
java -jar target/java-decompiler-1.13.0.jar --deobfuscate <file.class>        # Sanitize obfuscated names
java -jar target/java-decompiler-1.13.0.jar <file.jar> <ClassName>            # From JAR
java -jar target/java-decompiler-1.13.0.jar --batch <file.jar|dir> <out-dir>  # Batch (also .war/.ear/.apk)
java -jar target/java-decompiler-1.13.0.jar --gui [file.jar ...]              # GUI explicit
java -jar target/java-decompiler-1.13.0.jar --trace <dir> <file>              # Tracing
```

**Version is tracked in four places** (keep in sync): `pom.xml` `<version>`,
`DenzoDecompiler.getVersion()`, the `README.md` H1 title plus its `target/java-decompiler-X.Y.Z.jar`
build line, and the `ROADMAP.md` "Current state" line. `git grep -n <old-version>` finds any stragglers.

**Git note**: this working copy lives on a VirtualBox shared mount, so git refuses to operate until
the directory is marked safe (`git config --global --add safe.directory <repo path>`). The release
steps below all need this.

## Development Workflow

### Issue Tracking (MANDATORY)

Every bug, limitation, possible optimization or feature request must be documented before implementation.

There are 5 item types:

| Type | Prefix | Index File | Description |
|---|---|---|---|
| **Bug** | `BUG-YYYY-NNNN` | `docs/tracking/track-bugs.md` | Defects, incorrect behavior, crashes |
| **Request** | `REQ-YYYY-NNNN` | `docs/tracking/track-requests.md` | New feature requests, change requests |
| **Improvement** | `IMP-YYYY-NNNN` | `docs/tracking/track-improvements.md` | Enhancements to existing features |
| **Limitation** | `LIM-NNNN` | `docs/tracking/track-limitations.md` | Known limitations (permanent or fixable) |
| **Optimization** | `OPT-NNNN` | `docs/tracking/track-optimizations.md` | Performance and code quality improvements |

Each item gets its OWN DEDICATED FILE in `docs/tracking/items/`:
- **File naming**: `<ID>.md` (e.g., `BUG-2026-0013.md`, `REQ-2026-0001.md`, `IMP-2026-0001.md`, `LIM-0008.md`, `OPT-0007.md`)

**Index files** (`track-*.md`) represent ONLY the current situation:
- They contain ONLY open/in-progress items
- When an item is RESOLVED, it is removed from the index file
- The item's dedicated file remains in `docs/tracking/items/` until release

**Release directories** contain everything that was released:
- `docs/releases/v{X.Y.Z}/` - one directory per release
- Contains: `release-notes.md` + all item files included in that release (MOVED from `docs/tracking/items/`)

#### Lifecycle of each item

1. **OPEN**: Create dedicated file with description
2. **ANALYZED**: Add technical analysis section:
   - Root cause / technical description
   - Suggested approach
   - Affected modules and files
   - Estimated effort and impact
3. **IN_PROGRESS**: Implementation started, code tagged with START_CHANGE/END_CHANGE
4. **RESOLVED**: Add solution documentation section:
   - Implementation description
   - Files modified with summary of changes
   - Test case created (MANDATORY)
   - **Remove** the item from the index file (track-*.md) — index shows only open items
5. **CLOSED**: Item file **moved** from `docs/tracking/items/` to `docs/releases/v{X.Y.Z}/`. Summary added to release notes.
6. **RELEASED**: Version tagged and pushed. Item marked as RELEASED in its file.

#### Item file template

```markdown
# <ID>: <Title>

**Type**: Bug / Request / Improvement / Limitation / Optimization
**Status**: TO_ANALYZE | IN_ANALYSIS | IN_PROGRESS | RESOLVED | CLOSED | RELEASED
**Severity**: CRITICAL | HIGH | MEDIUM | LOW | COSMETIC
**Created**: YYYY-MM-DD
**Resolved**: YYYY-MM-DD (when resolved)

## Description
<What the problem/limitation/optimization is>

## Technical Analysis
<Root cause, affected bytecode patterns, relevant JVM spec sections>

### Suggested Approach
<How to fix it>

### Affected Modules
<List of files/classes that need changes>

### Estimated Effort
<LOW / MEDIUM / HIGH>

## Solution Implemented
<What was actually done>

### Files Modified
- `path/to/File.java` - Description of change

### Test Case
- Test class: `<TestClassName>`
- Test method: `<methodName>`
- Verifies: <what the test checks>
```

#### Status flows

All types follow the same flow: `TO_ANALYZE` -> `IN_ANALYSIS` -> `IN_PROGRESS` -> `RESOLVED` -> `CLOSED` -> `RELEASED`

- **Bug**: Defect found → analyzed → fixed → test added → closed → released
- **Request**: Feature requested → analyzed → implemented → test added → closed → released
- **Improvement**: Enhancement identified → analyzed → implemented → test added → closed → released
- **Limitation**: Discovered → analyzed → fixed or documented as permanent → closed → released
- **Optimization**: Opportunity found → analyzed → implemented → benchmarked → closed → released

Limitations that are IMPOSSIBLE to fix (e.g., data not in class file) remain as **permanent** with explanation.

The `RELEASED` status is applied during the Release Process when the version tag is created and pushed.

### Resolution Requirements (MANDATORY when resolving an item)

1. **Tag the code changes** with START_CHANGE/END_CHANGE
2. **Document the implementation** in the item's dedicated file (Solution Implemented section)
3. **Create a test case** that specifically validates the fix and prevents regression
4. **Remove the item from the index file** (track-bugs.md, etc.) — index files show only open items
5. **Move the item file** from `docs/tracking/items/` to `docs/releases/v{X.Y.Z}/`
6. **Add summary** to `docs/releases/v{X.Y.Z}/release-notes.md`

### Code Change Tagging

All code modifications must be tagged:
```java
// START_CHANGE: <ISSUE_ID>-<DATE>-<PROGRESSIVE> - Description
// ... modified code ...
// END_CHANGE: <ISSUE_ID>-<PROGRESSIVE>
```

These tags are load-bearing documentation in this codebase: most non-obvious branches in the converter
and the transformers are explained only by their START_CHANGE comment naming the bug it fixes. Read the
surrounding tag before changing such a branch, and never strip tags while refactoring.

### Documentation Updates (MANDATORY per release)

When adding/modifying public API classes or decompiler features, update:
- `README.md` - Feature list, usage examples, limitations
- `ROADMAP.md` - Future plans and priorities
- `docs/references/ref-api.md` - ClassPool/CtClass/CtMethod API reference
- `docs/references/ref-cli.md` - CLI options and usage
- `docs/references/ref-decompilation-architecture.md` - Technical architecture

All documentation must be in English. Naming convention: `[category-]descriptive-name.md` (lowercase, hyphens, no underscores).

Doc directories:
- `docs/guides/guide-*.md` - User and developer guides
- `docs/references/ref-*.md` - Technical references
- `docs/reports/report-*.md` - Analysis and audit reports
- `docs/tracking/track-*.md` - Index files (current open items only)
- `docs/tracking/items/<ID>.md` - Item files not yet released
- `docs/releases/v{X.Y.Z}/` - Released items and release notes per version

### Starting a New Release

When beginning work on a new version:

1. Increment version in `pom.xml` and `DenzoDecompiler.getVersion()`
2. Create the release directory: `docs/releases/v{X.Y.Z}/`
3. Create an empty `docs/releases/v{X.Y.Z}/release-notes.md` with the version header

This way, when items are resolved during development, we already know where to move their files at release time.

**The released version is the newest `git tag`, not the newest `docs/releases/` directory.** A
release directory whose version is above the highest tag is the *in-flight* release: add resolved
items to it and finish it, do not bump past it. As of this writing the highest tag is **v1.13.0**
(released 2026-10-30), so the next work starts v1.14.0 (bump the version first, see *Starting a New
Release*).

### Resolving Items (during development)

When an item reaches RESOLVED status:
1. Document the solution in the item file
2. Create the test case
3. **Remove** the item from the index file (track-*.md shows only open items)
4. **Move** the item file from `docs/tracking/items/` to `docs/releases/v{X.Y.Z}/`
5. **Add** a summary line to `docs/releases/v{X.Y.Z}/release-notes.md`
6. Mark status as CLOSED in the item file

#### Severity: when sources disagree, the more critical one wins

An item's severity often has two sources — the index row and an audit report, or an audit and the item
file. **Always take the more critical of the two**, and record in the item that you did and what the
other source said. A range (`LOW-MEDIUM`) resolves to its upper end. Never split the difference, and
never silently pick the lower one.

Under-rating costs more than over-rating here: severity is what decides whether an item is looked at
at all, and this project's defect classes are the kind that ship silently. Worked examples:
BUG-2026-0111 (audit HIGH vs index MEDIUM → HIGH) and BUG-2026-0116 (audit LOW-MEDIUM vs index
LOW → MEDIUM).

After changing a severity, sync it in BOTH places — the item file header and the index row. This
lists any that have drifted apart:

```bash
for f in docs/tracking/items/BUG-*.md; do id=$(basename $f .md)
  fs=$(grep -m1 "^\*\*Severity\*\*" $f | sed 's/^\*\*Severity\*\*: *//;s/ (.*//')
  is=$(grep -hE "^\| $id \|" docs/tracking/track-bugs.md | awk -F'|' '{print $3}' | tr -d ' ')
  [ "$fs" = "$is" ] || echo "DIFF $id file=$fs index=$is"
done
```

(`track-limitations.md` and `track-optimizations.md` have no severity column — a scanner that assumes
column 3 is severity will report false positives there.)

#### Partially resolved items: CLOSE the original and SPLIT it in two

Partial resolution is common here — a fix lands for `-g:none` but not for line-bearing classes, or for
two label shapes out of three. **There is no PARTIALLY_RESOLVED status.** The original item is closed
and replaced by two new items:

1. Set the original to **CLOSED**, replace its body with a short split record — a table naming the two
   successors and one sentence on why the halves are different problems — and move it to the release
   directory. It keeps no content of its own.
2. **Resolved half** → a new ID, **CLOSED**, in `docs/releases/v{X.Y.Z}/`. Carries the description,
   analysis, solution, files modified, test case and measured results.
3. **Open half** → a new ID, still open, in `docs/tracking/items/`, and listed in the index
   (`track-*.md`). Carries what remains, plus **what was already tried and why it was not shipped** —
   that section is the most valuable thing in the file, since the next attempt starts by not repeating it.

Both new items carry a `**Parent/related**:` line naming the original and each other. Worked example:
BUG-2026-0122 → BUG-2026-0125 (closed) + BUG-2026-0126 (open), and BUG-2026-0123 → BUG-2026-0127
(closed) + BUG-2026-0128 (open), all in `docs/releases/v1.13.0/` and `docs/tracking/items/`.

An item file is never copied — one ID, one file, one location. Two copies of one ID with divergent
status is a tracking bug: `for f in docs/tracking/items/*.md; do ls docs/releases/*/$(basename $f)
2>/dev/null; done` lists any that exist.

### Publishing a Release

**Build and verify:**
1. `JAVA_HOME=<jdk8> mvn clean compile` (must succeed)
2. Run `DecompilerTest` with the JDK 25 javac path (80/81 — only the known `BasicClass` expectation may fail)
3. Run the all-class decompilation test (0 errors required)
4. Run the construct-matrix round trip (compile → decompile → recompile clean)
5. `JAVA_HOME=<jdk8> mvn clean package` to produce the JAR

**Prepare release artifacts:**
6. Verify index files contain ONLY open items (no resolved/closed items)
7. Verify `docs/releases/v{X.Y.Z}/` contains all resolved items and release-notes.md
8. Update `CHANGELOG.md`
9. Mark all items in the release directory as RELEASED

**Push to GitHub:**
10. `git add -A && git commit -m "Release v{X.Y.Z}"`
11. `git push origin main`
12. Create git tag: `git tag -a v{X.Y.Z} -m "Release v{X.Y.Z}"`
13. Push tag: `git push origin v{X.Y.Z}`
14. Create GitHub Release with JAR artifact:
    ```bash
    gh release create v{X.Y.Z} target/java-decompiler-{X.Y.Z}.jar \
      --title "v{X.Y.Z}" \
      --notes-file docs/releases/v{X.Y.Z}/release-notes.md
    ```

Versioning: major = breaking changes, minor = new features, patch = bug fixes (auto-increment per session).

#### Directory structure after release

```
docs/
  tracking/
    track-bugs.md              ← ONLY open bugs
    track-requests.md          ← ONLY open requests
    track-improvements.md      ← ONLY open improvements
    track-limitations.md       ← ONLY open + permanent limitations
    track-optimizations.md     ← ONLY open optimizations
    items/                     ← ONLY items NOT YET released (in progress or closed)
  releases/
    v1.1.0/
      release-notes.md         ← Summary of v1.1.0
      BUG-2026-0001.md         ← Released item files (moved from items/)
      BUG-2026-0002.md
      LIM-0001.md
      ...
    v1.2.0/
      release-notes.md
      ...
```

## Code Constraints

- **Java 1.6 source compatibility**: No diamond operator `<>`, no lambda, no try-with-resources, no switch on String, no `getOrDefault`, no `List.of`, no `String.repeat`, no `@Override` on interface methods
- **Zero dependencies**: No external libraries. Only JDK classes.
- **Thread safety**: `DenzoDecompiler` is safe to share across threads, and decompilation is deterministic — the same input always yields byte-identical output. `decompile()` builds a fresh `ClassFileToJavaSyntaxConverter` AND a fresh `JavaSourceWriter` per call, because both hold per-class mutable state. Keep it that way: **per-method or per-class state must never live in a static field**. One such field (`BooleanSimplifier.methodReturnIsBoolean`) made batch output differ in 211 of 3,376 `java.base` classes run to run, sometimes emitting `return 0;` from a boolean method (BUG-2026-0124). `testThreadSafetyDeterminism` guards this; if you add per-call state, put it on the instance or in a `ThreadLocal` with save/restore.
- **GPLv3 license header** on all source files

## Architecture

Pipeline: `Deserializer` -> `CFG Builder` -> `StructuredFlowBuilder` -> `Transformers` -> `Writer`

Stages communicate through a `Message` header map, not method arguments: the deserializer reads
`loader`/`mainInternalTypeName` and sets `classFile`; the converter sets `javaSyntaxResult`; the writer
reads `printer` and `configuration`. CLI flags reach the writer as `configuration` booleans
(`showBytecode`, `showNativeInfo`, `deobfuscate`); line alignment vs `--compact` is a writer mode.

Key files:
- `DenzoDecompiler.java` - Orchestrator (per-thread entry point), wraps each stage failure in `DecompilationException`
- `Main.java` / `BatchDecompiler.java` - CLI dispatch; batch uses a thread pool whose threads get a **2MB stack** for archive input (deep recursion on complex JDK classes); directory input uses default-stack threads
- `ClassFileToJavaSyntaxConverter.java` (~5000 lines) - Bytecode decoder (stack simulation), flow-pipeline selection, transform orchestration
- `StructuredFlowBuilder.java` (~3100 lines) - Legacy CFG pattern matching (if/while/for/switch/ternary/do-while)
- `service/converter/cfg/jd/` - Ported JD-Core pipeline: `ControlFlowGraphMaker` → `ControlFlowGraphReducer` (+ loop/goto reducers) → `JdFlowBuilder` emitter
- `service/converter/transform/` - Post-processing AST rewriters (see below)
- `JavaSourceWriter.java` (~4400 lines) - Source generator; also where `DeobfuscationTransformer` runs
- `api/classmodel/` - Javassist-like API (ClassPool, CtClass, CtMethod, CtField)

### Two flow pipelines (important)

Method bodies can be structured by either builder, chosen per method in
`ClassFileToJavaSyntaxConverter`:

- **Legacy `StructuredFlowBuilder`** — the default; pattern-matches the CFG.
- **JD pipeline** (`cfg/jd/`) — physically reduces the CFG graph. Enabled globally by
  `-Ddenzo.jd.pipeline=true` or `DENZO_JD_PIPELINE=1`, and selectively for `typeSwitch`
  record-pattern methods, which the legacy path structurally cannot reconstruct.

The JD result passes a **per-method quality gate** before being kept: it is discarded (falling back to
legacy) if it produced new diagnostics, emitted placeholder comments, or — for selectively-routed
record-switch methods — failed to actually fold the record-deconstruction switch. Because the JD decode
mutates per-method decode state (`patternSwitchLabels`, `declaredVars`, slot-split maps), that state is
snapshotted and **restored** before falling through; if you add per-method decode state, add it to that
snapshot/restore set or the legacy fallback will emit bare assignments instead of declarations.

The full fallback chain is JD → structured → linear scan, and each downgrade records a diagnostic
(`JD_PIPELINE_FAILED`, `STRUCTURED_FLOW_FAILED`, `STACK_UNDERFLOW`, `DECODE_ERROR`) that surfaces in
the output. Guiding principle from `JdFlowBuilder`: *"Every truncation is a grave error"* — unreduced
blocks are emitted inline, never silently dropped. Prefer degraded-but-complete output over losing
statements, and prefer a recorded diagnostic over a silent miscompilation.

**The JD pipeline is being RETIRED** — decided 2026-09-07, tracked as IMP-2026-0072 (ROADMAP §3).
Measurement showed it loses control flow outright: with the flag on a `for` becomes a single
`if`/`else return` with the back-edge dropped, which compiles in a `void` method and is silently
wrong. Do not invest in it, and do not build new work on it. It is still in the tree because its
selective route is the only thing that folds record-deconstruction switches today; the retirement is
sequenced freeze → port the value merge to legacy (BUG-2026-0128) → route to legacy → delete
`cfg/jd/`. Read IMP-2026-0072 before touching either builder.

### Transform chain

The post-processing sequence is **written out twice** in `ClassFileToJavaSyntaxConverter` — once for
the JD result and once for the legacy result. A new transform generally has to be added to both, and
order matters (e.g. `RecordPatternReconstructor` must strip `MatchException` scaffolding before
`InstanceOfPatternReconstructor`, and `RecordDeconstructionFolder` runs after both). The legacy chain
additionally runs `TryCatchReconstructor` (JD reduces tries itself), `SwitchVarHoister` and
`BranchVarHoister`; the JD chain additionally runs `TypeSwitchRecordFolder`. `AstLocalRewriter` is not
a pass but a reusable visitor base used inline by the converter, writer and hoisters.

## Testing

- `DecompilerTest.java` - Automated tests (compile with JDK 25, decompile, assert on output substrings)
- `PerformanceBenchmark.java` - Throughput and per-class timing
- `src/test/resources/construct-matrix/` - Java 1.0-25 construct corpus for strict recompile round trips
- All-class test: decompile every .class in target/classes, expect 0 errors
- Each resolved issue MUST have a corresponding test case
