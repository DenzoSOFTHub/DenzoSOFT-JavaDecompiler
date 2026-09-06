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
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn clean package   # Build target/java-decompiler-1.10.0.jar
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
`DecompilerTest.main()` — there is no test selector. Current baseline: **45/46**; `BasicClass` fails
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
committed runner; drive it with a shell loop.

**Run it in all three debug modes — they are three different products**, and a defect usually lives in
exactly one of them:
- `-g:source,lines` (javac default) — the most forgiving; current 55/55
- `-g` (full LocalVariableTable; what Maven/Gradle emit, so every production jar) — current 53/55
- `-g:none` (no LineNumberTable; stripped and obfuscated jars) — current 54/55

**Recompiling is not enough of an oracle.** Output can compile cleanly and still have lost a `catch`
clause, a lock, or a statement — the defect class this project ranks as the most severe. Pair the
round trip with a keyword census against the source (`catch (`, `finally`, `synchronized (`, `try (`)
and, for scale, count diagnostic markers over a large corpus. See
`docs/reports/report-java25-plus-audit.md` for the method and the current numbers.

**Running the decompiler:**
```bash
java -jar target/java-decompiler-1.10.0.jar                                   # GUI (default)
java -jar target/java-decompiler-1.10.0.jar <file.class>                      # CLI, line-aligned
java -jar target/java-decompiler-1.10.0.jar --compact <file.class>            # Compact output
java -jar target/java-decompiler-1.10.0.jar --show-bytecode <file.class>      # Inline bytecode instructions
java -jar target/java-decompiler-1.10.0.jar --show-native-info <file.class>   # JNI info on native methods
java -jar target/java-decompiler-1.10.0.jar --deobfuscate <file.class>        # Sanitize obfuscated names
java -jar target/java-decompiler-1.10.0.jar <file.jar> <ClassName>            # From JAR
java -jar target/java-decompiler-1.10.0.jar --batch <file.jar|dir> <out-dir>  # Batch (also .war/.ear/.apk)
java -jar target/java-decompiler-1.10.0.jar --gui [file.jar ...]              # GUI explicit
java -jar target/java-decompiler-1.10.0.jar --trace <dir> <file>              # Tracing
```

**Version is tracked in two places** (keep in sync): `pom.xml` `<version>` and `DenzoDecompiler.getVersion()`.

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

v1.10.0 is already released (`docs/releases/v1.10.0/` exists), so new work starts by bumping to the
next version.

### Resolving Items (during development)

When an item reaches RESOLVED status:
1. Document the solution in the item file
2. Create the test case
3. **Remove** the item from the index file (track-*.md shows only open items)
4. **Move** the item file from `docs/tracking/items/` to `docs/releases/v{X.Y.Z}/`
5. **Add** a summary line to `docs/releases/v{X.Y.Z}/release-notes.md`
6. Mark status as CLOSED in the item file

### Publishing a Release

**Build and verify:**
1. `JAVA_HOME=<jdk8> mvn clean compile` (must succeed)
2. Run `DecompilerTest` with the JDK 25 javac path (45/46 — only the known `BasicClass` expectation may fail)
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
- **Thread safety**: `ClassFileToJavaSyntaxConverter` and `JavaSourceWriter` hold mutable per-call state. `DenzoDecompiler.decompile()` creates a fresh converter each call; the writer is a shared instance field, so use one `DenzoDecompiler` per thread (this is what `BatchDecompiler` does — one per class task).
- **GPLv3 license header** on all source files

## Architecture

Pipeline: `Deserializer` -> `CFG Builder` -> `StructuredFlowBuilder` -> `Transformers` -> `Writer`

Stages communicate through a `Message` header map, not method arguments: the deserializer reads
`loader`/`mainInternalTypeName` and sets `classFile`; the converter sets `javaSyntaxResult`; the writer
reads `printer` and `configuration`. CLI flags reach the writer as `configuration` booleans
(`showBytecode`, `showNativeInfo`, `deobfuscate`); line alignment vs `--compact` is a writer mode.

Key files:
- `DenzoDecompiler.java` - Orchestrator (per-thread entry point), wraps each stage failure in `DecompilationException`
- `Main.java` / `BatchDecompiler.java` - CLI dispatch; batch uses a thread pool whose threads get a **2MB stack** (deep recursion on complex JDK classes)
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

ROADMAP §3 has an open decision on whether to invest the JD pipeline to parity or retire it — check
there before doing large work on either builder.

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
