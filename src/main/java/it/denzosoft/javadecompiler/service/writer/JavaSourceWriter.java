/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.writer;

import it.denzosoft.javadecompiler.api.printer.Printer;
import it.denzosoft.javadecompiler.model.classfile.attribute.AnnotationInfo;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;
import it.denzosoft.javadecompiler.model.message.Message;
import it.denzosoft.javadecompiler.model.processor.Processor;
import it.denzosoft.javadecompiler.service.converter.JavaSyntaxResult;
import it.denzosoft.javadecompiler.util.SignatureParser;
import it.denzosoft.javadecompiler.util.StringConstants;
import it.denzosoft.javadecompiler.util.TypeNameUtil;

import java.util.*;

/**
 * Writes Java source code from the JavaSyntaxResult using the Printer interface.
 * This is the final stage of the decompilation pipeline.
 */
public class JavaSourceWriter implements Processor {

    // Field names whose static initializations were inlined into field declarations
    private Set<String> inlinedStaticFieldNames = new HashSet<String>();
    // START_CHANGE: LIM-0006-20260324-1 - Track major version for text block support
    private int currentMajorVersion;
    // END_CHANGE: LIM-0006-1

    @Override
    public void process(Message message) throws Exception {
        Printer printer = message.getHeader("printer");
        JavaSyntaxResult result = message.getHeader("javaSyntaxResult");

        if (result == null) {
            throw new IllegalStateException("No javaSyntaxResult in message");
        }

        // START_CHANGE: LIM-0006-20260324-2 - Store major version for text block detection
        currentMajorVersion = result.getMajorVersion();
        // END_CHANGE: LIM-0006-2
        int maxLine = computeMaxLine(result);
        printer.start(maxLine, result.getMajorVersion(), result.getMinorVersion());
        writeCompilationUnit(printer, result);
        printer.end();
    }

    private int computeMaxLine(JavaSyntaxResult result) {
        int max = 0;
        for (JavaSyntaxResult.MethodDeclaration m : result.getMethods()) {
            max = Math.max(max, m.maxLineNumber);
        }
        return max > 0 ? max : 100;
    }

    private void writeCompilationUnit(Printer printer, JavaSyntaxResult result) {
        String internalName = result.getInternalName();
        String packageName = TypeNameUtil.packageFromInternal(internalName);
        String simpleName = TypeNameUtil.simpleNameFromInternal(internalName);
        int lineNumber = 1;

        // Module declaration
        if (result.isModule() || (result.getAccessFlags() & 0x8000) != 0) {
            writeModuleDeclaration(printer, result, lineNumber);
            return;
        }

        // Preview features detection
        if (result.getMinorVersion() == 0xFFFF) {
            printer.startLine(lineNumber);
            printer.printText("// Compiled with preview features (Java " +
                StringConstants.javaVersionFromMajor(result.getMajorVersion()) + ")");
            printer.endLine();
            lineNumber++;
        }

        // Package declaration
        if (!packageName.isEmpty()) {
            printer.startLine(lineNumber);
            printer.printKeyword("package");
            printer.printText(" ");
            printer.printText(packageName);
            printer.printText(";");
            printer.endLine();
            lineNumber += 2;
            printer.startLine(lineNumber);
            printer.endLine();
        }

        // Collect imports
        Set<String> imports = collectImports(result);
        if (!imports.isEmpty()) {
            List<String> sortedImports = new ArrayList<String>(imports);
            Collections.sort(sortedImports);
            for (String imp : sortedImports) {
                printer.startLine(lineNumber);
                printer.printKeyword("import");
                printer.printText(" ");
                printer.printText(imp);
                printer.printText(";");
                printer.endLine();
                lineNumber++;
            }
            lineNumber++;
            printer.startLine(lineNumber);
            printer.endLine();
        }

        // Class-level annotations
        if (result.getClassAnnotations() != null) {
            for (AnnotationInfo ann : result.getClassAnnotations()) {
                printer.startLine(lineNumber);
                writeAnnotation(printer, ann, internalName);
                printer.endLine();
                lineNumber++;
            }
        }

        // Class/Interface/Enum/Record declaration
        printer.startLine(lineNumber);
        writeAccessFlags(printer, result.getAccessFlags(), true);

        if (result.isAnnotation()) {
            printer.printText("@");
            printer.printKeyword("interface");
        } else if (result.isInterface()) {
            printer.printKeyword("interface");
        } else if (result.isEnum()) {
            printer.printKeyword("enum");
        } else if (result.isRecord()) {
            printer.printKeyword("record");
        } else {
            if (result.isSealed()) {
                printer.printKeyword("sealed");
                printer.printText(" ");
            }
            printer.printKeyword("class");
        }
        printer.printText(" ");
        printer.printDeclaration(Printer.TYPE, internalName, simpleName, "");

        // Type parameters from generic signature
        if (result.getSignature() != null) {
            String typeParams = SignatureParser.parseClassTypeParameters(result.getSignature());
            if (typeParams != null && typeParams.length() > 0) {
                printer.printText(typeParams);
            }
        }

        // Record components
        if (result.isRecord() && result.getRecordComponents() != null) {
            printer.printText("(");
            List<JavaSyntaxResult.RecordComponentInfo> comps = result.getRecordComponents();
            for (int i = 0; i < comps.size(); i++) {
                if (i > 0) printer.printText(", ");
                JavaSyntaxResult.RecordComponentInfo comp = comps.get(i);
                writeType(printer, comp.type, internalName);
                printer.printText(" ");
                printer.printText(comp.name);
            }
            printer.printText(")");
        }

        // Extends
        String superName = result.getSuperName();
        if (superName != null && !result.isEnum() && !result.isRecord()
            && !StringConstants.JAVA_LANG_OBJECT.equals(superName)) {
            printer.printText(" ");
            printer.printKeyword("extends");
            printer.printText(" ");
            printer.printReference(Printer.TYPE, superName, TypeNameUtil.simpleNameFromInternal(superName), "", null);
        }

        // Implements / extends interfaces
        String[] interfaces = result.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            printer.printText(" ");
            printer.printKeyword(result.isInterface() ? "extends" : "implements");
            printer.printText(" ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) printer.printText(", ");
                printer.printReference(Printer.TYPE, interfaces[i],
                    TypeNameUtil.simpleNameFromInternal(interfaces[i]), "", null);
            }
        }

        // Permits (sealed)
        if (result.isSealed()) {
            printer.printText(" ");
            printer.printKeyword("permits");
            printer.printText(" ");
            for (int i = 0; i < result.getPermittedSubclasses().size(); i++) {
                if (i > 0) printer.printText(", ");
                String sub = result.getPermittedSubclasses().get(i);
                printer.printReference(Printer.TYPE, sub, TypeNameUtil.simpleNameFromInternal(sub), "", null);
            }
        }

        printer.printText(" {");
        printer.endLine();
        printer.indent();
        lineNumber++;

        // Enum constants
        if (result.isEnum()) {
            boolean first = true;
            for (JavaSyntaxResult.FieldDeclaration field : result.getFields()) {
                if (field.isEnum()) {
                    if (!first) {
                        printer.printText(",");
                        printer.endLine();
                    }
                    printer.startLine(lineNumber++);
                    printer.printDeclaration(Printer.FIELD, internalName, field.name, field.descriptor);
                    first = false;
                }
            }
            if (!first) {
                printer.printText(";");
                printer.endLine();
                lineNumber++;
                printer.startLine(lineNumber);
                printer.endLine();
            }
        }

        // Build map of field name -> initializer from static init (<clinit>)
        Map<String, Expression> staticInits = new LinkedHashMap<String, Expression>();
        Set<String> inlinedFieldNames = new HashSet<String>();
        for (JavaSyntaxResult.MethodDeclaration m : result.getMethods()) {
            if ("<clinit>".equals(m.name) && m.body != null) {
                for (Statement s : m.body) {
                    if (s instanceof ExpressionStatement) {
                        Expression e = ((ExpressionStatement) s).getExpression();
                        if (e instanceof AssignmentExpression) {
                            AssignmentExpression ae = (AssignmentExpression) e;
                            if (ae.getLeft() instanceof FieldAccessExpression) {
                                String fieldName = ((FieldAccessExpression) ae.getLeft()).getName();
                                staticInits.put(fieldName, ae.getRight());
                            } else {
                                break; // Stop at first non-field-assignment
                            }
                        } else {
                            break; // Stop at first non-assignment
                        }
                    } else if (s instanceof ReturnStatement) {
                        continue; // Skip trailing return void
                    } else {
                        break; // Stop at first non-assignment
                    }
                }
            }
        }

        // Fields (non-enum)
        for (JavaSyntaxResult.FieldDeclaration field : result.getFields()) {
            if (field.isEnum()) continue;
            // START_CHANGE: ISS-2026-0011-20260323-3 - Suppress synthetic $assertionsDisabled field
            if ("$assertionsDisabled".equals(field.name) && field.isSynthetic()) continue;
            // END_CHANGE: ISS-2026-0011-3
            // START_CHANGE: ISS-2026-0012-20260324-1 - Suppress synthetic enum fields ($VALUES, etc.)
            if (result.isEnum() && field.name.startsWith("$")) continue;
            // END_CHANGE: ISS-2026-0012-1
            if (field.annotations != null) {
                for (AnnotationInfo ann : field.annotations) {
                    printer.startLine(lineNumber++);
                    writeAnnotation(printer, ann, internalName);
                    printer.endLine();
                }
            }
            printer.startLine(lineNumber++);
            // Check if this static field can be inlined from <clinit>
            if (field.isStatic() && field.initialValue == null && staticInits.containsKey(field.name)) {
                writeFieldWithInit(printer, field, internalName, staticInits.get(field.name));
                inlinedFieldNames.add(field.name);
                inlinedStaticFieldNames.add(field.name);
            } else {
                writeField(printer, field, internalName);
            }
            printer.endLine();
        }

        // Methods
        // START_CHANGE: ISS-2026-0012-20260324-2 - Suppress synthetic enum methods (values, valueOf, $values)
        boolean firstVisibleMethod = true;
        for (int m = 0; m < result.getMethods().size(); m++) {
            JavaSyntaxResult.MethodDeclaration method = result.getMethods().get(m);
            if (result.isEnum()) {
                if ("values".equals(method.name) && method.parameterTypes.isEmpty()) continue;
                if ("valueOf".equals(method.name) && method.parameterTypes.size() == 1) continue;
                if (method.name.startsWith("$")) continue;
                // Suppress default enum constructor (only has synthetic name+ordinal params)
                if (method.isConstructor() && method.parameterTypes.size() <= 2) continue;
            }
            // END_CHANGE: ISS-2026-0012-2
            // Skip clinit if all its statements were inlined into field declarations
            if ("<clinit>".equals(method.name) && !inlinedFieldNames.isEmpty()) {
                boolean allInlined = true;
                if (method.body != null) {
                    for (Statement s : method.body) {
                        if (s instanceof ReturnStatement && !((ReturnStatement) s).hasExpression()) {
                            continue; // trailing return void
                        }
                        if (s instanceof ExpressionStatement) {
                            Expression e = ((ExpressionStatement) s).getExpression();
                            if (e instanceof AssignmentExpression) {
                                AssignmentExpression ae = (AssignmentExpression) e;
                                if (ae.getLeft() instanceof FieldAccessExpression) {
                                    String fn = ((FieldAccessExpression) ae.getLeft()).getName();
                                    if (inlinedFieldNames.contains(fn)) {
                                        continue;
                                    }
                                }
                            }
                        }
                        allInlined = false;
                        break;
                    }
                }
                if (allInlined) {
                    continue; // Suppress empty clinit
                }
            }
            if (firstVisibleMethod && !result.getFields().isEmpty()) {
                printer.startLine(lineNumber++);
                printer.endLine();
            }
            if (!firstVisibleMethod) {
                printer.startLine(lineNumber++);
                printer.endLine();
            }
            firstVisibleMethod = false;
            lineNumber = writeMethod(printer, method, result, internalName, lineNumber);
        }

        // Inner classes
        List<JavaSyntaxResult> innerResults = result.getInnerClassResults();
        if (innerResults != null && !innerResults.isEmpty()) {
            for (JavaSyntaxResult inner : innerResults) {
                printer.startLine(lineNumber++);
                printer.endLine();
                lineNumber = writeInnerClass(printer, inner, lineNumber, internalName);
            }
        }

        printer.unindent();
        printer.startLine(lineNumber);
        printer.printText("}");
        printer.endLine();
    }

    private int writeInnerClass(Printer printer, JavaSyntaxResult inner, int lineNumber,
                                 String outerInternalName) {
        String innerInternalName = inner.getInternalName();
        String simpleName = TypeNameUtil.simpleNameFromInternal(innerInternalName);

        // Write access flags from inner class attribute (static, private, etc.)
        printer.startLine(lineNumber);
        int flags = inner.getInnerClassAccessFlags();
        if (flags != 0) {
            // For inner classes, static should be printed
            if ((flags & StringConstants.ACC_PUBLIC) != 0) {
                printer.printKeyword("public");
                printer.printText(" ");
            } else if ((flags & StringConstants.ACC_PRIVATE) != 0) {
                printer.printKeyword("private");
                printer.printText(" ");
            } else if ((flags & StringConstants.ACC_PROTECTED) != 0) {
                printer.printKeyword("protected");
                printer.printText(" ");
            }
            if ((flags & StringConstants.ACC_STATIC) != 0) {
                printer.printKeyword("static");
                printer.printText(" ");
            }
            if ((flags & StringConstants.ACC_ABSTRACT) != 0
                && (flags & StringConstants.ACC_INTERFACE) == 0) {
                printer.printKeyword("abstract");
                printer.printText(" ");
            }
            if ((flags & StringConstants.ACC_FINAL) != 0
                && (flags & 0x4000) == 0) { // not enum
                printer.printKeyword("final");
                printer.printText(" ");
            }
        }

        // Write class/interface/enum keyword + name
        if (inner.isAnnotation()) {
            printer.printText("@");
            printer.printKeyword("interface");
        } else if (inner.isInterface()) {
            printer.printKeyword("interface");
        } else if (inner.isEnum()) {
            printer.printKeyword("enum");
        } else if (inner.isRecord()) {
            printer.printKeyword("record");
        } else {
            printer.printKeyword("class");
        }
        printer.printText(" ");
        printer.printDeclaration(Printer.TYPE, innerInternalName, simpleName, "");

        // Type parameters from generic signature
        if (inner.getSignature() != null) {
            String typeParams = SignatureParser.parseClassTypeParameters(inner.getSignature());
            if (typeParams != null && typeParams.length() > 0) {
                printer.printText(typeParams);
            }
        }

        // Record components
        if (inner.isRecord() && inner.getRecordComponents() != null) {
            printer.printText("(");
            List<JavaSyntaxResult.RecordComponentInfo> comps = inner.getRecordComponents();
            for (int i = 0; i < comps.size(); i++) {
                if (i > 0) printer.printText(", ");
                JavaSyntaxResult.RecordComponentInfo comp = comps.get(i);
                writeType(printer, comp.type, innerInternalName);
                printer.printText(" ");
                printer.printText(comp.name);
            }
            printer.printText(")");
        }

        // Extends (skip java.lang.Object and java.lang.Enum)
        String superName = inner.getSuperName();
        if (superName != null && !inner.isEnum() && !inner.isRecord()
            && !StringConstants.JAVA_LANG_OBJECT.equals(superName)) {
            printer.printText(" ");
            printer.printKeyword("extends");
            printer.printText(" ");
            printer.printReference(Printer.TYPE, superName,
                TypeNameUtil.simpleNameFromInternal(superName), "", null);
        }

        // Implements / extends interfaces
        String[] interfaces = inner.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            printer.printText(" ");
            printer.printKeyword(inner.isInterface() ? "extends" : "implements");
            printer.printText(" ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) printer.printText(", ");
                printer.printReference(Printer.TYPE, interfaces[i],
                    TypeNameUtil.simpleNameFromInternal(interfaces[i]), "", null);
            }
        }

        printer.printText(" {");
        printer.endLine();
        printer.indent();
        lineNumber++;

        // Enum constants
        if (inner.isEnum()) {
            boolean first = true;
            for (JavaSyntaxResult.FieldDeclaration field : inner.getFields()) {
                if (field.isEnum()) {
                    if (!first) {
                        printer.printText(",");
                        printer.endLine();
                    }
                    printer.startLine(lineNumber++);
                    printer.printDeclaration(Printer.FIELD, innerInternalName, field.name, field.descriptor);
                    first = false;
                }
            }
            if (!first) {
                printer.printText(";");
                printer.endLine();
                lineNumber++;
                printer.startLine(lineNumber);
                printer.endLine();
            }
        }

        // Fields (non-enum)
        for (JavaSyntaxResult.FieldDeclaration field : inner.getFields()) {
            if (field.isEnum()) continue;
            // START_CHANGE: ISS-2026-0012-20260324-9 - Suppress synthetic $ fields in inner enum classes
            if (inner.isEnum() && field.name.startsWith("$")) continue;
            // END_CHANGE: ISS-2026-0012-9
            if (field.annotations != null) {
                for (AnnotationInfo ann : field.annotations) {
                    printer.startLine(lineNumber++);
                    writeAnnotation(printer, ann, innerInternalName);
                    printer.endLine();
                }
            }
            printer.startLine(lineNumber++);
            writeField(printer, field, innerInternalName);
            printer.endLine();
        }

        // Methods
        // START_CHANGE: ISS-2026-0012-20260324-7 - Suppress synthetic enum members in inner classes
        boolean innerFirstMethod = true;
        for (int m = 0; m < inner.getMethods().size(); m++) {
            JavaSyntaxResult.MethodDeclaration method = inner.getMethods().get(m);
            if (inner.isEnum()) {
                if ("values".equals(method.name) && method.parameterTypes.isEmpty()) continue;
                if ("valueOf".equals(method.name) && method.parameterTypes.size() == 1) continue;
                if (method.name.startsWith("$")) continue;
                if (method.isConstructor() && method.parameterTypes.size() <= 2) continue;
                if ("<clinit>".equals(method.name)) continue;
            }
            if (innerFirstMethod && !inner.getFields().isEmpty()) {
                printer.startLine(lineNumber++);
                printer.endLine();
            }
            if (!innerFirstMethod) {
                printer.startLine(lineNumber++);
                printer.endLine();
            }
            innerFirstMethod = false;
            lineNumber = writeMethod(printer, method, inner, innerInternalName, lineNumber);
        }
        // END_CHANGE: ISS-2026-0012-7

        // Nested inner classes (recursive)
        List<JavaSyntaxResult> nestedInners = inner.getInnerClassResults();
        if (nestedInners != null && !nestedInners.isEmpty()) {
            for (JavaSyntaxResult nested : nestedInners) {
                printer.startLine(lineNumber++);
                printer.endLine();
                lineNumber = writeInnerClass(printer, nested, lineNumber, innerInternalName);
            }
        }

        printer.unindent();
        printer.startLine(lineNumber);
        printer.printText("}");
        printer.endLine();
        lineNumber++;
        return lineNumber;
    }

    private void writeModuleDeclaration(Printer printer, JavaSyntaxResult result, int lineNumber) {
        String moduleName = result.getModuleName();
        if (moduleName == null) {
            moduleName = TypeNameUtil.internalToQualified(result.getInternalName());
        }

        // open module?
        printer.startLine(lineNumber);
        if ((result.getModuleFlags() & 0x0020) != 0) { // ACC_OPEN
            printer.printKeyword("open");
            printer.printText(" ");
        }
        printer.printKeyword("module");
        printer.printText(" ");
        printer.printText(moduleName);
        printer.printText(" {");
        printer.endLine();
        printer.indent();
        lineNumber++;

        // requires
        if (result.getModuleRequires() != null) {
            for (int i = 0; i < result.getModuleRequires().size(); i++) {
                String[] req = result.getModuleRequires().get(i);
                printer.startLine(lineNumber++);
                printer.printKeyword("requires");
                printer.printText(" ");
                printer.printText(req[0].replace('/', '.'));
                printer.printText(";");
                printer.endLine();
            }
        }

        // exports
        if (result.getModuleExports() != null) {
            for (int i = 0; i < result.getModuleExports().size(); i++) {
                String[] exp = result.getModuleExports().get(i);
                printer.startLine(lineNumber++);
                printer.printKeyword("exports");
                printer.printText(" ");
                printer.printText(exp[0].replace('/', '.'));
                if (exp.length > 1) {
                    printer.printText(" ");
                    printer.printKeyword("to");
                    printer.printText(" ");
                    for (int j = 1; j < exp.length; j++) {
                        if (j > 1) printer.printText(", ");
                        printer.printText(exp[j].replace('/', '.'));
                    }
                }
                printer.printText(";");
                printer.endLine();
            }
        }

        // opens
        if (result.getModuleOpens() != null) {
            for (int i = 0; i < result.getModuleOpens().size(); i++) {
                String[] open = result.getModuleOpens().get(i);
                printer.startLine(lineNumber++);
                printer.printKeyword("opens");
                printer.printText(" ");
                printer.printText(open[0].replace('/', '.'));
                if (open.length > 1) {
                    printer.printText(" ");
                    printer.printKeyword("to");
                    printer.printText(" ");
                    for (int j = 1; j < open.length; j++) {
                        if (j > 1) printer.printText(", ");
                        printer.printText(open[j].replace('/', '.'));
                    }
                }
                printer.printText(";");
                printer.endLine();
            }
        }

        // uses
        if (result.getModuleUses() != null) {
            for (int i = 0; i < result.getModuleUses().size(); i++) {
                printer.startLine(lineNumber++);
                printer.printKeyword("uses");
                printer.printText(" ");
                printer.printText(result.getModuleUses().get(i).replace('/', '.'));
                printer.printText(";");
                printer.endLine();
            }
        }

        // provides
        if (result.getModuleProvides() != null) {
            for (int i = 0; i < result.getModuleProvides().size(); i++) {
                String[] prov = result.getModuleProvides().get(i);
                printer.startLine(lineNumber++);
                printer.printKeyword("provides");
                printer.printText(" ");
                printer.printText(prov[0].replace('/', '.'));
                if (prov.length > 1) {
                    printer.printText(" ");
                    printer.printKeyword("with");
                    printer.printText(" ");
                    for (int j = 1; j < prov.length; j++) {
                        if (j > 1) printer.printText(", ");
                        printer.printText(prov[j].replace('/', '.'));
                    }
                }
                printer.printText(";");
                printer.endLine();
            }
        }

        printer.unindent();
        printer.startLine(lineNumber);
        printer.printText("}");
        printer.endLine();
    }

    private Set<String> collectImports(JavaSyntaxResult result) {
        Set<String> imports = new TreeSet<String>();
        String thisPackage = TypeNameUtil.packageFromInternal(result.getInternalName());

        // Collect from super class
        if (result.getSuperName() != null && !StringConstants.JAVA_LANG_OBJECT.equals(result.getSuperName())) {
            addImport(imports, result.getSuperName(), thisPackage);
        }

        // Collect from interfaces
        if (result.getInterfaces() != null) {
            for (String iface : result.getInterfaces()) {
                addImport(imports, iface, thisPackage);
            }
        }

        return imports;
    }

    private void addImport(Set<String> imports, String internalName, String thisPackage) {
        if (internalName == null) return;
        String pkg = TypeNameUtil.packageFromInternal(internalName);
        if (!pkg.isEmpty() && !"java.lang".equals(pkg) && !pkg.equals(thisPackage)) {
            imports.add(TypeNameUtil.internalToQualified(internalName));
        }
    }

    private void writeAccessFlags(Printer printer, int accessFlags, boolean isClass) {
        writeAccessFlags(printer, accessFlags, isClass, false);
    }

    private void writeAccessFlags(Printer printer, int accessFlags, boolean isClass, boolean isField) {
        if ((accessFlags & StringConstants.ACC_PUBLIC) != 0) {
            printer.printKeyword("public");
            printer.printText(" ");
        } else if ((accessFlags & StringConstants.ACC_PRIVATE) != 0) {
            printer.printKeyword("private");
            printer.printText(" ");
        } else if ((accessFlags & StringConstants.ACC_PROTECTED) != 0) {
            printer.printKeyword("protected");
            printer.printText(" ");
        }

        if (!isClass) {
            if ((accessFlags & StringConstants.ACC_STATIC) != 0) {
                printer.printKeyword("static");
                printer.printText(" ");
            }
        }
        if ((accessFlags & StringConstants.ACC_ABSTRACT) != 0 && !isClass) {
            printer.printKeyword("abstract");
            printer.printText(" ");
        } else if ((accessFlags & StringConstants.ACC_ABSTRACT) != 0 && isClass
                   && (accessFlags & StringConstants.ACC_INTERFACE) == 0) {
            printer.printKeyword("abstract");
            printer.printText(" ");
        }
        if ((accessFlags & StringConstants.ACC_FINAL) != 0 && (accessFlags & StringConstants.ACC_ENUM) == 0) {
            printer.printKeyword("final");
            printer.printText(" ");
        }
        if (!isClass && (accessFlags & StringConstants.ACC_STRICT) != 0) {
            printer.printKeyword("strictfp");
            printer.printText(" ");
        }
        if (!isClass) {
            if ((accessFlags & StringConstants.ACC_SYNCHRONIZED) != 0 && (accessFlags & StringConstants.ACC_STATIC) == 0) {
                printer.printKeyword("synchronized");
                printer.printText(" ");
            }
            if ((accessFlags & StringConstants.ACC_NATIVE) != 0) {
                printer.printKeyword("native");
                printer.printText(" ");
            }
            // ACC_VOLATILE (0x0040) = volatile for fields, bridge for methods
            if (isField && (accessFlags & StringConstants.ACC_VOLATILE) != 0) {
                printer.printKeyword("volatile");
                printer.printText(" ");
            }
            // ACC_TRANSIENT (0x0080) = transient for fields, varargs for methods
            if (isField && (accessFlags & StringConstants.ACC_TRANSIENT) != 0) {
                printer.printKeyword("transient");
                printer.printText(" ");
            }
        }
    }

    private void writeType(Printer printer, Type type, String ownerInternalName) {
        if (type instanceof PrimitiveType) {
            printer.printKeyword(type.getName());
        } else if (type instanceof VoidType) {
            printer.printKeyword("void");
        } else if (type instanceof ObjectType) {
            ObjectType ot = (ObjectType) type;
            printer.printReference(Printer.TYPE, ot.getInternalName(), ot.getName(), "", ownerInternalName);
        } else if (type instanceof ArrayType) {
            ArrayType at = (ArrayType) type;
            writeType(printer, at.getElementType(), ownerInternalName);
            for (int i = 0; i < at.getDimension(); i++) {
                printer.printText("[]");
            }
        } else if (type instanceof GenericType) {
            GenericType gt = (GenericType) type;
            printer.printText(gt.getName());
        }
    }

    private void writeFieldWithInit(Printer printer, JavaSyntaxResult.FieldDeclaration field,
                                      String ownerInternalName, Expression initValue) {
        writeAccessFlags(printer, field.accessFlags, false, true);
        if (field.signature != null) {
            String genericType = SignatureParser.parseFieldSignature(field.signature);
            if (genericType != null) {
                printer.printText(genericType);
            } else {
                writeType(printer, field.type, ownerInternalName);
            }
        } else {
            writeType(printer, field.type, ownerInternalName);
        }
        printer.printText(" ");
        printer.printDeclaration(Printer.FIELD, ownerInternalName, field.name, field.descriptor);
        printer.printText(" = ");
        writeExpression(printer, initValue, ownerInternalName);
        printer.printText(";");
    }

    private void writeField(Printer printer, JavaSyntaxResult.FieldDeclaration field, String ownerInternalName) {
        writeAccessFlags(printer, field.accessFlags, false, true);
        // Use generic signature if available
        if (field.signature != null) {
            String genericType = SignatureParser.parseFieldSignature(field.signature);
            if (genericType != null) {
                printer.printText(genericType);
            } else {
                writeType(printer, field.type, ownerInternalName);
            }
        } else {
            writeType(printer, field.type, ownerInternalName);
        }
        printer.printText(" ");
        printer.printDeclaration(Printer.FIELD, ownerInternalName, field.name, field.descriptor);

        if (field.initialValue != null) {
            printer.printText(" = ");
            writeExpression(printer, field.initialValue, ownerInternalName);
        }

        printer.printText(";");
    }

    private int writeMethod(Printer printer, JavaSyntaxResult.MethodDeclaration method,
                             JavaSyntaxResult result, String ownerInternalName, int lineNumber) {
        // Method annotations
        if (method.annotations != null) {
            for (AnnotationInfo ann : method.annotations) {
                printer.startLine(lineNumber);
                writeAnnotation(printer, ann, ownerInternalName);
                printer.endLine();
                lineNumber++;
            }
        }
        printer.startLine(lineNumber);

        // Static initializer block - handle before access flags
        if ("<clinit>".equals(method.name)) {
            // START_CHANGE: ISS-2026-0012-20260324-6 - Suppress entire clinit for enum classes (all compiler-generated)
            if (result.isEnum()) {
                return lineNumber;
            }
            // END_CHANGE: ISS-2026-0012-6
            // Collect non-inlined statements
            List<Statement> clinitStmts = new ArrayList<Statement>();
            for (Statement stmt : method.body) {
                // Skip the trailing return void in clinit
                if (stmt instanceof ReturnStatement && !((ReturnStatement) stmt).hasExpression()) {
                    continue;
                }
                // Skip field assignments that were inlined into field declarations
                if (stmt instanceof ExpressionStatement) {
                    Expression e = ((ExpressionStatement) stmt).getExpression();
                    if (e instanceof AssignmentExpression) {
                        AssignmentExpression ae = (AssignmentExpression) e;
                        if (ae.getLeft() instanceof FieldAccessExpression) {
                            String fn = ((FieldAccessExpression) ae.getLeft()).getName();
                            if (!inlinedStaticFieldNames.isEmpty() && inlinedStaticFieldNames.contains(fn)) {
                                continue;
                            }
                            // START_CHANGE: ISS-2026-0011-20260323-4 - Skip $assertionsDisabled init in clinit
                            if ("$assertionsDisabled".equals(fn)) {
                                continue;
                            }
                            // END_CHANGE: ISS-2026-0011-4
                        }
                    }
                }
                // START_CHANGE: ISS-2026-0011-20260323-5 - Skip return of $assertionsDisabled ternary in clinit
                if (stmt instanceof ReturnStatement) {
                    ReturnStatement rs = (ReturnStatement) stmt;
                    if (rs.hasExpression() && isAssertionsDisabledInit(rs.getExpression())) {
                        continue;
                    }
                }
                // END_CHANGE: ISS-2026-0011-5
                clinitStmts.add(stmt);
            }
            // If all statements were inlined/skipped, suppress the static block entirely
            if (clinitStmts.isEmpty()) {
                return lineNumber;
            }
            printer.printKeyword("static");
            printer.printText(" {");
            printer.endLine();
            printer.indent();
            lineNumber++;
            for (Statement stmt : clinitStmts) {
                lineNumber = writeStatement(printer, stmt, ownerInternalName, lineNumber);
            }
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
            printer.endLine();
            lineNumber++;
            return lineNumber;
        }

        writeAccessFlags(printer, method.accessFlags, false);

        // Method type parameters
        String[] genericParamTypes = null;
        String genericReturnType = null;
        String methodTypeParams = null;
        if (method.signature != null) {
            methodTypeParams = SignatureParser.parseMethodTypeParameters(method.signature);
            genericParamTypes = SignatureParser.parseMethodParameterTypes(method.signature);
            genericReturnType = SignatureParser.parseMethodReturnType(method.signature);
        }

        // Return type (skip for constructors)
        String simpleName = TypeNameUtil.simpleNameFromInternal(ownerInternalName);
        if (!method.isConstructor()) {
            // Type parameters on method (e.g., <T>)
            if (methodTypeParams != null && methodTypeParams.length() > 0) {
                printer.printText(methodTypeParams);
                printer.printText(" ");
            }
            if (genericReturnType != null) {
                printer.printText(genericReturnType);
            } else {
                writeType(printer, method.returnType, ownerInternalName);
            }
            printer.printText(" ");
            printer.printDeclaration(Printer.METHOD, ownerInternalName, method.name, method.descriptor);
        } else {
            printer.printDeclaration(Printer.CONSTRUCTOR, ownerInternalName, simpleName, method.descriptor);
        }

        // Parameters
        // START_CHANGE: ISS-2026-0012-20260324-3 - Skip first 2 synthetic params for enum constructors
        int paramStart = 0;
        if (result.isEnum() && method.isConstructor() && method.parameterTypes.size() >= 2) {
            paramStart = 2;
        }
        // END_CHANGE: ISS-2026-0012-3
        printer.printText("(");
        for (int i = paramStart; i < method.parameterTypes.size(); i++) {
            if (i > paramStart) printer.printText(", ");
            // Parameter annotations
            if (method.parameterAnnotations != null && i < method.parameterAnnotations.size()) {
                List<AnnotationInfo> pAnns = method.parameterAnnotations.get(i);
                for (AnnotationInfo pAnn : pAnns) {
                    writeAnnotation(printer, pAnn, ownerInternalName);
                    printer.printText(" ");
                }
            }
            // Check if last param is varargs
            if (method.isVarargs() && i == method.parameterTypes.size() - 1) {
                Type paramType = method.parameterTypes.get(i);
                if (paramType instanceof ArrayType) {
                    ArrayType at = (ArrayType) paramType;
                    writeType(printer, at.getElementType(), ownerInternalName);
                    printer.printText("...");
                } else {
                    writeType(printer, paramType, ownerInternalName);
                }
            } else if (genericParamTypes != null && i < genericParamTypes.length) {
                printer.printText(genericParamTypes[i]);
            } else {
                writeType(printer, method.parameterTypes.get(i), ownerInternalName);
            }
            printer.printText(" ");
            printer.printText(method.parameterNames.get(i));
        }
        printer.printText(")");

        // Throws
        if (!method.thrownExceptions.isEmpty()) {
            printer.printText(" ");
            printer.printKeyword("throws");
            printer.printText(" ");
            for (int i = 0; i < method.thrownExceptions.size(); i++) {
                if (i > 0) printer.printText(", ");
                String exc = method.thrownExceptions.get(i);
                printer.printReference(Printer.TYPE, exc, TypeNameUtil.simpleNameFromInternal(exc), "", ownerInternalName);
            }
        }

        // Body
        if (method.isAbstract() || method.isNative()) {
            printer.printText(";");
            if (method.isNative()) {
                // JNI function name: Java_package_Class_methodName
                // For overloaded methods, JNI appends mangled descriptor
                String jniName = "Java_" + ownerInternalName.replace('/', '_') + "_" + method.name;
                printer.printText(" // JNI: " + jniName);
                // Show parameter types for JNI mapping
                if (method.parameterTypes != null && !method.parameterTypes.isEmpty()) {
                    printer.printText(" | params: (JNIEnv*, ");
                    printer.printText(method.isStatic() ? "jclass" : "jobject");
                    for (int pi = 0; pi < method.parameterTypes.size(); pi++) {
                        printer.printText(", ");
                        String jniType = toJniTypeName(method.parameterTypes.get(pi));
                        printer.printText(jniType);
                    }
                    printer.printText(")");
                } else {
                    printer.printText(" | params: (JNIEnv*, ");
                    printer.printText(method.isStatic() ? "jclass" : "jobject");
                    printer.printText(")");
                }
            }
            printer.endLine();
            lineNumber++;
        } else {
            printer.printText(" {");
            printer.endLine();
            printer.indent();
            lineNumber++;

            for (Statement stmt : method.body) {
                // Skip trailing void return in constructors and void methods
                if (stmt instanceof ReturnStatement && !((ReturnStatement) stmt).hasExpression()) {
                    continue;
                }
                // START_CHANGE: ISS-2026-0012-20260324-4 - Skip super(name, ordinal) in enum constructors
                if (result.isEnum() && method.isConstructor() && isEnumSuperCall(stmt)) {
                    continue;
                }
                // END_CHANGE: ISS-2026-0012-4
                lineNumber = writeStatement(printer, stmt, ownerInternalName, lineNumber);
            }

            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
            printer.endLine();
            lineNumber++;
        }

        return lineNumber;
    }

    private int writeStatement(Printer printer, Statement stmt, String ownerInternalName, int lineNumber) {
        int line = stmt.getLineNumber() > 0 ? stmt.getLineNumber() : lineNumber;

        // BlockStatement is a container - don't emit startLine for it, just recurse
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            for (Statement s : bs.getStatements()) {
                lineNumber = writeStatement(printer, s, ownerInternalName, lineNumber);
            }
            return lineNumber;
        }

        printer.startLine(line);

        if (stmt instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) stmt;
            printer.printKeyword("return");
            if (rs.hasExpression()) {
                printer.printText(" ");
                writeExpression(printer, rs.getExpression(), ownerInternalName);
            }
            printer.printText(";");
        } else if (stmt instanceof ExpressionStatement) {
            ExpressionStatement es = (ExpressionStatement) stmt;
            writeExpression(printer, es.getExpression(), ownerInternalName);
            printer.printText(";");
        } else if (stmt instanceof ThrowStatement) {
            ThrowStatement ts = (ThrowStatement) stmt;
            printer.printKeyword("throw");
            printer.printText(" ");
            writeExpression(printer, ts.getExpression(), ownerInternalName);
            printer.printText(";");
        } else if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            if (vds.isFinal()) {
                printer.printKeyword("final");
                printer.printText(" ");
            }
            if (vds.isVar()) {
                printer.printKeyword("var");
            } else {
                writeType(printer, vds.getType(), ownerInternalName);
            }
            printer.printText(" ");
            printer.printText(vds.getName());
            if (vds.hasInitializer()) {
                printer.printText(" = ");
                writeExpression(printer, vds.getInitializer(), ownerInternalName);
            }
            printer.printText(";");
        } else if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            printer.printKeyword("if");
            printer.printText(" (");
            writeExpression(printer, is.getCondition(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, is.getThenBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            printer.printKeyword("if");
            printer.printText(" (");
            writeExpression(printer, ies.getCondition(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, ies.getThenBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("} ");
            printer.printKeyword("else");
            printer.printText(" {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, ies.getElseBody(), ownerInternalName, lineNumber + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) stmt;
            printer.printKeyword("while");
            printer.printText(" (");
            writeExpression(printer, ws.getCondition(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, ws.getBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            for (Statement s : bs.getStatements()) {
                lineNumber = writeStatement(printer, s, ownerInternalName, lineNumber);
            }
            return lineNumber;
        } else if (stmt instanceof BreakStatement) {
            BreakStatement bs = (BreakStatement) stmt;
            printer.printKeyword("break");
            if (bs.hasLabel()) {
                printer.printText(" ");
                printer.printText(bs.getLabel());
            }
            printer.printText(";");
        } else if (stmt instanceof ContinueStatement) {
            ContinueStatement cs = (ContinueStatement) stmt;
            printer.printKeyword("continue");
            if (cs.hasLabel()) {
                printer.printText(" ");
                printer.printText(cs.getLabel());
            }
            printer.printText(";");
        } else if (stmt instanceof DoWhileStatement) {
            DoWhileStatement dws = (DoWhileStatement) stmt;
            printer.printKeyword("do");
            printer.printText(" {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, dws.getBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("} ");
            printer.printKeyword("while");
            printer.printText(" (");
            writeExpression(printer, dws.getCondition(), ownerInternalName);
            printer.printText(");");
        } else if (stmt instanceof ForStatement) {
            ForStatement fs = (ForStatement) stmt;
            printer.printKeyword("for");
            printer.printText(" (");
            if (fs.getInit() != null) {
                writeInlineStatement(printer, fs.getInit(), ownerInternalName);
            }
            printer.printText("; ");
            if (fs.getCondition() != null) {
                writeExpression(printer, fs.getCondition(), ownerInternalName);
            }
            printer.printText("; ");
            if (fs.getUpdate() != null) {
                writeInlineStatement(printer, fs.getUpdate(), ownerInternalName);
            }
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, fs.getBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof ForEachStatement) {
            ForEachStatement fes = (ForEachStatement) stmt;
            printer.printKeyword("for");
            printer.printText(" (");
            writeType(printer, fes.getVariableType(), ownerInternalName);
            printer.printText(" ");
            printer.printText(fes.getVariableName());
            printer.printText(" : ");
            writeExpression(printer, fes.getIterable(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, fes.getBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof SwitchStatement) {
            SwitchStatement ss = (SwitchStatement) stmt;
            printer.printKeyword("switch");
            printer.printText(" (");
            writeExpression(printer, ss.getSelector(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            for (SwitchStatement.SwitchCase sc : ss.getCases()) {
                printer.startLine(lineNumber++);
                if (sc.isDefault()) {
                    printer.printKeyword("default");
                    printer.printText(":");
                } else {
                    for (int ci = 0; ci < sc.getLabels().size(); ci++) {
                        if (ci > 0) {
                            printer.endLine();
                            printer.startLine(lineNumber++);
                        }
                        printer.printKeyword("case");
                        printer.printText(" ");
                        writeExpression(printer, sc.getLabels().get(ci), ownerInternalName);
                        printer.printText(":");
                    }
                }
                printer.endLine();
                printer.indent();
                for (Statement s : sc.getStatements()) {
                    lineNumber = writeStatement(printer, s, ownerInternalName, lineNumber);
                }
                printer.unindent();
            }
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt;
            printer.printKeyword("try");
            if (tcs.hasResources()) {
                printer.printText(" (");
                for (int ri = 0; ri < tcs.getResources().size(); ri++) {
                    if (ri > 0) printer.printText("; ");
                    writeInlineStatement(printer, tcs.getResources().get(ri), ownerInternalName);
                }
                printer.printText(")");
            }
            printer.printText(" {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, tcs.getTryBody(), ownerInternalName, line + 1);
            printer.unindent();
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                printer.startLine(lineNumber);
                printer.printText("} ");
                printer.printKeyword("catch");
                printer.printText(" (");
                for (int ti = 0; ti < cc.exceptionTypes.size(); ti++) {
                    if (ti > 0) printer.printText(" | ");
                    writeType(printer, cc.exceptionTypes.get(ti), ownerInternalName);
                }
                printer.printText(" ");
                printer.printText(cc.variableName);
                printer.printText(") {");
                printer.endLine();
                printer.indent();
                lineNumber = writeStatement(printer, cc.body, ownerInternalName, lineNumber + 1);
                printer.unindent();
            }
            if (tcs.hasFinally()) {
                printer.startLine(lineNumber);
                printer.printText("} ");
                printer.printKeyword("finally");
                printer.printText(" {");
                printer.endLine();
                printer.indent();
                lineNumber = writeStatement(printer, tcs.getFinallyBody(), ownerInternalName, lineNumber + 1);
                printer.unindent();
            }
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof SynchronizedStatement) {
            SynchronizedStatement ss = (SynchronizedStatement) stmt;
            printer.printKeyword("synchronized");
            printer.printText(" (");
            writeExpression(printer, ss.getMonitor(), ownerInternalName);
            printer.printText(") {");
            printer.endLine();
            printer.indent();
            lineNumber = writeStatement(printer, ss.getBody(), ownerInternalName, line + 1);
            printer.unindent();
            printer.startLine(lineNumber);
            printer.printText("}");
        } else if (stmt instanceof AssertStatement) {
            AssertStatement as = (AssertStatement) stmt;
            printer.printKeyword("assert");
            printer.printText(" ");
            writeExpression(printer, as.getCondition(), ownerInternalName);
            if (as.hasMessage()) {
                printer.printText(" : ");
                writeExpression(printer, as.getMessage(), ownerInternalName);
            }
            printer.printText(";");
        } else if (stmt instanceof LabelStatement) {
            LabelStatement ls = (LabelStatement) stmt;
            printer.printText(ls.getLabel());
            printer.printText(":");
            printer.endLine();
            lineNumber = writeStatement(printer, ls.getBody(), ownerInternalName, line + 1);
            return lineNumber;
        } else if (stmt instanceof YieldStatement) {
            YieldStatement ys = (YieldStatement) stmt;
            printer.printKeyword("yield");
            printer.printText(" ");
            writeExpression(printer, ys.getExpression(), ownerInternalName);
            printer.printText(";");
        } else {
            printer.printText("/* unsupported statement */");
        }

        printer.endLine();
        return Math.max(line + 1, lineNumber + 1);
    }

    private void writeExpression(Printer printer, Expression expr, String ownerInternalName) {
        if (expr instanceof IntegerConstantExpression) {
            IntegerConstantExpression ice = (IntegerConstantExpression) expr;
            printer.printNumericConstant(String.valueOf(ice.getValue()));
        } else if (expr instanceof LongConstantExpression) {
            LongConstantExpression lce = (LongConstantExpression) expr;
            printer.printNumericConstant(lce.getValue() + "L");
        } else if (expr instanceof FloatConstantExpression) {
            FloatConstantExpression fce = (FloatConstantExpression) expr;
            printer.printNumericConstant(fce.getValue() + "F");
        } else if (expr instanceof DoubleConstantExpression) {
            DoubleConstantExpression dce = (DoubleConstantExpression) expr;
            printer.printNumericConstant(String.valueOf(dce.getValue()));
        } else if (expr instanceof StringConstantExpression) {
            StringConstantExpression sce = (StringConstantExpression) expr;
            // START_CHANGE: LIM-0006-20260324-3 - Emit text block for Java 15+ strings with newlines
            if (currentMajorVersion >= 59 && sce.getValue().contains("\n")) {
                String raw = sce.getValue();
                // Ensure text block ends with newline for proper closing delimiter
                if (!raw.endsWith("\n")) {
                    raw = raw + "\\";
                }
                printer.printStringConstant("\"\"\"\n" + raw + "\"\"\"", ownerInternalName);
            } else {
                printer.printStringConstant("\"" + escapeString(sce.getValue()) + "\"", ownerInternalName);
            }
            // END_CHANGE: LIM-0006-3
        } else if (expr instanceof NullExpression) {
            printer.printKeyword("null");
        } else if (expr instanceof BooleanExpression) {
            BooleanExpression be = (BooleanExpression) expr;
            printer.printKeyword(String.valueOf(be.getValue()));
        } else if (expr instanceof ThisExpression) {
            printer.printKeyword("this");
        } else if (expr instanceof LocalVariableExpression) {
            LocalVariableExpression lve = (LocalVariableExpression) expr;
            printer.printText(lve.getName());
        } else if (expr instanceof FieldAccessExpression) {
            FieldAccessExpression fae = (FieldAccessExpression) expr;
            if (fae.getObject() != null) {
                writeExpression(printer, fae.getObject(), ownerInternalName);
                printer.printText(".");
            } else {
                // Static field access
                String owner = fae.getOwnerInternalName();
                if (!owner.equals(ownerInternalName)) {
                    printer.printReference(Printer.TYPE, owner, TypeNameUtil.simpleNameFromInternal(owner), "", ownerInternalName);
                    printer.printText(".");
                }
            }
            printer.printReference(Printer.FIELD, fae.getOwnerInternalName(), fae.getName(), fae.getDescriptor(), ownerInternalName);
        } else if (expr instanceof MethodInvocationExpression) {
            MethodInvocationExpression mie = (MethodInvocationExpression) expr;
            String mName = mie.getMethodName();
            // super() and this() calls don't need object prefix
            if ("super".equals(mName) || "this".equals(mName)) {
                printer.printKeyword(mName);
                writeArguments(printer, mie.getArguments(), ownerInternalName);
            } else {
                // START_CHANGE: ISS-2026-0003-20260323-1 - Parenthesize cast expressions used as method receiver
                if (mie.getObject() instanceof CastExpression) {
                    printer.printText("(");
                    writeExpression(printer, mie.getObject(), ownerInternalName);
                    printer.printText(")");
                } else {
                    writeExpression(printer, mie.getObject(), ownerInternalName);
                }
                // END_CHANGE: ISS-2026-0003-1
                printer.printText(".");
                printer.printReference(Printer.METHOD, mie.getOwnerInternalName(), mName, mie.getDescriptor(), ownerInternalName);
                writeArguments(printer, mie.getArguments(), ownerInternalName);
            }
        } else if (expr instanceof StaticMethodInvocationExpression) {
            StaticMethodInvocationExpression smie = (StaticMethodInvocationExpression) expr;
            // Simplify autoboxing: Integer.valueOf(1) -> 1, etc.
            if ("valueOf".equals(smie.getMethodName()) && smie.getArguments().size() == 1) {
                String autoboxOwner = smie.getOwnerInternalName();
                if ("java/lang/Integer".equals(autoboxOwner) || "java/lang/Long".equals(autoboxOwner) ||
                    "java/lang/Short".equals(autoboxOwner) || "java/lang/Byte".equals(autoboxOwner) ||
                    "java/lang/Float".equals(autoboxOwner) || "java/lang/Double".equals(autoboxOwner) ||
                    "java/lang/Boolean".equals(autoboxOwner) || "java/lang/Character".equals(autoboxOwner)) {
                    writeExpression(printer, smie.getArguments().get(0), ownerInternalName);
                    return;
                }
            }
            String owner = smie.getOwnerInternalName();
            if (owner != null && !owner.isEmpty() && !owner.equals(ownerInternalName)) {
                printer.printReference(Printer.TYPE, owner, TypeNameUtil.simpleNameFromInternal(owner), "", ownerInternalName);
                printer.printText(".");
            }
            printer.printReference(Printer.METHOD, owner, smie.getMethodName(), smie.getDescriptor(), ownerInternalName);
            writeArguments(printer, smie.getArguments(), ownerInternalName);
        } else if (expr instanceof NewExpression) {
            NewExpression ne = (NewExpression) expr;
            printer.printKeyword("new");
            printer.printText(" ");
            printer.printReference(Printer.TYPE, ne.getInternalTypeName(),
                TypeNameUtil.simpleNameFromInternal(ne.getInternalTypeName()), "", ownerInternalName);
            writeArguments(printer, ne.getArguments(), ownerInternalName);
        } else if (expr instanceof NewArrayExpression) {
            NewArrayExpression nae = (NewArrayExpression) expr;
            printer.printKeyword("new");
            printer.printText(" ");
            writeType(printer, nae.getType(), ownerInternalName);
            // START_CHANGE: ISS-2026-0002-20260323-4 - Emit array initializer syntax when init values present
            if (nae.hasInitValues()) {
                printer.printText("[]{");
                List<Expression> initVals = nae.getInitValues();
                for (int iv = 0; iv < initVals.size(); iv++) {
                    if (iv > 0) printer.printText(", ");
                    writeExpression(printer, initVals.get(iv), ownerInternalName);
                }
                printer.printText("}");
            } else {
                for (Expression dim : nae.getDimensionExpressions()) {
                    printer.printText("[");
                    writeExpression(printer, dim, ownerInternalName);
                    printer.printText("]");
                }
            }
            // END_CHANGE: ISS-2026-0002-4
        } else if (expr instanceof ArrayAccessExpression) {
            ArrayAccessExpression aae = (ArrayAccessExpression) expr;
            writeExpression(printer, aae.getArray(), ownerInternalName);
            printer.printText("[");
            writeExpression(printer, aae.getIndex(), ownerInternalName);
            printer.printText("]");
        } else if (expr instanceof CastExpression) {
            CastExpression ce = (CastExpression) expr;
            Type castType = ce.getType();
            Type exprType = ce.getExpression().getType();

            // Suppress redundant casts (same type)
            boolean redundant = false;
            if (castType != null && exprType != null &&
                castType.getDescriptor() != null &&
                castType.getDescriptor().equals(exprType.getDescriptor())) {
                redundant = true;
            }
            // Suppress casts to Object (always redundant)
            if (!redundant && castType instanceof ObjectType &&
                "java/lang/Object".equals(((ObjectType) castType).getInternalName())) {
                redundant = true;
            }

            if (redundant) {
                writeExpression(printer, ce.getExpression(), ownerInternalName);
            } else {
                printer.printText("(");
                writeType(printer, castType, ownerInternalName);
                printer.printText(") ");
                writeExpression(printer, ce.getExpression(), ownerInternalName);
            }
        } else if (expr instanceof InstanceOfExpression) {
            InstanceOfExpression ioe = (InstanceOfExpression) expr;
            writeExpression(printer, ioe.getExpression(), ownerInternalName);
            printer.printText(" ");
            printer.printKeyword("instanceof");
            printer.printText(" ");
            writeType(printer, ioe.getCheckType(), ownerInternalName);
            if (ioe.hasPatternVariable()) {
                printer.printText(" ");
                printer.printText(ioe.getPatternVariableName());
            }
        } else if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            writeExpression(printer, boe.getLeft(), ownerInternalName);
            printer.printText(" " + boe.getOperator() + " ");
            writeExpression(printer, boe.getRight(), ownerInternalName);
        } else if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            if (uoe.isPrefix()) {
                // Wrap complex expressions in parentheses for correct precedence
                // e.g., !(x instanceof Foo) instead of !x instanceof Foo
                boolean needsParens = uoe.getExpression() instanceof InstanceOfExpression
                    || uoe.getExpression() instanceof BinaryOperatorExpression;
                printer.printText(uoe.getOperator());
                if (needsParens) printer.printText("(");
                writeExpression(printer, uoe.getExpression(), ownerInternalName);
                if (needsParens) printer.printText(")");
            } else {
                writeExpression(printer, uoe.getExpression(), ownerInternalName);
                printer.printText(uoe.getOperator());
            }
        } else if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            writeExpression(printer, te.getCondition(), ownerInternalName);
            printer.printText(" ? ");
            writeExpression(printer, te.getTrueExpression(), ownerInternalName);
            printer.printText(" : ");
            writeExpression(printer, te.getFalseExpression(), ownerInternalName);
        } else if (expr instanceof AssignmentExpression) {
            AssignmentExpression ae = (AssignmentExpression) expr;
            writeExpression(printer, ae.getLeft(), ownerInternalName);
            printer.printText(" " + ae.getOperator() + " ");
            writeExpression(printer, ae.getRight(), ownerInternalName);
        } else if (expr instanceof ClassExpression) {
            ClassExpression ce = (ClassExpression) expr;
            writeType(printer, ce.getClassType(), ownerInternalName);
            printer.printText(".class");
        } else if (expr instanceof ReturnExpression) {
            ReturnExpression re = (ReturnExpression) expr;
            printer.printKeyword("return");
            if (re.hasExpression()) {
                printer.printText(" ");
                writeExpression(printer, re.getExpression(), ownerInternalName);
            }
        } else if (expr instanceof LambdaExpression) {
            LambdaExpression le = (LambdaExpression) expr;
            if (le.getParameterNames().isEmpty()) {
                printer.printText("()");
            } else if (le.getParameterNames().size() == 1) {
                printer.printText(le.getParameterNames().get(0));
            } else {
                printer.printText("(");
                for (int i = 0; i < le.getParameterNames().size(); i++) {
                    if (i > 0) printer.printText(", ");
                    printer.printText(le.getParameterNames().get(i));
                }
                printer.printText(")");
            }
            printer.printText(" -> ");
            if (le.getBody() != null) {
                // Write the actual lambda body
                List<Statement> bodyStmts = null;
                if (le.getBody() instanceof BlockStatement) {
                    bodyStmts = ((BlockStatement) le.getBody()).getStatements();
                }
                if (bodyStmts != null && bodyStmts.size() == 1) {
                    Statement single = bodyStmts.get(0);
                    // Strip trailing void return for single-expression lambdas
                    if (single instanceof ReturnStatement && ((ReturnStatement) single).hasExpression()) {
                        writeExpression(printer, ((ReturnStatement) single).getExpression(), ownerInternalName);
                    } else if (single instanceof ExpressionStatement) {
                        writeExpression(printer, ((ExpressionStatement) single).getExpression(), ownerInternalName);
                    } else {
                        printer.printText("{ ");
                        writeInlineLambdaStatement(printer, single, ownerInternalName);
                        printer.printText(" }");
                    }
                } else if (bodyStmts != null && bodyStmts.size() == 2) {
                    // Check if second statement is void return (common pattern)
                    Statement last = bodyStmts.get(bodyStmts.size() - 1);
                    if (last instanceof ReturnStatement && !((ReturnStatement) last).hasExpression()) {
                        Statement single = bodyStmts.get(0);
                        if (single instanceof ExpressionStatement) {
                            writeExpression(printer, ((ExpressionStatement) single).getExpression(), ownerInternalName);
                        } else if (single instanceof ReturnStatement && ((ReturnStatement) single).hasExpression()) {
                            writeExpression(printer, ((ReturnStatement) single).getExpression(), ownerInternalName);
                        } else {
                            printer.printText("{ ");
                            writeInlineLambdaStatement(printer, single, ownerInternalName);
                            printer.printText(" }");
                        }
                    } else {
                        printer.printText("{ ");
                        for (int i = 0; i < bodyStmts.size(); i++) {
                            if (!(bodyStmts.get(i) instanceof ReturnStatement && !((ReturnStatement) bodyStmts.get(i)).hasExpression())) {
                                writeInlineLambdaStatement(printer, bodyStmts.get(i), ownerInternalName);
                                printer.printText(" ");
                            }
                        }
                        printer.printText("}");
                    }
                } else if (bodyStmts != null) {
                    printer.printText("{ ");
                    for (int i = 0; i < bodyStmts.size(); i++) {
                        if (!(bodyStmts.get(i) instanceof ReturnStatement && !((ReturnStatement) bodyStmts.get(i)).hasExpression())) {
                            writeInlineLambdaStatement(printer, bodyStmts.get(i), ownerInternalName);
                            printer.printText(" ");
                        }
                    }
                    printer.printText("}");
                } else {
                    printer.printText("{ /* lambda body */ }");
                }
            } else {
                printer.printText("{ }");
            }
        } else if (expr instanceof MethodReferenceExpression) {
            MethodReferenceExpression mre = (MethodReferenceExpression) expr;
            if (mre.getObject() != null) {
                writeExpression(printer, mre.getObject(), ownerInternalName);
            } else {
                printer.printReference(Printer.TYPE, mre.getOwnerInternalName(),
                    TypeNameUtil.simpleNameFromInternal(mre.getOwnerInternalName()), "", ownerInternalName);
            }
            printer.printText("::");
            printer.printText(mre.getMethodName());
        } else if (expr instanceof SwitchExpression) {
            SwitchExpression se = (SwitchExpression) expr;
            printer.printKeyword("switch");
            printer.printText(" (");
            writeExpression(printer, se.getSelector(), ownerInternalName);
            printer.printText(") { /* switch expression */ }");
        } else if (expr instanceof TextBlockExpression) {
            TextBlockExpression tbe = (TextBlockExpression) expr;
            printer.printStringConstant("\"\"\"\n" + tbe.getValue() + "\"\"\"", ownerInternalName);
        } else if (expr instanceof PatternMatchExpression) {
            PatternMatchExpression pme = (PatternMatchExpression) expr;
            writeExpression(printer, pme.getExpression(), ownerInternalName);
            printer.printText(" ");
            printer.printKeyword("instanceof");
            printer.printText(" ");
            writeType(printer, pme.getPatternType(), ownerInternalName);
            printer.printText(" ");
            printer.printText(pme.getVariableName());
        } else {
            printer.printText("/* expr */");
        }
    }

    private void writeInlineStatement(Printer printer, Statement stmt, String ownerInternalName) {
        if (stmt instanceof ExpressionStatement) {
            writeExpression(printer, ((ExpressionStatement) stmt).getExpression(), ownerInternalName);
        } else if (stmt instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
            writeType(printer, vds.getType(), ownerInternalName);
            printer.printText(" ");
            printer.printText(vds.getName());
            if (vds.hasInitializer()) {
                printer.printText(" = ");
                writeExpression(printer, vds.getInitializer(), ownerInternalName);
            }
        // START_CHANGE: ISS-2026-0006-20260324-2 - Write multi-init/multi-update for loop parts
        } else if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> stmts = bs.getStatements();
            // Multi-init: first is type + name = val, rest are name = val (same type)
            boolean allDecls = true;
            for (int bi = 0; bi < stmts.size(); bi++) {
                if (!(stmts.get(bi) instanceof VariableDeclarationStatement)
                    && !(stmts.get(bi) instanceof ExpressionStatement)) {
                    allDecls = false;
                    break;
                }
            }
            if (allDecls && stmts.size() >= 2 && stmts.get(0) instanceof VariableDeclarationStatement) {
                // Multi-variable declaration: int i = 0, j = 10
                VariableDeclarationStatement firstDecl = (VariableDeclarationStatement) stmts.get(0);
                writeType(printer, firstDecl.getType(), ownerInternalName);
                printer.printText(" ");
                printer.printText(firstDecl.getName());
                if (firstDecl.hasInitializer()) {
                    printer.printText(" = ");
                    writeExpression(printer, firstDecl.getInitializer(), ownerInternalName);
                }
                for (int bi = 1; bi < stmts.size(); bi++) {
                    printer.printText(", ");
                    if (stmts.get(bi) instanceof VariableDeclarationStatement) {
                        VariableDeclarationStatement vds2 = (VariableDeclarationStatement) stmts.get(bi);
                        printer.printText(vds2.getName());
                        if (vds2.hasInitializer()) {
                            printer.printText(" = ");
                            writeExpression(printer, vds2.getInitializer(), ownerInternalName);
                        }
                    } else {
                        writeInlineStatement(printer, stmts.get(bi), ownerInternalName);
                    }
                }
            } else {
                // Multi-update: i++, j--
                for (int bi = 0; bi < stmts.size(); bi++) {
                    if (bi > 0) printer.printText(", ");
                    writeInlineStatement(printer, stmts.get(bi), ownerInternalName);
                }
            }
        // END_CHANGE: ISS-2026-0006-2
        } else {
            printer.printText("/* inline stmt */");
        }
    }

    private void writeInlineLambdaStatement(Printer printer, Statement stmt, String ownerInternalName) {
        if (stmt instanceof ExpressionStatement) {
            writeExpression(printer, ((ExpressionStatement) stmt).getExpression(), ownerInternalName);
            printer.printText(";");
        } else if (stmt instanceof ReturnStatement) {
            ReturnStatement rs = (ReturnStatement) stmt;
            printer.printKeyword("return");
            if (rs.hasExpression()) {
                printer.printText(" ");
                writeExpression(printer, rs.getExpression(), ownerInternalName);
            }
            printer.printText(";");
        } else {
            writeInlineStatement(printer, stmt, ownerInternalName);
            printer.printText(";");
        }
    }

    private void writeArguments(Printer printer, List<Expression> args, String ownerInternalName) {
        printer.printText("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) printer.printText(", ");
            writeExpression(printer, args.get(i), ownerInternalName);
        }
        printer.printText(")");
    }

    /**
     * Convert a Java type to its JNI C type name for native method documentation.
     */
    private String toJniTypeName(Type type) {
        if (type instanceof PrimitiveType) {
            String name = type.getName();
            if ("boolean".equals(name)) return "jboolean";
            if ("byte".equals(name)) return "jbyte";
            if ("char".equals(name)) return "jchar";
            if ("short".equals(name)) return "jshort";
            if ("int".equals(name)) return "jint";
            if ("long".equals(name)) return "jlong";
            if ("float".equals(name)) return "jfloat";
            if ("double".equals(name)) return "jdouble";
        }
        if (type instanceof VoidType) return "void";
        if (type instanceof ArrayType) return "jarray";
        if (type instanceof ObjectType) {
            String iname = ((ObjectType) type).getInternalName();
            if ("java/lang/String".equals(iname)) return "jstring";
            if ("java/lang/Class".equals(iname)) return "jclass";
            return "jobject";
        }
        return "jobject";
    }

    private String escapeString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeAnnotation(Printer printer, AnnotationInfo annotation, String ownerInternalName) {
        printer.printText("@");
        String typeDesc = annotation.getTypeDescriptor();
        // Convert descriptor like "Ljava/lang/Override;" to simple name
        String annTypeName = descriptorToSimpleName(typeDesc);
        printer.printText(annTypeName);

        List<AnnotationInfo.ElementValuePair> pairs = annotation.getElementValuePairs();
        if (pairs != null && !pairs.isEmpty()) {
            printer.printText("(");
            if (pairs.size() == 1 && "value".equals(pairs.get(0).getName())) {
                writeElementValue(printer, pairs.get(0).getValue(), ownerInternalName);
            } else {
                for (int i = 0; i < pairs.size(); i++) {
                    if (i > 0) printer.printText(", ");
                    printer.printText(pairs.get(i).getName());
                    printer.printText(" = ");
                    writeElementValue(printer, pairs.get(i).getValue(), ownerInternalName);
                }
            }
            printer.printText(")");
        }
    }

    @SuppressWarnings("unchecked")
    private void writeElementValue(Printer printer, AnnotationInfo.ElementValue ev, String ownerInternalName) {
        char tag = ev.getTag();
        Object value = ev.getValue();
        if (tag == 'B' || tag == 'S' || tag == 'I') {
            printer.printText(String.valueOf(value));
        } else if (tag == 'J') {
            printer.printText(value + "L");
        } else if (tag == 'F') {
            printer.printText(value + "F");
        } else if (tag == 'D') {
            printer.printText(String.valueOf(value));
        } else if (tag == 'Z') {
            int boolVal = ((Integer) value).intValue();
            printer.printText(boolVal != 0 ? "true" : "false");
        } else if (tag == 'C') {
            int charVal = ((Integer) value).intValue();
            printer.printText("'" + escapeChar((char) charVal) + "'");
        } else if (tag == 's') {
            printer.printText("\"" + escapeString((String) value) + "\"");
        } else if (tag == 'e') {
            String[] enumVal = (String[]) value;
            String enumType = descriptorToSimpleName(enumVal[0]);
            printer.printText(enumType);
            printer.printText(".");
            printer.printText(enumVal[1]);
        } else if (tag == 'c') {
            String classDesc = (String) value;
            String className = descriptorToSimpleName(classDesc);
            printer.printText(className);
            printer.printText(".class");
        } else if (tag == '@') {
            writeAnnotation(printer, (AnnotationInfo) value, ownerInternalName);
        } else if (tag == '[') {
            List<AnnotationInfo.ElementValue> elements = (List<AnnotationInfo.ElementValue>) value;
            printer.printText("{");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) printer.printText(", ");
                writeElementValue(printer, elements.get(i), ownerInternalName);
            }
            printer.printText("}");
        } else {
            printer.printText("/* unknown annotation value */");
        }
    }

    private String descriptorToSimpleName(String descriptor) {
        if (descriptor == null) return "";
        // Handle descriptors like "Ljava/lang/Override;" or "V" or "I"
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            String internal = descriptor.substring(1, descriptor.length() - 1);
            return TypeNameUtil.simpleNameFromInternal(internal);
        }
        // Primitive descriptors
        if ("I".equals(descriptor)) return "int";
        if ("J".equals(descriptor)) return "long";
        if ("D".equals(descriptor)) return "double";
        if ("F".equals(descriptor)) return "float";
        if ("B".equals(descriptor)) return "byte";
        if ("S".equals(descriptor)) return "short";
        if ("C".equals(descriptor)) return "char";
        if ("Z".equals(descriptor)) return "boolean";
        if ("V".equals(descriptor)) return "void";
        return descriptor;
    }

    private String escapeChar(char c) {
        switch (c) {
            case '\\': return "\\\\";
            case '\'': return "\\'";
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\t': return "\\t";
            case '\b': return "\\b";
            case '\f': return "\\f";
            default: return String.valueOf(c);
        }
    }

    // START_CHANGE: ISS-2026-0011-20260323-6 - Detect $assertionsDisabled initialization expression
    private boolean isAssertionsDisabledInit(Expression expr) {
        // Matches: ClassName.class.desiredAssertionStatus() == 0 ? 1 : 0
        // or any ternary involving desiredAssertionStatus
        if (expr instanceof TernaryExpression) {
            TernaryExpression te = (TernaryExpression) expr;
            return isDesiredAssertionStatusExpr(te.getCondition());
        }
        return isDesiredAssertionStatusExpr(expr);
    }

    private boolean isDesiredAssertionStatusExpr(Expression expr) {
        if (expr instanceof MethodInvocationExpression) {
            String name = ((MethodInvocationExpression) expr).getMethodName();
            return "desiredAssertionStatus".equals(name) || "desiredAssertionStatus".equals(name);
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            return isDesiredAssertionStatusExpr(boe.getLeft()) || isDesiredAssertionStatusExpr(boe.getRight());
        }
        if (expr instanceof UnaryOperatorExpression) {
            return isDesiredAssertionStatusExpr(((UnaryOperatorExpression) expr).getExpression());
        }
        return false;
    }
    // END_CHANGE: ISS-2026-0011-6

    // START_CHANGE: ISS-2026-0012-20260324-5 - Detect super(name, ordinal) call in enum constructor
    private boolean isEnumSuperCall(Statement stmt) {
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof MethodInvocationExpression) {
                MethodInvocationExpression mie = (MethodInvocationExpression) expr;
                if ("<init>".equals(mie.getMethodName()) || "super".equals(mie.getMethodName())) {
                    return true;
                }
            }
        }
        return false;
    }
    // END_CHANGE: ISS-2026-0012-5
}
