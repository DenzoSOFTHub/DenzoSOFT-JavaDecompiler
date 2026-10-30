# Guide: corpus gates (JDK 25 `java.base`)

Two gates that run over the full JDK 25 `java.base` corpus in about 15 seconds together. They catch
what fixtures and the construct matrix cannot: whether a change helps or hurts across 3,376 classes.

Use them as CLAUDE.md rung 6 prescribes — always against the **baseline build** from rung 3 as well as
your own, because an absolute number means nothing without the before.

```bash
JDK25=/usr/lib/jvm/java-25-openjdk-amd64
W=/tmp/jbgate; rm -rf $W; mkdir -p $W
CLASSES=${CLASSES:-target/classes}          # point at the baseline dir to get the "before"

# 1. extract java.base (~1 s, 7,406 class files / 3,376 top-level)
$JDK25/bin/jimage extract --dir $W/cls $JDK25/lib/modules

# 2. batch-decompile (~3.5 s) - must report 0 errors
java -cp $CLASSES it.denzosoft.javadecompiler.Main --batch $W/cls/java.base $W/src

# 3. recompile as a module (~9 s). The layout must be <root>/java.base/<packages>.
mkdir -p $W/msp && cp -r $W/src $W/msp/java.base
$JDK25/bin/javac --system none --module-source-path $W/msp -d $W/out --module java.base \
    -nowarn -proc:none -Xmaxerrs 100000 2> $W/errors.txt

echo "errors: $(grep -c 'error:' $W/errors.txt)"
echo "files : $(grep -oE '^[^:]+\.java' $W/errors.txt | sort -u | wc -l) of 3376"
grep -oE 'error: .*' $W/errors.txt | sed 's/error: //' | awk '{print $1,$2,$3,$4,$5}' \
    | sort | uniq -c | sort -rn | head -15
```

**`-Xmaxerrs 100000` is not optional.** javac stops at 100 errors by default; without it the gate
reports 100 errors / 39 files no matter how bad the output is.

## Baseline, measured 2026-09-08 against the v1.13.0 tree

| | |
|---|---|
| Batch decompile | 3,376 classes, **0 errors**, 3.4 s |
| Recompile as a module | **14,286 errors across 965 files** (28.6% of the corpus) |

Top error shapes:

| Count | Message | Relates to |
|---|---|---|
| 7,728 | `cannot find symbol` | dominant; largely undeclared locals |
| 649 | `incomparable types: boolean and int` | boolean/int confusion |
| 539 / 218 / 198 | `incompatible types: int cannot be …` | same family |
| 438 | `incompatible types: possible lossy conversion` | integer constants into `byte`/`short`/`char` |
| 363 | `bad operand type int for …` | boolean/int confusion |
| 173 / 169 | `no suitable method/constructor found for` | argument typing |
| **123** | `break outside switch or loop` | loop emitted outside the construct it belongs to |

## A number that did not reproduce — pin the method

`docs/reports/report-java25-and-beyond-analysis.md` reports **13,685 errors / 846 files (25.1%)** for
this gate. The procedure above, on the same tree, gives **14,286 / 965 (28.6%)**. The build is the same,
so the difference is METHOD — most likely a different javac invocation (`--patch-module` versus
`--system none --module-source-path`), which changes which references resolve.

The lesson is not that one number is wrong. It is that this metric is only comparable against itself:
**quote it only alongside the exact command that produced it**, and when comparing a fix against a
baseline, run both through the identical procedure. Use the command in this guide, or record yours.
