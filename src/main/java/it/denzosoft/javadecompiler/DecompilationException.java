/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler;

/**
 * Exception thrown when decompilation fails.
 * Contains diagnostic information to help analyze the problem.
 */
public class DecompilationException extends Exception {
    private final String internalClassName;
    private final String pipelineStage;

    public DecompilationException(String message, Throwable cause, String internalClassName, String pipelineStage) {
        super(message, cause);
        this.internalClassName = internalClassName;
        this.pipelineStage = pipelineStage;
    }

    /**
     * The internal class name that failed to decompile (e.g., "com/example/MyClass").
     */
    public String getInternalClassName() {
        return internalClassName;
    }

    /**
     * The pipeline stage where the failure occurred: "deserializer", "converter", or "writer".
     */
    public String getPipelineStage() {
        return pipelineStage;
    }

    /**
     * Get a detailed diagnostic string with all available information.
     */
    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DenzoSOFT Decompiler Diagnostic Report ===\n");
        sb.append("Class: ").append(internalClassName).append("\n");
        sb.append("Pipeline stage: ").append(pipelineStage).append("\n");
        sb.append("Error: ").append(getMessage()).append("\n");
        if (getCause() != null) {
            sb.append("Root cause: ").append(getCause().getClass().getName()).append(": ").append(getCause().getMessage()).append("\n");
            sb.append("Stack trace:\n");
            StackTraceElement[] trace = getCause().getStackTrace();
            int limit = Math.min(trace.length, 15);
            for (int i = 0; i < limit; i++) {
                sb.append("  at ").append(trace[i].toString()).append("\n");
            }
            if (trace.length > limit) {
                sb.append("  ... ").append(trace.length - limit).append(" more\n");
            }
        }
        sb.append("===============================================\n");
        return sb.toString();
    }
}
