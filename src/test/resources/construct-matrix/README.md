# Construct Matrix — assurance corpus (Java 1.0–25)

Self-contained classes densely covering Java language constructs from 1.0 to 25, used to verify the decompiler
reproduces each construct (decompile → recompile round-trip). See `docs/reports/report-coverage-assurance.md`.

## Run
```bash
JDK=/path/to/jdk-25; CP=/path/to/decompiler/classes
$JDK/bin/javac -d /tmp/mx/cls src/test/resources/construct-matrix/*.java
for cf in /tmp/mx/cls/*.class; do case "$cf" in *\$*) continue;; esac
  b=$(basename "$cf" .class)
  $JDK/bin/java -cp "$CP" it.denzosoft.javadecompiler.Main --compact "$cf" > /tmp/mx/dec/$b.java
  $JDK/bin/javac -d /tmp/mx/rec -cp /tmp/mx/cls /tmp/mx/dec/$b.java   # 0 errors == round-trip
done
```
Baseline 2026-06-09: 28/55 top-level classes round-trip; 0 crashes. Breadth (1674 JDK 25 classes): 0 crashes,
93.3% marker-clean.
