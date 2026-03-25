/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler;

import it.denzosoft.javadecompiler.api.Decompiler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;
import it.denzosoft.javadecompiler.model.message.Message;
import it.denzosoft.javadecompiler.service.converter.ClassFileToJavaSyntaxConverter;
import it.denzosoft.javadecompiler.service.deserializer.ClassFileDeserializer;
import it.denzosoft.javadecompiler.service.writer.JavaSourceWriter;

import java.util.Map;

/**
 * DenzoSOFT Java Decompiler - Main decompiler orchestrator.
 *
 * Decompilation pipeline:
 * 1. Deserialize: Parse binary .class file into ClassFile model
 * 2. Convert: Transform ClassFile model into Java syntax AST
 * 3. Write: Generate Java source code from AST
 *
 * Supports Java class file format versions 45.0 (Java 1.0) through 69.0 (Java 25).
 */
public class DenzoDecompiler implements Decompiler {

    private final ClassFileDeserializer deserializer = new ClassFileDeserializer();
    private final JavaSourceWriter writer = new JavaSourceWriter();
    private DecompilerTrace trace = new DecompilerTrace(); // disabled by default

    /**
     * Enable trace mode. When enabled, a .trace file is written for each
     * class decompiled, containing diagnostic information for troubleshooting.
     *
     * @param traceDir directory where .trace files will be saved
     */
    public void setTraceDir(java.io.File traceDir) {
        this.trace = new DecompilerTrace(traceDir);
    }

    /**
     * Get the current trace collector.
     */
    public DecompilerTrace getTrace() {
        return trace;
    }

    @Override
    public void decompile(Loader loader, Printer printer, String internalName) throws Exception {
        decompile(loader, printer, internalName, null);
    }

    @Override
    public void decompile(Loader loader, Printer printer, String internalName,
                            Map<String, Object> configuration) throws Exception {
        trace.begin(internalName);
        trace.log("pipeline", "Starting decompilation of " + internalName);

        Message message = new Message();
        message.setHeader("mainInternalTypeName", internalName);
        message.setHeader("loader", loader);
        message.setHeader("printer", printer);
        message.setHeader("trace", trace);

        if (configuration != null) {
            message.setHeader("configuration", configuration);
        }

        // Pipeline execution with diagnostic error reporting
        try {
            trace.log("deserializer", "Parsing class file");
            deserializer.process(message);
            trace.log("deserializer", "Class file parsed successfully");
        } catch (Exception e) {
            trace.logError("deserializer", "Failed to parse class file", e);
            trace.end();
            throw new DecompilationException("Deserialization failed for class '" + internalName + "': " + e.getMessage(), e, internalName, "deserializer");
        }

        // Log class info from parsed class file
        it.denzosoft.javadecompiler.model.classfile.ClassFile classFile = message.getHeader("classFile");
        if (classFile != null && trace.isEnabled()) {
            trace.logClassInfo(
                classFile.getMajorVersion(), classFile.getMinorVersion(),
                classFile.getAccessFlags(),
                classFile.getThisClassName(), classFile.getSuperClassName(),
                classFile.getInterfaces(),
                classFile.getFields().length, classFile.getMethods().length,
                classFile.getConstantPool().getSize());

            // Log each method's info
            for (it.denzosoft.javadecompiler.model.classfile.MethodInfo m : classFile.getMethods()) {
                it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute code = m.findAttribute("Code");
                trace.logMethod(m.getName(), m.getDescriptor(), m.getAccessFlags(),
                    code != null ? code.getCode().length : 0,
                    code != null && code.getExceptionTable() != null ? code.getExceptionTable().length : 0,
                    code != null ? code.getMaxStack() : 0,
                    code != null ? code.getMaxLocals() : 0);
            }
        }

        // Create a new converter per call for thread safety (mutable state in instance fields)
        ClassFileToJavaSyntaxConverter converter = new ClassFileToJavaSyntaxConverter();
        try {
            trace.log("converter", "Converting bytecode to Java syntax AST");
            converter.process(message);
            trace.log("converter", "Conversion completed successfully");
        } catch (Exception e) {
            trace.logError("converter", "Failed to convert bytecode", e);
            trace.end();
            throw new DecompilationException("Conversion failed for class '" + internalName + "': " + e.getMessage(), e, internalName, "converter");
        }

        try {
            trace.log("writer", "Generating Java source code");
            writer.process(message);
            trace.log("writer", "Source generation completed");
        } catch (Exception e) {
            trace.logError("writer", "Failed to generate source code", e);
            trace.end();
            throw new DecompilationException("Writer failed for class '" + internalName + "': " + e.getMessage(), e, internalName, "writer");
        }

        trace.log("pipeline", "Decompilation completed successfully");
        trace.end();
    }

    /**
     * Get the version string of this decompiler.
     */
    public static String getVersion() {
        return "1.2.0-SNAPSHOT";
    }

    /**
     * Get maximum supported Java version.
     */
    public static int getMaxSupportedJavaVersion() {
        return 25;
    }
}
