/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter;

import it.denzosoft.javadecompiler.model.classfile.ClassFile;
import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.FieldInfo;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.*;
import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.*;
import it.denzosoft.javadecompiler.util.BytecodeDisassembler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.model.message.Message;
import it.denzosoft.javadecompiler.model.processor.Processor;
import it.denzosoft.javadecompiler.service.deserializer.ClassFileDeserializer;
import it.denzosoft.javadecompiler.util.ByteReader;
import it.denzosoft.javadecompiler.util.StringConstants;
import it.denzosoft.javadecompiler.util.TypeNameUtil;
import it.denzosoft.javadecompiler.util.SignatureParser;

import it.denzosoft.javadecompiler.DecompilerLimits;
import it.denzosoft.javadecompiler.service.converter.cfg.BasicBlock;
import it.denzosoft.javadecompiler.service.converter.cfg.ControlFlowGraph;
import it.denzosoft.javadecompiler.service.converter.cfg.StructuredFlowBuilder;
import it.denzosoft.javadecompiler.service.converter.transform.BooleanSimplifier;
import it.denzosoft.javadecompiler.service.converter.transform.CompoundAssignmentSimplifier;
import it.denzosoft.javadecompiler.service.converter.transform.ForEachDetector;
import it.denzosoft.javadecompiler.service.converter.transform.ForLoopDetector;
import it.denzosoft.javadecompiler.service.converter.transform.PatternSwitchReconstructor;
import it.denzosoft.javadecompiler.service.converter.transform.StringSwitchReconstructor;
import it.denzosoft.javadecompiler.service.converter.transform.TryCatchReconstructor;
import it.denzosoft.javadecompiler.util.OpcodeInfo;

import java.util.*;

/**
 * Converts a parsed ClassFile into Java syntax AST.
 * This is the core decompilation logic that interprets bytecode instructions
 * and reconstructs Java-level constructs.
 */
public class ClassFileToJavaSyntaxConverter implements Processor {

    @Override
    public void process(Message message) throws Exception {
        ClassFile classFile = message.getHeader("classFile");
        if (classFile == null) {
            throw new IllegalStateException("No classFile in message - deserializer must run first");
        }

        JavaSyntaxResult result = convert(classFile);

        // Process inner classes - load and decompile each one
        Loader loader = message.getHeader("loader");
        if (loader != null) {
            InnerClassesAttribute innerAttr = classFile.findAttribute("InnerClasses");
            if (innerAttr != null) {
                String thisClassName = classFile.getThisClassName();
                for (InnerClassesAttribute.InnerClass ic : innerAttr.getClasses()) {
                    // Only process inner classes where this class is the outer class
                    if (ic.outerClassName != null && ic.outerClassName.equals(thisClassName)
                        && ic.innerClassName != null && !ic.innerClassName.equals(thisClassName)) {
                        loadAndAddInnerClass(loader, ic, result);
                    }
                    // Handle anonymous classes (outerClassName is null but innerClassName starts with thisClass$)
                    if (ic.outerClassName == null && ic.innerName == null
                        && ic.innerClassName != null && ic.innerClassName.startsWith(thisClassName + "$")) {
                        loadAndAddInnerClass(loader, ic, result);
                    }
                }
            }
        }

        message.setHeader("javaSyntaxResult", result);
        message.setBody(result);
    }

    private void loadAndAddInnerClass(Loader loader, InnerClassesAttribute.InnerClass ic, JavaSyntaxResult outerResult) {
        if (loader.canLoad(ic.innerClassName)) {
            try {
                byte[] innerData = loader.load(ic.innerClassName);
                if (innerData != null) {
                    ClassFileDeserializer deser = new ClassFileDeserializer();
                    ClassFile innerCf = deser.deserialize(innerData);

                    ClassFileToJavaSyntaxConverter innerConverter = new ClassFileToJavaSyntaxConverter();
                    JavaSyntaxResult innerResult = innerConverter.convert(innerCf);
                    innerResult.setInnerClass(true);
                    innerResult.setInnerClassAccessFlags(ic.accessFlags);
                    outerResult.addInnerClassResult(innerResult);
                }
            } catch (Exception e) {
                // Skip inner class if it can't be loaded or decompiled
            }
        }
    }

    public JavaSyntaxResult convert(ClassFile classFile) {
        // START_CHANGE: ISS-2026-0010-20260323-2 - Store current class name for this() vs super()
        currentClassInternalName = classFile.getThisClassName();
        // END_CHANGE: ISS-2026-0010-2
        JavaSyntaxResult result = new JavaSyntaxResult();
        result.setMajorVersion(classFile.getMajorVersion());
        result.setMinorVersion(classFile.getMinorVersion());
        result.setAccessFlags(classFile.getAccessFlags());
        result.setInternalName(classFile.getThisClassName());
        result.setSuperName(classFile.getSuperClassName());
        result.setInterfaces(classFile.getInterfaces());

        // Source file
        SourceFileAttribute sourceFile = classFile.findAttribute("SourceFile");
        if (sourceFile != null) {
            result.setSourceFile(sourceFile.getSourceFile());
        }

        // Signature (generics)
        SignatureAttribute sig = classFile.findAttribute("Signature");
        if (sig != null) {
            result.setSignature(sig.getSignature());
        }

        // Record components
        RecordAttribute record = classFile.findAttribute("Record");
        if (record != null) {
            List<JavaSyntaxResult.RecordComponentInfo> components = new ArrayList<JavaSyntaxResult.RecordComponentInfo>();
            for (RecordAttribute.RecordComponent rc : record.getComponents()) {
                components.add(new JavaSyntaxResult.RecordComponentInfo(
                    rc.name, rc.descriptor, parseType(rc.descriptor)));
            }
            result.setRecordComponents(components);
        }

        // Sealed class
        PermittedSubclassesAttribute permitted = classFile.findAttribute("PermittedSubclasses");
        if (permitted != null) {
            result.setPermittedSubclasses(Arrays.asList(permitted.getPermittedSubclasses()));
        }

        // Inner classes
        InnerClassesAttribute inner = classFile.findAttribute("InnerClasses");
        if (inner != null) {
            List<JavaSyntaxResult.InnerClassInfo> innerClasses = new ArrayList<JavaSyntaxResult.InnerClassInfo>();
            for (InnerClassesAttribute.InnerClass ic : inner.getClasses()) {
                innerClasses.add(new JavaSyntaxResult.InnerClassInfo(
                    ic.innerClassName, ic.outerClassName, ic.innerName, ic.accessFlags));
            }
            result.setInnerClasses(innerClasses);
        }

        // Class-level annotations
        result.setClassAnnotations(extractAnnotations(classFile.getAttributes()));

        // Load BootstrapMethods attribute
        bootstrapMethodsAttr = classFile.findAttribute("BootstrapMethods");

        // Build synthetic method map for lambda body reconstruction
        syntheticBodies = new HashMap<String, List<Statement>>();
        syntheticParamNames = new HashMap<String, List<String>>();
        for (MethodInfo method : classFile.getMethods()) {
            if (method.isSynthetic() && method.getName().startsWith("lambda$")) {
                CodeAttribute code = method.findAttribute("Code");
                if (code != null) {
                    List<Statement> body = decompileMethodBody(code, classFile.getConstantPool(), method);
                    syntheticBodies.put(method.getName(), body);
                    // Extract parameter names from LVT
                    List<String> paramNames = new ArrayList<String>();
                    String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
                    int slot = method.isStatic() ? 0 : 1;
                    Map<Integer, String> lvtNames = new HashMap<Integer, String>();
                    for (Attribute attr : code.getAttributes()) {
                        if (attr instanceof LocalVariableTableAttribute) {
                            LocalVariableTableAttribute lvt = (LocalVariableTableAttribute) attr;
                            for (LocalVariableTableAttribute.LocalVariable lv : lvt.getLocalVariables()) {
                                lvtNames.put(lv.index, lv.name);
                            }
                        }
                    }
                    for (int pi = 0; pi < paramDescs.length; pi++) {
                        String name = lvtNames.get(slot);
                        paramNames.add(name != null ? name : "arg" + pi);
                        slot += ("D".equals(paramDescs[pi]) || "J".equals(paramDescs[pi])) ? 2 : 1;
                    }
                    syntheticParamNames.put(method.getName(), paramNames);
                }
            }
        }

        // Module info
        if (classFile.isModule()) {
            ModuleAttribute moduleAttr = classFile.findAttribute("Module");
            if (moduleAttr != null) {
                result.setModuleName(moduleAttr.getModuleName());
                result.setModuleFlags(moduleAttr.getModuleFlags());
                result.setModuleVersion(moduleAttr.getModuleVersion());

                List<String[]> reqList = new ArrayList<String[]>();
                for (ModuleAttribute.Requires req : moduleAttr.getRequires()) {
                    reqList.add(new String[]{req.name, req.version});
                }
                result.setModuleRequires(reqList);

                List<String[]> expList = new ArrayList<String[]>();
                for (ModuleAttribute.Exports exp : moduleAttr.getExports()) {
                    String[] entry = new String[1 + (exp.to != null ? exp.to.length : 0)];
                    entry[0] = exp.name;
                    if (exp.to != null) {
                        for (int i = 0; i < exp.to.length; i++) {
                            entry[i + 1] = exp.to[i];
                        }
                    }
                    expList.add(entry);
                }
                result.setModuleExports(expList);

                List<String[]> opensList = new ArrayList<String[]>();
                for (ModuleAttribute.Opens open : moduleAttr.getOpens()) {
                    String[] entry = new String[1 + (open.to != null ? open.to.length : 0)];
                    entry[0] = open.name;
                    if (open.to != null) {
                        for (int i = 0; i < open.to.length; i++) {
                            entry[i + 1] = open.to[i];
                        }
                    }
                    opensList.add(entry);
                }
                result.setModuleOpens(opensList);

                result.setModuleUses(moduleAttr.getUses());

                List<String[]> provList = new ArrayList<String[]>();
                for (ModuleAttribute.Provides prov : moduleAttr.getProvides()) {
                    String[] entry = new String[1 + (prov.providers != null ? prov.providers.length : 0)];
                    entry[0] = prov.service;
                    if (prov.providers != null) {
                        for (int i = 0; i < prov.providers.length; i++) {
                            entry[i + 1] = prov.providers[i];
                        }
                    }
                    provList.add(entry);
                }
                result.setModuleProvides(provList);
            }
        }

        // Fields
        for (FieldInfo field : classFile.getFields()) {
            if (field.isSynthetic()) continue;
            result.addField(convertField(field, classFile));
        }

        // Methods
        for (MethodInfo method : classFile.getMethods()) {
            if (method.isSynthetic() && !method.isBridge()) continue;
            if (method.isBridge()) continue; // bridge methods are compiler-generated, suppress
            result.addMethod(convertMethod(method, classFile));
        }

        return result;
    }

    private JavaSyntaxResult.FieldDeclaration convertField(FieldInfo field, ClassFile classFile) {
        Type type = parseType(field.getDescriptor());

        // Check for constant value
        Expression initialValue = null;
        ConstantValueAttribute cv = field.findAttribute("ConstantValue");
        if (cv != null) {
            initialValue = getConstantExpression(cv.getConstantValueIndex(), classFile.getConstantPool());
        }

        SignatureAttribute sig = field.findAttribute("Signature");
        String signature = sig != null ? sig.getSignature() : null;

        List<AnnotationInfo> annotations = extractAnnotations(field.getAttributes());

        JavaSyntaxResult.FieldDeclaration fd = new JavaSyntaxResult.FieldDeclaration(
            field.getAccessFlags(), field.getName(), field.getDescriptor(),
            type, initialValue, signature, annotations);
        // START_CHANGE: LIM-0004-20260326-8 - Populate field type annotations
        List<AnnotationInfo> fieldTypeAnns = extractTypeAnnotationsByTarget(field.getAttributes(), 0x13);
        if (!fieldTypeAnns.isEmpty()) {
            fd.typeAnnotations = fieldTypeAnns;
        }
        // END_CHANGE: LIM-0004-8
        return fd;
    }

    private JavaSyntaxResult.MethodDeclaration convertMethod(MethodInfo method, ClassFile classFile) {
        String[] paramDescriptors = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
        String returnDescriptor = TypeNameUtil.parseMethodReturnDescriptor(method.getDescriptor());

        Type returnType = parseType(returnDescriptor);
        List<Type> paramTypes = new ArrayList<Type>();
        for (String pd : paramDescriptors) {
            paramTypes.add(parseType(pd));
        }

        // Get parameter names from LocalVariableTable
        List<String> paramNames = new ArrayList<String>();
        CodeAttribute code = method.findAttribute("Code");
        if (code != null) {
            LocalVariableTableAttribute lvt = null;
            for (Attribute attr : code.getAttributes()) {
                if (attr instanceof LocalVariableTableAttribute) {
                    lvt = (LocalVariableTableAttribute) attr;
                    break;
                }
            }
            if (lvt != null) {
                int startIndex = method.isStatic() ? 0 : 1;
                Map<Integer, String> indexToName = new HashMap<Integer, String>();
                for (LocalVariableTableAttribute.LocalVariable lv : lvt.getLocalVariables()) {
                    indexToName.put(lv.index, lv.name);
                }
                int slot = startIndex;
                for (int i = 0; i < paramDescriptors.length; i++) {
                    String name = indexToName.get(slot);
                    paramNames.add(name != null ? name : "arg" + i);
                    slot += ("D".equals(paramDescriptors[i]) || "J".equals(paramDescriptors[i])) ? 2 : 1;
                }
            }
        }
        while (paramNames.size() < paramTypes.size()) {
            paramNames.add("arg" + paramNames.size());
        }

        // Exception types
        List<String> thrownExceptions = new ArrayList<String>();
        ExceptionsAttribute exc = method.findAttribute("Exceptions");
        if (exc != null) {
            thrownExceptions.addAll(Arrays.asList(exc.getExceptions()));
        }

        // Decompile method body
        List<Statement> bodyStatements = new ArrayList<Statement>();
        if (code != null) {
            bodyStatements = decompileMethodBody(code, classFile.getConstantPool(), method);
        }

        // Max line number for printer
        int maxLineNumber = 0;
        if (code != null) {
            for (Attribute attr : code.getAttributes()) {
                if (attr instanceof LineNumberTableAttribute) {
                    LineNumberTableAttribute lnt = (LineNumberTableAttribute) attr;
                    maxLineNumber = Math.max(maxLineNumber, lnt.getMaxLineNumber());
                }
            }
        }

        SignatureAttribute sig = method.findAttribute("Signature");
        String signature = sig != null ? sig.getSignature() : null;

        // Method annotations
        List<AnnotationInfo> methodAnnotations = extractAnnotations(method.getAttributes());

        // Parameter annotations
        List<List<AnnotationInfo>> paramAnnotations = new ArrayList<List<AnnotationInfo>>();
        for (Attribute attr : method.getAttributes()) {
            if (attr instanceof RuntimeParameterAnnotationsAttribute) {
                RuntimeParameterAnnotationsAttribute rpa = (RuntimeParameterAnnotationsAttribute) attr;
                AnnotationInfo[][] pa = rpa.getParameterAnnotations();
                while (paramAnnotations.size() < pa.length) {
                    paramAnnotations.add(new ArrayList<AnnotationInfo>());
                }
                for (int pi = 0; pi < pa.length; pi++) {
                    for (int ai = 0; ai < pa[pi].length; ai++) {
                        paramAnnotations.get(pi).add(pa[pi][ai]);
                    }
                }
            }
        }

        // START_CHANGE: BUG-2026-0031-20260325-1 - Add generic type cast for type variable return types
        if (signature != null) {
            String genericReturnType = SignatureParser.parseMethodReturnType(signature);
            if (genericReturnType != null && genericReturnType.length() <= 2
                && !genericReturnType.contains(".") && !genericReturnType.contains("/")
                && !"void".equals(genericReturnType) && !"int".equals(genericReturnType)
                && !"long".equals(genericReturnType) && !"boolean".equals(genericReturnType)
                && !"byte".equals(genericReturnType) && !"char".equals(genericReturnType)
                && !"short".equals(genericReturnType) && !"float".equals(genericReturnType)
                && !"double".equals(genericReturnType)) {
                GenericType genRetType = new GenericType(genericReturnType);
                addGenericReturnCasts(bodyStatements, genRetType);
            }
        }
        // END_CHANGE: BUG-2026-0031-1

        JavaSyntaxResult.MethodDeclaration md = new JavaSyntaxResult.MethodDeclaration(
            method.getAccessFlags(), method.getName(), method.getDescriptor(),
            returnType, paramTypes, paramNames, thrownExceptions,
            bodyStatements, maxLineNumber, signature,
            methodAnnotations, paramAnnotations);
        // START_CHANGE: IMP-LINES-20260326-6 - Populate bytecode metadata
        if (code != null) {
            md.bytecodeLength = code.getCode().length;
            md.maxStack = code.getMaxStack();
            md.maxLocals = code.getMaxLocals();
            // Disassemble bytecode for --show-bytecode feature
            LineNumberTableAttribute lnt = null;
            for (Attribute codeAttr : code.getAttributes()) {
                if (codeAttr instanceof LineNumberTableAttribute) {
                    lnt = (LineNumberTableAttribute) codeAttr;
                    break;
                }
            }
            Map<Integer, String> lvNames = new HashMap<Integer, String>();
            LocalVariableTableAttribute lvt = null;
            for (Attribute codeAttr : code.getAttributes()) {
                if (codeAttr instanceof LocalVariableTableAttribute) {
                    lvt = (LocalVariableTableAttribute) codeAttr;
                    break;
                }
            }
            if (lvt != null) {
                for (LocalVariableTableAttribute.LocalVariable lv : lvt.getLocalVariables()) {
                    lvNames.put(lv.index, lv.name);
                }
            }
            // Add fallback param names
            int pSlot = method.isStatic() ? 0 : 1;
            String[] pDescs = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
            for (int pi2 = 0; pi2 < pDescs.length; pi2++) {
                if (!lvNames.containsKey(pSlot)) lvNames.put(pSlot, "arg" + pi2);
                pSlot += ("D".equals(pDescs[pi2]) || "J".equals(pDescs[pi2])) ? 2 : 1;
            }
            md.bytecodeInstructions = BytecodeDisassembler.disassemble(
                code.getCode(), classFile.getConstantPool(), lnt, lvNames);
        }
        // END_CHANGE: IMP-LINES-6
        // START_CHANGE: LIM-0004-20260326-9 - Populate method return type annotations
        List<AnnotationInfo> returnTypeAnns = extractTypeAnnotationsByTarget(method.getAttributes(), 0x14);
        if (!returnTypeAnns.isEmpty()) {
            md.returnTypeAnnotations = returnTypeAnns;
        }
        // END_CHANGE: LIM-0004-9
        return md;
    }

    private List<AnnotationInfo> extractAnnotations(List<Attribute> attributes) {
        List<AnnotationInfo> result = new ArrayList<AnnotationInfo>();
        for (Attribute attr : attributes) {
            if (attr instanceof RuntimeAnnotationsAttribute) {
                RuntimeAnnotationsAttribute raa = (RuntimeAnnotationsAttribute) attr;
                for (AnnotationInfo ann : raa.getAnnotations()) {
                    result.add(ann);
                }
            }
        }
        return result;
    }

    // START_CHANGE: LIM-0004-20260326-5 - Extract type annotations by target type
    private List<AnnotationInfo> extractTypeAnnotationsByTarget(List<Attribute> attributes, int targetType) {
        List<AnnotationInfo> result = new ArrayList<AnnotationInfo>();
        for (Attribute attr : attributes) {
            if (attr instanceof RuntimeTypeAnnotationsAttribute) {
                RuntimeTypeAnnotationsAttribute rtaa = (RuntimeTypeAnnotationsAttribute) attr;
                for (TypeAnnotationInfo tai : rtaa.getTypeAnnotations()) {
                    if (tai.getTargetType() == targetType) {
                        result.add(tai.getAnnotation());
                    }
                }
            }
        }
        return result;
    }
    // END_CHANGE: LIM-0004-5

    /**
     * Add casts to generic type variable for return statements in methods with generic return types.
     * E.g., "return obj" becomes "return (T) obj" when method returns type variable T.
     */
    // START_CHANGE: BUG-2026-0031-20260325-2 - Recursively add generic return casts
    private void addGenericReturnCasts(List<Statement> stmts, GenericType genType) {
        if (stmts == null) return;
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            if (stmt instanceof ReturnStatement) {
                ReturnStatement rs = (ReturnStatement) stmt;
                if (rs.hasExpression()) {
                    Expression expr = rs.getExpression();
                    // Don't wrap if already a cast to the same type
                    if (expr instanceof CastExpression) continue;
                    // Don't wrap null (null doesn't need a cast)
                    if (expr instanceof NullExpression) continue;
                    stmts.set(i, new ReturnStatement(rs.getLineNumber(),
                        new CastExpression(rs.getLineNumber(), genType, expr)));
                }
            } else if (stmt instanceof IfStatement) {
                IfStatement is = (IfStatement) stmt;
                if (is.getThenBody() instanceof BlockStatement) {
                    addGenericReturnCasts(((BlockStatement) is.getThenBody()).getStatements(), genType);
                }
            } else if (stmt instanceof IfElseStatement) {
                IfElseStatement ies = (IfElseStatement) stmt;
                if (ies.getThenBody() instanceof BlockStatement) {
                    addGenericReturnCasts(((BlockStatement) ies.getThenBody()).getStatements(), genType);
                }
                if (ies.getElseBody() instanceof BlockStatement) {
                    addGenericReturnCasts(((BlockStatement) ies.getElseBody()).getStatements(), genType);
                }
            } else if (stmt instanceof BlockStatement) {
                addGenericReturnCasts(((BlockStatement) stmt).getStatements(), genType);
            }
        }
    }
    // END_CHANGE: BUG-2026-0031-2

    /**
     * Decompile a method's bytecode into a list of statements.
     * Uses Control Flow Graph analysis to reconstruct structured control flow
     * (if/else, while, for loops) from bytecode branch instructions.
     */
    private List<Statement> decompileMethodBody(CodeAttribute codeAttr, ConstantPool pool, MethodInfo method) {
        byte[] bytecode = codeAttr.getCode();

        if (bytecode.length > DecompilerLimits.MAX_METHOD_BYTECODE_SIZE) {
            // Fall back to linear decoder for oversized methods
            final Map<Integer, Integer> pcToLine = new HashMap<Integer, Integer>(16);
            final Map<Integer, String> localVarNames = new HashMap<Integer, String>(16);
            final Map<Integer, String> localVarDescriptors = new HashMap<Integer, String>(16);
            currentLocalVarSignatures = null;
            return decompileMethodBodyLinear(codeAttr, pool, method, pcToLine, localVarNames, localVarDescriptors);
        }

        // Build line number map (pre-sized to reduce rehashing)
        final Map<Integer, Integer> pcToLine = new HashMap<Integer, Integer>(bytecode.length);
        for (Attribute attr : codeAttr.getAttributes()) {
            if (attr instanceof LineNumberTableAttribute) {
                LineNumberTableAttribute lnt = (LineNumberTableAttribute) attr;
                for (LineNumberTableAttribute.LineNumber ln : lnt.getLineNumbers()) {
                    pcToLine.put(ln.startPc, ln.lineNumber);
                }
            }
        }

        // Build local variable name map (pre-sized)
        final Map<Integer, String> localVarNames = new HashMap<Integer, String>(32);
        final Map<Integer, String> localVarDescriptors = new HashMap<Integer, String>(32);
        final Map<Integer, String> localVarSignatures = new HashMap<Integer, String>(16);
        // START_CHANGE: ISS-2026-0005-20260324-3 - Build exception handler slot set to handle LVT slot reuse
        Set<Integer> exHandlerSlots = new HashSet<Integer>();
        CodeAttribute.ExceptionEntry[] excEntries = codeAttr.getExceptionTable();
        if (excEntries != null) {
            for (int exi = 0; exi < excEntries.length; exi++) {
                int hpc = excEntries[exi].handlerPc;
                if (hpc >= 0 && hpc < bytecode.length) {
                    int hOp = bytecode[hpc] & 0xFF;
                    int hSlot = -1;
                    if (hOp == 0x3A && hpc + 1 < bytecode.length) {
                        hSlot = bytecode[hpc + 1] & 0xFF;
                    } else if (hOp >= 0x4B && hOp <= 0x4E) {
                        hSlot = hOp - 0x4B;
                    }
                    if (hSlot >= 0) {
                        exHandlerSlots.add(hSlot);
                    }
                }
            }
        }
        // END_CHANGE: ISS-2026-0005-3
        for (Attribute attr : codeAttr.getAttributes()) {
            if (attr instanceof LocalVariableTableAttribute) {
                LocalVariableTableAttribute lvt = (LocalVariableTableAttribute) attr;
                for (LocalVariableTableAttribute.LocalVariable lv : lvt.getLocalVariables()) {
                    // START_CHANGE: ISS-2026-0005-20260324-4 - For shared slots (try/catch), prefer try-body entry
                    if (exHandlerSlots.contains(lv.index) && localVarNames.containsKey(lv.index)) {
                        continue; // Don't overwrite try-body variable with catch variable
                    }
                    // END_CHANGE: ISS-2026-0005-4
                    localVarNames.put(lv.index, lv.name);
                    localVarDescriptors.put(lv.index, lv.descriptor);
                }
            }
            if (attr instanceof LocalVariableTypeTableAttribute) {
                LocalVariableTypeTableAttribute lvtt = (LocalVariableTypeTableAttribute) attr;
                for (LocalVariableTypeTableAttribute.LocalVariableType lv : lvtt.getLocalVariableTypes()) {
                    localVarSignatures.put(lv.index, lv.signature);
                }
            }
        }

        // Store bytecode for block-level decoding
        currentBytecode = bytecode;
        // Store generic signatures for use in storeLocal
        currentLocalVarSignatures = localVarSignatures;

        // Initialize variable declaration tracking
        declaredVars = new HashSet<Integer>();
        String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
        int paramSlot = method.isStatic() ? 0 : 1;
        for (int pi = 0; pi < paramDescs.length; pi++) {
            declaredVars.add(paramSlot);
            // START_CHANGE: BUG-2026-0033-20260327-1 - Populate localVarNames with param names when LVT absent
            if (!localVarNames.containsKey(paramSlot)) {
                localVarNames.put(paramSlot, "arg" + pi);
                if (pi < paramDescs.length) {
                    localVarDescriptors.put(paramSlot, paramDescs[pi]);
                }
            }
            // END_CHANGE: BUG-2026-0033-1
            paramSlot += ("D".equals(paramDescs[pi]) || "J".equals(paramDescs[pi])) ? 2 : 1;
        }
        if (!method.isStatic()) {
            declaredVars.add(0); // 'this' is already declared
        }

        // Build Control Flow Graph
        ControlFlowGraph cfg = new ControlFlowGraph(bytecode, codeAttr.getExceptionTable());
        try {
            cfg.build();
        } catch (Exception e) {
            // Fallback to linear scan if CFG build fails
            return decompileMethodBodyLinear(codeAttr, pool, method, pcToLine, localVarNames, localVarDescriptors);
        }

        if (cfg.getBlocks().isEmpty()) {
            return new ArrayList<Statement>();
        }

        // Set line numbers on blocks
        for (BasicBlock block : cfg.getBlocks()) {
            Integer lineNum = pcToLine.get(block.startPc);
            if (lineNum != null) {
                block.lineNumber = lineNum.intValue();
            }
        }

        // Pre-declare only local variables that are assigned in 2+ different
        // basic blocks (used across if/else branches). Variables assigned in
        // only one block will be declared at their assignment site, avoiding
        // duplicate declarations for for-each loop variables.
        List<Statement> preDeclarations = new ArrayList<Statement>();
        Map<Integer, Set<Integer>> varAssignBlocks = new HashMap<Integer, Set<Integer>>();
        for (BasicBlock block : cfg.getBlocks()) {
            ByteReader scanReader = new ByteReader(currentBytecode);
            scanReader.setOffset(block.startPc);
            while (scanReader.getOffset() < block.endPc && scanReader.remaining() > 0) {
                int scanOp = scanReader.readUnsignedByte();
                int storeIndex = -1;
                // istore, lstore, fstore, dstore, astore (each takes 1-byte index)
                if (scanOp >= 0x36 && scanOp <= 0x3A) {
                    storeIndex = scanReader.readUnsignedByte();
                }
                // istore_0..astore_3 (implicit index, no operand)
                else if (scanOp >= 0x3B && scanOp <= 0x4E) {
                    storeIndex = (scanOp - 0x3B) % 4;
                } else {
                    // Skip operands for non-store opcodes to avoid misreading
                    skipOpcodeOperands(scanOp, scanReader);
                }
                if (storeIndex >= 0) {
                    Set<Integer> blocks = varAssignBlocks.get(storeIndex);
                    if (blocks == null) {
                        blocks = new HashSet<Integer>();
                        varAssignBlocks.put(storeIndex, blocks);
                    }
                    blocks.add(block.startPc);
                }
            }
        }
        // START_CHANGE: ISS-2026-0005-20260324-1 - Exclude exception handler variables from pre-declarations
        // Build set of local variable slots that are exception handler catch variables.
        // The first instruction of a handler block is astore_N which stores the exception.
        Set<Integer> exceptionHandlerSlots = new HashSet<Integer>();
        CodeAttribute.ExceptionEntry[] excTable = codeAttr.getExceptionTable();
        if (excTable != null) {
            for (int ei = 0; ei < excTable.length; ei++) {
                int hpc = excTable[ei].handlerPc;
                if (hpc >= 0 && hpc < bytecode.length) {
                    int op = bytecode[hpc] & 0xFF;
                    int storeSlot = -1;
                    // astore (0x3A) takes 1-byte index
                    if (op == 0x3A && hpc + 1 < bytecode.length) {
                        storeSlot = bytecode[hpc + 1] & 0xFF;
                    }
                    // astore_0..astore_3 (0x4B..0x4E)
                    else if (op >= 0x4B && op <= 0x4E) {
                        storeSlot = op - 0x4B;
                    }
                    if (storeSlot >= 0) {
                        exceptionHandlerSlots.add(storeSlot);
                    }
                }
            }
        }
        // END_CHANGE: ISS-2026-0005-1
        for (Map.Entry<Integer, String> entry : localVarNames.entrySet()) {
            int idx = ((Integer) entry.getKey()).intValue();
            if (!declaredVars.contains(idx)) {
                // START_CHANGE: ISS-2026-0005-20260324-2 - Skip exception handler catch variables
                if (exceptionHandlerSlots.contains(idx)) {
                    continue;
                }
                // END_CHANGE: ISS-2026-0005-2
                Set<Integer> assignBlocks = varAssignBlocks.get(idx);
                if (assignBlocks != null && assignBlocks.size() >= 2) {
                    String desc = (String) localVarDescriptors.get(idx);
                    if (desc != null) {
                        Type varType = null;
                        // Prefer generic signature type
                        String sig = (String) localVarSignatures.get(idx);
                        if (sig != null) {
                            varType = parseSignatureType(sig);
                        }
                        if (varType == null) {
                            varType = parseType(desc);
                        }
                        String varName = (String) entry.getValue();
                        preDeclarations.add(new VariableDeclarationStatement(0, varType, varName, null, false, false));
                        declaredVars.add(idx);
                    }
                }
            }
        }

        // Create the bytecode decoder that populates block statements
        final ConstantPool fPool = pool;
        final MethodInfo fMethod = method;
        final Map<Integer, Integer> fPcToLine = pcToLine;

        StructuredFlowBuilder.BytecodeDecoder decoder = new StructuredFlowBuilder.BytecodeDecoder() {
            public void decodeBlock(BasicBlock block) {
                decodeBasicBlock(block, fPool, fMethod, localVarNames, localVarDescriptors, fPcToLine);
            }
        };

        // Build structured statements from CFG
        StructuredFlowBuilder builder = new StructuredFlowBuilder(cfg, decoder);
        try {
            List<Statement> result = builder.buildStatements();
            if (result != null && !result.isEmpty()) {
                // START_CHANGE: ISS-2026-0005-20260324-5 - Build handler var name map for catch variable names
                Map<Integer, String> handlerVarNames = new HashMap<Integer, String>();
                if (excEntries != null) {
                    for (int exi2 = 0; exi2 < excEntries.length; exi2++) {
                        int hpc2 = excEntries[exi2].handlerPc;
                        if (hpc2 >= 0 && hpc2 < bytecode.length) {
                            int hOp2 = bytecode[hpc2] & 0xFF;
                            int hSlot2 = -1;
                            if (hOp2 == 0x3A && hpc2 + 1 < bytecode.length) {
                                hSlot2 = bytecode[hpc2 + 1] & 0xFF;
                            } else if (hOp2 >= 0x4B && hOp2 <= 0x4E) {
                                hSlot2 = hOp2 - 0x4B;
                            }
                            if (hSlot2 >= 0) {
                                // Find LVT entry for this slot that starts at or near handler PC
                                for (Attribute attr2 : codeAttr.getAttributes()) {
                                    if (attr2 instanceof LocalVariableTableAttribute) {
                                        LocalVariableTableAttribute lvt2 = (LocalVariableTableAttribute) attr2;
                                        for (LocalVariableTableAttribute.LocalVariable lv2 : lvt2.getLocalVariables()) {
                                            if (lv2.index == hSlot2 && lv2.startPc >= hpc2) {
                                                handlerVarNames.put(hpc2, lv2.name);
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (!handlerVarNames.containsKey(hpc2)) {
                                    handlerVarNames.put(hpc2, "e");
                                }
                            }
                        }
                    }
                }
                // END_CHANGE: ISS-2026-0005-5
                // Post-process: wrap statements in try-catch using exception table
                TryCatchReconstructor tryCatchReconstructor = new TryCatchReconstructor(
                    cfg, pcToLine, localVarNames, currentBytecode, pool, handlerVarNames);
                result = tryCatchReconstructor.reconstruct(result, codeAttr.getExceptionTable());
                // Post-process: detect for-each patterns
                result = ForEachDetector.convert(result);
                // Post-process: simplify boolean comparisons (x != 0 → x, x == 0 → !x)
                String retDesc = TypeNameUtil.parseMethodReturnDescriptor(method.getDescriptor());
                boolean returnIsBoolean = "Z".equals(retDesc);
                result = BooleanSimplifier.simplify(result, returnIsBoolean);
                // START_CHANGE: ISS-2026-0011-20260323-1 - Reconstruct assert statements from $assertionsDisabled pattern
                result = reconstructAsserts(result);
                // END_CHANGE: ISS-2026-0011-1
                // START_CHANGE: ISS-2026-0008-20260324-2 - Reconstruct synchronized blocks from monitor markers
                result = reconstructSynchronized(result);
                // END_CHANGE: ISS-2026-0008-2
                // Post-process: simplify compound assignments (x = x + y → x += y)
                result = CompoundAssignmentSimplifier.simplify(result);
                // Post-process: reconstruct for-loops from while patterns
                result = ForLoopDetector.convert(result);
                // Post-process: reconstruct string switch from hashCode/equals pattern
                result = StringSwitchReconstructor.reconstruct(result);
                // START_CHANGE: LIM-0005-20260326-4 - Reconstruct pattern switch from typeSwitch bootstrap
                if (patternSwitchLabels != null && !patternSwitchLabels.isEmpty()) {
                    result = PatternSwitchReconstructor.reconstruct(result, patternSwitchLabels);
                }
                // END_CHANGE: LIM-0005-4
                // Prepend variable pre-declarations (for vars used across if/else branches)
                if (!preDeclarations.isEmpty()) {
                    List<Statement> withDecls = new ArrayList<Statement>();
                    withDecls.addAll(preDeclarations);
                    withDecls.addAll(result);
                    result = withDecls;
                }
                // Post-process: merge separate declaration + assignment into single declaration
                mergeDeclarationsWithAssignments(result);
                return result;
            }
        } catch (Exception e) {
            // Fallback to linear scan
        }

        return decompileMethodBodyLinear(codeAttr, pool, method, pcToLine, localVarNames, localVarDescriptors);
    }

    // START_CHANGE: ISS-2026-0010-20260323-1 - Track current class internal name for this() vs super() detection
    private String currentClassInternalName;
    // END_CHANGE: ISS-2026-0010-1
    // Shared bytecode reference for block-level decoding
    private byte[] currentBytecode;
    // When true, suppress branch/goto comment output (CFG handles control flow)
    private boolean suppressBranchComments = false;
    // Tracks which local variable slots have been declared (for variable declaration tracking)
    private Set<Integer> declaredVars;
    // Generic signatures from LocalVariableTypeTable (index -> signature like "TT;")
    private Map<Integer, String> currentLocalVarSignatures;
    // Map of synthetic lambda method names to their decompiled bodies
    private Map<String, List<Statement>> syntheticBodies;
    // Map of synthetic lambda method names to their parameter names from LVT
    private Map<String, List<String>> syntheticParamNames;
    // Bootstrap methods attribute for the current class
    private BootstrapMethodsAttribute bootstrapMethodsAttr;
    // START_CHANGE: LIM-0005-20260326-1 - Pattern switch case labels from typeSwitch bootstrap
    // Maps variable name → list of case label strings (types/constants) from SwitchBootstraps
    private Map<String, List<String>> patternSwitchLabels;
    // END_CHANGE: LIM-0005-1

    /**
     * Decode instructions in a single basic block.
     * Populates block.statements and block.condition.
     */
    private void decodeBasicBlock(BasicBlock block, ConstantPool pool, MethodInfo method,
                                   Map<Integer, String> localVarNames,
                                   Map<Integer, String> localVarDescriptors,
                                   Map<Integer, Integer> pcToLine) {
        if (currentBytecode == null || block.startPc >= currentBytecode.length) return;

        Deque<Expression> stack = new ArrayDeque<Expression>();
        // Seed stack from predecessor blocks that left a value (e.g., split try blocks)
        if (block.predecessors != null) {
            for (BasicBlock pred : block.predecessors) {
                if (pred.stackTopExpression != null &&
                    (pred.type == BasicBlock.FALL_THROUGH || pred.type == BasicBlock.NORMAL)) {
                    stack.push(pred.stackTopExpression);
                    // Inherit line number from predecessor if this block has none
                    if (block.lineNumber == 0 && pred.lineNumber > 0) {
                        block.lineNumber = pred.lineNumber;
                    }
                    break;
                }
            }
        }
        List<Statement> stmts = new ArrayList<Statement>();
        ByteReader reader = new ByteReader(currentBytecode);
        reader.setOffset(block.startPc);
        int currentLine = block.lineNumber;
        suppressBranchComments = true; // CFG handles control flow

        while (reader.getOffset() < block.endPc && reader.remaining() > 0) {
            int pc = reader.getOffset();
            Integer lineNum = pcToLine.get(pc);
            if (lineNum != null) currentLine = lineNum.intValue();

            int opcode = reader.readUnsignedByte();

            // For switch blocks, save the selector from the stack
            if (block.type == BasicBlock.SWITCH && (opcode == 0xAA || opcode == 0xAB)) {
                if (!stack.isEmpty()) {
                    block.selectorExpression = stack.pop();
                }
            }

            // Check if this is the branch instruction at the end of a conditional block
            if (block.isConditional() && pc >= block.endPc - 3) {
                // This is likely the branch instruction - extract condition
                Expression condition = extractBranchCondition(opcode, stack, currentLine);
                if (condition != null) {
                    block.condition = condition;
                    break;
                }
            }

            try {
                decodeOpcode(opcode, reader, stack, stmts, pool, localVarNames,
                             localVarDescriptors, currentLine, method, currentBytecode, pc);
            } catch (Exception e) {
                stmts.add(new ExpressionStatement(
                    new StringConstantExpression(currentLine,
                        "/* ERROR: opcode 0x" + Integer.toHexString(opcode) + " at pc=" + pc + " */")));
            }
        }

        block.statements = stmts;
        if (block.lineNumber == 0 && currentLine > 0) {
            block.lineNumber = currentLine;
        }
        // Save the top-of-stack expression for ternary detection
        // If the block produced no statements but has a value on the stack,
        // it's a "value producer" block (part of a ternary expression)
        if (!stack.isEmpty()) {
            block.stackTopExpression = stack.peek();
        }
    }

    /**
     * Skip the operands of a bytecode opcode (for scanning purposes).
     * Delegates to shared OpcodeInfo utility.
     */
    private void skipOpcodeOperands(int opcode, ByteReader reader) {
        // The reader is positioned just after the opcode byte, so pc = offset - 1
        int pc = reader.getOffset() - 1;
        int size = OpcodeInfo.operandSize(opcode, currentBytecode, pc);
        if (size > 0) {
            reader.skip(size);
        }
    }

    /**
     * Extract the branch condition from a conditional branch opcode.
     * Returns the condition expression (in Java terms, not bytecode terms).
     *
     * Bytecode semantics: "ifeq" = "if value == 0, branch" = "if NOT condition, skip"
     * So we NEGATE: ifeq → condition is "!= 0" (i.e., the Java condition is the opposite).
     */
    private Expression extractBranchCondition(int opcode, Deque<Expression> stack, int line) {
        Expression condition = null;

        switch (opcode) {
            // Single-operand: compare with 0
            case 0x99: { // ifeq → branch if == 0 → Java condition: != 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "!=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9A: { // ifne → branch if != 0 → Java condition: == 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "==", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9B: { // iflt → branch if < 0 → Java condition: >= 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, ">=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9C: { // ifge → branch if >= 0 → Java condition: < 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "<", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9D: { // ifgt → branch if > 0 → Java condition: <= 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "<=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9E: { // ifle → branch if <= 0 → Java condition: > 0
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, ">", IntegerConstantExpression.valueOf(line, 0));
                break;
            }

            // Two-operand: compare two values
            case 0x9F: { // if_icmpeq → branch if == → Java condition: !=
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "!=", b);
                break;
            }
            case 0xA0: { // if_icmpne → branch if != → Java condition: ==
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "==", b);
                break;
            }
            case 0xA1: { // if_icmplt → branch if < → Java condition: >=
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, ">=", b);
                break;
            }
            case 0xA2: { // if_icmpge → branch if >= → Java condition: <
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "<", b);
                break;
            }
            case 0xA3: { // if_icmpgt → branch if > → Java condition: <=
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "<=", b);
                break;
            }
            case 0xA4: { // if_icmple → branch if <= → Java condition: >
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, ">", b);
                break;
            }

            // Reference comparison
            case 0xA5: { // if_acmpeq
                Expression b = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Expression a = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "!=", b);
                break;
            }
            case 0xA6: { // if_acmpne
                Expression b = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Expression a = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "==", b);
                break;
            }

            // Null checks
            case 0xC6: { // ifnull → branch if null → Java condition: != null
                Expression val = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "!=", NullExpression.INSTANCE);
                break;
            }
            case 0xC7: { // ifnonnull → branch if not null → Java condition: == null
                Expression val = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "==", NullExpression.INSTANCE);
                break;
            }

            default:
                break;
        }

        return condition;
    }


    /**
     * Fallback: linear decompilation without CFG analysis.
     */
    private List<Statement> decompileMethodBodyLinear(CodeAttribute codeAttr, ConstantPool pool,
                                                       MethodInfo method,
                                                       Map<Integer, Integer> pcToLine,
                                                       Map<Integer, String> localVarNames,
                                                       Map<Integer, String> localVarDescriptors) {
        byte[] bytecode = codeAttr.getCode();
        List<Statement> statements = new ArrayList<Statement>();
        Deque<Expression> stack = new ArrayDeque<Expression>();

        // Initialize variable declaration tracking for linear mode
        declaredVars = new HashSet<Integer>();
        String[] paramDescsLin = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
        int paramSlotLin = method.isStatic() ? 0 : 1;
        for (int pi = 0; pi < paramDescsLin.length; pi++) {
            declaredVars.add(paramSlotLin);
            paramSlotLin += ("D".equals(paramDescsLin[pi]) || "J".equals(paramDescsLin[pi])) ? 2 : 1;
        }
        if (!method.isStatic()) {
            declaredVars.add(0); // 'this' is already declared
        }

        ByteReader reader = new ByteReader(bytecode);
        int currentLine = 0;

        while (reader.remaining() > 0) {
            int pc = reader.getOffset();
            Integer lineNum = pcToLine.get(pc);
            if (lineNum != null) currentLine = lineNum;

            int opcode = reader.readUnsignedByte();

            try {
                decodeOpcode(opcode, reader, stack, statements, pool, localVarNames,
                             localVarDescriptors, currentLine, method, bytecode, pc);
            } catch (Exception e) {
                // Log the error as a comment and continue decompilation
                String hexOpcode = "0x" + Integer.toHexString(opcode).toUpperCase();
                String errorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
                statements.add(new ExpressionStatement(
                    new StringConstantExpression(currentLine,
                        "/* ERROR: Unable to decompile opcode " + hexOpcode +
                        " at pc=" + pc + " - " + errorDetail +
                        " (stack size=" + stack.size() + ", bytecode remaining=" + reader.remaining() + ") */")));
                // Don't break - try to continue with remaining bytecode
            }
        }

        return statements;
    }

    @SuppressWarnings("fallthrough")
    private void decodeOpcode(int opcode, ByteReader reader, Deque<Expression> stack,
                               List<Statement> statements, ConstantPool pool,
                               Map<Integer, String> localVarNames,
                               Map<Integer, String> localVarDescriptors,
                               int line, MethodInfo method, byte[] bytecode, int pc) {

        switch (opcode) {
            // Constants
            case 0x00: // nop
                break;
            case 0x01: // aconst_null
                stack.push(NullExpression.INSTANCE);
                break;
            case 0x02: // iconst_m1
                stack.push(IntegerConstantExpression.valueOf(line, -1));
                break;
            case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: // iconst_0 .. iconst_5
                stack.push(IntegerConstantExpression.valueOf(line, opcode - 0x03));
                break;
            case 0x09: case 0x0A: // lconst_0, lconst_1
                stack.push(new LongConstantExpression(line, opcode - 0x09));
                break;
            case 0x0B: case 0x0C: case 0x0D: // fconst_0, fconst_1, fconst_2
                stack.push(new FloatConstantExpression(line, opcode - 0x0B));
                break;
            case 0x0E: case 0x0F: // dconst_0, dconst_1
                stack.push(new DoubleConstantExpression(line, opcode - 0x0E));
                break;
            case 0x10: // bipush
                stack.push(IntegerConstantExpression.valueOf(line, reader.readByte()));
                break;
            case 0x11: // sipush
                stack.push(IntegerConstantExpression.valueOf(line, reader.readShort()));
                break;
            case 0x12: { // ldc
                int index = reader.readUnsignedByte();
                stack.push(getConstantExpression(index, pool, line));
                break;
            }
            case 0x13: case 0x14: { // ldc_w, ldc2_w
                int index = reader.readUnsignedShort();
                stack.push(getConstantExpression(index, pool, line));
                break;
            }

            // Loads
            case 0x15: // iload
                pushLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, line, PrimitiveType.INT);
                break;
            case 0x16: // lload
                pushLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, line, PrimitiveType.LONG);
                break;
            case 0x17: // fload
                pushLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, line, PrimitiveType.FLOAT);
                break;
            case 0x18: // dload
                pushLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, line, PrimitiveType.DOUBLE);
                break;
            case 0x19: // aload
                pushLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, line, ObjectType.OBJECT);
                break;
            case 0x1A: case 0x1B: case 0x1C: case 0x1D: // iload_0..3
                pushLocal(stack, opcode - 0x1A, localVarNames, localVarDescriptors, line, PrimitiveType.INT);
                break;
            case 0x1E: case 0x1F: case 0x20: case 0x21: // lload_0..3
                pushLocal(stack, opcode - 0x1E, localVarNames, localVarDescriptors, line, PrimitiveType.LONG);
                break;
            case 0x22: case 0x23: case 0x24: case 0x25: // fload_0..3
                pushLocal(stack, opcode - 0x22, localVarNames, localVarDescriptors, line, PrimitiveType.FLOAT);
                break;
            case 0x26: case 0x27: case 0x28: case 0x29: // dload_0..3
                pushLocal(stack, opcode - 0x26, localVarNames, localVarDescriptors, line, PrimitiveType.DOUBLE);
                break;
            case 0x2A: case 0x2B: case 0x2C: case 0x2D: { // aload_0..3
                int idx = opcode - 0x2A;
                if (idx == 0 && !method.isStatic()) {
                    String thisDesc = localVarDescriptors.containsKey(0) ? (String) localVarDescriptors.get(0) : "Ljava/lang/Object;";
                    stack.push(new ThisExpression(line, new ObjectType(thisDesc.replace("L","").replace(";",""))));
                } else {
                    pushLocal(stack, idx, localVarNames, localVarDescriptors, line, ObjectType.OBJECT);
                }
                break;
            }

            // Array operations (loads)
            case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: case 0x35: { // iaload..saload
                Expression idx = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression arr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                stack.push(new ArrayAccessExpression(line, PrimitiveType.INT, arr, idx));
                break;
            }

            // Stores
            case 0x36: // istore
                storeLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.INT);
                break;
            case 0x37: // lstore
                storeLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.LONG);
                break;
            case 0x38: // fstore
                storeLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.FLOAT);
                break;
            case 0x39: // dstore
                storeLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.DOUBLE);
                break;
            case 0x3A: // astore
                storeLocal(stack, reader.readUnsignedByte(), localVarNames, localVarDescriptors, statements, line, ObjectType.OBJECT);
                break;
            case 0x3B: case 0x3C: case 0x3D: case 0x3E: // istore_0..3
                storeLocal(stack, opcode - 0x3B, localVarNames, localVarDescriptors, statements, line, PrimitiveType.INT);
                break;
            case 0x3F: case 0x40: case 0x41: case 0x42: // lstore_0..3
                storeLocal(stack, opcode - 0x3F, localVarNames, localVarDescriptors, statements, line, PrimitiveType.LONG);
                break;
            case 0x43: case 0x44: case 0x45: case 0x46: // fstore_0..3
                storeLocal(stack, opcode - 0x43, localVarNames, localVarDescriptors, statements, line, PrimitiveType.FLOAT);
                break;
            case 0x47: case 0x48: case 0x49: case 0x4A: // dstore_0..3
                storeLocal(stack, opcode - 0x47, localVarNames, localVarDescriptors, statements, line, PrimitiveType.DOUBLE);
                break;
            case 0x4B: case 0x4C: case 0x4D: case 0x4E: // astore_0..3
                storeLocal(stack, opcode - 0x4B, localVarNames, localVarDescriptors, statements, line, ObjectType.OBJECT);
                break;

            // Array operations (stores)
            case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56: { // iastore..sastore
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression idx = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression arr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                // START_CHANGE: ISS-2026-0002-20260323-3 - Detect array init pattern: newarray + dup + idx + val + iastore
                if (arr instanceof NewArrayExpression) {
                    NewArrayExpression nae = (NewArrayExpression) arr;
                    nae.addInitValue(val);
                    // Push the array back on the stack (the dup before each store keeps a copy)
                    stack.push(nae);
                    break;
                }
                // END_CHANGE: ISS-2026-0002-3
                Expression access = new ArrayAccessExpression(line, PrimitiveType.INT, arr, idx);
                statements.add(new ExpressionStatement(
                    new AssignmentExpression(line, PrimitiveType.INT, access, "=", val)));
                break;
            }

            // Stack manipulation
            case 0x57: { // pop - discard top of stack
                if (!stack.isEmpty()) {
                    Expression popped = stack.pop();
                    // If the popped expression is a method call with side effects, emit it as a statement
                    if (popped instanceof MethodInvocationExpression
                        || popped instanceof StaticMethodInvocationExpression
                        || popped instanceof NewExpression
                        || popped instanceof AssignmentExpression) {
                        statements.add(new ExpressionStatement(popped));
                    }
                }
                break;
            }
            case 0x58: { // pop2
                if (!stack.isEmpty()) {
                    Expression popped = stack.pop();
                    if (popped instanceof MethodInvocationExpression
                        || popped instanceof StaticMethodInvocationExpression) {
                        statements.add(new ExpressionStatement(popped));
                    }
                }
                if (!stack.isEmpty()) stack.pop();
                break;
            }
            case 0x59: // dup
                if (!stack.isEmpty()) stack.push(stack.peek());
                break;
            case 0x5A: { // dup_x1
                if (stack.size() >= 2) {
                    Expression v1 = stack.pop();
                    Expression v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                break;
            }
            case 0x5B: { // dup_x2
                if (stack.size() >= 3) {
                    Expression v1 = stack.pop();
                    Expression v2 = stack.pop();
                    Expression v3 = stack.pop();
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                } else if (stack.size() >= 2) {
                    Expression v1 = stack.pop();
                    Expression v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                break;
            }
            case 0x5C: { // dup2
                if (stack.size() >= 2) {
                    Expression v1 = stack.pop();
                    Expression v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                } else if (!stack.isEmpty()) {
                    stack.push(stack.peek());
                }
                break;
            }
            case 0x5D: case 0x5E: // dup2_x1, dup2_x2
                break;
            case 0x5F: { // swap
                if (stack.size() >= 2) {
                    Expression a = stack.pop();
                    Expression b = stack.pop();
                    stack.push(a);
                    stack.push(b);
                }
                break;
            }

            // Arithmetic
            case 0x60: binaryOp(stack, "+", PrimitiveType.INT, line); break; // iadd
            case 0x61: binaryOp(stack, "+", PrimitiveType.LONG, line); break; // ladd
            case 0x62: binaryOp(stack, "+", PrimitiveType.FLOAT, line); break; // fadd
            case 0x63: binaryOp(stack, "+", PrimitiveType.DOUBLE, line); break; // dadd
            case 0x64: binaryOp(stack, "-", PrimitiveType.INT, line); break; // isub
            case 0x65: binaryOp(stack, "-", PrimitiveType.LONG, line); break; // lsub
            case 0x66: binaryOp(stack, "-", PrimitiveType.FLOAT, line); break; // fsub
            case 0x67: binaryOp(stack, "-", PrimitiveType.DOUBLE, line); break; // dsub
            case 0x68: binaryOp(stack, "*", PrimitiveType.INT, line); break; // imul
            case 0x69: binaryOp(stack, "*", PrimitiveType.LONG, line); break; // lmul
            case 0x6A: binaryOp(stack, "*", PrimitiveType.FLOAT, line); break; // fmul
            case 0x6B: binaryOp(stack, "*", PrimitiveType.DOUBLE, line); break; // dmul
            case 0x6C: binaryOp(stack, "/", PrimitiveType.INT, line); break; // idiv
            case 0x6D: binaryOp(stack, "/", PrimitiveType.LONG, line); break; // ldiv
            case 0x6E: binaryOp(stack, "/", PrimitiveType.FLOAT, line); break; // fdiv
            case 0x6F: binaryOp(stack, "/", PrimitiveType.DOUBLE, line); break; // ddiv
            case 0x70: binaryOp(stack, "%", PrimitiveType.INT, line); break; // irem
            case 0x71: binaryOp(stack, "%", PrimitiveType.LONG, line); break; // lrem
            case 0x72: binaryOp(stack, "%", PrimitiveType.FLOAT, line); break; // frem
            case 0x73: binaryOp(stack, "%", PrimitiveType.DOUBLE, line); break; // drem
            case 0x74: unaryOp(stack, "-", PrimitiveType.INT, line); break; // ineg
            case 0x75: unaryOp(stack, "-", PrimitiveType.LONG, line); break; // lneg
            case 0x76: unaryOp(stack, "-", PrimitiveType.FLOAT, line); break; // fneg
            case 0x77: unaryOp(stack, "-", PrimitiveType.DOUBLE, line); break; // dneg
            case 0x78: binaryOp(stack, "<<", PrimitiveType.INT, line); break; // ishl
            case 0x79: binaryOp(stack, "<<", PrimitiveType.LONG, line); break; // lshl
            case 0x7A: binaryOp(stack, ">>", PrimitiveType.INT, line); break; // ishr
            case 0x7B: binaryOp(stack, ">>", PrimitiveType.LONG, line); break; // lshr
            case 0x7C: binaryOp(stack, ">>>", PrimitiveType.INT, line); break; // iushr
            case 0x7D: binaryOp(stack, ">>>", PrimitiveType.LONG, line); break; // lushr
            case 0x7E: binaryOp(stack, "&", PrimitiveType.INT, line); break; // iand
            case 0x7F: binaryOp(stack, "&", PrimitiveType.LONG, line); break; // land
            case 0x80: binaryOp(stack, "|", PrimitiveType.INT, line); break; // ior
            case 0x81: binaryOp(stack, "|", PrimitiveType.LONG, line); break; // lor
            case 0x82: binaryOp(stack, "^", PrimitiveType.INT, line); break; // ixor
            case 0x83: binaryOp(stack, "^", PrimitiveType.LONG, line); break; // lxor
            case 0x84: { // iinc
                int varIdx = reader.readUnsignedByte();
                int incr = reader.readByte();
                String name = localVarNames.containsKey(varIdx) ? (String) localVarNames.get(varIdx) : "var" + varIdx;
                Expression var = new LocalVariableExpression(line, PrimitiveType.INT, name, varIdx);
                if (incr == 1) {
                    statements.add(new ExpressionStatement(
                        new UnaryOperatorExpression(line, PrimitiveType.INT, "++", var, false)));
                } else if (incr == -1) {
                    statements.add(new ExpressionStatement(
                        new UnaryOperatorExpression(line, PrimitiveType.INT, "--", var, false)));
                } else {
                    statements.add(new ExpressionStatement(
                        new AssignmentExpression(line, PrimitiveType.INT, var, "+=",
                            IntegerConstantExpression.valueOf(line, incr))));
                }
                break;
            }

            // Type conversions
            case 0x85: castTop(stack, PrimitiveType.LONG, line); break; // i2l
            case 0x86: castTop(stack, PrimitiveType.FLOAT, line); break; // i2f
            case 0x87: castTop(stack, PrimitiveType.DOUBLE, line); break; // i2d
            case 0x88: castTop(stack, PrimitiveType.INT, line); break; // l2i
            case 0x89: castTop(stack, PrimitiveType.FLOAT, line); break; // l2f
            case 0x8A: castTop(stack, PrimitiveType.DOUBLE, line); break; // l2d
            case 0x8B: castTop(stack, PrimitiveType.INT, line); break; // f2i
            case 0x8C: castTop(stack, PrimitiveType.LONG, line); break; // f2l
            case 0x8D: castTop(stack, PrimitiveType.DOUBLE, line); break; // f2d
            case 0x8E: castTop(stack, PrimitiveType.INT, line); break; // d2i
            case 0x8F: castTop(stack, PrimitiveType.LONG, line); break; // d2l
            case 0x90: castTop(stack, PrimitiveType.FLOAT, line); break; // d2f
            case 0x91: castTop(stack, PrimitiveType.BYTE, line); break; // i2b
            case 0x92: castTop(stack, PrimitiveType.CHAR, line); break; // i2c
            case 0x93: castTop(stack, PrimitiveType.SHORT, line); break; // i2s

            // comparison ops
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98: { // lcmp, fcmp, dcmp
                Expression b = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Expression a = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                stack.push(new BinaryOperatorExpression(line, PrimitiveType.INT, a, "<=>", b));
                break;
            }

            // Conditional branches - single operand compared to 0 (ifeq..ifle)
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                    String op;
                    switch (opcode) {
                        case 0x99: op = "=="; break;
                        case 0x9A: op = "!="; break;
                        case 0x9B: op = "<"; break;
                        case 0x9C: op = ">="; break;
                        case 0x9D: op = ">"; break;
                        case 0x9E: op = "<="; break;
                        default: op = "??"; break;
                    }
                    statements.add(new ExpressionStatement(
                        new StringConstantExpression(line,
                            "/* if (" + val + " " + op + " 0) goto pc=" + targetPc + " */")));
                }
                break;
            }
            // Conditional branches - two int operands (if_icmpeq..if_icmple)
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val2 = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                    Expression val1 = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                    String op;
                    switch (opcode) {
                        case 0x9F: op = "=="; break;
                        case 0xA0: op = "!="; break;
                        case 0xA1: op = "<"; break;
                        case 0xA2: op = ">="; break;
                        case 0xA3: op = ">"; break;
                        case 0xA4: op = "<="; break;
                        default: op = "??"; break;
                    }
                    statements.add(new ExpressionStatement(
                        new StringConstantExpression(line,
                            "/* if (" + val1 + " " + op + " " + val2 + ") goto pc=" + targetPc + " */")));
                }
                break;
            }
            // Conditional branches - two reference operands (if_acmpeq, if_acmpne)
            case 0xA5: case 0xA6: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val2 = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                    Expression val1 = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                    String op = (opcode == 0xA5) ? "==" : "!=";
                    statements.add(new ExpressionStatement(
                        new StringConstantExpression(line,
                            "/* if (" + val1 + " " + op + " " + val2 + ") goto pc=" + targetPc + " */")));
                }
                break;
            }
            // Unconditional branch
            case 0xA7: { // goto
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    statements.add(new ExpressionStatement(
                        new StringConstantExpression(line,
                            "/* goto pc=" + targetPc + " */")));
                }
                break;
            }
            case 0xA8: // jsr
                reader.readShort();
                break;
            case 0xA9: // ret
                reader.readUnsignedByte();
                break;

            // tableswitch / lookupswitch - skip for now
            case 0xAA: { // tableswitch
                int pad = (4 - ((reader.getOffset()) % 4)) % 4;
                reader.skip(pad);
                reader.readInt(); // default
                int low = reader.readInt();
                int high = reader.readInt();
                reader.skip((high - low + 1) * 4);
                break;
            }
            case 0xAB: { // lookupswitch
                int pad = (4 - ((reader.getOffset()) % 4)) % 4;
                reader.skip(pad);
                reader.readInt(); // default
                int npairs = reader.readInt();
                reader.skip(npairs * 8);
                break;
            }

            // Returns
            case 0xAC: { // ireturn
                Expression val = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                statements.add(new ReturnStatement(line, val));
                break;
            }
            case 0xAD: { // lreturn
                Expression val = stack.isEmpty() ? new LongConstantExpression(line, 0) : stack.pop();
                statements.add(new ReturnStatement(line, val));
                break;
            }
            case 0xAE: { // freturn
                Expression val = stack.isEmpty() ? new FloatConstantExpression(line, 0) : stack.pop();
                statements.add(new ReturnStatement(line, val));
                break;
            }
            case 0xAF: { // dreturn
                Expression val = stack.isEmpty() ? new DoubleConstantExpression(line, 0) : stack.pop();
                statements.add(new ReturnStatement(line, val));
                break;
            }
            case 0xB0: { // areturn
                Expression val = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                statements.add(new ReturnStatement(line, val));
                break;
            }
            case 0xB1: { // return (void)
                statements.add(new ReturnStatement(line));
                break;
            }

            // Field access
            case 0xB2: { // getstatic
                int index = reader.readUnsignedShort();
                String className = pool.getMemberClassName(index);
                String fieldName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                Type fieldType = parseType(desc);
                stack.push(new FieldAccessExpression(line, fieldType, null, className, fieldName, desc));
                break;
            }
            case 0xB3: { // putstatic
                int index = reader.readUnsignedShort();
                String className = pool.getMemberClassName(index);
                String fieldName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                Type fieldType = parseType(desc);
                Expression value = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Expression field = new FieldAccessExpression(line, fieldType, null, className, fieldName, desc);
                statements.add(new ExpressionStatement(
                    new AssignmentExpression(line, fieldType, field, "=", value)));
                break;
            }
            case 0xB4: { // getfield
                int index = reader.readUnsignedShort();
                String className = pool.getMemberClassName(index);
                String fieldName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                Type fieldType = parseType(desc);
                Expression obj = stack.isEmpty() ? new ThisExpression(line, ObjectType.OBJECT) : stack.pop();
                stack.push(new FieldAccessExpression(line, fieldType, obj, className, fieldName, desc));
                break;
            }
            case 0xB5: { // putfield
                int index = reader.readUnsignedShort();
                String className = pool.getMemberClassName(index);
                String fieldName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                Type fieldType = parseType(desc);
                Expression value = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Expression obj = stack.isEmpty() ? new ThisExpression(line, ObjectType.OBJECT) : stack.pop();
                Expression field = new FieldAccessExpression(line, fieldType, obj, className, fieldName, desc);
                statements.add(new ExpressionStatement(
                    new AssignmentExpression(line, fieldType, field, "=", value)));
                break;
            }

            // Method invocation
            case 0xB6: case 0xB7: case 0xB9: { // invokevirtual, invokespecial, invokeinterface
                int index = reader.readUnsignedShort();
                if (opcode == 0xB9) {
                    reader.readUnsignedByte(); // count
                    reader.readUnsignedByte(); // 0
                }
                String className = pool.getMemberClassName(index);
                String methodName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(desc);
                String retDesc = TypeNameUtil.parseMethodReturnDescriptor(desc);
                Type retType = parseType(retDesc);

                List<Expression> args = new ArrayList<Expression>();
                for (int i = paramDescs.length - 1; i >= 0; i--) {
                    Expression arg = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                    // START_CHANGE: BUG-2026-0043-20260327-2 - Convert int constants to correct types for typed params
                    if (arg instanceof IntegerConstantExpression) {
                        int v = ((IntegerConstantExpression) arg).getValue();
                        if ("Z".equals(paramDescs[i]) && (v == 0 || v == 1)) {
                            arg = v != 0 ? BooleanExpression.TRUE : BooleanExpression.FALSE;
                        } else if ("C".equals(paramDescs[i])) {
                            arg = new CastExpression(arg.getLineNumber(), PrimitiveType.CHAR, arg);
                        }
                    }
                    // END_CHANGE: BUG-2026-0043-2
                    args.add(0, arg);
                }
                Expression obj = stack.isEmpty() ? new ThisExpression(line, ObjectType.OBJECT) : stack.pop();

                if (StringConstants.CONSTRUCTOR_NAME.equals(methodName)) {
                    // Constructor invocation: new + dup + invokespecial pattern
                    // Bytecode: new X → dup → [args...] → invokespecial X.<init>
                    // After dup, stack has [..., NewExpr, NewExpr_copy]
                    // invokespecial pops the copy (obj) + args, we replace the original
                    if (obj instanceof NewExpression) {
                        Expression newExpr = new NewExpression(line, new ObjectType(className), className, desc, args);
                        // Remove the original NewExpression that dup placed (it's still on stack)
                        // The dup pushed a copy - invokespecial consumed it (obj).
                        // The original is still below. Pop it and replace with the fully-constructed version.
                        if (!stack.isEmpty() && stack.peek() instanceof NewExpression) {
                            stack.pop(); // remove the original placeholder from dup
                        }
                        stack.push(newExpr);
                    } else {
                        // super() or this() call in constructor
                        // START_CHANGE: ISS-2026-0010-20260323-3 - Distinguish this() from super() by comparing target class
                        String displayName = "super";
                        if (currentClassInternalName != null && className.equals(currentClassInternalName)) {
                            displayName = "this";
                        }
                        // END_CHANGE: ISS-2026-0010-3
                        Expression invocation = new MethodInvocationExpression(
                            line, VoidType.INSTANCE, obj, className, displayName, desc, args);
                        statements.add(new ExpressionStatement(invocation));
                    }
                } else {
                    Expression invocation = new MethodInvocationExpression(
                        line, retType, obj, className, methodName, desc, args);
                    if ("V".equals(retDesc)) {
                        statements.add(new ExpressionStatement(invocation));
                    } else {
                        stack.push(invocation);
                    }
                }
                break;
            }
            case 0xB8: { // invokestatic
                int index = reader.readUnsignedShort();
                String className = pool.getMemberClassName(index);
                String methodName = pool.getMemberName(index);
                String desc = pool.getMemberDescriptor(index);
                String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(desc);
                String retDesc = TypeNameUtil.parseMethodReturnDescriptor(desc);
                Type retType = parseType(retDesc);

                List<Expression> args = new ArrayList<Expression>();
                for (int i = paramDescs.length - 1; i >= 0; i--) {
                    Expression arg = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                    // START_CHANGE: BUG-2026-0043-20260327-3 - Convert int constants to correct types for typed params (static)
                    if (arg instanceof IntegerConstantExpression) {
                        int v = ((IntegerConstantExpression) arg).getValue();
                        if ("Z".equals(paramDescs[i]) && (v == 0 || v == 1)) {
                            arg = v != 0 ? BooleanExpression.TRUE : BooleanExpression.FALSE;
                        } else if ("C".equals(paramDescs[i])) {
                            arg = new CastExpression(arg.getLineNumber(), PrimitiveType.CHAR, arg);
                        }
                    }
                    // END_CHANGE: BUG-2026-0043-3
                    args.add(0, arg);
                }

                Expression invocation = new StaticMethodInvocationExpression(
                    line, retType, className, methodName, desc, args);
                if ("V".equals(retDesc)) {
                    statements.add(new ExpressionStatement(invocation));
                } else {
                    stack.push(invocation);
                }
                break;
            }

            // invokedynamic
            case 0xBA: {
                int index = reader.readUnsignedShort();
                reader.readUnsignedShort(); // 0, 0
                int[] indyEntry = pool.getValue(index);
                String methodName = pool.getNameFromNameAndType(indyEntry[1]);
                String desc = pool.getDescriptorFromNameAndType(indyEntry[1]);
                String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(desc);
                String retDesc = TypeNameUtil.parseMethodReturnDescriptor(desc);
                Type retType = parseType(retDesc);

                List<Expression> args = new ArrayList<Expression>();
                for (int i = paramDescs.length - 1; i >= 0; i--) {
                    args.add(0, stack.isEmpty() ? NullExpression.INSTANCE : stack.pop());
                }

                // Detect string concatenation pattern (Java 9+)
                if ("makeConcatWithConstants".equals(methodName) && args.size() > 0) {
                    // Try to get the template from bootstrap method arguments
                    String template = null;
                    if (bootstrapMethodsAttr != null) {
                        int bsmIndex = indyEntry[0];
                        BootstrapMethodsAttribute.BootstrapMethod[] bsms = bootstrapMethodsAttr.getBootstrapMethods();
                        if (bsmIndex >= 0 && bsmIndex < bsms.length) {
                            BootstrapMethodsAttribute.BootstrapMethod bsm = bsms[bsmIndex];
                            if (bsm.bootstrapArguments.length > 0) {
                                // First argument is the template string
                                template = pool.getStringConstant(bsm.bootstrapArguments[0]);
                                if (template == null) {
                                    template = pool.getUtf8(bsm.bootstrapArguments[0]);
                                }
                            }
                        }
                    }

                    if (template != null) {
                        // Parse template: \1 markers are replaced with args
                        Expression concat = null;
                        int argIndex = 0;
                        int ti = 0;
                        while (ti < template.length()) {
                            char c = template.charAt(ti);
                            if (c == '\u0001' && argIndex < args.size()) {
                                // Argument placeholder
                                Expression arg = args.get(argIndex++);
                                if (concat == null) {
                                    concat = arg;
                                } else {
                                    concat = new BinaryOperatorExpression(line, ObjectType.STRING, concat, "+", arg);
                                }
                                ti++;
                            } else {
                                // Literal text
                                int start = ti;
                                while (ti < template.length() && template.charAt(ti) != '\u0001') ti++;
                                String literal = template.substring(start, ti);
                                Expression strExpr = new StringConstantExpression(line, literal);
                                if (concat == null) {
                                    concat = strExpr;
                                } else {
                                    concat = new BinaryOperatorExpression(line, ObjectType.STRING, concat, "+", strExpr);
                                }
                            }
                        }
                        // Append remaining args not covered by template
                        while (argIndex < args.size()) {
                            Expression arg = args.get(argIndex++);
                            if (concat == null) {
                                concat = arg;
                            } else {
                                concat = new BinaryOperatorExpression(line, ObjectType.STRING, concat, "+", arg);
                            }
                        }
                        if (concat != null) {
                            stack.push(concat);
                            break;
                        }
                    }

                    // Fallback: simple concatenation
                    Expression concat = args.get(0);
                    // Ensure first arg is treated as String
                    if (!(concat instanceof StringConstantExpression)) {
                        // Wrap with empty string to force string context
                        concat = new BinaryOperatorExpression(line, ObjectType.STRING,
                            new StringConstantExpression(line, ""), "+", concat);
                    }
                    for (int i = 1; i < args.size(); i++) {
                        concat = new BinaryOperatorExpression(line, ObjectType.STRING, concat, "+", args.get(i));
                    }
                    stack.push(concat);
                    break;
                }

                // START_CHANGE: LIM-0005-20260326-2 - Detect SwitchBootstraps.typeSwitch/enumSwitch
                if (("typeSwitch".equals(methodName) || "enumSwitch".equals(methodName))
                        && bootstrapMethodsAttr != null) {
                    int bsmIndex = indyEntry[0];
                    BootstrapMethodsAttribute.BootstrapMethod[] bsms = bootstrapMethodsAttr.getBootstrapMethods();
                    if (bsmIndex >= 0 && bsmIndex < bsms.length) {
                        BootstrapMethodsAttribute.BootstrapMethod bsm = bsms[bsmIndex];
                        // Extract case label types from bootstrap arguments
                        List<String> caseLabels = new ArrayList<String>();
                        for (int bi = 0; bi < bsm.bootstrapArguments.length; bi++) {
                            int argIdx = bsm.bootstrapArguments[bi];
                            int tag = pool.getTag(argIdx);
                            if (tag == ConstantPool.CONSTANT_Class) {
                                caseLabels.add(pool.getClassName(argIdx));
                            } else if (tag == ConstantPool.CONSTANT_String) {
                                caseLabels.add("\"" + pool.getStringConstant(argIdx) + "\"");
                            } else if (tag == ConstantPool.CONSTANT_Integer) {
                                Object val = pool.getValue(argIdx);
                                caseLabels.add(String.valueOf(val));
                            } else {
                                String utf8 = pool.getUtf8(argIdx);
                                caseLabels.add(utf8 != null ? utf8 : "/* case " + bi + " */");
                            }
                        }
                        // Store labels keyed by method name for PatternSwitchReconstructor
                        if (patternSwitchLabels == null) {
                            patternSwitchLabels = new HashMap<String, List<String>>();
                        }
                        patternSwitchLabels.put(methodName + "_" + line, caseLabels);
                        // Push the selector (first arg) as the result - the switch will use it
                        Expression selector = args.isEmpty() ? NullExpression.INSTANCE : args.get(0);
                        // Create a tagged method invocation so the reconstructor can find it
                        Expression invocation = new StaticMethodInvocationExpression(
                            line, retType, "java/lang/runtime/SwitchBootstraps",
                            methodName, desc, args);
                        stack.push(invocation);
                        break;
                    }
                }
                // END_CHANGE: LIM-0005-2

                // Check if this is a lambda (not string concat, empty class name)
                if (methodName != null && !"makeConcatWithConstants".equals(methodName)) {
                    // Try to find lambda body from BootstrapMethods
                    if (bootstrapMethodsAttr != null && syntheticBodies != null) {
                        int bsmIndex = indyEntry[0];
                        BootstrapMethodsAttribute.BootstrapMethod[] bsms = bootstrapMethodsAttr.getBootstrapMethods();
                        if (bsmIndex >= 0 && bsmIndex < bsms.length) {
                            BootstrapMethodsAttribute.BootstrapMethod bsm = bsms[bsmIndex];
                            if (bsm.bootstrapArguments.length >= 3) {
                                // Second argument (index 1) is the implementation method handle
                                int methodHandleIndex = bsm.bootstrapArguments[1];
                                if (pool.getTag(methodHandleIndex) == ConstantPool.CONSTANT_MethodHandle) {
                                    int[] handleEntry = pool.getValue(methodHandleIndex);
                                    // handleEntry[0] is reference kind, handleEntry[1] is reference index
                                    String implMethodName = pool.getMemberName(handleEntry[1]);
                                    if (implMethodName != null && syntheticBodies.containsKey(implMethodName)) {
                                        List<Statement> lambdaBody = syntheticBodies.get(implMethodName);
                                        // Determine lambda parameter names from the synthetic method descriptor
                                        String implDesc = pool.getMemberDescriptor(handleEntry[1]);
                                        List<String> lambdaParamNames = new ArrayList<String>();
                                        List<Type> lambdaParamTypes = new ArrayList<Type>();
                                        // Get LVT-based names if available
                                        List<String> lvtNames = syntheticParamNames != null
                                            ? syntheticParamNames.get(implMethodName) : null;
                                        if (implDesc != null) {
                                            String[] implParamDescs = TypeNameUtil.parseMethodParameterDescriptors(implDesc);
                                            // Skip captured args (the invokedynamic args are captures)
                                            int capturedCount = args.size();
                                            for (int pi = capturedCount; pi < implParamDescs.length; pi++) {
                                                String pName = (lvtNames != null && pi < lvtNames.size())
                                                    ? lvtNames.get(pi) : "arg" + (pi - capturedCount);
                                                lambdaParamNames.add(pName);
                                                lambdaParamTypes.add(parseType(implParamDescs[pi]));
                                            }
                                        }
                                        if (lambdaParamNames.isEmpty()) {
                                            // Zero-arg lambda (e.g., Runnable)
                                        }
                                        Statement body = new BlockStatement(line, lambdaBody);
                                        Expression lambda = new LambdaExpression(line, retType, lambdaParamNames, lambdaParamTypes, body);
                                        stack.push(lambda);
                                        break;
                                    }
                                    // START_CHANGE: BUG-2026-0020-20260324-1 - Detect method references (non-synthetic impl method)
                                    // If the impl method is not a synthetic lambda body, it's a method reference
                                    if (implMethodName != null) {
                                        int refKind = handleEntry[0];
                                        String ownerName = pool.getMemberClassName(handleEntry[1]);
                                        String implDesc = pool.getMemberDescriptor(handleEntry[1]);
                                        if (ownerName == null) ownerName = "";
                                        // For REF_invokeVirtual (5), REF_invokeInterface (9): instance method ref
                                        // For REF_invokeStatic (6): static method ref
                                        // For REF_newInvokeSpecial (8): constructor ref (Type::new)
                                        String refMethodName = implMethodName;
                                        if (refKind == 8) {
                                            refMethodName = "new";
                                        }
                                        Expression methodRef = new MethodReferenceExpression(
                                            line, retType, null, ownerName, refMethodName, implDesc);
                                        stack.push(methodRef);
                                        break;
                                    }
                                    // END_CHANGE: BUG-2026-0020-1
                                }
                            }
                        }
                    }

                    // Fallback: lambda without body
                    // This is likely a lambda from LambdaMetafactory
                    String lambdaRetDesc = TypeNameUtil.parseMethodReturnDescriptor(desc);
                    // Use captured args as the lambda parameters
                    List<String> lambdaParamNames = new ArrayList<String>();
                    List<Type> lambdaParamTypes = new ArrayList<Type>();
                    for (int i = 0; i < args.size(); i++) {
                        lambdaParamNames.add("arg" + i);
                        lambdaParamTypes.add(args.get(i).getType());
                    }
                    // If no captured args, create placeholder params based on methodName
                    if (lambdaParamNames.isEmpty()) {
                        lambdaParamNames.add("arg0");
                        lambdaParamTypes.add(ObjectType.OBJECT);
                    }
                    Expression lambda = new LambdaExpression(line, retType, lambdaParamNames, lambdaParamTypes, null);
                    if ("V".equals(lambdaRetDesc)) {
                        statements.add(new ExpressionStatement(lambda));
                    } else {
                        stack.push(lambda);
                    }
                    break;
                }

                Expression invocation = new StaticMethodInvocationExpression(
                    line, retType, "", methodName, desc, args);
                if ("V".equals(retDesc)) {
                    statements.add(new ExpressionStatement(invocation));
                } else {
                    stack.push(invocation);
                }
                break;
            }

            // Object creation
            case 0xBB: { // new
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                stack.push(new NewExpression(line, new ObjectType(className), className, "()V", Collections.<Expression>emptyList()));
                break;
            }

            // newarray
            case 0xBC: {
                int atype = reader.readUnsignedByte();
                Expression count = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                stack.push(new NewArrayExpression(line, primitiveArrayType(atype), Collections.singletonList(count)));
                break;
            }

            // anewarray
            case 0xBD: {
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                Expression count = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
                Type elemType;
                if (className != null && className.startsWith("[")) {
                    elemType = parseType(className);
                } else {
                    elemType = new ObjectType(className != null ? className : "java/lang/Object");
                }
                stack.push(new NewArrayExpression(line, elemType, Collections.singletonList(count)));
                break;
            }

            // Misc
            case 0xBE: { // arraylength
                Expression arr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                stack.push(new FieldAccessExpression(line, PrimitiveType.INT, arr, "", "length", "I"));
                break;
            }

            // Throw
            case 0xBF: { // athrow
                Expression exception = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                statements.add(new ThrowStatement(line, exception));
                break;
            }

            // Type checking
            case 0xC0: { // checkcast
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                Expression expr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Type castType;
                if (className != null && className.startsWith("[")) {
                    castType = parseType(className);
                } else {
                    castType = new ObjectType(className != null ? className : "java/lang/Object");
                }
                stack.push(new CastExpression(line, castType, expr));
                break;
            }
            case 0xC1: { // instanceof
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                Expression expr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                Type instType;
                if (className != null && className.startsWith("[")) {
                    instType = parseType(className);
                } else {
                    instType = new ObjectType(className != null ? className : "java/lang/Object");
                }
                stack.push(new InstanceOfExpression(line, expr, instType));
                break;
            }

            // START_CHANGE: ISS-2026-0008-20260324-1 - Emit sync markers for synchronized reconstruction
            // monitorenter
            case 0xC2: {
                Expression monExpr = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                statements.add(new ExpressionStatement(
                    new StringConstantExpression(line, "/* __MONITORENTER__ */")));
                break;
            }
            // monitorexit
            case 0xC3:
                if (!stack.isEmpty()) stack.pop();
                statements.add(new ExpressionStatement(
                    new StringConstantExpression(line, "/* __MONITOREXIT__ */")));
                break;
            // END_CHANGE: ISS-2026-0008-1

            // wide
            case 0xC4: {
                int wideOpcode = reader.readUnsignedByte();
                if (wideOpcode == 0x84) { // wide iinc
                    reader.readUnsignedShort();
                    reader.readShort();
                } else {
                    reader.readUnsignedShort();
                }
                break;
            }

            // multianewarray
            case 0xC5: {
                int typeIndex = reader.readUnsignedShort();
                int dims = reader.readUnsignedByte();
                String className = pool.getClassName(typeIndex);
                List<Expression> dimExprs = new ArrayList<Expression>();
                for (int i = 0; i < dims; i++) {
                    if (!stack.isEmpty()) {
                        dimExprs.add(0, stack.pop());
                    }
                }
                // Parse the full array type, then extract its base element type
                // because NewArrayExpression will add the dimension brackets itself
                Type fullType = parseType(className != null ? className : "Ljava/lang/Object;");
                Type baseType = fullType;
                if (baseType instanceof ArrayType) {
                    baseType = ((ArrayType) baseType).getElementType();
                }
                stack.push(new NewArrayExpression(line, baseType, dimExprs));
                break;
            }

            // ifnull, ifnonnull
            case 0xC6: case 0xC7: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();
                    String op = (opcode == 0xC6) ? "== null" : "!= null";
                    statements.add(new ExpressionStatement(
                        new StringConstantExpression(line,
                        "/* if (" + val + " " + op + ") goto pc=" + targetPc + " */")));
                }
                break;
            }

            // goto_w
            case 0xC8:
                reader.readInt();
                break;

            // jsr_w
            case 0xC9:
                reader.readInt();
                break;

            default:
                // Unknown opcode - just skip
                break;
        }

        // Flush remaining expressions on stack to statements if it looks like
        // they are standalone expressions (method calls that return void-like)
    }

    private void pushLocal(Deque<Expression> stack, int index,
                            Map<Integer, String> names, Map<Integer, String> descriptors,
                            int line, Type defaultType) {
        String name = names.containsKey(index) ? (String) names.get(index) : "var" + index;
        // Prefer generic signature type over erased descriptor
        Type type = defaultType;
        if (currentLocalVarSignatures != null) {
            String sig = (String) currentLocalVarSignatures.get(index);
            if (sig != null) {
                Type sigType = parseSignatureType(sig);
                if (sigType != null) {
                    type = sigType;
                }
            }
        }
        if (type == defaultType) {
            String desc = descriptors.get(index);
            type = desc != null ? parseType(desc) : defaultType;
        }
        stack.push(new LocalVariableExpression(line, type, name, index));
    }

    private void storeLocal(Deque<Expression> stack, int index,
                             Map<Integer, String> names, Map<Integer, String> descriptors,
                             List<Statement> statements, int line, Type defaultType) {
        String name = names.containsKey(index) ? (String) names.get(index) : "var" + index;
        // Prefer generic signature type (e.g., "TT;" -> GenericType "T") over erased descriptor
        Type type = defaultType;
        if (currentLocalVarSignatures != null) {
            String sig = (String) currentLocalVarSignatures.get(index);
            if (sig != null) {
                Type sigType = parseSignatureType(sig);
                if (sigType != null) {
                    type = sigType;
                }
            }
        }
        if (type == defaultType) {
            String desc = (String) descriptors.get(index);
            type = desc != null ? parseType(desc) : defaultType;
        }
        Expression value = stack.isEmpty() ? NullExpression.INSTANCE : stack.pop();

        // START_CHANGE: LIM-0002-20260324-1 - Infer type from RHS when descriptor is unavailable (e.g., TWR temp vars)
        if (type == ObjectType.OBJECT && value != null && value.getType() != null
            && value.getType() != ObjectType.OBJECT && !(value instanceof NullExpression)) {
            type = value.getType();
        }
        // END_CHANGE: LIM-0002-1

        if (declaredVars != null && !declaredVars.contains(index)) {
            // First assignment - emit as variable declaration
            declaredVars.add(index);
            statements.add(new VariableDeclarationStatement(line, type, name, value, false, false));
        } else {
            Expression var = new LocalVariableExpression(line, type, name, index);
            statements.add(new ExpressionStatement(
                new AssignmentExpression(line, type, var, "=", value)));
        }
    }

    /**
     * Merge consecutive declaration (no initializer) + assignment into a single declaration with initializer.
     * Also applies recursively to nested statement bodies.
     */
    private void mergeDeclarationsWithAssignments(List<Statement> statements) {
        for (int i = 0; i < statements.size() - 1; i++) {
            if (statements.get(i) instanceof VariableDeclarationStatement) {
                VariableDeclarationStatement vds = (VariableDeclarationStatement) statements.get(i);
                if (!vds.hasInitializer()) {
                    // Search forward for the first assignment to this variable
                    for (int j = i + 1; j < statements.size(); j++) {
                        Statement candidate = statements.get(j);
                        if (candidate instanceof ExpressionStatement) {
                            Expression expr = ((ExpressionStatement) candidate).getExpression();
                            if (expr instanceof AssignmentExpression) {
                                AssignmentExpression ae = (AssignmentExpression) expr;
                                if (ae.getLeft() instanceof LocalVariableExpression) {
                                    String assignName = ((LocalVariableExpression) ae.getLeft()).getName();
                                    if (vds.getName().equals(assignName)) {
                                        // Merge: move the declaration to the assignment site
                                        statements.set(j, new VariableDeclarationStatement(
                                            candidate.getLineNumber() > 0 ? candidate.getLineNumber() : vds.getLineNumber(),
                                            vds.getType(), vds.getName(),
                                            ae.getRight(), vds.isFinal(), vds.isVar()));
                                        statements.remove(i);
                                        i--;
                                        break;
                                    }
                                }
                            }
                        }
                        // Stop searching if we encounter a statement that could use the variable
                        // (but only stop on control flow statements, not simple declarations/assignments)
                        if (candidate instanceof IfStatement || candidate instanceof IfElseStatement
                            || candidate instanceof WhileStatement || candidate instanceof ForStatement
                            || candidate instanceof ForEachStatement || candidate instanceof DoWhileStatement
                            || candidate instanceof TryCatchStatement || candidate instanceof ReturnStatement) {
                            break;
                        }
                    }
                }
            }
        }
        // Apply recursively to nested bodies
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (s instanceof BlockStatement) {
                mergeDeclarationsWithAssignments(((BlockStatement) s).getStatements());
            } else if (s instanceof IfStatement) {
                IfStatement is = (IfStatement) s;
                if (is.getThenBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) is.getThenBody()).getStatements());
                }
            } else if (s instanceof IfElseStatement) {
                IfElseStatement ies = (IfElseStatement) s;
                if (ies.getThenBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) ies.getThenBody()).getStatements());
                }
                if (ies.getElseBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) ies.getElseBody()).getStatements());
                }
            } else if (s instanceof WhileStatement) {
                WhileStatement ws = (WhileStatement) s;
                if (ws.getBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) ws.getBody()).getStatements());
                }
            } else if (s instanceof ForStatement) {
                ForStatement fs = (ForStatement) s;
                if (fs.getBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) fs.getBody()).getStatements());
                }
            } else if (s instanceof ForEachStatement) {
                ForEachStatement fes = (ForEachStatement) s;
                if (fes.getBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) fes.getBody()).getStatements());
                }
            } else if (s instanceof DoWhileStatement) {
                DoWhileStatement dws = (DoWhileStatement) s;
                if (dws.getBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) dws.getBody()).getStatements());
                }
            } else if (s instanceof TryCatchStatement) {
                TryCatchStatement tcs = (TryCatchStatement) s;
                if (tcs.getTryBody() instanceof BlockStatement) {
                    mergeDeclarationsWithAssignments(((BlockStatement) tcs.getTryBody()).getStatements());
                }
                for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                    if (cc.body instanceof BlockStatement) {
                        mergeDeclarationsWithAssignments(((BlockStatement) cc.body).getStatements());
                    }
                }
            }
        }
    }

    private void binaryOp(Deque<Expression> stack, String op, Type type, int line) {
        Expression right = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
        Expression left = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
        stack.push(new BinaryOperatorExpression(line, type, left, op, right));
    }

    private void unaryOp(Deque<Expression> stack, String op, Type type, int line) {
        Expression expr = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
        stack.push(new UnaryOperatorExpression(line, type, op, expr, true));
    }

    private void castTop(Deque<Expression> stack, Type targetType, int line) {
        Expression expr = stack.isEmpty() ? IntegerConstantExpression.valueOf(line, 0) : stack.pop();
        stack.push(new CastExpression(line, targetType, expr));
    }

    private Expression getConstantExpression(int index, ConstantPool pool) {
        return getConstantExpression(index, pool, 0);
    }

    private Expression getConstantExpression(int index, ConstantPool pool, int line) {
        int tag = pool.getTag(index);
        switch (tag) {
            case ConstantPool.CONSTANT_Integer: return IntegerConstantExpression.valueOf(line, ((Integer) pool.getValue(index)).intValue());
            case ConstantPool.CONSTANT_Float: return new FloatConstantExpression(line, ((Float) pool.getValue(index)).floatValue());
            case ConstantPool.CONSTANT_Long: return new LongConstantExpression(line, ((Long) pool.getValue(index)).longValue());
            case ConstantPool.CONSTANT_Double: return new DoubleConstantExpression(line, ((Double) pool.getValue(index)).doubleValue());
            case ConstantPool.CONSTANT_String: return new StringConstantExpression(line, pool.getStringConstant(index));
            case ConstantPool.CONSTANT_Class: return new ClassExpression(line, new ObjectType(pool.getClassName(index)));
            default: return new StringConstantExpression(line, "/* constant:" + index + " */");
        }
    }

    private Type parseType(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || "V".equals(descriptor)) {
            return VoidType.INSTANCE;
        }
        int arrayDim = 0;
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') {
            arrayDim++;
            i++;
        }
        Type baseType;
        char c = descriptor.charAt(i);
        if (c == 'L') {
            int semi = descriptor.indexOf(';', i);
            String internalName = descriptor.substring(i + 1, semi);
            baseType = new ObjectType(internalName);
        } else {
            switch (c) {
                case 'B': baseType = PrimitiveType.BYTE; break;
                case 'C': baseType = PrimitiveType.CHAR; break;
                case 'D': baseType = PrimitiveType.DOUBLE; break;
                case 'F': baseType = PrimitiveType.FLOAT; break;
                case 'I': baseType = PrimitiveType.INT; break;
                case 'J': baseType = PrimitiveType.LONG; break;
                case 'S': baseType = PrimitiveType.SHORT; break;
                case 'Z': baseType = PrimitiveType.BOOLEAN; break;
                default: baseType = ObjectType.OBJECT; break;
            }
        }
        if (arrayDim > 0) {
            return new ArrayType(baseType, arrayDim);
        }
        return baseType;
    }



    /**
     * Parse a generic signature string into a Type.
     * Handles type parameters like "TT;" -> GenericType("T"),
     * and falls back to parseType for standard descriptors.
     */
    private Type parseSignatureType(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        int arrayDim = 0;
        int i = 0;
        while (i < signature.length() && signature.charAt(i) == '[') {
            arrayDim++;
            i++;
        }
        if (i >= signature.length()) return null;
        char c = signature.charAt(i);
        if (c == 'T') {
            // Type parameter: "TT;" or "TName;"
            int semi = signature.indexOf(';', i);
            if (semi > i + 1) {
                String typeName = signature.substring(i + 1, semi);
                Type baseType = new GenericType(typeName);
                if (arrayDim > 0) {
                    return new ArrayType(baseType, arrayDim);
                }
                return baseType;
            }
        }
        // START_CHANGE: ISS-2026-0004-20260324-1 - Correctly parse generic signatures by skipping <...> sections
        if (c == 'L') {
            // Find the closing ';' that matches the outer type, skipping nested '<...>'
            int depth = 0;
            int j = i + 1;
            int endOfName = -1;
            while (j < signature.length()) {
                char ch = signature.charAt(j);
                if (ch == '<') {
                    if (endOfName < 0) endOfName = j;
                    depth++;
                } else if (ch == '>') {
                    depth--;
                } else if (ch == ';' && depth == 0) {
                    if (endOfName < 0) endOfName = j;
                    break;
                }
                j++;
            }
            if (endOfName > i + 1) {
                String internalName = signature.substring(i + 1, endOfName);
                Type baseType = new ObjectType(internalName);
                if (arrayDim > 0) {
                    return new ArrayType(baseType, arrayDim);
                }
                return baseType;
            }
        }
        // END_CHANGE: ISS-2026-0004-1
        // For other signatures (primitives), fall back to parseType
        return parseType(signature);
    }

    private Type primitiveArrayType(int atype) {
        switch (atype) {
            case 4: return PrimitiveType.BOOLEAN;
            case 5: return PrimitiveType.CHAR;
            case 6: return PrimitiveType.FLOAT;
            case 7: return PrimitiveType.DOUBLE;
            case 8: return PrimitiveType.BYTE;
            case 9: return PrimitiveType.SHORT;
            case 10: return PrimitiveType.INT;
            case 11: return PrimitiveType.LONG;
            default: return PrimitiveType.INT;
        }
    }

    // START_CHANGE: ISS-2026-0011-20260323-2 - Reconstruct assert statements from if(!$assertionsDisabled && cond) throw AssertionError
    private List<Statement> reconstructAsserts(List<Statement> statements) {
        if (statements == null) return statements;
        boolean changed = false;
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            Statement replaced = tryReconstructAssert(stmt);
            if (replaced != null) {
                result.add(replaced);
                changed = true;
            } else {
                // Recurse into block statements
                Statement recursed = reconstructAssertInner(stmt);
                if (recursed != stmt) changed = true;
                result.add(recursed);
            }
        }
        return changed ? result : statements;
    }

    private Statement reconstructAssertInner(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            BlockStatement bs = (BlockStatement) stmt;
            List<Statement> inner = reconstructAsserts(bs.getStatements());
            if (inner != bs.getStatements()) {
                return new BlockStatement(bs.getLineNumber(), inner);
            }
        }
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            Statement body = reconstructAssertInner(is.getThenBody());
            if (body != is.getThenBody()) {
                return new IfStatement(is.getLineNumber(), is.getCondition(), body);
            }
        }
        if (stmt instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) stmt;
            Statement thenBody = reconstructAssertInner(ies.getThenBody());
            Statement elseBody = reconstructAssertInner(ies.getElseBody());
            if (thenBody != ies.getThenBody() || elseBody != ies.getElseBody()) {
                return new IfElseStatement(ies.getLineNumber(), ies.getCondition(), thenBody, elseBody);
            }
        }
        return stmt;
    }

    /**
     * Try to match: if (!$assertionsDisabled && condition) { throw new AssertionError(msg); }
     * Returns AssertStatement or null if no match.
     */
    private Statement tryReconstructAssert(Statement stmt) {
        // Pattern: if (condition) { throw new AssertionError(...); }
        Expression condition = null;
        Statement body = null;
        int lineNumber = 0;
        if (stmt instanceof IfStatement) {
            IfStatement is = (IfStatement) stmt;
            condition = is.getCondition();
            body = is.getThenBody();
            lineNumber = is.getLineNumber();
        } else {
            return null;
        }

        // Check if condition contains $assertionsDisabled reference
        if (!containsAssertionsDisabled(condition)) return null;

        // Extract the throw statement from body
        ThrowStatement throwStmt = extractThrowAssertionError(body);
        if (throwStmt == null) return null;

        // The condition is: !$assertionsDisabled && userCondition
        // Assert semantics: assert !(userCondition) : msg
        // So we negate the user condition part
        Expression userCondition = removeAssertionsDisabledFromCondition(condition);
        if (userCondition == null) return null;

        // Negate the user condition (assert checks the positive, throws on negative)
        Expression assertCondition = negateExpression(userCondition, lineNumber);

        // Extract message from AssertionError constructor
        Expression message = extractAssertionErrorMessage(throwStmt);

        return new AssertStatement(lineNumber, assertCondition, message);
    }

    private boolean containsAssertionsDisabled(Expression expr) {
        if (expr instanceof FieldAccessExpression) {
            return "$assertionsDisabled".equals(((FieldAccessExpression) expr).getName());
        }
        if (expr instanceof UnaryOperatorExpression) {
            return containsAssertionsDisabled(((UnaryOperatorExpression) expr).getExpression());
        }
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            return containsAssertionsDisabled(boe.getLeft()) || containsAssertionsDisabled(boe.getRight());
        }
        return false;
    }

    /**
     * Remove the $assertionsDisabled part from the condition.
     * Pattern: !$assertionsDisabled && cond  ->  cond
     * Pattern: $assertionsDisabled == 0 && cond  ->  cond (after boolean simplification)
     */
    private Expression removeAssertionsDisabledFromCondition(Expression expr) {
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            if ("&&".equals(boe.getOperator())) {
                if (isAssertionsDisabledCheck(boe.getLeft())) {
                    return boe.getRight();
                }
                if (isAssertionsDisabledCheck(boe.getRight())) {
                    return boe.getLeft();
                }
            }
        }
        // If the whole condition is just !$assertionsDisabled
        if (isAssertionsDisabledCheck(expr)) {
            return new BooleanExpression(expr.getLineNumber(), false);
        }
        return null;
    }

    private boolean isAssertionsDisabledCheck(Expression expr) {
        if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            if ("!".equals(uoe.getOperator())) {
                return isAssertionsDisabledField(uoe.getExpression());
            }
        }
        if (isAssertionsDisabledField(expr)) return true;
        return false;
    }

    private boolean isAssertionsDisabledField(Expression expr) {
        if (expr instanceof FieldAccessExpression) {
            return "$assertionsDisabled".equals(((FieldAccessExpression) expr).getName());
        }
        return false;
    }

    private ThrowStatement extractThrowAssertionError(Statement body) {
        if (body instanceof ThrowStatement) {
            return isAssertionErrorThrow((ThrowStatement) body) ? (ThrowStatement) body : null;
        }
        if (body instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) body).getStatements();
            for (int i = 0; i < stmts.size(); i++) {
                if (stmts.get(i) instanceof ThrowStatement) {
                    ThrowStatement ts = (ThrowStatement) stmts.get(i);
                    if (isAssertionErrorThrow(ts)) return ts;
                }
            }
        }
        return null;
    }

    private boolean isAssertionErrorThrow(ThrowStatement ts) {
        Expression thrown = ts.getExpression();
        if (thrown instanceof NewExpression) {
            NewExpression ne = (NewExpression) thrown;
            String typeName = ne.getInternalTypeName();
            return typeName != null && (typeName.contains("AssertionError") || typeName.contains("AssertionError"));
        }
        return false;
    }

    private Expression extractAssertionErrorMessage(ThrowStatement ts) {
        Expression thrown = ts.getExpression();
        if (thrown instanceof NewExpression) {
            NewExpression ne = (NewExpression) thrown;
            if (ne.getArguments() != null && !ne.getArguments().isEmpty()) {
                return ne.getArguments().get(0);
            }
        }
        return null;
    }

    private Expression negateExpression(Expression expr, int lineNumber) {
        // Double negation: !!x -> x
        if (expr instanceof UnaryOperatorExpression) {
            UnaryOperatorExpression uoe = (UnaryOperatorExpression) expr;
            if ("!".equals(uoe.getOperator())) {
                return uoe.getExpression();
            }
        }
        // Negate comparison operators
        if (expr instanceof BinaryOperatorExpression) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) expr;
            String op = boe.getOperator();
            String negated = null;
            if ("<=".equals(op)) negated = ">";
            else if (">=".equals(op)) negated = "<";
            else if ("<".equals(op)) negated = ">=";
            else if (">".equals(op)) negated = "<=";
            else if ("==".equals(op)) negated = "!=";
            else if ("!=".equals(op)) negated = "==";
            if (negated != null) {
                return new BinaryOperatorExpression(lineNumber, PrimitiveType.BOOLEAN,
                    boe.getLeft(), negated, boe.getRight());
            }
        }
        return new UnaryOperatorExpression(lineNumber, PrimitiveType.BOOLEAN, "!", expr, true);
    }
    // END_CHANGE: ISS-2026-0011-2

    // START_CHANGE: ISS-2026-0008-20260324-3 - Reconstruct synchronized blocks from monitor markers
    /**
     * Detect the pattern: varX = lockExpr; __MONITORENTER__; ... body ...; __MONITOREXIT__;
     * and replace with SynchronizedStatement(lockExpr, body).
     * Also removes the synthetic temp variable declaration used for monitorexit in finally,
     * and unwraps try-finally blocks whose finally body is only a monitorexit marker.
     */
    private List<Statement> reconstructSynchronized(List<Statement> statements) {
        if (statements == null || statements.size() < 2) return statements;
        // First pass: strip monitor markers from inside try-finally and unwrap synthetic try-finally
        List<Statement> cleaned = stripMonitorFromTryFinally(statements);
        List<Statement> result = new ArrayList<Statement>(cleaned.size());
        for (int i = 0; i < cleaned.size(); i++) {
            // Look for __MONITORENTER__ marker
            if (isMonitorMarker(cleaned.get(i), "MONITORENTER")) {
                // Find the lock expression: the statement before monitorenter should be
                // varX = lockExpr (the dup+astore pattern)
                Expression lockExpr = null;
                int lockStmtIdx = -1;
                if (!result.isEmpty()) {
                    Statement prev = result.get(result.size() - 1);
                    if (prev instanceof VariableDeclarationStatement) {
                        VariableDeclarationStatement vds = (VariableDeclarationStatement) prev;
                        if (vds.hasInitializer()) {
                            lockExpr = vds.getInitializer();
                            lockStmtIdx = result.size() - 1;
                        }
                    } else if (prev instanceof ExpressionStatement) {
                        Expression expr = ((ExpressionStatement) prev).getExpression();
                        if (expr instanceof AssignmentExpression) {
                            AssignmentExpression ae = (AssignmentExpression) expr;
                            lockExpr = ae.getRight();
                            lockStmtIdx = result.size() - 1;
                        }
                    }
                }
                if (lockExpr == null) {
                    continue;
                }
                result.remove(lockStmtIdx);
                // Collect body statements until __MONITOREXIT__ or end
                List<Statement> syncBody = new ArrayList<Statement>();
                i++;
                while (i < cleaned.size()) {
                    if (isMonitorMarker(cleaned.get(i), "MONITOREXIT")) {
                        break;
                    }
                    // Skip nested monitor markers in collected body
                    if (!isMonitorMarker(cleaned.get(i), "MONITORENTER")) {
                        syncBody.add(cleaned.get(i));
                    }
                    i++;
                }
                // Remove any remaining monitor markers from collected body
                syncBody = removeMonitorMarkers(syncBody);
                int line = lockExpr instanceof AbstractExpression
                    ? ((AbstractExpression) lockExpr).getLineNumber() : 0;
                Statement body;
                if (syncBody.size() == 1) {
                    body = syncBody.get(0);
                } else {
                    body = new BlockStatement(line, syncBody);
                }
                result.add(new SynchronizedStatement(line, lockExpr, body));
                continue;
            }
            if (isMonitorMarker(cleaned.get(i), "MONITOREXIT")) {
                continue;
            }
            result.add(cleaned.get(i));
        }
        return result;
    }

    /**
     * Strip monitor markers from inside try-finally blocks.
     * If a try-finally's finally body is only a monitorexit marker,
     * unwrap the try body directly.
     */
    private List<Statement> stripMonitorFromTryFinally(List<Statement> statements) {
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (s instanceof TryCatchStatement) {
                TryCatchStatement tcs = (TryCatchStatement) s;
                // Check if finally body is just a monitorexit marker
                if (tcs.getFinallyBody() != null && isFinallyOnlyMonitorexit(tcs.getFinallyBody())) {
                    // Unwrap the try body
                    Statement tryBody = tcs.getTryBody();
                    if (tryBody instanceof BlockStatement) {
                        List<Statement> inner = stripMonitorFromTryFinally(((BlockStatement) tryBody).getStatements());
                        result.addAll(inner);
                    } else {
                        result.add(tryBody);
                    }
                    continue;
                }
            }
            // Recurse into block statements
            if (s instanceof BlockStatement) {
                BlockStatement bs = (BlockStatement) s;
                List<Statement> inner = stripMonitorFromTryFinally(bs.getStatements());
                result.add(new BlockStatement(bs.getLineNumber(), inner));
                continue;
            }
            result.add(s);
        }
        return result;
    }

    private boolean isFinallyOnlyMonitorexit(Statement finallyBody) {
        if (isMonitorMarker(finallyBody, "MONITOREXIT")) return true;
        if (finallyBody instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) finallyBody).getStatements();
            for (int i = 0; i < stmts.size(); i++) {
                Statement s = stmts.get(i);
                if (isMonitorMarker(s, "MONITOREXIT")) continue;
                if (s instanceof ReturnStatement && !((ReturnStatement) s).hasExpression()) continue;
                // Check for aload+monitorexit pattern (ExpressionStatement with just a variable ref)
                if (s instanceof ExpressionStatement) {
                    Expression e = ((ExpressionStatement) s).getExpression();
                    if (e instanceof StringConstantExpression) {
                        String val = ((StringConstantExpression) e).getValue();
                        if (val.contains("__MONITOR")) continue;
                    }
                }
                return false;
            }
            return true;
        }
        return false;
    }

    private List<Statement> removeMonitorMarkers(List<Statement> statements) {
        List<Statement> result = new ArrayList<Statement>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (isMonitorMarker(s, "MONITORENTER") || isMonitorMarker(s, "MONITOREXIT")) {
                continue;
            }
            if (s instanceof BlockStatement) {
                BlockStatement bs = (BlockStatement) s;
                List<Statement> inner = removeMonitorMarkers(bs.getStatements());
                result.add(new BlockStatement(bs.getLineNumber(), inner));
                continue;
            }
            result.add(s);
        }
        return result;
    }

    private boolean isMonitorMarker(Statement stmt, String type) {
        if (stmt instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmt).getExpression();
            if (expr instanceof StringConstantExpression) {
                return ("/* __" + type + "__ */").equals(((StringConstantExpression) expr).getValue());
            }
        }
        return false;
    }
    // END_CHANGE: ISS-2026-0008-3

}
