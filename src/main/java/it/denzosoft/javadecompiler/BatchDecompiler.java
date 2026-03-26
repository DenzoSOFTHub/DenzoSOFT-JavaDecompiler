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

    public BatchDecompiler(File outputDir, int threadCount) {
        this(outputDir, threadCount, true, false, false);
    }

    public BatchDecompiler(File outputDir, int threadCount, boolean alignLines) {
        this(outputDir, threadCount, alignLines, false, false);
    }

    public BatchDecompiler(File outputDir, int threadCount, boolean alignLines,
                           boolean showBytecode, boolean showNativeInfo) {
        this.outputDir = outputDir;
        this.threadCount = threadCount;
        this.alignLines = alignLines;
        this.showBytecode = showBytecode;
        this.showNativeInfo = showNativeInfo;
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
            // Collect all class entries
            List<JarEntry> classEntries = new ArrayList<JarEntry>();
            Enumeration entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = (JarEntry) entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    classEntries.add(entry);
                }
            }

            result.totalClasses = classEntries.size();

            // Pre-read all class data (JarFile is not thread-safe for concurrent reads)
            final List<String> classNames = new ArrayList<String>();
            final List<byte[]> classDataList = new ArrayList<byte[]>();
            for (int i = 0; i < classEntries.size(); i++) {
                JarEntry entry = classEntries.get(i);
                String entryName = entry.getName();
                String className = entryName.substring(0, entryName.length() - 6); // remove .class
                classNames.add(className);
                InputStream is = jar.getInputStream(entry);
                try {
                    classDataList.add(readAllBytes(is));
                } finally {
                    is.close();
                }
            }

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
                    final int idx = i;
                    final String className = classNames.get(idx);
                    final byte[] classData = classDataList.get(idx);

                    futures.add(executor.submit(new Callable<Object>() {
                        public Object call() {
                            try {
                                decompileClass(className, classData);
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

        // Collect all .class files recursively
        List<File> classFiles = new ArrayList<File>();
        collectClassFiles(classDir, classFiles);
        result.totalClasses = classFiles.size();

        // Process with thread pool
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future> futures = new ArrayList<Future>();
            for (int i = 0; i < classFiles.size(); i++) {
                final File classFile = classFiles.get(i);
                final String relativePath = getRelativePath(classDir, classFile);
                final String className = relativePath.substring(0, relativePath.length() - 6); // remove .class

                futures.add(executor.submit(new Callable<Object>() {
                    public Object call() {
                        try {
                            FileInputStream fis = new FileInputStream(classFile);
                            byte[] data;
                            try {
                                data = readAllBytes(fis);
                            } finally {
                                fis.close();
                            }
                            decompileClass(className, data);
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

    private void decompileClass(String className, final byte[] classData) throws Exception {
        DenzoDecompiler decompiler = new DenzoDecompiler();
        StringCollector collector = new StringCollector(alignLines);

        Loader loader = new Loader() {
            public boolean canLoad(String internalName) {
                return true;
            }

            public byte[] load(String internalName) {
                return classData;
            }
        };

        Map<String, Object> config = null;
        if (showBytecode || showNativeInfo) {
            config = new HashMap<String, Object>();
            if (showBytecode) config.put("showBytecode", Boolean.TRUE);
            if (showNativeInfo) config.put("showNativeInfo", Boolean.TRUE);
        }
        decompiler.decompile(loader, collector, className, config);

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
