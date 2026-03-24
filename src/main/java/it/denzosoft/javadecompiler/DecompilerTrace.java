/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Collects diagnostic trace information during decompilation.
 * Used for troubleshooting decompilation errors or unexpected output.
 *
 * When enabled, writes a detailed trace file for each class decompiled,
 * containing: class file metadata, constant pool summary, method signatures,
 * bytecode analysis, CFG structure, and any errors encountered.
 *
 * Usage:
 *   DecompilerTrace trace = new DecompilerTrace(traceDir);
 *   trace.begin("com/example/MyClass");
 *   trace.log("stage", "message");
 *   trace.end(); // writes the trace file
 */
public class DecompilerTrace {
    private final File traceDir;
    private StringBuilder buffer;
    private String currentClass;
    private long startTime;
    private boolean enabled;

    /**
     * Create a trace collector that writes to the given directory.
     * Pass null to disable tracing.
     */
    public DecompilerTrace(File traceDir) {
        this.traceDir = traceDir;
        this.enabled = traceDir != null;
        if (enabled && !traceDir.exists()) {
            traceDir.mkdirs();
        }
    }

    /**
     * Create a disabled trace (no-op).
     */
    public DecompilerTrace() {
        this.traceDir = null;
        this.enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Begin tracing a class decompilation.
     */
    public void begin(String internalClassName) {
        if (!enabled) return;
        this.currentClass = internalClassName;
        this.startTime = System.currentTimeMillis();
        this.buffer = new StringBuilder(4096);
        buffer.append("================================================================\n");
        buffer.append("DenzoSOFT Java Decompiler - Trace Report\n");
        buffer.append("================================================================\n");
        buffer.append("Class: ").append(internalClassName).append("\n");
        buffer.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        buffer.append("Decompiler version: ").append(DenzoDecompiler.getVersion()).append("\n");
        buffer.append("================================================================\n\n");
    }

    /**
     * Log a trace entry with a stage name and message.
     */
    public void log(String stage, String message) {
        if (!enabled || buffer == null) return;
        long elapsed = System.currentTimeMillis() - startTime;
        buffer.append("[").append(elapsed).append("ms] [").append(stage).append("] ").append(message).append("\n");
    }

    /**
     * Log class file header information.
     */
    public void logClassInfo(int majorVersion, int minorVersion, int accessFlags,
                              String thisClass, String superClass, String[] interfaces,
                              int fieldCount, int methodCount, int constantPoolSize) {
        if (!enabled || buffer == null) return;
        buffer.append("\n--- Class File Info ---\n");
        buffer.append("  Version: ").append(majorVersion).append(".").append(minorVersion);
        if (minorVersion == 0xFFFF) buffer.append(" (preview features)");
        buffer.append("\n");
        buffer.append("  Access flags: 0x").append(Integer.toHexString(accessFlags)).append("\n");
        buffer.append("  This class: ").append(thisClass).append("\n");
        buffer.append("  Super class: ").append(superClass != null ? superClass : "(none)").append("\n");
        if (interfaces != null && interfaces.length > 0) {
            buffer.append("  Interfaces: ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) buffer.append(", ");
                buffer.append(interfaces[i]);
            }
            buffer.append("\n");
        }
        buffer.append("  Constant pool entries: ").append(constantPoolSize).append("\n");
        buffer.append("  Fields: ").append(fieldCount).append("\n");
        buffer.append("  Methods: ").append(methodCount).append("\n");
        buffer.append("\n");
    }

    /**
     * Log method decompilation details.
     */
    public void logMethod(String name, String descriptor, int accessFlags,
                           int bytecodeLength, int exceptionTableSize, int maxStack, int maxLocals) {
        if (!enabled || buffer == null) return;
        buffer.append("  --- Method: ").append(name).append(descriptor).append(" ---\n");
        buffer.append("    Access: 0x").append(Integer.toHexString(accessFlags)).append("\n");
        buffer.append("    Bytecode: ").append(bytecodeLength).append(" bytes\n");
        buffer.append("    Max stack: ").append(maxStack).append(", Max locals: ").append(maxLocals).append("\n");
        if (exceptionTableSize > 0) {
            buffer.append("    Exception table entries: ").append(exceptionTableSize).append("\n");
        }
    }

    /**
     * Log CFG information for a method.
     */
    public void logCFG(int blockCount, String blockSummary) {
        if (!enabled || buffer == null) return;
        buffer.append("    CFG blocks: ").append(blockCount).append("\n");
        if (blockSummary != null) {
            buffer.append("    ").append(blockSummary).append("\n");
        }
    }

    /**
     * Log attribute information.
     */
    public void logAttribute(String name, int length) {
        if (!enabled || buffer == null) return;
        buffer.append("    Attribute: ").append(name).append(" (").append(length).append(" bytes)\n");
    }

    /**
     * Log an error encountered during decompilation.
     */
    public void logError(String stage, String message, Throwable error) {
        if (!enabled || buffer == null) return;
        buffer.append("\n  *** ERROR in ").append(stage).append(": ").append(message).append(" ***\n");
        if (error != null) {
            buffer.append("  Exception: ").append(error.getClass().getName()).append(": ").append(error.getMessage()).append("\n");
            StackTraceElement[] trace = error.getStackTrace();
            int limit = Math.min(trace.length, 10);
            for (int i = 0; i < limit; i++) {
                buffer.append("    at ").append(trace[i].toString()).append("\n");
            }
            if (trace.length > limit) {
                buffer.append("    ... ").append(trace.length - limit).append(" more\n");
            }
        }
        buffer.append("\n");
    }

    /**
     * Log the number of statements generated for a method.
     */
    public void logStatements(String methodName, int statementCount) {
        if (!enabled || buffer == null) return;
        buffer.append("    Statements generated: ").append(statementCount).append("\n\n");
    }

    /**
     * End tracing and write the trace file.
     */
    public void end() {
        if (!enabled || buffer == null || currentClass == null) return;

        long elapsed = System.currentTimeMillis() - startTime;
        buffer.append("\n================================================================\n");
        buffer.append("Decompilation completed in ").append(elapsed).append(" ms\n");
        buffer.append("================================================================\n");

        // Write trace file
        String fileName = currentClass.replace('/', '_').replace('$', '_') + ".trace";
        File traceFile = new File(traceDir, fileName);

        FileWriter fw = null;
        try {
            fw = new FileWriter(traceFile);
            fw.write(buffer.toString());
        } catch (IOException e) {
            System.err.println("Warning: could not write trace file " + traceFile + ": " + e.getMessage());
        } finally {
            if (fw != null) {
                try { fw.close(); } catch (IOException ignore) {}
            }
        }

        buffer = null;
        currentClass = null;
    }
}
