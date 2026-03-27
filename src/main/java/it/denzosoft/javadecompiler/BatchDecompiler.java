/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Batch decompiler for processing entire JAR files or directories.
 * Supports parallel decompilation using thread pools.
 */
public class BatchDecompiler {
    private int threadCount;
    private File outputDir;
    // START_CHANGE: IMP-LINES-20260326-3 - Add output options
    private boolean alignLines;
    private boolean showBytecode;
    private boolean showNativeInfo;
    private boolean deobfuscate;

    public BatchDecompiler(File outputDir, int threadCount) {
        this(outputDir, threadCount, true, false, false, false);
    }

    public BatchDecompiler(File outputDir, int threadCount, boolean alignLines) {
        this(outputDir, threadCount, alignLines, false, false, false);
    }

    public BatchDecompiler(File outputDir, int threadCount, boolean alignLines,
                           boolean showBytecode, boolean showNativeInfo) {
        this(outputDir, threadCount, alignLines, showBytecode, showNativeInfo, false);
    }

    public BatchDecompiler(File outputDir, int threadCount, boolean alignLines,
                           boolean showBytecode, boolean showNativeInfo, boolean deobfuscate) {
        this.outputDir = outputDir;
        this.threadCount = threadCount;
        this.alignLines = alignLines;
        this.showBytecode = showBytecode;
        this.showNativeInfo = showNativeInfo;
        this.deobfuscate = deobfuscate;
    }
    // END_CHANGE: IMP-LINES-3

    /**
     * Decompile all classes in a JAR file to the output directory.
     * Returns a BatchResult with statistics.
     */
    public BatchResult decompileJar(File jarFile) throws Exception {
        long startTime = System.currentTimeMillis();
        final BatchResult result = new BatchResult();
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());

        final JarFile jar = new JarFile(jarFile);
        try {
            // START_CHANGE: BUG-2026-0037-20260327-1 - Load all classes, decompile only top-level
            // Pre-read ALL class data into a shared map (for inner class resolution)
            final Map<String, byte[]> allClassData = new HashMap<String, byte[]>();
            List<String> topLevelClasses = new ArrayList<String>();
            Enumeration entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = (JarEntry) entries.nextElement();
                String entryName = entry.getName();
                if (entryName.endsWith(".class")) {
                    String className = entryName.substring(0, entryName.length() - 6);
                    InputStream is = jar.getInputStream(entry);
                    try {
                        allClassData.put(className, readAllBytes(is));
                    } finally {
                        is.close();
                    }
                    // Only decompile top-level classes (inner classes are inlined by the decompiler)
                    if (className.indexOf('$') < 0) {
                        topLevelClasses.add(className);
                    }
                }
            }

            final List<String> classNames = topLevelClasses;
            result.totalClasses = classNames.size();
            // END_CHANGE: BUG-2026-0037-1

            // Process with thread pool
            // START_CHANGE: BUG-2026-0032-20260325-7 - Use ThreadFactory with 2MB stack for complex JDK classes
            ExecutorService executor = Executors.newFixedThreadPool(threadCount, new java.util.concurrent.ThreadFactory() {
                private int counter = 0;
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(null, r, "decompiler-" + (counter++), 2 * 1024 * 1024); // 2MB stack
                    t.setDaemon(true);
                    return t;
                }
            });
            // END_CHANGE: BUG-2026-0032-7
            try {
                List<Future> futures = new ArrayList<Future>();
                for (int i = 0; i < classNames.size(); i++) {
                    final String className = classNames.get(i);

                    futures.add(executor.submit(new Callable<Object>() {
                        public Object call() {
                            try {
                                decompileClassWithLoader(className, allClassData);
                            } catch (Exception e) {
                                errors.add(className);
                            }
                            return null;
                        }
                    }));
                }

                // Wait for all tasks
                for (int i = 0; i < futures.size(); i++) {
                    futures.get(i).get();
                }
            } finally {
                executor.shutdown();
            }
        } finally {
            jar.close();
        }

        result.errorCount = errors.size();
        result.successCount = result.totalClasses - result.errorCount;
        result.errors = errors;
        result.totalTimeMs = System.currentTimeMillis() - startTime;
        return result;
    }

    /**
     * Decompile all .class files in a directory.
     */
    public BatchResult decompileDirectory(File classDir) throws Exception {
        long startTime = System.currentTimeMillis();
        final BatchResult result = new BatchResult();
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());

        // START_CHANGE: BUG-2026-0037-20260327-4 - Pre-load all classes for inner class resolution
        List<File> allClassFiles = new ArrayList<File>();
        collectClassFiles(classDir, allClassFiles);
        final Map<String, byte[]> allClassData = new HashMap<String, byte[]>();
        List<String> topLevelClasses = new ArrayList<String>();
        for (int i = 0; i < allClassFiles.size(); i++) {
            File classFile = allClassFiles.get(i);
            String relativePath = getRelativePath(classDir, classFile);
            String className = relativePath.substring(0, relativePath.length() - 6);
            FileInputStream fis = new FileInputStream(classFile);
            try {
                allClassData.put(className, readAllBytes(fis));
            } finally {
                fis.close();
            }
            if (className.indexOf('$') < 0) {
                topLevelClasses.add(className);
            }
        }
        result.totalClasses = topLevelClasses.size();
        // END_CHANGE: BUG-2026-0037-4

        // Process with thread pool
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future> futures = new ArrayList<Future>();
            for (int i = 0; i < topLevelClasses.size(); i++) {
                final String className = topLevelClasses.get(i);

                futures.add(executor.submit(new Callable<Object>() {
                    public Object call() {
                        try {
                            decompileClassWithLoader(className, allClassData);
                        } catch (Exception e) {
                            errors.add(className);
                        }
                        return null;
                    }
                }));
            }

            // Wait for all tasks
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).get();
            }
        } finally {
            executor.shutdown();
        }

        result.errorCount = errors.size();
        result.successCount = result.totalClasses - result.errorCount;
        result.errors = errors;
        result.totalTimeMs = System.currentTimeMillis() - startTime;
        return result;
    }

    // START_CHANGE: BUG-2026-0037-20260327-3 - Loader with access to all classes for inner class resolution
    private void decompileClassWithLoader(String className, final Map<String, byte[]> allClassData) throws Exception {
        DenzoDecompiler decompiler = new DenzoDecompiler();
        StringCollector collector = new StringCollector(alignLines);

        Loader loader = new Loader() {
            public boolean canLoad(String internalName) {
                return allClassData.containsKey(internalName);
            }

            public byte[] load(String internalName) {
                return (byte[]) allClassData.get(internalName);
            }
        };

        Map<String, Object> config = null;
        if (showBytecode || showNativeInfo || deobfuscate) {
            config = new HashMap<String, Object>();
            if (showBytecode) config.put("showBytecode", Boolean.TRUE);
            if (showNativeInfo) config.put("showNativeInfo", Boolean.TRUE);
            if (deobfuscate) config.put("deobfuscate", Boolean.TRUE);
        }
        decompiler.decompile(loader, collector, className, config);
    // END_CHANGE: BUG-2026-0037-3

        // Write output maintaining package directory structure
        String outputPath = className.replace('/', File.separatorChar) + ".java";
        File outFile = new File(outputDir, outputPath);
        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            Writer writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(collector.getResult());
            writer.flush();
        } finally {
            fos.close();
        }
    }

    private void collectClassFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                collectClassFiles(f, result);
            } else if (f.getName().endsWith(".class")) {
                result.add(f);
            }
        }
    }

    private String getRelativePath(File base, File file) {
        String basePath = base.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(basePath)) {
            String rel = filePath.substring(basePath.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return rel.replace(File.separatorChar, '/');
        }
        return file.getName();
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    public static class BatchResult {
        public int totalClasses;
        public int successCount;
        public int errorCount;
        public long totalTimeMs;
        public List<String> errors;
    }

    /**
     * Simple Printer that collects output into a String.
     */
    // START_CHANGE: IMP-LINES-20260326-4 - Compact/aligned line modes for batch decompiler
    private static class StringCollector implements Printer {
        private final StringBuilder sb = new StringBuilder();
        private int indentLevel = 0;
        private int currentLine = 1;
        private final boolean alignLines;
        private static final String INDENT = "    ";

        StringCollector(boolean alignLines) {
            this.alignLines = alignLines;
        }

        public void start(int maxLineNumber, int majorVersion, int minorVersion) { currentLine = 1; }
        public void end() {}

        public void printText(String text) { sb.append(text); }
        public void printNumericConstant(String constant) { sb.append(constant); }
        public void printStringConstant(String constant, String ownerInternalName) { sb.append(constant); }
        public void printKeyword(String keyword) { sb.append(keyword); }

        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            sb.append(name);
        }

        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            sb.append(name);
        }

        public void indent() { indentLevel++; }
        public void unindent() { indentLevel--; }

        public void startLine(int lineNumber) {
            if (alignLines && lineNumber > 0 && lineNumber > currentLine) {
                int gap = lineNumber - currentLine;
                for (int g = 0; g < gap; g++) sb.append("\n");
                currentLine = lineNumber;
            }
            for (int i = 0; i < indentLevel; i++) sb.append(INDENT);
        }

        public void endLine() {
            sb.append("\n");
            currentLine++;
        }

        public void extraLine(int count) {
            for (int i = 0; i < count; i++) { sb.append("\n"); currentLine++; }
        }

        public void startMarker(int type) {}
        public void endMarker(int type) {}

        public String getResult() { return sb.toString(); }
    }
    // END_CHANGE: IMP-LINES-4
}
