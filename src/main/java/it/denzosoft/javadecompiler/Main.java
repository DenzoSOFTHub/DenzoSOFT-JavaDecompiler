/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler;

import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Command-line interface for the DenzoSOFT Java Decompiler.
 *
 * Usage: java -jar denzosoft-decompiler.jar <class-file-or-jar> [class-name]
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String path = args[0];

        if ("--gui".equals(path)) {
            String[] guiArgs = new String[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                guiArgs[i - 1] = args[i];
            }
            it.denzosoft.javadecompiler.gui.DecompilerGui.main(guiArgs);
            return;
        }

        if ("--version".equals(path) || "-v".equals(path)) {
            System.out.println("DenzoSOFT Java Decompiler v" + DenzoDecompiler.getVersion());
            System.out.println("Supports Java 1.0 through Java " + DenzoDecompiler.getMaxSupportedJavaVersion());
            return;
        }

        if ("--trace".equals(path)) {
            if (args.length < 3) {
                System.err.println("Error: --trace requires <trace-dir> <file.class|file.jar class-name>");
                System.exit(1);
            }
            try {
                File traceDir = new File(args[1]);
                DenzoDecompiler decompiler = new DenzoDecompiler();
                decompiler.setTraceDir(traceDir);
                StringPrinter printer = new StringPrinter();

                String target = args[2];
                if (target.endsWith(".class")) {
                    decompileClassFile(decompiler, printer, target);
                } else if (target.endsWith(".jar") && args.length >= 4) {
                    decompileFromJar(decompiler, printer, target, args[3]);
                } else {
                    System.err.println("Error: specify a .class file or .jar + class-name");
                    System.exit(1);
                }
                System.out.println(printer.getResult());
                System.err.println("Trace files written to: " + traceDir.getAbsolutePath());
            } catch (DecompilationException e) {
                System.err.println(e.getDiagnosticInfo());
                System.err.println("Trace files may contain additional details in: " + args[1]);
                System.exit(1);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            return;
        }

        if ("--batch".equals(path)) {
            if (args.length < 3) {
                System.err.println("Error: --batch requires <file.jar|class-dir> <output-dir>");
                System.exit(1);
            }
            try {
                File input = new File(args[1]);
                File outputDir = new File(args[2]);
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }
                int threads = Runtime.getRuntime().availableProcessors();
                BatchDecompiler batch = new BatchDecompiler(outputDir, threads);
                BatchDecompiler.BatchResult result;
                if (input.isDirectory()) {
                    result = batch.decompileDirectory(input);
                } else if (input.getName().endsWith(".jar")) {
                    result = batch.decompileJar(input);
                } else {
                    System.err.println("Error: batch input must be a .jar file or directory");
                    System.exit(1);
                    return;
                }
                System.out.println("Batch decompilation complete:");
                System.out.println("  Total classes: " + result.totalClasses);
                System.out.println("  Succeeded:     " + result.successCount);
                System.out.println("  Errors:        " + result.errorCount);
                System.out.println("  Time:          " + result.totalTimeMs + " ms");
                if (!result.errors.isEmpty()) {
                    System.out.println("  Failed classes:");
                    for (int i = 0; i < result.errors.size(); i++) {
                        System.out.println("    - " + result.errors.get(i));
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in batch mode: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            return;
        }

        try {
            DenzoDecompiler decompiler = new DenzoDecompiler();
            StringPrinter printer = new StringPrinter();

            if (path.endsWith(".class")) {
                decompileClassFile(decompiler, printer, path);
            } else if (path.endsWith(".jar")) {
                if (args.length < 2) {
                    System.err.println("Error: specify the class name to decompile from the jar");
                    System.err.println("Example: java -jar denzosoft-decompiler.jar myapp.jar com/example/MyClass");
                    System.exit(1);
                }
                decompileFromJar(decompiler, printer, path, args[1]);
            } else {
                System.err.println("Error: unsupported file type. Use .class or .jar files.");
                System.exit(1);
            }

            System.out.println(printer.getResult());
        } catch (DecompilationException e) {
            System.err.println(e.getDiagnosticInfo());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error decompiling: " + e.getMessage());
            System.err.println("Exception type: " + e.getClass().getName());
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
            }
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static byte[] readAllBytesFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static void decompileClassFile(DenzoDecompiler decompiler, StringPrinter printer, String path) throws Exception {
        final File classFile = new File(path);
        FileInputStream fis = new FileInputStream(classFile);
        final byte[] data;
        try {
            data = readAllBytesFromStream(fis);
        } finally {
            fis.close();
        }
        String fileName = classFile.getName();
        final String className = fileName.substring(0, fileName.length() - 6); // remove .class
        final File classDir = classFile.getParentFile(); // directory for loading inner classes

        Loader loader = new Loader() {
            public boolean canLoad(String internalName) {
                // Support main class
                if (className.equals(internalName) || internalName.equals(className.replace('.', '/'))) {
                    return true;
                }
                // Support inner classes: look for $-named files in same directory
                String simpleName = internalName;
                int lastSlash = internalName.lastIndexOf('/');
                if (lastSlash >= 0) simpleName = internalName.substring(lastSlash + 1);
                File innerFile = new File(classDir, simpleName + ".class");
                return innerFile.exists();
            }

            public byte[] load(String internalName) throws Exception {
                // Main class
                if (className.equals(internalName) || internalName.equals(className.replace('.', '/'))) {
                    return data;
                }
                // Inner class from same directory
                String simpleName = internalName;
                int lastSlash = internalName.lastIndexOf('/');
                if (lastSlash >= 0) simpleName = internalName.substring(lastSlash + 1);
                File innerFile = new File(classDir, simpleName + ".class");
                if (innerFile.exists()) {
                    FileInputStream innerFis = new FileInputStream(innerFile);
                    try {
                        return readAllBytesFromStream(innerFis);
                    } finally {
                        innerFis.close();
                    }
                }
                return null;
            }
        };

        decompiler.decompile(loader, printer, className);
    }

    private static void decompileFromJar(DenzoDecompiler decompiler, StringPrinter printer,
                                          String jarPath, String className) throws Exception {
        String entryName = className.replace('.', '/') + ".class";

        final JarFile jar = new JarFile(jarPath);
        try {
            Loader loader = new Loader() {
                @Override
                public boolean canLoad(String internalName) {
                    return jar.getJarEntry(internalName + ".class") != null;
                }

                @Override
                public byte[] load(String internalName) throws Exception {
                    JarEntry entry = jar.getJarEntry(internalName + ".class");
                    if (entry == null) return null;
                    InputStream is = jar.getInputStream(entry);
                    try {
                        return readAllBytesFromStream(is);
                    } finally {
                        is.close();
                    }
                }
            };

            decompiler.decompile(loader, printer, className.replace('.', '/'));
        } finally {
            jar.close();
        }
    }

    private static void printUsage() {
        System.out.println("DenzoSOFT Java Decompiler v" + DenzoDecompiler.getVersion());
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar denzosoft-decompiler.jar <file.class>              Decompile a .class file");
        System.out.println("  java -jar denzosoft-decompiler.jar <file.jar> <class-name>   Decompile a class from a .jar");
        System.out.println("  java -jar denzosoft-decompiler.jar --batch <file.jar> <output-dir>    Batch decompile JAR");
        System.out.println("  java -jar denzosoft-decompiler.jar --batch <class-dir> <output-dir>   Batch decompile directory");
        System.out.println("  java -jar denzosoft-decompiler.jar --gui [file.jar ...]       Launch GUI");
        System.out.println("  java -jar denzosoft-decompiler.jar --version                 Show version");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar denzosoft-decompiler.jar MyClass.class");
        System.out.println("  java -jar denzosoft-decompiler.jar myapp.jar com/example/MyClass");
        System.out.println("  java -jar denzosoft-decompiler.jar --batch myapp.jar output/");
    }

    /**
     * Simple Printer implementation that collects output into a String.
     */
    static class StringPrinter implements Printer {
        private final StringBuilder sb = new StringBuilder();
        private int indentLevel = 0;
        private static final String INDENT = "    ";

        @Override public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
        @Override public void end() {}

        @Override public void printText(String text) { sb.append(text); }
        @Override public void printNumericConstant(String constant) { sb.append(constant); }
        @Override public void printStringConstant(String constant, String ownerInternalName) { sb.append(constant); }
        @Override public void printKeyword(String keyword) { sb.append(keyword); }

        @Override
        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            sb.append(name);
        }

        @Override
        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            sb.append(name);
        }

        @Override public void indent() { indentLevel++; }
        @Override public void unindent() { indentLevel--; }

        @Override
        public void startLine(int lineNumber) {
            for (int i = 0; i < indentLevel; i++) {
                sb.append(INDENT);
            }
        }

        @Override
        public void endLine() {
            sb.append("\n");
        }

        @Override
        public void extraLine(int count) {
            for (int i = 0; i < count; i++) sb.append("\n");
        }

        @Override public void startMarker(int type) {}
        @Override public void endMarker(int type) {}

        public String getResult() { return sb.toString(); }
    }
}
