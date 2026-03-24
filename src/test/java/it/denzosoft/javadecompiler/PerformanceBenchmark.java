/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Performance benchmark for the DenzoSOFT Java Decompiler.
 *
 * Usage: java PerformanceBenchmark <jar-or-class-dir> [warmup-rounds] [measure-rounds]
 *
 * Measures:
 * - Total decompilation time
 * - Per-class average time
 * - Peak memory usage
 * - Classes per second throughput
 * - Fastest / slowest class times
 */
public class PerformanceBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java PerformanceBenchmark <jar-file-or-class-dir> [warmup] [rounds]");
            System.exit(1);
        }

        String path = args[0];
        int warmupRounds = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int measureRounds = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        // Collect all class entries
        List<ClassEntry> classes = collectClasses(path);
        System.out.println("DenzoSOFT Java Decompiler - Performance Benchmark");
        System.out.println("==================================================");
        System.out.println("Source: " + path);
        System.out.println("Classes found: " + classes.size());
        System.out.println("Warmup rounds: " + warmupRounds);
        System.out.println("Measure rounds: " + measureRounds);
        System.out.println();

        // Warmup
        System.out.print("Warming up...");
        for (int w = 0; w < warmupRounds; w++) {
            decompileAll(classes, false);
            System.out.print(".");
        }
        System.out.println(" done");
        System.out.println();

        // Force GC before measurement
        System.gc();
        Thread.sleep(500);

        // Measure
        long[] roundTimes = new long[measureRounds];
        int[] roundErrors = new int[measureRounds];
        long[] perClassTimes = null;
        int totalErrors = 0;

        for (int r = 0; r < measureRounds; r++) {
            System.gc();
            Thread.sleep(200);

            long memBefore = getUsedMemory();
            long startTime = System.nanoTime();

            int errors = decompileAll(classes, false);

            long endTime = System.nanoTime();
            long memAfter = getUsedMemory();

            roundTimes[r] = endTime - startTime;
            roundErrors[r] = errors;
            totalErrors += errors;

            double ms = roundTimes[r] / 1_000_000.0;
            double memMB = (memAfter - memBefore) / (1024.0 * 1024.0);
            System.out.printf("  Round %d: %.1f ms, %d errors, mem delta ~%.1f MB%n",
                r + 1, ms, errors, memMB);
        }

        // Per-class timing (single detailed round)
        System.out.println();
        System.out.println("Per-class timing (detailed round)...");
        final long[] perClassTimesFinal = new long[classes.size()];
        perClassTimes = perClassTimesFinal;
        String[] classNames = new String[classes.size()];
        NullPrinter np = new NullPrinter();

        for (int i = 0; i < classes.size(); i++) {
            ClassEntry ce = classes.get(i);
            classNames[i] = ce.name;
            long t1 = System.nanoTime();
            try {
                DenzoDecompiler decompiler = new DenzoDecompiler();
                final byte[] data = ce.data;
                final String name = ce.name;
                Loader loader = new Loader() {
                    public boolean canLoad(String n) { return true; }
                    public byte[] load(String n) { return data; }
                };
                decompiler.decompile(loader, np, name);
            } catch (Exception e) {
                // ignore
            }
            perClassTimes[i] = System.nanoTime() - t1;
        }

        // Results
        System.out.println();
        System.out.println("==================================================");
        System.out.println("RESULTS");
        System.out.println("==================================================");

        // Sort round times
        long[] sorted = roundTimes.clone();
        Arrays.sort(sorted);
        long median = sorted[measureRounds / 2];
        long best = sorted[0];
        long worst = sorted[measureRounds - 1];
        long total = 0;
        for (long t : roundTimes) total += t;
        long avg = total / measureRounds;

        double medianMs = median / 1_000_000.0;
        double bestMs = best / 1_000_000.0;
        double worstMs = worst / 1_000_000.0;
        double avgMs = avg / 1_000_000.0;
        double classesPerSec = classes.size() / (medianMs / 1000.0);
        double avgPerClass = medianMs / classes.size();

        System.out.printf("Classes:          %d%n", classes.size());
        System.out.printf("Errors:           %d / %d%n", totalErrors / measureRounds, classes.size());
        System.out.printf("Median time:      %.1f ms%n", medianMs);
        System.out.printf("Best time:        %.1f ms%n", bestMs);
        System.out.printf("Worst time:       %.1f ms%n", worstMs);
        System.out.printf("Average time:     %.1f ms%n", avgMs);
        System.out.printf("Avg per class:    %.2f ms%n", avgPerClass);
        System.out.printf("Throughput:       %.0f classes/sec%n", classesPerSec);

        // Memory snapshot
        System.gc();
        Thread.sleep(200);
        long usedMem = getUsedMemory();
        System.out.printf("Heap used (post): %.1f MB%n", usedMem / (1024.0 * 1024.0));

        // Top 5 slowest classes
        System.out.println();
        System.out.println("Top 10 slowest classes:");
        Integer[] indices = new Integer[classes.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                long diff = perClassTimesFinal[b.intValue()] - perClassTimesFinal[a.intValue()];
                return diff > 0 ? 1 : (diff < 0 ? -1 : 0);
            }
        });
        for (int i = 0; i < Math.min(10, indices.length); i++) {
            int idx = indices[i].intValue();
            System.out.printf("  %.2f ms  %s (%d bytes)%n",
                perClassTimes[idx] / 1_000_000.0, classNames[idx], classes.get(idx).data.length);
        }

        // Top 5 fastest
        System.out.println();
        System.out.println("Top 5 fastest classes:");
        for (int i = Math.max(0, indices.length - 5); i < indices.length; i++) {
            int idx = indices[i].intValue();
            System.out.printf("  %.2f ms  %s (%d bytes)%n",
                perClassTimes[idx] / 1_000_000.0, classNames[idx], classes.get(idx).data.length);
        }

        System.out.println();
        System.out.println("==================================================");
    }

    private static int decompileAll(List<ClassEntry> classes, boolean verbose) {
        int errors = 0;
        NullPrinter printer = new NullPrinter();
        for (ClassEntry ce : classes) {
            try {
                DenzoDecompiler decompiler = new DenzoDecompiler();
                final byte[] data = ce.data;
                final String name = ce.name;
                Loader loader = new Loader() {
                    public boolean canLoad(String n) { return true; }
                    public byte[] load(String n) { return data; }
                };
                decompiler.decompile(loader, printer, name);
            } catch (Exception e) {
                errors++;
                if (verbose) {
                    System.err.println("ERROR: " + ce.name + " - " + e.getMessage());
                }
            }
        }
        return errors;
    }

    private static List<ClassEntry> collectClasses(String path) throws Exception {
        List<ClassEntry> result = new ArrayList<ClassEntry>();
        File f = new File(path);
        if (f.isDirectory()) {
            collectFromDir(f, f, result);
        } else if (path.endsWith(".jar")) {
            collectFromJar(path, result);
        } else if (path.endsWith(".class")) {
            byte[] data = readFile(f);
            String name = f.getName().replace(".class", "");
            result.add(new ClassEntry(name, data));
        }
        return result;
    }

    private static void collectFromDir(File root, File dir, List<ClassEntry> result) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFromDir(root, f, result);
            } else if (f.getName().endsWith(".class")) {
                byte[] data = readFile(f);
                String relPath = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                String name = relPath.replace(".class", "").replace(File.separatorChar, '/');
                result.add(new ClassEntry(name, data));
            }
        }
    }

    private static void collectFromJar(String jarPath, List<ClassEntry> result) throws Exception {
        JarFile jar = new JarFile(jarPath);
        try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    try {
                        byte[] data = readStream(is);
                        String name = entry.getName().replace(".class", "");
                        result.add(new ClassEntry(name, data));
                    } finally {
                        is.close();
                    }
                }
            }
        } finally {
            jar.close();
        }
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            return readStream(fis);
        } finally {
            fis.close();
        }
    }

    private static byte[] readStream(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static long getUsedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static class ClassEntry {
        final String name;
        final byte[] data;
        ClassEntry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    /**
     * Printer that discards all output (for timing without I/O overhead).
     */
    static class NullPrinter implements Printer {
        public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
        public void end() {}
        public void printText(String text) {}
        public void printNumericConstant(String constant) {}
        public void printStringConstant(String constant, String ownerInternalName) {}
        public void printKeyword(String keyword) {}
        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {}
        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {}
        public void indent() {}
        public void unindent() {}
        public void startLine(int lineNumber) {}
        public void endLine() {}
        public void extraLine(int count) {}
        public void startMarker(int type) {}
        public void endMarker(int type) {}
    }
}
