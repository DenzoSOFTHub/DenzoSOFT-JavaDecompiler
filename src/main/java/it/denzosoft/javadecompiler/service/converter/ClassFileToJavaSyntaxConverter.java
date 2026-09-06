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
// START_CHANGE: BUG-2026-0097-20260610-6 - Walker/rewriter used to restore captured-variable names
import it.denzosoft.javadecompiler.service.converter.transform.AstLocalRewriter;
// END_CHANGE: BUG-2026-0097-6
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
        // START_CHANGE: OPT-0007-20260905-1 - Read the showBytecode flag here so the per-method
        // disassembly can be skipped when nothing will print it (about 12% of batch CPU time).
        java.util.Map<String, Object> config0007 = message.getHeader("configuration");
        this.showBytecodeRequested = config0007 != null
            && Boolean.TRUE.equals(config0007.get("showBytecode"));
        // END_CHANGE: OPT-0007-1

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
                    // START_CHANGE: BUG-2026-0097-20260610-1 - Load LOCAL classes (declared inside a
                    // method): their InnerClasses entry has outer_class_info == null but a non-null
                    // inner_name (javap: `#51= #18; // Local=class C_InnerClasses$1Local`). Previously
                    // they matched neither branch above so the class was never emitted, while the use
                    // site still referenced `C_InnerClasses._1Local` -> 'cannot find symbol'.
                    if (ic.outerClassName == null && ic.innerName != null
                        && ic.innerClassName != null && ic.innerClassName.startsWith(thisClassName + "$")) {
                        loadAndAddInnerClass(loader, ic, result);
                    }
                    // END_CHANGE: BUG-2026-0097-1
                }
            }
        }

        // START_CHANGE: BUG-2026-0097-20260610-2 - Restore original capture names: rename
        // synthesized enclosing locals (argN/varN) passed as captured constructor arguments
        // to anonymous classes back to the source name preserved in the val$ capture field,
        // so the inlined body's reference resolves to the right variable (and is not
        // shadowed by the inlined method's own synthesized parameter names).
        reconcileAnonymousCaptureNames(result);
        // END_CHANGE: BUG-2026-0097-2

        message.setHeader("javaSyntaxResult", result);
        message.setBody(result);
    }

    // START_CHANGE: BUG-2026-0053-20260610-4 - Rebuild the left spine of a `+` chain so the
    // string-context prefix lands on the leftmost LEAF: `"" + i + j` (correct, left-to-right
    // string concat) instead of `"" + (i + j)` (int addition first).
    private Expression forceStringContextOnLeftLeaf(int line, Expression concat) {
        if (concat instanceof BinaryOperatorExpression
                && "+".equals(((BinaryOperatorExpression) concat).getOperator())) {
            BinaryOperatorExpression boe = (BinaryOperatorExpression) concat;
            Expression newLeft = forceStringContextOnLeftLeaf(line, boe.getLeft());
            if (newLeft == boe.getLeft()) {
                return concat;
            }
            return new BinaryOperatorExpression(line, ObjectType.STRING, newLeft, "+", boe.getRight());
        }
        if (concat instanceof StringConstantExpression || ObjectType.STRING.equals(concat.getType())) {
            return concat;
        }
        return new BinaryOperatorExpression(line, ObjectType.STRING,
            new StringConstantExpression(line, ""), "+", concat);
    }
    // END_CHANGE: BUG-2026-0053-4

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
                    // START_CHANGE: BUG-2026-0097-20260610-3 - Mark method-local classes (no
                    // outer_class_info but named) so the writer can emit them with a valid
                    // identifier and the correct static-ness.
                    if (ic.outerClassName == null && ic.innerName != null) {
                        innerResult.setLocalClass(true);
                    }
                    // END_CHANGE: BUG-2026-0097-3

                    // START_CHANGE: BUG-2026-0059-20260421-1 - Recurse into the inner class's own
                    // InnerClasses attribute. Previously `convert()` only converted the top-level
                    // body, so doubly-nested classes (e.g. `Outer$Row$Kind`) were never emitted,
                    // producing 'cannot find symbol Kind' compile errors in the generated source.
                    InnerClassesAttribute nested = innerCf.findAttribute("InnerClasses");
                    if (nested != null) {
                        String innerThisName = innerCf.getThisClassName();
                        for (InnerClassesAttribute.InnerClass sub : nested.getClasses()) {
                            if (sub.outerClassName != null && sub.outerClassName.equals(innerThisName)
                                    && sub.innerClassName != null && !sub.innerClassName.equals(innerThisName)) {
                                innerConverter.loadAndAddInnerClass(loader, sub, innerResult);
                            } else if (sub.outerClassName == null && sub.innerName == null
                                    && sub.innerClassName != null && sub.innerClassName.startsWith(innerThisName + "$")) {
                                innerConverter.loadAndAddInnerClass(loader, sub, innerResult);
                            // START_CHANGE: BUG-2026-0097-20260610-4 - Also recurse into LOCAL classes
                            // declared inside this nested class's methods (mirrors the new branch in
                            // process(); BUG-2026-0059 recursion previously skipped them too).
                            } else if (sub.outerClassName == null && sub.innerName != null
                                    && sub.innerClassName != null && sub.innerClassName.startsWith(innerThisName + "$")) {
                                innerConverter.loadAndAddInnerClass(loader, sub, innerResult);
                            }
                            // END_CHANGE: BUG-2026-0097-4
                        }
                    }
                    // END_CHANGE: BUG-2026-0059-1

                    outerResult.addInnerClassResult(innerResult);
                }
            } catch (Exception e) {
                // START_CHANGE: IMP-2026-0002-20260420-11 - Surface inner-class skip to the outer
                // class's diagnostics so the generated source flags the missing nested class.
                if (outerResult.decompilationNotes == null) {
                    outerResult.decompilationNotes = new ArrayList<String>();
                }
                outerResult.decompilationNotes.add("INNER_CLASS_SKIPPED " + ic.innerClassName
                    + " " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                // END_CHANGE: IMP-2026-0002-11
            }
        }
    }

    // START_CHANGE: BUG-2026-0097-20260610-7 - Capture-name reconciliation. When a class is
    // compiled without -g, enclosing locals get synthesized names (argN/varN) while the original
    // source name of each captured variable survives in the anonymous class's val$ field. The
    // writer renders `getfield val$x` as `x`, so the inlined body referenced a name that no
    // longer exists ("cannot find symbol: n/captured/base"). This pass renames the enclosing
    // local back to the val$ source name when that is unambiguous and collision-free.
    private void reconcileAnonymousCaptureNames(JavaSyntaxResult root) {
        Map<String, JavaSyntaxResult> anonIndex = new HashMap<String, JavaSyntaxResult>();
        indexAnonymousResults(root, anonIndex);
        if (anonIndex.isEmpty()) return;
        reconcileCapturesIn(root, anonIndex);
    }

    private void indexAnonymousResults(JavaSyntaxResult result, Map<String, JavaSyntaxResult> index) {
        List<JavaSyntaxResult> inners = result.getInnerClassResults();
        if (inners == null) return;
        for (JavaSyntaxResult inner : inners) {
            String name = inner.getInternalName();
            if (name != null && isAnonymousSimpleName(TypeNameUtil.simpleNameFromInternal(name))) {
                index.put(name, inner);
            }
            indexAnonymousResults(inner, index);
        }
    }

    private static boolean isAnonymousSimpleName(String simple) {
        if (simple == null || simple.length() == 0) return false;
        for (int i = 0; i < simple.length(); i++) {
            if (!Character.isDigit(simple.charAt(i))) return false;
        }
        return true;
    }

    private void reconcileCapturesIn(JavaSyntaxResult result, Map<String, JavaSyntaxResult> anonIndex) {
        if (result.getMethods() != null) {
            for (JavaSyntaxResult.MethodDeclaration method : result.getMethods()) {
                if (method.body != null && !method.body.isEmpty()) {
                    reconcileMethodCaptures(method, anonIndex);
                }
            }
        }
        List<JavaSyntaxResult> inners = result.getInnerClassResults();
        if (inners != null) {
            for (JavaSyntaxResult inner : inners) {
                reconcileCapturesIn(inner, anonIndex);
            }
        }
    }

    private void reconcileMethodCaptures(JavaSyntaxResult.MethodDeclaration method,
                                         Map<String, JavaSyntaxResult> anonIndex) {
        final Set<String> usedNames = new HashSet<String>(method.parameterNames);
        final List<NewExpression> sites = new ArrayList<NewExpression>();
        AstLocalRewriter finder = new AstLocalRewriter() {
            protected Expression onLocal(LocalVariableExpression lv) {
                usedNames.add(lv.getName());
                return lv;
            }
            protected Expression rw(Expression e) {
                if (e instanceof NewExpression) sites.add((NewExpression) e);
                return super.rw(e);
            }
            public Statement rewrite(Statement s) {
                if (s instanceof VariableDeclarationStatement) {
                    usedNames.add(((VariableDeclarationStatement) s).getName());
                } else if (s instanceof ForEachStatement) {
                    usedNames.add(((ForEachStatement) s).getVariableName());
                }
                return super.rewrite(s);
            }
        };
        for (Statement s : method.body) finder.rewrite(s);
        if (sites.isEmpty()) return;

        final Map<String, String> renames = new HashMap<String, String>();
        for (NewExpression site : sites) {
            JavaSyntaxResult anon = anonIndex.get(site.getInternalTypeName());
            if (anon == null) continue;
            JavaSyntaxResult.MethodDeclaration init = findFirstConstructor(anon);
            if (init == null || init.body == null) continue;
            List<Expression> args = site.getArguments();
            if (args == null || args.isEmpty()) continue;
            for (Statement stmt : init.body) {
                // Match the capture store `this.val$x = <ctor param>` in the anon <init>.
                if (!(stmt instanceof ExpressionStatement)) continue;
                Expression e = ((ExpressionStatement) stmt).getExpression();
                if (!(e instanceof AssignmentExpression)) continue;
                AssignmentExpression ae = (AssignmentExpression) e;
                if (!(ae.getLeft() instanceof FieldAccessExpression)) continue;
                String fieldName = ((FieldAccessExpression) ae.getLeft()).getName();
                if (fieldName == null || !fieldName.startsWith("val$") || fieldName.length() <= 4) continue;
                if (!(ae.getRight() instanceof LocalVariableExpression)) continue;
                int paramIndex = init.parameterNames.indexOf(((LocalVariableExpression) ae.getRight()).getName());
                if (paramIndex < 0 || paramIndex >= args.size()) continue;
                if (!(args.get(paramIndex) instanceof LocalVariableExpression)) continue;
                String oldName = ((LocalVariableExpression) args.get(paramIndex)).getName();
                String sourceName = fieldName.substring(4);
                if (oldName == null || oldName.equals(sourceName)) continue;
                if (!isSynthesizedLocalName(oldName)) continue;
                if (renames.containsKey(oldName)) continue; // first decision wins
                if (usedNames.contains(sourceName)) continue; // collision: writer map handles it
                renames.put(oldName, sourceName);
                usedNames.add(sourceName);
            }
        }
        if (renames.isEmpty()) return;

        AstLocalRewriter renamer = new AstLocalRewriter() {
            protected Expression onLocal(LocalVariableExpression lv) {
                String renamed = renames.get(lv.getName());
                if (renamed == null) return lv;
                return new LocalVariableExpression(lv.getLineNumber(), lv.getType(), renamed, lv.getIndex());
            }
            public Statement rewrite(Statement s) {
                Statement r = super.rewrite(s);
                if (r instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement v = (VariableDeclarationStatement) r;
                    String renamed = renames.get(v.getName());
                    if (renamed != null) {
                        VariableDeclarationStatement nv = new VariableDeclarationStatement(
                            v.getLineNumber(), v.getType(), renamed,
                            v.hasInitializer() ? v.getInitializer() : null, v.isFinal(), v.isVar());
                        if (v.getGenericSignature() != null) nv.setGenericSignature(v.getGenericSignature());
                        return nv;
                    }
                }
                return r;
            }
        };
        for (int i = 0; i < method.body.size(); i++) {
            method.body.set(i, renamer.rewrite(method.body.get(i)));
        }
        for (int i = 0; i < method.parameterNames.size(); i++) {
            String renamed = renames.get(method.parameterNames.get(i));
            if (renamed != null) method.parameterNames.set(i, renamed);
        }
    }

    private static JavaSyntaxResult.MethodDeclaration findFirstConstructor(JavaSyntaxResult result) {
        if (result.getMethods() == null) return null;
        for (JavaSyntaxResult.MethodDeclaration m : result.getMethods()) {
            if (m.isConstructor()) return m;
        }
        return null;
    }

    private static boolean isSynthesizedLocalName(String name) {
        if (name == null || name.length() < 4) return false;
        if (!name.startsWith("arg") && !name.startsWith("var")) return false;
        for (int i = 3; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }
    // END_CHANGE: BUG-2026-0097-7

    public JavaSyntaxResult convert(ClassFile classFile) {
        // START_CHANGE: ISS-2026-0010-20260323-2 - Store current class name for this() vs super()
        currentClassInternalName = classFile.getThisClassName();
        currentSuperClassInternalName = classFile.getSuperClassName();
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
                // START_CHANGE: BUG-2026-0094-20260610-1 - Use the per-component Signature
                // attribute (when present) instead of the erased descriptor, so generic record
                // components decompile as `T value` / `List<T> list` rather than `Object value`.
                String componentSignature = null;
                if (rc.attributes != null) {
                    for (Attribute rcAttr : rc.attributes) {
                        if (rcAttr instanceof SignatureAttribute) {
                            componentSignature = ((SignatureAttribute) rcAttr).getSignature();
                            break;
                        }
                    }
                }
                Type componentType = null;
                if (componentSignature != null) {
                    componentType = parseSignatureType(componentSignature);
                }
                if (componentType == null) {
                    componentType = parseType(rc.descriptor);
                }
                components.add(new JavaSyntaxResult.RecordComponentInfo(
                    rc.name, rc.descriptor, componentType, componentSignature));
                // END_CHANGE: BUG-2026-0094-1
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

        // Build synthetic method map for lambda body reconstruction.
        // START_CHANGE: BUG-2026-0073-20260608-1 - Decode synthetic bodies in TWO passes. A lambda
        // body can reference a *later* lambda$ method (a nested lambda); on the first pass that nested
        // body is not yet registered, so it leaks as `Class::lambda$x$n`. The second pass re-decodes
        // every body with the full map available, so nested lambdas inline correctly.
        syntheticBodies = new HashMap<String, List<Statement>>();
        syntheticParamNames = new HashMap<String, List<String>>();
        for (int synthPass = 0; synthPass < 2; synthPass++) {
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
                    // START_CHANGE: BUG-2026-0065-20260608-2 - Only record param names when the LVT
                    // actually names every parameter. When it does not (default javac without -g), leave
                    // the entry absent so the call site knows the body uses `argN` defaults and must
                    // rename/substitute them to avoid shadowing the enclosing method's `argN`.
                    boolean allNamed = paramDescs.length > 0;
                    for (int pi = 0; pi < paramDescs.length; pi++) {
                        String name = lvtNames.get(slot);
                        if (name == null) allNamed = false;
                        paramNames.add(name != null ? name : "arg" + pi);
                        slot += ("D".equals(paramDescs[pi]) || "J".equals(paramDescs[pi])) ? 2 : 1;
                    }
                    if (allNamed) {
                        syntheticParamNames.put(method.getName(), paramNames);
                    }
                    // END_CHANGE: BUG-2026-0065-2
                }
            }
        }
        } // END_CHANGE: BUG-2026-0073-1 (synthPass)

        // Module info
        if (classFile.isModule()) {
            ModuleAttribute moduleAttr = classFile.findAttribute("Module");
            if (moduleAttr != null) {
                result.setModuleName(moduleAttr.getModuleName());
                result.setModuleFlags(moduleAttr.getModuleFlags());
                result.setModuleVersion(moduleAttr.getModuleVersion());

                List<String[]> reqList = new ArrayList<String[]>();
                // START_CHANGE: BUG-2026-0099-20260610-2 - Carry requires_flags through to the
                // writer ([name, version, flags-as-decimal-string]) so `requires transitive` /
                // `requires static` modifiers survive decompilation.
                for (ModuleAttribute.Requires req : moduleAttr.getRequires()) {
                    reqList.add(new String[]{req.name, req.version, String.valueOf(req.flags)});
                }
                // END_CHANGE: BUG-2026-0099-2
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
            // START_CHANGE: BUG-2026-0097-20260610-5 - Synthetic fields: record the this$N
            // outer-instance field (drives outer-parameter stripping in the writer) and KEEP
            // val$ capture fields, renamed without the prefix, so emitted local classes
            // declare the captured values their bodies reference (the writer renders
            // `val$x` reads as `x`). Other synthetic fields stay suppressed.
            if (field.isSynthetic()) {
                String synthName = field.getName();
                if (synthName != null && synthName.startsWith("this$")) {
                    result.setHasOuterThisField(true);
                } else if (synthName != null && synthName.startsWith("val$") && synthName.length() > 4) {
                    JavaSyntaxResult.FieldDeclaration cap = convertField(field, classFile);
                    result.addField(new JavaSyntaxResult.FieldDeclaration(
                        cap.accessFlags, synthName.substring(4), cap.descriptor,
                        cap.type, cap.initialValue, cap.signature, cap.annotations));
                }
                continue;
            }
            // END_CHANGE: BUG-2026-0097-5
            result.addField(convertField(field, classFile));
        }

        // Methods
        // START_CHANGE: BUG-2026-0046-20260327-3 - Include access$ synthetic methods for resolver
        for (MethodInfo method : classFile.getMethods()) {
            if (method.isBridge()) continue; // bridge methods are compiler-generated, suppress
            if (method.isSynthetic() && !method.getName().startsWith("access$")) continue;
            result.addMethod(convertMethod(method, classFile));
        }
        // END_CHANGE: BUG-2026-0046-3

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

        // START_CHANGE: IMP-2026-0002-20260420-9 - Reset per-method diagnostics before body conversion
        currentMethodDiagnostics = new ArrayList<String>();
        currentDecodePc = -1;
        currentDecodeOpcode = -1;
        // END_CHANGE: IMP-2026-0002-9
        // START_CHANGE: BUG-2026-0100-20260905-2 - Per-method statement/pc map.
        statementPcs = new java.util.IdentityHashMap<Statement, Integer>();
        // END_CHANGE: BUG-2026-0100-2

        // Decompile method body
        List<Statement> bodyStatements = new ArrayList<Statement>();
        if (code != null) {
            bodyStatements = decompileMethodBody(code, classFile.getConstantPool(), method);
        }

        // START_CHANGE: BUG-2026-0089-20260610-1 - A record canonical constructor with a non-trivial
        // component assignment (`this.celsius = Math.max(-273.15, celsius)`) was dropped by the
        // writer's RHS-blind triviality check, silently deleting the user's validation/clamping
        // logic. Rewrite such assignments into compact-constructor form (`celsius = Math.max(...);
        // this.celsius = celsius;`): the parameter reassignment makes the ctor visibly non-trivial,
        // and the writer's compact emitter keeps it while stripping the now-trivial field assignment.
        if ("<init>".equals(method.getName()) && classFile.findAttribute("Record") != null) {
            RecordAttribute recordAttr = (RecordAttribute) classFile.findAttribute("Record");
            compactifyRecordCanonicalCtor(bodyStatements, paramDescriptors, paramNames, recordAttr);
        }
        // END_CHANGE: BUG-2026-0089-1

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

        // START_CHANGE: BUG-2026-0090-20260610-2 - Surface the AnnotationDefault attribute
        // (parsed by AttributeParser but previously dropped) so annotation type elements
        // keep their `default <value>` clause.
        AnnotationDefaultAttribute annotationDefaultAttr = method.findAttribute("AnnotationDefault");
        // END_CHANGE: BUG-2026-0090-2

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
        // START_CHANGE: BUG-2026-0090-20260610-3 - Attach the annotation element default value
        if (annotationDefaultAttr != null) {
            md.annotationDefault = annotationDefaultAttr.getDefaultValue();
        }
        // END_CHANGE: BUG-2026-0090-3
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
            // START_CHANGE: OPT-0007-20260905-2 - Only the --show-bytecode output consumes this.
            if (showBytecodeRequested) {
                md.bytecodeInstructions = BytecodeDisassembler.disassemble(
                    code.getCode(), classFile.getConstantPool(), lnt, lvNames);
            }
            // END_CHANGE: OPT-0007-2
        }
        // END_CHANGE: IMP-LINES-6
        // START_CHANGE: LIM-0004-20260326-9 - Populate method return type annotations
        List<AnnotationInfo> returnTypeAnns = extractTypeAnnotationsByTarget(method.getAttributes(), 0x14);
        if (!returnTypeAnns.isEmpty()) {
            md.returnTypeAnnotations = returnTypeAnns;
        }
        // END_CHANGE: LIM-0004-9
        // START_CHANGE: IMP-2026-0002-20260420-10 - Attach accumulated diagnostics to the method
        if (!currentMethodDiagnostics.isEmpty()) {
            md.decompilationNotes = new ArrayList<String>(currentMethodDiagnostics);
        }
        // END_CHANGE: IMP-2026-0002-10
        return md;
    }

    // START_CHANGE: BUG-2026-0089-20260610-2 - Rewrite non-trivial component assignments of a record
    // canonical constructor into compact-constructor form so no user logic is silently dropped.
    // `this.<comp> = <expr>` (expr != bare parameter) becomes `<param> = <expr>; this.<comp> = <param>;`
    // — the exact desugaring javac performs for a compact constructor, run in reverse. The trailing
    // trivial field assignment is then correctly classified as implicit by the writer, while the
    // parameter reassignment survives into the emitted compact body. The transform is aborted (body
    // left untouched) if any reassigned parameter is read by a later statement, since the
    // reassignment would change the value those reads observe.
    private void compactifyRecordCanonicalCtor(List<Statement> body, String[] paramDescriptors,
                                               List<String> paramNames, RecordAttribute recordAttr) {
        if (body == null || body.isEmpty() || recordAttr == null) return;
        RecordAttribute.RecordComponent[] comps = recordAttr.getComponents();
        if (comps == null || comps.length == 0 || comps.length != paramDescriptors.length
                || paramNames.size() < comps.length) {
            return;
        }
        // Canonical constructor only: parameter descriptors match the record components in order.
        for (int i = 0; i < comps.length; i++) {
            if (comps[i].descriptor == null || !comps[i].descriptor.equals(paramDescriptors[i])) return;
        }
        // Component order -> parameter local slot (instance ctor: slot 0 is `this`).
        int[] slots = new int[comps.length];
        int slot = 1;
        for (int i = 0; i < comps.length; i++) {
            slots[i] = slot;
            slot += ("D".equals(paramDescriptors[i]) || "J".equals(paramDescriptors[i])) ? 2 : 1;
        }
        // Collect top-level `this.<comp_i> = <expr>` statements whose RHS is NOT the bare parameter i.
        List<int[]> pending = new ArrayList<int[]>(); // {statementIndex, componentIndex}
        for (int si = 0; si < body.size(); si++) {
            Statement s = body.get(si);
            if (!(s instanceof ExpressionStatement)) continue;
            Expression e = ((ExpressionStatement) s).getExpression();
            if (!(e instanceof AssignmentExpression)) continue;
            AssignmentExpression ae = (AssignmentExpression) e;
            if (!"=".equals(ae.getOperator()) || !(ae.getLeft() instanceof FieldAccessExpression)) continue;
            FieldAccessExpression fa = (FieldAccessExpression) ae.getLeft();
            if (fa.getObject() != null && !(fa.getObject() instanceof ThisExpression)) continue;
            int ci = -1;
            for (int i = 0; i < comps.length; i++) {
                if (comps[i].name != null && comps[i].name.equals(fa.getName())) { ci = i; break; }
            }
            if (ci < 0) continue;
            Expression rhs = ae.getRight();
            if (rhs instanceof LocalVariableExpression
                    && paramNames.get(ci).equals(((LocalVariableExpression) rhs).getName())) {
                continue; // already the implicit `this.x = x` form
            }
            // Safety gate: the parameter must not be read after this statement — the compact-form
            // reassignment would change the value those later reads observe. Abort entirely.
            for (int sj = si + 1; sj < body.size(); sj++) {
                if (statementReadsLocal(body.get(sj), paramNames.get(ci))) return;
            }
            pending.add(new int[]{si, ci});
        }
        // Apply back-to-front so collected statement indices stay valid.
        for (int pi = pending.size() - 1; pi >= 0; pi--) {
            int si = pending.get(pi)[0];
            int ci = pending.get(pi)[1];
            AssignmentExpression ae = (AssignmentExpression) ((ExpressionStatement) body.get(si)).getExpression();
            int ln = ae.getLineNumber();
            Type pt = parseType(paramDescriptors[ci]);
            body.set(si, new ExpressionStatement(new AssignmentExpression(ln, pt,
                new LocalVariableExpression(ln, pt, paramNames.get(ci), slots[ci]), "=", ae.getRight())));
            body.add(si + 1, new ExpressionStatement(new AssignmentExpression(ln, ae.getType(),
                ae.getLeft(), "=", new LocalVariableExpression(ln, pt, paramNames.get(ci), slots[ci]))));
        }
    }

    /** True if the statement tree references the named local (conservative: writes count too). */
    private boolean statementReadsLocal(Statement s, final String name) {
        final boolean[] found = new boolean[1];
        new it.denzosoft.javadecompiler.service.converter.transform.AstLocalRewriter() {
            protected Expression onLocal(LocalVariableExpression lv) {
                if (name.equals(lv.getName())) found[0] = true;
                return lv;
            }
        }.rewrite(s);
        return found[0];
    }
    // END_CHANGE: BUG-2026-0089-2

    // START_CHANGE: IMP-2026-0062-20260422-25 - Quality heuristic for JD output.
    // Walks the statement tree looking for placeholder string constants the emitter
    // inserts when a block type wasn't fully reduced / condition wasn't decoded.
    // If any are found, the JD result is structurally degraded and we fall back to
    // the legacy path rather than ship broken code.
    private static boolean containsPlaceholders(List<Statement> stmts) {
        if (stmts == null) return false;
        for (Statement s : stmts) {
            if (statementHasPlaceholder(s)) return true;
        }
        return false;
    }

    private static boolean statementHasPlaceholder(Statement s) {
        if (s == null) return false;
        if (s instanceof ExpressionStatement) {
            return exprHasPlaceholder(((ExpressionStatement) s).getExpression());
        }
        if (s instanceof IfStatement) {
            IfStatement is = (IfStatement) s;
            return exprHasPlaceholder(is.getCondition()) || statementHasPlaceholder(is.getThenBody());
        }
        if (s instanceof IfElseStatement) {
            IfElseStatement ies = (IfElseStatement) s;
            return exprHasPlaceholder(ies.getCondition())
                || statementHasPlaceholder(ies.getThenBody())
                || statementHasPlaceholder(ies.getElseBody());
        }
        if (s instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) s;
            return exprHasPlaceholder(ws.getCondition()) || statementHasPlaceholder(ws.getBody());
        }
        if (s instanceof BlockStatement) {
            return containsPlaceholders(((BlockStatement) s).getStatements());
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) s;
            if (statementHasPlaceholder(tcs.getTryBody())) return true;
            for (TryCatchStatement.CatchClause cc : tcs.getCatchClauses()) {
                if (statementHasPlaceholder(cc.body)) return true;
            }
            return tcs.hasFinally() && statementHasPlaceholder(tcs.getFinallyBody());
        }
        if (s instanceof SwitchStatement) {
            SwitchStatement ss = (SwitchStatement) s;
            for (SwitchStatement.SwitchCase sc : ss.getCases()) {
                for (Statement inner : sc.getStatements()) {
                    if (statementHasPlaceholder(inner)) return true;
                }
            }
        }
        return false;
    }

    private static boolean exprHasPlaceholder(Expression e) {
        if (e instanceof StringConstantExpression) {
            String v = ((StringConstantExpression) e).getValue();
            return v != null && (v.startsWith("/* condition") || v.startsWith("/* expr")
                || v.startsWith("/* switch selector"));
        }
        return false;
    }
    // END_CHANGE: IMP-2026-0062-25

    // START_CHANGE: IMP-2026-0062-20260422-19 - JD-Core pipeline entry points.
    // Opt-in behind a system property / env var so existing callers are unaffected.
    // Once the new emitter reaches feature parity with the legacy flow builder this
    // will become the default (and the legacy StructuredFlowBuilder will go away).
    private static boolean useJdPipeline() {
        String sys = System.getProperty("denzo.jd.pipeline");
        if (sys != null && ("true".equalsIgnoreCase(sys) || "1".equals(sys))) return true;
        String env = System.getenv("DENZO_JD_PIPELINE");
        return env != null && ("1".equals(env) || "true".equalsIgnoreCase(env));
    }

    private List<Statement> runJdPipeline(
            final MethodInfo method, final ConstantPool pool, final CodeAttribute codeAttr,
            final Map<Integer, String> localVarNames,
            final Map<Integer, String> localVarDescriptors) {
        // Build the CFG up-front so we can index handler-entry blocks. The bridge
        // passes that info through so the legacy decoder can seed the operand stack
        // with the caught exception (parity with BUG-2026-0050 / 0051 fixes).
        final it.denzosoft.javadecompiler.service.converter.cfg.jd.ControlFlowGraph jdCfg =
            it.denzosoft.javadecompiler.service.converter.cfg.jd.ControlFlowGraphMaker.make(method, pool);
        if (jdCfg == null) return null;

        // START_CHANGE: BUG-2026-0065-20260609-2 - Tail-duplicate the shared `*return`
        // merge block that switch-EXPRESSION arms all `goto`. Each arm's value lives on
        // the operand stack at the END of that arm's own predecessor block; the JD
        // pipeline decodes each block once, so a SINGLE shared return block would seed
        // its `ireturn` from only ONE predecessor's exitStack (case-1/default lose
        // theirs). Giving every predecessor its own copy of the return block makes the
        // per-block decode seed each copy from that arm's exitStack, so each arm
        // returns its own value.
        it.denzosoft.javadecompiler.service.converter.cfg.jd.ReturnMergeTailDuplicator.duplicate(jdCfg);
        // END_CHANGE: BUG-2026-0065-2

        // Map from handler-entry block.index -> throwable internal name.
        // null name => catch-all / finally handler.
        final java.util.Map<Integer, String> handlerTypes = new java.util.HashMap<Integer, String>();
        for (it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock b : jdCfg.getBasicBlocks()) {
            for (it.denzosoft.javadecompiler.service.converter.cfg.jd.ExceptionHandler h : b.getExceptionHandlers()) {
                handlerTypes.put(Integer.valueOf(h.getBasicBlock().getIndex()),
                                 h.getInternalThrowableName());
            }
        }

        // Map from jd.BasicBlock.index -> the transient legacy BasicBlock we decoded
        // from it. Used to pass predecessors' exitStacks into successor blocks so the
        // legacy decoder's multi-value inheritance (BUG-2026-0051) kicks in.
        final java.util.Map<Integer, BasicBlock> jdToLegacy = new java.util.HashMap<Integer, BasicBlock>();

        it.denzosoft.javadecompiler.service.converter.cfg.jd.JdFlowBuilder.BlockDecoder decoder
            = new it.denzosoft.javadecompiler.service.converter.cfg.jd.JdFlowBuilder.BlockDecoder() {
                public void decode(it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock bb) {
                    boolean isHandler = handlerTypes.containsKey(Integer.valueOf(bb.getIndex()));
                    String handlerType = handlerTypes.get(Integer.valueOf(bb.getIndex()));
                    // Map jd type -> legacy type so extractBranchCondition fires for
                    // actual CONDITIONAL_BRANCH blocks only.
                    int legacyType = BasicBlock.NORMAL;
                    int jdType = bb.getType();
                    if (jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_CONDITIONAL_BRANCH) {
                        legacyType = BasicBlock.CONDITIONAL;
                    } else if (jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_GOTO
                            || jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR) {
                        legacyType = BasicBlock.GOTO;
                    } else if (jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_RETURN
                            || jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_RETURN_VALUE) {
                        legacyType = BasicBlock.RETURN;
                    } else if (jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_THROW) {
                        legacyType = BasicBlock.THROW;
                    } else if (jdType == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_SWITCH_DECLARATION) {
                        legacyType = BasicBlock.SWITCH;
                    }
                    // START_CHANGE: IMP-2026-0062-20260422-26 - Build predecessor list for
                    // exit-stack propagation. The legacy decoder uses `block.predecessors`
                    // with each predecessor's `exitStack` to seed the operand stack of a
                    // successor when the preceding block left values on the stack (multi-
                    // value cross-block inheritance, BUG-2026-0051). Without this, compound
                    // ternary-around-arithmetic patterns lose the pre-ternary stack values.
                    java.util.List<BasicBlock> preds = new java.util.ArrayList<BasicBlock>();
                    for (it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock jpred : bb.getPredecessors()) {
                        BasicBlock legacyPred = jdToLegacy.get(Integer.valueOf(jpred.getIndex()));
                        if (legacyPred != null) preds.add(legacyPred);
                    }
                    // END_CHANGE: IMP-2026-0062-26
                    // START_CHANGE: BUG-2026-0064-20260609-1 - See through TRY_DECLARATION
                    // predecessors. A Java 21 record-pattern switch desugars each record
                    // accessor invocation into its own try-region whose `from` PC lands on
                    // the invokevirtual. ControlFlowGraphMaker marks that PC as a block leader
                    // (Pass 1, line 295: map[entry.startPc]=MARK) and Pass 4 wraps the region
                    // in a TYPE_TRY_DECLARATION block inserted between the producer block (the
                    // one ending with `aload N` that pushes the accessor receiver) and the
                    // consumer block (the one starting with the invokevirtual). The decoder
                    // bridge never decodes TRY_DECLARATION blocks (JdFlowBuilder.build only
                    // decodes the GROUP_CODE-ish mask), so they have no exitStack and the
                    // receiver value is lost -> the invoke path falls back to `new
                    // ThisExpression` (this.start() instead of var4.start()). Resolve any
                    // TRY_DECLARATION predecessor to the real (decoded) producer block(s)
                    // sitting before it so the exitStack seeding in decodeBasicBlock carries
                    // the receiver across the synthetic try boundary.
                    if (preds.isEmpty()) {
                        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
                        java.util.List<it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock> worklist =
                            new java.util.ArrayList<it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock>();
                        for (it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock jp : bb.getPredecessors()) {
                            worklist.add(jp);
                        }
                        while (!worklist.isEmpty()) {
                            it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock jp =
                                worklist.remove(worklist.size() - 1);
                            if (!seen.add(Integer.valueOf(jp.getIndex()))) continue;
                            BasicBlock legacyPred = jdToLegacy.get(Integer.valueOf(jp.getIndex()));
                            if (legacyPred != null) {
                                preds.add(legacyPred);
                            } else if (jp.getType() == it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock.TYPE_TRY_DECLARATION) {
                                // Undecoded synthetic try-region: descend to its predecessors.
                                for (it.denzosoft.javadecompiler.service.converter.cfg.jd.BasicBlock pp : jp.getPredecessors()) {
                                    worklist.add(pp);
                                }
                            }
                        }
                    }
                    // END_CHANGE: BUG-2026-0064-1
                    BasicBlock transient_ = decodeBytecodeRangeFull(bb.getFromOffset(), bb.getToOffset(),
                        pool, method, localVarNames, localVarDescriptors, bb.getFirstLineNumber(),
                        isHandler, handlerType, legacyType, preds);
                    bb.statements = transient_.statements != null ? transient_.statements
                        : new ArrayList<Statement>();
                    // Propagate branch condition + stack top for emitter / reducer use
                    bb.conditionExpression = transient_.condition;
                    bb.stackTopExpression = transient_.stackTopExpression;
                    bb.selectorExpression = transient_.selectorExpression;
                    bb.exitStack = transient_.exitStack;
                    // Remember the legacy block so successor decodes can see its exitStack
                    jdToLegacy.put(Integer.valueOf(bb.getIndex()), transient_);
                }
            };
        // Use the cfg-aware overload so Maker isn't called twice.
        it.denzosoft.javadecompiler.service.converter.cfg.jd.JdFlowBuilder jdBuilder =
            new it.denzosoft.javadecompiler.service.converter.cfg.jd.JdFlowBuilder(method, pool, decoder, jdCfg);
        return jdBuilder.build();
    }

    /** Decode a bytecode range, returning the transient block so callers can see
     *  statements + condition + stackTopExpression. */
    private BasicBlock decodeBytecodeRangeFull(int startPc, int endPc,
            ConstantPool pool, MethodInfo method,
            Map<Integer, String> localVarNames, Map<Integer, String> localVarDescriptors,
            int startLine, boolean isExceptionHandler, String exceptionHandlerType,
            int legacyType, List<BasicBlock> predecessors) {
        BasicBlock transient_ = new BasicBlock(startPc);
        transient_.endPc = endPc;
        transient_.type = legacyType;
        transient_.lineNumber = startLine;
        // START_CHANGE: IMP-2026-0062-20260422-21 - Propagate exception-handler context so
        // the decoder pre-seeds the operand stack with the caught exception (parity with
        // BUG-2026-0050). Previously the handler's opening astore emitted a garbage
        // `Exception e = null` because the transient block lacked this flag.
        transient_.isExceptionHandler = isExceptionHandler;
        transient_.exceptionHandlerType = exceptionHandlerType;
        // END_CHANGE: IMP-2026-0062-21
        // START_CHANGE: IMP-2026-0062-20260422-27 - Wire in predecessors so the legacy
        // decoder's exitStack inheritance (BUG-2026-0051) activates across jd-blocks.
        if (predecessors != null && !predecessors.isEmpty()) {
            transient_.predecessors = new ArrayList<BasicBlock>(predecessors);
        }
        // END_CHANGE: IMP-2026-0062-27
        Map<Integer, Integer> pcToLine = buildPcToLineMap(codeAttribute(method));
        decodeBasicBlock(transient_, pool, method, localVarNames, localVarDescriptors, pcToLine);
        return transient_;
    }

    private static CodeAttribute codeAttribute(MethodInfo method) {
        return method.findAttribute("Code");
    }

    private Map<Integer, Integer> buildPcToLineMap(CodeAttribute codeAttr) {
        Map<Integer, Integer> pcToLine = new HashMap<Integer, Integer>();
        if (codeAttr == null) return pcToLine;
        for (Attribute a : codeAttr.getAttributes()) {
            if (a instanceof LineNumberTableAttribute) {
                LineNumberTableAttribute lnt = (LineNumberTableAttribute) a;
                for (LineNumberTableAttribute.LineNumber e : lnt.getLineNumbers()) {
                    pcToLine.put(Integer.valueOf(e.startPc), Integer.valueOf(e.lineNumber));
                }
            }
        }
        return pcToLine;
    }
    // END_CHANGE: IMP-2026-0062-19

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
        // START_CHANGE: BUG-2026-0103-20260905-2 - Index the LVT by slot, keeping the scope of each
        // entry, and retain only slots that genuinely hold more than one variable.
        sharedSlotRanges = null;
        {
            Map<Integer, List<LvtRange>> bySlot103 = new HashMap<Integer, List<LvtRange>>();
            for (Attribute attr103 : codeAttr.getAttributes()) {
                if (!(attr103 instanceof LocalVariableTableAttribute)) continue;
                LocalVariableTableAttribute lvt103 = (LocalVariableTableAttribute) attr103;
                for (LocalVariableTableAttribute.LocalVariable lv103 : lvt103.getLocalVariables()) {
                    Integer key103 = Integer.valueOf(lv103.index);
                    List<LvtRange> list103 = bySlot103.get(key103);
                    if (list103 == null) {
                        list103 = new ArrayList<LvtRange>();
                        bySlot103.put(key103, list103);
                    }
                    list103.add(new LvtRange(lv103.startPc, lv103.startPc + lv103.length,
                                             lv103.name, lv103.descriptor));
                }
            }
            // Attach the generic signature of the LVTT entry covering the same slot and start.
            for (Attribute attr103 : codeAttr.getAttributes()) {
                if (!(attr103 instanceof LocalVariableTypeTableAttribute)) continue;
                LocalVariableTypeTableAttribute lvtt103 = (LocalVariableTypeTableAttribute) attr103;
                for (LocalVariableTypeTableAttribute.LocalVariableType lt103 : lvtt103.getLocalVariableTypes()) {
                    List<LvtRange> list103 = bySlot103.get(Integer.valueOf(lt103.index));
                    if (list103 == null) continue;
                    for (int i103 = 0; i103 < list103.size(); i103++) {
                        if (list103.get(i103).startPc == lt103.startPc) {
                            list103.get(i103).signature = lt103.signature;
                        }
                    }
                }
            }
            for (Map.Entry<Integer, List<LvtRange>> e103 : bySlot103.entrySet()) {
                Set<String> distinct103 = new HashSet<String>();
                for (int i103 = 0; i103 < e103.getValue().size(); i103++) {
                    distinct103.add(e103.getValue().get(i103).name);
                }
                if (distinct103.size() > 1) {
                    if (sharedSlotRanges == null) sharedSlotRanges = new HashMap<Integer, List<LvtRange>>();
                    sharedSlotRanges.put(e103.getKey(), e103.getValue());
                }
            }
        }
        // END_CHANGE: BUG-2026-0103-2

        // Store bytecode for block-level decoding
        currentBytecode = bytecode;
        // Store generic signatures for use in storeLocal
        currentLocalVarSignatures = localVarSignatures;

        // Initialize variable declaration tracking
        declaredVars = new HashSet<Integer>();
        // START_CHANGE: BUG-2026-0096-20260610-2 - Reset per-method slot typing/split state
        slotDeclCategories = new HashMap<Integer, Integer>();
        slotRenames = new HashMap<Integer, String>();
        slotSplitCounts = new HashMap<Integer, Integer>();
        // END_CHANGE: BUG-2026-0096-2
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
            // START_CHANGE: IMP-2026-0002-20260420-7 - Note the fallback so the reader knows
            // the method body comes from a linear-scan recovery, not the structured path.
            recordDiagnostic("CFG_BUILD_FAILED " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "")
                + " -- using linear-scan fallback (loops/conditionals may be degraded)");
            // END_CHANGE: IMP-2026-0002-7
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
                // START_CHANGE: BUG-2026-0103-20260905-6 - A slot holding several variables cannot
                // be pre-declared once: the stores now carry each variable's OWN name (resolved by
                // scope), so a single slot-keyed declaration would name only one of them and leave
                // the others undeclared. Let promoteUndeclaredAssignments give each distinct name
                // its declaration at its first assignment instead.
                if (sharedSlotRanges != null && sharedSlotRanges.containsKey(Integer.valueOf(idx))) {
                    continue;
                }
                // END_CHANGE: BUG-2026-0103-6
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

        // START_CHANGE: IMP-2026-0062-20260422-18 - JD-Core pipeline opt-in.
        // When `-Ddenzo.jd.pipeline=true` or env `DENZO_JD_PIPELINE=1` is set, run the
        // ported ControlFlowGraphMaker + Reducer + GotoReducer + JdFlowBuilder emitter
        // instead of the legacy pattern-matcher. The new pipeline physically reduces
        // the CFG graph (no "visited as claim-tracker" truncation). If it succeeds we
        // return its output; if it throws we fall through to the legacy path so a
        // single failure cannot regress the whole build.
        // BUG-2026-0079: selectively route record-pattern SWITCH methods (typeSwitch + MatchException) to the
        // JD pipeline even when the global flag is off — the legacy path structurally cannot reconstruct them.
        // The per-method quality gate below still falls back to legacy when the JD fold does not succeed.
        boolean recordSwitchMethod = methodHasTypeSwitch(currentBytecode, pool);
        // BUG-2026-0079: running the JD pipeline mutates per-method decode state (e.g. patternSwitchLabels)
        // that the legacy fallback also consumes — snapshot it so a fall-through to legacy starts clean.
        Map<String, List<String>> savedPatternSwitchLabels = patternSwitchLabels == null
            ? null : new HashMap<String, List<String>>(patternSwitchLabels);
        Set<Integer> savedDeclaredVars = declaredVars == null ? null : new HashSet<Integer>(declaredVars);
        if (useJdPipeline() || recordSwitchMethod) {
            // START_CHANGE: IMP-2026-0062-20260422-24 - Per-method quality check.
            // Run the JD pipeline into a sandbox (diagnostics snapshot + captured
            // result). If the JD pass recorded any STACK_UNDERFLOW / DECODE_ERROR
            // markers or produced a result that is less complete than what the
            // legacy builder will produce, discard it and fall through to legacy.
            // This guarantees the new pipeline never regresses a method.
            int diagSnapshot = currentMethodDiagnostics.size();
            try {
                List<Statement> jdResult = runJdPipeline(method, pool, codeAttr,
                    localVarNames, localVarDescriptors);
                boolean jdIntroducedDiagnostics =
                    currentMethodDiagnostics.size() > diagSnapshot;
                // START_CHANGE: IMP-2026-0062-20260422-25 - Quality heuristic:
                // if the JD emitter produced placeholder comments (e.g. `/* condition */`)
                // the body is structurally degraded; prefer legacy.
                boolean jdHasPlaceholders = jdResult != null && containsPlaceholders(jdResult);
                if (jdResult != null && !jdIntroducedDiagnostics && !jdHasPlaceholders) {
                // END_CHANGE: IMP-2026-0062-25
                    // START_CHANGE: IMP-2026-0062-20260424-28 - Run the same post-processing
                    // pipeline on JD output as on the legacy structured-flow output. Without
                    // this the JD emitter produces `x != 0` style comparisons instead of plain
                    // boolean expressions, compound assignments never collapse, etc. (caused
                    // 1600+ compile errors on sba-classes vs 635 for the legacy path).
                    jdResult = ForEachDetector.convert(jdResult);
                    String retDesc = TypeNameUtil.parseMethodReturnDescriptor(method.getDescriptor());
                    boolean returnIsBoolean = "Z".equals(retDesc);
                    jdResult = BooleanSimplifier.simplify(jdResult, returnIsBoolean);
                    // BUG-2026-0079: record-pattern transforms (parity with the legacy pipeline). The JD
                    // emitter preserves the clean desugar (MatchException try/catch + if(1!=0) + nested
                    // instanceof + value in place), which these folders reconstruct into real patterns.
                    jdResult = it.denzosoft.javadecompiler.service.converter.transform.RecordPatternReconstructor.reconstruct(jdResult);
                    jdResult = it.denzosoft.javadecompiler.service.converter.transform.InstanceOfPatternReconstructor.reconstruct(jdResult);
                    jdResult = it.denzosoft.javadecompiler.service.converter.transform.RecordDeconstructionFolder.reconstruct(jdResult);
                    // BUG-2026-0079: fold the typeSwitch record-pattern switch into `return switch(subj){...}`.
                    jdResult = it.denzosoft.javadecompiler.service.converter.transform.TypeSwitchRecordFolder.reconstruct(jdResult);
                    jdResult = reconstructAsserts(jdResult);
                    jdResult = reconstructSynchronized(jdResult);
                    jdResult = CompoundAssignmentSimplifier.simplify(jdResult);
                    jdResult = ForLoopDetector.convert(jdResult);
                    jdResult = StringSwitchReconstructor.reconstruct(jdResult);
                    if (patternSwitchLabels != null && !patternSwitchLabels.isEmpty()) {
                        jdResult = PatternSwitchReconstructor.reconstruct(jdResult, patternSwitchLabels);
                    }
                    // END_CHANGE: IMP-2026-0062-28
                    if (!preDeclarations.isEmpty()) {
                        List<Statement> withDecls = new ArrayList<Statement>();
                        withDecls.addAll(preDeclarations);
                        withDecls.addAll(jdResult);
                        jdResult = withDecls;
                    }
                    // START_CHANGE: BUG-2026-0107-20260905-3 - Same pass on the JD output.
                    jdResult = it.denzosoft.javadecompiler.service.converter.transform.DuplicateDeclarationDemoter.reconstruct(jdResult);
                    // END_CHANGE: BUG-2026-0107-3
                    mergeDeclarationsWithAssignments(jdResult);
                    // BUG-2026-0079: a record-pattern switch method is only routed to JD when the
                    // TypeSwitchRecordFolder actually produced a record-deconstruction switch expression
                    // (`return switch(subj){ case T(comps) -> ... }`). Simple pattern switches (no
                    // deconstruction) and guarded arms do not fold — for those the legacy path is better, so
                    // fall through. This keeps selective activation from regressing area/classify-style methods.
                    if (!recordSwitchMethod || foldedRecordPatternSwitch(jdResult)) {
                        return jdResult;
                    }
                }
                // JD produced a degraded result; roll back diagnostics so the
                // legacy run can decide for itself without false-positive noise.
                while (currentMethodDiagnostics.size() > diagSnapshot) {
                    currentMethodDiagnostics.remove(currentMethodDiagnostics.size() - 1);
                }
            } catch (Exception e) {
                // Roll back any partial diagnostics for same reason.
                while (currentMethodDiagnostics.size() > diagSnapshot) {
                    currentMethodDiagnostics.remove(currentMethodDiagnostics.size() - 1);
                }
                recordDiagnostic("JD_PIPELINE_FAILED " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + " -- falling back to legacy StructuredFlowBuilder");
            }
            // END_CHANGE: IMP-2026-0062-24
            // BUG-2026-0079: JD pipeline declined / fell through — restore the pre-JD decode state so the
            // legacy builder reconstructs from a clean slate (else the JD decode's slot-declaration tracking
            // makes legacy emit bare assignments instead of `Type v = ...` declarations).
            patternSwitchLabels = savedPatternSwitchLabels;
            declaredVars = savedDeclaredVars;
            // START_CHANGE: BUG-2026-0096-20260610-3 - Discard any slot splits the JD decode
            // recorded; the legacy re-decode must start from a clean slot-typing slate or its
            // first declarations would pick up stale fresh names.
            slotDeclCategories = new HashMap<Integer, Integer>();
            slotRenames = new HashMap<Integer, String>();
            slotSplitCounts = new HashMap<Integer, Integer>();
            // END_CHANGE: BUG-2026-0096-3
        }
        // END_CHANGE: IMP-2026-0062-18

        // Build structured statements from CFG
        StructuredFlowBuilder builder = new StructuredFlowBuilder(cfg, decoder);
        // START_CHANGE: BUG-2026-0066-20260610-16 - Tell the flow builder whether `ireturn`
        // returns a boolean so reconstructed switch-expression arms with int 0/1 values render
        // as boolean literals (BooleanSimplifier runs too late to descend into the arms).
        builder.setMethodReturnsBoolean(
            "Z".equals(TypeNameUtil.parseMethodReturnDescriptor(method.getDescriptor())));
        // END_CHANGE: BUG-2026-0066-16
        // START_CHANGE: BUG-2026-0067-20260610-56 - Expose the typeSwitch bootstrap labels to the
        // flow builder so it can synthesize unnamed type-pattern arms (`case Integer _ ->`),
        // which carry NO cast-bind statement. The callback reads the field LIVE: the labels are
        // recorded during block decode, which happens inside builder.buildStatements().
        builder.setPatternLabelSource(new StructuredFlowBuilder.PatternLabelSource() {
            public List<String> labelsFor(String key) {
                return patternSwitchLabels == null ? null : patternSwitchLabels.get(key);
            }
        });
        // END_CHANGE: BUG-2026-0067-56
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
                // START_CHANGE: BUG-2026-0056-20260610-14 - Let the reconstructor structure
                // handler bodies that contain internal control flow (if/else, switch) instead
                // of walking their blocks linearly, which dropped conditions and else-branches.
                tryCatchReconstructor.setFlowBuilder(builder);
                // END_CHANGE: BUG-2026-0056-14
                // START_CHANGE: BUG-2026-0100-20260905-8 - Hand over the statement/pc map so
                // try-region membership no longer depends on the LineNumberTable.
                tryCatchReconstructor.setStatementPcs(statementPcs);
                // END_CHANGE: BUG-2026-0100-8
                result = tryCatchReconstructor.reconstruct(result, codeAttr.getExceptionTable());
                // BUG-2026-0068: collapse the Java 9+ try-with-resources desugar into `try (res) {...}`.
                result = it.denzosoft.javadecompiler.service.converter.transform.ModernTwrReconstructor.reconstruct(result);
                // Post-process: detect for-each patterns
                result = ForEachDetector.convert(result);
                // START_CHANGE: BUG-2026-0077-20260609-1 - Promote the first bare assignment of an
                // otherwise-undeclared local to a declaration. ForEachDetector removes the iterator
                // declaration that "owned" a reused slot, leaving its later reuse declaration-less
                // (`var6 = new HashMap();` -> "cannot find symbol").
                java.util.Set<String> paramNames077 = new java.util.HashSet<String>();
                {
                    String[] pds077 = TypeNameUtil.parseMethodParameterDescriptors(method.getDescriptor());
                    int pslot077 = method.isStatic() ? 0 : 1;
                    for (int pi077 = 0; pi077 < pds077.length; pi077++) {
                        String pn077 = localVarNames.get(Integer.valueOf(pslot077));
                        if (pn077 != null) paramNames077.add(pn077);
                        pslot077 += ("D".equals(pds077[pi077]) || "J".equals(pds077[pi077])) ? 2 : 1;
                    }
                }
                // START_CHANGE: BUG-2026-0106-20260905-1 - Seed the "already declared" set with the
                // LVT-driven pre-declarations that are prepended only AFTER this pass (see below).
                // Without them promoteUndeclaredAssignments treats `sum = 0` as undeclared and
                // promotes it to `int sum = 0;`; prepending `int sum;` then yields the illegal
                // `int sum; int sum = 0;` (mergeDeclarationsWithAssignments cannot merge two
                // declarations). Leaving the assignment bare lets that merge produce `int sum = 0;`.
                for (int pdi106 = 0; pdi106 < preDeclarations.size(); pdi106++) {
                    Statement pd106 = preDeclarations.get(pdi106);
                    if (pd106 instanceof VariableDeclarationStatement) {
                        paramNames077.add(((VariableDeclarationStatement) pd106).getName());
                    }
                }
                // END_CHANGE: BUG-2026-0106-1
                promoteUndeclaredAssignments(result, paramNames077);
                // END_CHANGE: BUG-2026-0077-1
                // START_CHANGE: BUG-2026-0069-20260610-22 - Re-run the for-each signature
                // back-prop now that promoted declarations exist (late/reused slots were bare
                // assignments during ForEachDetector, so the first pass had no declaration target).
                ForEachDetector.backPropagateSignatures(result);
                // END_CHANGE: BUG-2026-0069-22
                // Post-process: simplify boolean comparisons (x != 0 → x, x == 0 → !x)
                String retDesc = TypeNameUtil.parseMethodReturnDescriptor(method.getDescriptor());
                boolean returnIsBoolean = "Z".equals(retDesc);
                result = BooleanSimplifier.simplify(result, returnIsBoolean);
                // START_CHANGE: BUG-2026-0064-20260608-1 - Reconstruct instanceof pattern bindings
                // BUG-2026-0069: strip record-pattern MatchException try/catch scaffolding + always-true ifs.
                result = it.denzosoft.javadecompiler.service.converter.transform.RecordPatternReconstructor.reconstruct(result);
                result = it.denzosoft.javadecompiler.service.converter.transform.InstanceOfPatternReconstructor.reconstruct(result);
                // BUG-2026-0067: fold record-deconstruction component extraction into `instanceof Type(comp, ...)`.
                result = it.denzosoft.javadecompiler.service.converter.transform.RecordDeconstructionFolder.reconstruct(result);
                // END_CHANGE: BUG-2026-0064-1
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
                // BUG-2026-0080: hoist a var declared in a switch case but used in another case / after.
                result = it.denzosoft.javadecompiler.service.converter.transform.SwitchVarHoister.reconstruct(result);
                // START_CHANGE: BUG-2026-0069-20260610-17 - Hoist a var declared inside an if-branch
                // but assigned in the other branch / read after the if (residual (a)).
                result = it.denzosoft.javadecompiler.service.converter.transform.BranchVarHoister.reconstruct(result);
                // END_CHANGE: BUG-2026-0069-17
                // Prepend variable pre-declarations (for vars used across if/else branches)
                if (!preDeclarations.isEmpty()) {
                    List<Statement> withDecls = new ArrayList<Statement>();
                    withDecls.addAll(preDeclarations);
                    withDecls.addAll(result);
                    result = withDecls;
                }
                // Post-process: merge separate declaration + assignment into single declaration
                // START_CHANGE: BUG-2026-0107-20260905-2 - Drop re-declarations left in scope by
                // slot reuse (two consecutive `for (int i = ...)` loops whose declarations were
                // hoisted out of their headers). Runs last, so it sees the final block structure.
                result = it.denzosoft.javadecompiler.service.converter.transform.DuplicateDeclarationDemoter.reconstruct(result);
                // END_CHANGE: BUG-2026-0107-2
                mergeDeclarationsWithAssignments(result);
                return result;
            }
        } catch (Exception e) {
            // START_CHANGE: IMP-2026-0002-20260420-8 - Structured-flow fallback diagnostic
            recordDiagnostic("STRUCTURED_FLOW_FAILED " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "")
                + " -- using linear-scan fallback");
            // END_CHANGE: IMP-2026-0002-8
            // Fallback to linear scan
        }

        return decompileMethodBodyLinear(codeAttr, pool, method, pcToLine, localVarNames, localVarDescriptors);
    }

    // START_CHANGE: ISS-2026-0010-20260323-1 - Track current class internal name for this() vs super() detection
    private String currentClassInternalName;
    // END_CHANGE: ISS-2026-0010-1
    // START_CHANGE: BUG-2026-0055-20260421-4 - Track super class internal name so INVOKESPECIAL
    // can distinguish a legitimate super(args) call from an embedded `new X(args)` when both
    // appear in the same constructor body.
    private String currentSuperClassInternalName;
    // END_CHANGE: BUG-2026-0055-4
    // START_CHANGE: BUG-2026-0082-20260610-1 - Pending iinc state: an `iinc` decoded with no
    // matching value on the operand stack is held here so it can fuse as a PREFIX ++var/--var
    // into the immediately following iload of the same slot (restores `++a` value semantics,
    // e.g. `a++ + ++a`). It is flushed as a standalone `var++;` statement at the first
    // non-matching opcode and at every decode-run boundary (basic block end / linear end),
    // so for-loop tail increments keep their current statement form.
    private boolean pendingIincActive;
    private int pendingIincSlot;
    private int pendingIincIncr;
    private int pendingIincLine;
    private String pendingIincName;
    // END_CHANGE: BUG-2026-0082-1
    // Shared bytecode reference for block-level decoding
    private byte[] currentBytecode;
    // When true, suppress branch/goto comment output (CFG handles control flow)
    private boolean suppressBranchComments = false;
    // Tracks which local variable slots have been declared (for variable declaration tracking)
    private Set<Integer> declaredVars;
    // START_CHANGE: BUG-2026-0096-20260610-1 - Per-slot declaration typing for no-LVT slot reuse.
    // slotDeclCategories: verifier type category (int-family/long/float/double/reference) recorded
    // when a slot's declaration is emitted; a later store of a DIFFERENT category means javac
    // reused a dead slot for an unrelated variable, so the slot is split into a fresh name.
    // slotRenames: active fresh name per split slot (consulted by load/store/iinc decoding).
    // slotSplitCounts: per-slot split counter for deterministic fresh-name suffixes.
    private Map<Integer, Integer> slotDeclCategories;
    private Map<Integer, String> slotRenames;
    private Map<Integer, Integer> slotSplitCounts;
    // END_CHANGE: BUG-2026-0096-1
    // Generic signatures from LocalVariableTypeTable (index -> signature like "TT;")
    private Map<Integer, String> currentLocalVarSignatures;
    // Map of synthetic lambda method names to their decompiled bodies
    private Map<String, List<Statement>> syntheticBodies;
    // Map of synthetic lambda method names to their parameter names from LVT
    private Map<String, List<String>> syntheticParamNames;
    // BUG-2026-0065: counter for fresh, non-shadowing lambda parameter names (`pN`)
    private int lambdaVarCounter;
    // Bootstrap methods attribute for the current class
    private BootstrapMethodsAttribute bootstrapMethodsAttr;
    // START_CHANGE: LIM-0005-20260326-1 - Pattern switch case labels from typeSwitch bootstrap
    // Maps variable name → list of case label strings (types/constants) from SwitchBootstraps
    private Map<String, List<String>> patternSwitchLabels;
    // END_CHANGE: LIM-0005-1

    // START_CHANGE: IMP-2026-0002-20260420-2 - Per-method decompilation diagnostics.
    // Every silent fallback (stack underflow -> placeholder, opcode decode exception,
    // CFG build failure, inner-class skip) appends a note here; the note is then emitted
    // as a comment before the method body so readers see exactly what went wrong and where.
    private List<String> currentMethodDiagnostics = new ArrayList<String>();
    private int currentDecodePc = -1;
    private int currentDecodeOpcode = -1;
    // START_CHANGE: OPT-0007-20260905-3 - Mirrors the writer's showBytecode configuration flag.
    private boolean showBytecodeRequested;
    // END_CHANGE: OPT-0007-3
    // START_CHANGE: BUG-2026-0100-20260905-1 - Bytecode position per decoded statement.
    // Statements carry only a line number, so try/catch membership used to be decided by
    // comparing source lines. Without a LineNumberTable that comparison cannot run at all and
    // every handler was dropped; with one, statements sharing a line with the region boundary
    // were displaced out of the try. This identity map records the pc each top-level statement
    // was decoded at, giving the reconstructor an exact, debug-info-independent criterion.
    private java.util.IdentityHashMap<Statement, Integer> statementPcs =
        new java.util.IdentityHashMap<Statement, Integer>();
    // END_CHANGE: BUG-2026-0100-1

    // START_CHANGE: BUG-2026-0103-20260905-1 - Range-aware LocalVariableTable lookup.
    // The LVT is otherwise flattened to one name/descriptor per SLOT (last entry wins), so two
    // distinct variables that javac assigned to the same slot collapse into one: a Future and a
    // Throwable sharing a slot produced `throw f;`, and a `List<String>` iteration variable was
    // typed from an unrelated `Integer` entry. Only slots carrying MORE THAN ONE distinct name are
    // resolved by range, so every single-entry slot keeps its previous behaviour exactly.
    private Map<Integer, List<LvtRange>> sharedSlotRanges;

    /** One LocalVariableTable entry: the scope [startPc, endPc) of one variable in one slot. */
    private static final class LvtRange {
        final int startPc;
        final int endPc;
        final String name;
        final String descriptor;
        String signature;
        LvtRange(int startPc, int endPc, String name, String descriptor) {
            this.startPc = startPc;
            this.endPc = endPc;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    /**
     * The variable occupying {@code slot} at {@code pc}, or null when the slot is not shared or
     * the position is unknown. A store lands a few bytes BEFORE its variable's start_pc (javac
     * opens the scope after the storing instruction), so a near-miss just ahead of pc counts.
     */
    private LvtRange resolveSlotRange(int slot, int pc) {
        if (sharedSlotRanges == null || pc < 0) return null;
        List<LvtRange> ranges = sharedSlotRanges.get(Integer.valueOf(slot));
        if (ranges == null) return null;
        for (int i = 0; i < ranges.size(); i++) {
            LvtRange r = ranges.get(i);
            if (pc >= r.startPc && pc < r.endPc) return r;
        }
        LvtRange best = null;
        for (int i = 0; i < ranges.size(); i++) {
            LvtRange r = ranges.get(i);
            int delta = r.startPc - pc;
            if (delta > 0 && delta <= 4 && (best == null || r.startPc < best.startPc)) best = r;
        }
        return best;
    }
    // END_CHANGE: BUG-2026-0103-1

    // START_CHANGE: BUG-2026-0104-20260905-2 - JVMS 2.11.1 computational type category.
    /** True for long and double, the only values occupying two JVM operand-stack slots. */
    private static boolean isCategory2(Expression e) {
        if (e == null) return false;
        it.denzosoft.javadecompiler.model.javasyntax.type.Type t = e.getType();
        if (t == null) return false;
        String d = t.getDescriptor();
        return "J".equals(d) || "D".equals(d);
    }
    // END_CHANGE: BUG-2026-0104-2

    private void recordDiagnostic(String note) {
        currentMethodDiagnostics.add(note);
    }

    private Expression popOrUnderflowInt(Deque<Expression> stack, int line) {
        if (stack.isEmpty()) {
            recordUnderflow("int");
            return IntegerConstantExpression.valueOf(line, 0);
        }
        return stack.pop();
    }

    private Expression popOrUnderflowRef(Deque<Expression> stack) {
        if (stack.isEmpty()) {
            recordUnderflow("ref");
            return NullExpression.INSTANCE;
        }
        return stack.pop();
    }

    // START_CHANGE: BUG-2026-0055-20260421-3 - Cheap "is this the ctor?" check used by INVOKESPECIAL
    private static boolean isInConstructor(MethodInfo method) {
        return method != null && StringConstants.CONSTRUCTOR_NAME.equals(method.getName());
    }
    // END_CHANGE: BUG-2026-0055-3

    private void recordUnderflow(String kind) {
        StringBuilder sb = new StringBuilder("STACK_UNDERFLOW");
        if (currentDecodePc >= 0) sb.append(" pc=").append(currentDecodePc);
        if (currentDecodeOpcode >= 0) {
            sb.append(" opcode=0x").append(Integer.toHexString(currentDecodeOpcode).toUpperCase());
        }
        sb.append(" (").append(kind).append(" placeholder used)");
        recordDiagnostic(sb.toString());
    }
    // END_CHANGE: IMP-2026-0002-2

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
        // START_CHANGE: BUG-2026-0050-20260420-3 - Pre-seed the operand stack of exception
        // handler blocks with a reference to the caught exception. The JVM transfers control
        // to a handler with exactly one value on the stack (the exception); the typical first
        // instruction `astore_N` needs that value to produce `localN = <exception>`.
        if (block.isExceptionHandler) {
            String excType = block.exceptionHandlerType != null
                ? block.exceptionHandlerType : "java/lang/Throwable";
            stack.push(new LocalVariableExpression(block.lineNumber, new ObjectType(excType), "$exception", 0));
        } else // END_CHANGE: BUG-2026-0050-3
        // START_CHANGE: BUG-2026-0051-20260420-2 - Seed the entire stack from a predecessor's
        // exit snapshot when available (handles compound arithmetic around ternaries and
        // other expressions whose sub-values span block boundaries). Falls back to the prior
        // single-slot `stackTopExpression` behaviour when no snapshot exists.
        if (block.predecessors != null) {
            BasicBlock source = null;
            for (BasicBlock pred : block.predecessors) {
                if (pred.exitStack != null && !pred.exitStack.isEmpty()
                        && (pred.type == BasicBlock.FALL_THROUGH || pred.type == BasicBlock.NORMAL
                            || pred.type == BasicBlock.GOTO || pred.type == BasicBlock.CONDITIONAL)) {
                    source = pred;
                    break;
                }
            }
            if (source != null) {
                for (int i = 0; i < source.exitStack.size(); i++) {
                    stack.push(source.exitStack.get(i));
                }
                if (block.lineNumber == 0 && source.lineNumber > 0) {
                    block.lineNumber = source.lineNumber;
                }
            } else {
                for (BasicBlock pred : block.predecessors) {
                    if (pred.stackTopExpression != null &&
                        (pred.type == BasicBlock.FALL_THROUGH || pred.type == BasicBlock.NORMAL)) {
                        stack.push(pred.stackTopExpression);
                        if (block.lineNumber == 0 && pred.lineNumber > 0) {
                            block.lineNumber = pred.lineNumber;
                        }
                        break;
                    }
                }
            }
        }
        // END_CHANGE: BUG-2026-0051-2
        List<Statement> stmts = new ArrayList<Statement>();
        ByteReader reader = new ByteReader(currentBytecode);
        reader.setOffset(block.startPc);
        int currentLine = block.lineNumber;
        suppressBranchComments = true; // CFG handles control flow
        // START_CHANGE: BUG-2026-0082-20260610-5 - A pending iinc never crosses a decode run
        pendingIincActive = false;
        // END_CHANGE: BUG-2026-0082-5

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
                // START_CHANGE: IMP-2026-0002-20260420-3 - Track pc/opcode for underflow diagnostics
                currentDecodePc = pc;
                currentDecodeOpcode = opcode;
                // END_CHANGE: IMP-2026-0002-3
                // START_CHANGE: BUG-2026-0100-20260905-3 - Tag every statement this opcode emits
                // with the pc it came from, so try/catch membership can be decided on bytecode
                // ranges instead of source lines.
                int pcTagBefore100 = stmts.size();
                // END_CHANGE: BUG-2026-0100-3
                decodeOpcode(opcode, reader, stack, stmts, pool, localVarNames,
                             localVarDescriptors, currentLine, method, currentBytecode, pc);
                // START_CHANGE: BUG-2026-0100-20260905-4
                for (int pcTag100 = pcTagBefore100; pcTag100 < stmts.size(); pcTag100++) {
                    Statement tagged100 = stmts.get(pcTag100);
                    if (!statementPcs.containsKey(tagged100)) {
                        statementPcs.put(tagged100, Integer.valueOf(pc));
                    }
                }
                // END_CHANGE: BUG-2026-0100-4
            } catch (Exception e) {
                // START_CHANGE: IMP-2026-0002-20260420-4 - Record decoder exceptions too
                recordDiagnostic("DECODE_ERROR pc=" + pc
                    + " opcode=0x" + Integer.toHexString(opcode).toUpperCase()
                    + " " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                // END_CHANGE: IMP-2026-0002-4
                stmts.add(new ExpressionStatement(
                    new StringConstantExpression(currentLine,
                        "/* ERROR: opcode 0x" + Integer.toHexString(opcode) + " at pc=" + pc + " */")));
            }
        }

        // START_CHANGE: BUG-2026-0082-20260610-6 - Block boundary: an unfused iinc becomes the
        // plain statement form (this keeps for-loop tail increments exactly as before).
        flushPendingIinc(stmts);
        // END_CHANGE: BUG-2026-0082-6

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
        // START_CHANGE: BUG-2026-0051-20260420-3 - Snapshot full exit stack so successors
        // can restore the complete operand state, not just the top value.
        if (!stack.isEmpty()) {
            List<Expression> snapshot = new ArrayList<Expression>(stack.size());
            // Iterator over Deque yields top-first; reverse so list is bottom-first.
            Iterator<Expression> it = stack.iterator();
            List<Expression> topFirst = new ArrayList<Expression>();
            while (it.hasNext()) topFirst.add(it.next());
            for (int i = topFirst.size() - 1; i >= 0; i--) snapshot.add(topFirst.get(i));
            block.exitStack = snapshot;
        }
        // END_CHANGE: BUG-2026-0051-3
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

    // BUG-2026-0079: is this a record-DECONSTRUCTION switch method — a `SwitchBootstraps.typeSwitch` dispatch
    // AND a `new java/lang/MatchException` (the record-deconstruction scaffolding)? Only those are routed to
    // the JD pipeline; plain pattern switches (typeSwitch but no deconstruction, e.g. `case Circle c ->`) have
    // no MatchException and stay on the legacy path which handles them well. Walks instructions with
    // OpcodeInfo so tableswitch/lookupswitch/wide are length-correct.
    private boolean methodHasTypeSwitch(byte[] code, ConstantPool pool) {
        if (code == null || pool == null) return false;
        boolean hasTypeSwitch = false, hasMatchException = false;
        int pc = 0;
        while (pc >= 0 && pc < code.length) {
            int op = code[pc] & 0xff;
            try {
                if (op == 0xBA && pc + 2 < code.length) { // invokedynamic
                    int cpIndex = ((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff);
                    int[] indy = pool.getValue(cpIndex);
                    if (indy != null && indy.length > 1) {
                        String name = pool.getNameFromNameAndType(indy[1]);
                        if ("typeSwitch".equals(name) || "enumSwitch".equals(name)) hasTypeSwitch = true;
                    }
                } else if (op == 0xBB && pc + 2 < code.length) { // new
                    int cpIndex = ((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff);
                    String cn = pool.getClassName(cpIndex);
                    if ("java/lang/MatchException".equals(cn)) hasMatchException = true;
                }
            } catch (RuntimeException ignore) {
                // malformed entry — keep scanning
            }
            if (hasTypeSwitch && hasMatchException) return true;
            int size = OpcodeInfo.operandSize(op, code, pc);
            if (size < 0) return false; // unknown opcode -> give up safely
            pc += 1 + size;
        }
        return false;
    }

    // BUG-2026-0079: true if the JD result contains a record-deconstruction switch expression
    // (`return switch(s){ case T(comps) -> ... }`) produced by TypeSwitchRecordFolder — the signal that JD
    // genuinely reconstructed a record-pattern switch and should be preferred over legacy.
    private boolean foldedRecordPatternSwitch(List<Statement> stmts) {
        if (stmts == null) return false;
        for (Statement s : stmts) {
            Expression e = null;
            if (s instanceof ReturnStatement && ((ReturnStatement) s).hasExpression()) e = ((ReturnStatement) s).getExpression();
            else if (s instanceof ExpressionStatement) e = ((ExpressionStatement) s).getExpression();
            if (e instanceof SwitchExpression) {
                for (SwitchExpression.SwitchCase c : ((SwitchExpression) e).getCases()) {
                    if (c.isRecordPattern()) return true;
                }
            }
            if (s instanceof BlockStatement && foldedRecordPatternSwitch(((BlockStatement) s).getStatements())) return true;
        }
        return false;
    }

    // BUG-2026-0079: true if the statement tree still holds a raw `switch(SwitchBootstraps.typeSwitch(...))`
    // — i.e. the TypeSwitchRecordFolder bailed and the JD output is degraded.
    private boolean containsResidualTypeSwitch(List<Statement> stmts) {
        if (stmts == null) return false;
        for (Statement s : stmts) if (residualTypeSwitch(s)) return true;
        return false;
    }
    private boolean residualTypeSwitch(Statement s) {
        if (s == null) return false;
        if (s instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) s;
            Expression sel = sw.getSelector();
            if (sel instanceof StaticMethodInvocationExpression) {
                String n = ((StaticMethodInvocationExpression) sel).getMethodName();
                if ("typeSwitch".equals(n) || "enumSwitch".equals(n)) return true;
            }
            for (SwitchStatement.SwitchCase c : sw.getCases()) if (containsResidualTypeSwitch(c.getStatements())) return true;
            return false;
        }
        if (s instanceof BlockStatement) return containsResidualTypeSwitch(((BlockStatement) s).getStatements());
        if (s instanceof IfStatement) return residualTypeSwitch(((IfStatement) s).getThenBody());
        if (s instanceof IfElseStatement) return residualTypeSwitch(((IfElseStatement) s).getThenBody()) || residualTypeSwitch(((IfElseStatement) s).getElseBody());
        if (s instanceof WhileStatement) return residualTypeSwitch(((WhileStatement) s).getBody());
        if (s instanceof DoWhileStatement) return residualTypeSwitch(((DoWhileStatement) s).getBody());
        if (s instanceof ForStatement) return residualTypeSwitch(((ForStatement) s).getBody());
        if (s instanceof ForEachStatement) return residualTypeSwitch(((ForEachStatement) s).getBody());
        if (s instanceof LabelStatement) return residualTypeSwitch(((LabelStatement) s).getBody());
        if (s instanceof SynchronizedStatement) return residualTypeSwitch(((SynchronizedStatement) s).getBody());
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            if (residualTypeSwitch(t.getTryBody())) return true;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) if (residualTypeSwitch(cc.body)) return true;
            return t.getFinallyBody() != null && residualTypeSwitch(t.getFinallyBody());
        }
        return false;
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
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "!=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9A: { // ifne → branch if != 0 → Java condition: == 0
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "==", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9B: { // iflt → branch if < 0 → Java condition: >= 0
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, ">=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9C: { // ifge → branch if >= 0 → Java condition: < 0
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "<", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9D: { // ifgt → branch if > 0 → Java condition: <= 0
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "<=", IntegerConstantExpression.valueOf(line, 0));
                break;
            }
            case 0x9E: { // ifle → branch if <= 0 → Java condition: > 0
                Expression val = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, ">", IntegerConstantExpression.valueOf(line, 0));
                break;
            }

            // Two-operand: compare two values
            case 0x9F: { // if_icmpeq → branch if == → Java condition: !=
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "!=", b);
                break;
            }
            case 0xA0: { // if_icmpne → branch if != → Java condition: ==
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "==", b);
                break;
            }
            case 0xA1: { // if_icmplt → branch if < → Java condition: >=
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, ">=", b);
                break;
            }
            case 0xA2: { // if_icmpge → branch if >= → Java condition: <
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "<", b);
                break;
            }
            case 0xA3: { // if_icmpgt → branch if > → Java condition: <=
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "<=", b);
                break;
            }
            case 0xA4: { // if_icmple → branch if <= → Java condition: >
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, ">", b);
                break;
            }

            // Reference comparison
            case 0xA5: { // if_acmpeq
                Expression b = popOrUnderflowRef(stack);
                Expression a = popOrUnderflowRef(stack);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "!=", b);
                break;
            }
            case 0xA6: { // if_acmpne
                Expression b = popOrUnderflowRef(stack);
                Expression a = popOrUnderflowRef(stack);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN, a, "==", b);
                break;
            }

            // Null checks
            case 0xC6: { // ifnull → branch if null → Java condition: != null
                Expression val = popOrUnderflowRef(stack);
                condition = new BinaryOperatorExpression(line, PrimitiveType.BOOLEAN,
                    val, "!=", NullExpression.INSTANCE);
                break;
            }
            case 0xC7: { // ifnonnull → branch if not null → Java condition: == null
                Expression val = popOrUnderflowRef(stack);
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
        // START_CHANGE: BUG-2026-0096-20260610-4 - Reset per-method slot typing/split state (linear mode)
        slotDeclCategories = new HashMap<Integer, Integer>();
        slotRenames = new HashMap<Integer, String>();
        slotSplitCounts = new HashMap<Integer, Integer>();
        // END_CHANGE: BUG-2026-0096-4
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
        // START_CHANGE: BUG-2026-0082-20260610-7 - A pending iinc never crosses a decode run
        pendingIincActive = false;
        // END_CHANGE: BUG-2026-0082-7

        while (reader.remaining() > 0) {
            int pc = reader.getOffset();
            Integer lineNum = pcToLine.get(pc);
            if (lineNum != null) currentLine = lineNum;

            int opcode = reader.readUnsignedByte();

            try {
                // START_CHANGE: IMP-2026-0002-20260420-5 - Track pc/opcode for underflow diagnostics
                currentDecodePc = pc;
                currentDecodeOpcode = opcode;
                // END_CHANGE: IMP-2026-0002-5
                decodeOpcode(opcode, reader, stack, statements, pool, localVarNames,
                             localVarDescriptors, currentLine, method, bytecode, pc);
            } catch (Exception e) {
                // Log the error as a comment and continue decompilation
                String hexOpcode = "0x" + Integer.toHexString(opcode).toUpperCase();
                String errorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
                // START_CHANGE: IMP-2026-0002-20260420-6 - Record decoder exceptions
                recordDiagnostic("DECODE_ERROR pc=" + pc + " opcode=" + hexOpcode + " " + errorDetail);
                // END_CHANGE: IMP-2026-0002-6
                statements.add(new ExpressionStatement(
                    new StringConstantExpression(currentLine,
                        "/* ERROR: Unable to decompile opcode " + hexOpcode +
                        " at pc=" + pc + " - " + errorDetail +
                        " (stack size=" + stack.size() + ", bytecode remaining=" + reader.remaining() + ") */")));
                // Don't break - try to continue with remaining bytecode
            }
        }

        // START_CHANGE: BUG-2026-0082-20260610-8 - End-of-method boundary for an unfused iinc
        flushPendingIinc(statements);
        // END_CHANGE: BUG-2026-0082-8

        return statements;
    }

    // START_CHANGE: BUG-2026-0082-20260610-2 - Shared iinc decoding (used by narrow 0x84 and
    // wide 0xC4/0x84). Restores post/pre-increment VALUE semantics:
    // (a) if the operand stack top is a load of the same slot (javac pattern `iload; iinc`),
    //     replace it with a POSTFIX var++/var-- expression (e.g. `return a++` returned the OLD
    //     value in the original; the previous statement-only decode returned the NEW value);
    // (b) otherwise, for +-1 increments, record a pending iinc that fuses as PREFIX ++var/--var
    //     into an immediately following iload of the same slot (javac pattern `iinc; iload`,
    //     e.g. `a++ + ++a`), and is flushed as a `var++;` statement at any other opcode or at
    //     the end of the decode run (so for-loop tail increments are unchanged);
    // (c) non-unit increments keep the previous `var += N;` statement form, which is always
    //     correct because javac never leaves a stale load of the slot on the stack for them.
    private void decodeIinc(int varIdx, int incr, Deque<Expression> stack,
                            List<Statement> statements, Map<Integer, String> localVarNames, int line) {
        String name = localVarNames.containsKey(varIdx) ? (String) localVarNames.get(varIdx) : "var" + varIdx;
        // START_CHANGE: BUG-2026-0096-20260610-5 - iinc on a split slot targets the fresh variable
        boolean iincRenamed = slotRenames != null && slotRenames.containsKey(Integer.valueOf(varIdx));
        if (iincRenamed) {
            name = (String) slotRenames.get(Integer.valueOf(varIdx));
        }
        // END_CHANGE: BUG-2026-0096-5
        // START_CHANGE: BUG-2026-0103-20260905-8 - The increment must target the variable live at
        // this position too. Resolving only loads and stores left the `iinc` bound to the slot's
        // flattened name, so a loop counter in a shared slot was incremented under a DIFFERENT name
        // than the one its loads used: the update was orphaned and the loop no longer folded to a
        // `for` (java/lang/Package.versionFromArray emitted a stray `da++;`).
        if (!iincRenamed) {
            LvtRange lvr103i = resolveSlotRange(varIdx, currentDecodePc);
            if (lvr103i != null) name = lvr103i.name;
        }
        // END_CHANGE: BUG-2026-0103-8
        if ((incr == 1 || incr == -1) && !stack.isEmpty()
                && stack.peek() instanceof LocalVariableExpression
                && ((LocalVariableExpression) stack.peek()).getIndex() == varIdx) {
            // (a) postfix: the loaded old value stays on the stack, the variable is bumped
            Expression loaded = stack.pop();
            stack.push(new UnaryOperatorExpression(line, PrimitiveType.INT,
                incr == 1 ? "++" : "--", loaded, false));
        } else if (incr == 1 || incr == -1) {
            // (b) pending: may fuse as prefix into the immediately following iload
            flushPendingIinc(statements); // at most one pending at a time
            pendingIincActive = true;
            pendingIincSlot = varIdx;
            pendingIincIncr = incr;
            pendingIincLine = line;
            pendingIincName = name;
        } else {
            // (c) compound assignment statement (same emission as before this change)
            Expression var = new LocalVariableExpression(line, PrimitiveType.INT, name, varIdx);
            statements.add(new ExpressionStatement(
                new AssignmentExpression(line, PrimitiveType.INT, var, "+=",
                    IntegerConstantExpression.valueOf(line, incr))));
        }
    }

    /** Flush a recorded-but-unfused iinc as the standalone statement form (`var++;`). */
    private void flushPendingIinc(List<Statement> statements) {
        if (!pendingIincActive) return;
        pendingIincActive = false;
        Expression var = new LocalVariableExpression(pendingIincLine, PrimitiveType.INT,
            pendingIincName, pendingIincSlot);
        statements.add(new ExpressionStatement(
            new UnaryOperatorExpression(pendingIincLine, PrimitiveType.INT,
                pendingIincIncr == 1 ? "++" : "--", var, false)));
    }
    // END_CHANGE: BUG-2026-0082-2

    // START_CHANGE: BUG-2026-0081-20260610-1 - Receiver marker that the existing
    // JavaSourceWriter renders as the bare keyword `super`. The writer has no dedicated
    // super-receiver node; this composes two existing, stable writer behaviours:
    // (1) a prefix UnaryOperatorExpression prints its operator string verbatim, and
    // (2) an instance MethodInvocationExpression whose name starts with "access$" prints
    //     nothing (synthetic-accessor suppression). The composition therefore renders
    //     exactly `super`, so `super.m(args)` is emitted for invokespecial super-calls
    //     instead of the previous `this.m(args)` (which dispatched virtually back to the
    //     subclass override and recursed forever).
    private Expression buildSuperReceiver(int line) {
        Expression silent = new MethodInvocationExpression(line, VoidType.INSTANCE,
            new ThisExpression(line, ObjectType.OBJECT), currentClassInternalName,
            "access$superMarker", "()V", new ArrayList<Expression>());
        return new UnaryOperatorExpression(line, ObjectType.OBJECT, "super", silent, true);
    }
    // END_CHANGE: BUG-2026-0081-1

    @SuppressWarnings("fallthrough")
    private void decodeOpcode(int opcode, ByteReader reader, Deque<Expression> stack,
                               List<Statement> statements, ConstantPool pool,
                               Map<Integer, String> localVarNames,
                               Map<Integer, String> localVarDescriptors,
                               int line, MethodInfo method, byte[] bytecode, int pc) {

        // START_CHANGE: BUG-2026-0082-20260610-3 - Fuse a pending iinc as a PREFIX ++var/--var
        // into the immediately following iload of the same slot; flush it as a statement
        // before ANY other opcode (conservative boundary: the fusion window is exactly one
        // instruction, so basic-block layout, branches and for-loop tails are unaffected).
        if (pendingIincActive) {
            int loadSlot = -1;
            if (opcode >= 0x1A && opcode <= 0x1D) { // iload_0..3
                loadSlot = opcode - 0x1A;
            } else if (opcode == 0x15) { // iload with u1 operand
                int saved = reader.getOffset();
                int idx = reader.readUnsignedByte();
                if (idx == pendingIincSlot) {
                    loadSlot = idx;
                } else {
                    reader.setOffset(saved); // un-read; the regular case 0x15 re-reads it
                }
            }
            if (loadSlot == pendingIincSlot) {
                pendingIincActive = false;
                Expression var = new LocalVariableExpression(line, PrimitiveType.INT,
                    pendingIincName, pendingIincSlot);
                stack.push(new UnaryOperatorExpression(line, PrimitiveType.INT,
                    pendingIincIncr == 1 ? "++" : "--", var, true));
                return; // the iload is fully consumed by the fusion
            }
            flushPendingIinc(statements);
        }
        // END_CHANGE: BUG-2026-0082-3

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
                Expression idx = popOrUnderflowInt(stack, line);
                Expression arr = popOrUnderflowRef(stack);
                // START_CHANGE: BUG-2026-0069-20260610-6 - Array loads carried a hardcoded `int`
                // element type, so without LVT `String s = arr[i]` (aaload) declared an int local
                // via the LIM-0002 RHS inference and array for-each loops garbled their element
                // type. Type each load by its opcode; aaload/baload derive the component type
                // from the array expression's static type (baload serves both byte[] and
                // boolean[], so it stays byte unless the array is known boolean[]).
                Type elemType;
                Type arrType = arr != null ? arr.getType() : null;
                switch (opcode) {
                    case 0x2F: elemType = PrimitiveType.LONG; break;
                    case 0x30: elemType = PrimitiveType.FLOAT; break;
                    case 0x31: elemType = PrimitiveType.DOUBLE; break;
                    case 0x32: elemType = arrayComponentType(arrType); break;
                    case 0x33: {
                        Type ct = arrayComponentType(arrType);
                        elemType = ct == PrimitiveType.BOOLEAN ? PrimitiveType.BOOLEAN : PrimitiveType.BYTE;
                        break;
                    }
                    case 0x34: elemType = PrimitiveType.CHAR; break;
                    case 0x35: elemType = PrimitiveType.SHORT; break;
                    default: elemType = PrimitiveType.INT; break;
                }
                stack.push(new ArrayAccessExpression(line, elemType, arr, idx));
                break;
                // END_CHANGE: BUG-2026-0069-6
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
                Expression val = popOrUnderflowInt(stack, line);
                Expression idx = popOrUnderflowInt(stack, line);
                Expression arr = popOrUnderflowRef(stack);
                // START_CHANGE: ISS-2026-0002-20260323-3 - Detect array init pattern: newarray + dup + idx + val + iastore
                if (arr instanceof NewArrayExpression) {
                    NewArrayExpression nae = (NewArrayExpression) arr;
                    nae.addInitValue(val);
                    // START_CHANGE: BUG-2026-0062-20260608-1 - The `dup` before each store already left
                    // a copy of the array on the stack; only re-push if it was actually consumed,
                    // otherwise the array ends up duplicated and a following `invokevirtual` reads it as
                    // BOTH the receiver and the argument (e.g. `new Object[]{a}.formatted(...)`).
                    if (stack.isEmpty() || stack.peek() != nae) {
                        stack.push(nae);
                    }
                    // END_CHANGE: BUG-2026-0062-1
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
                    // START_CHANGE: BUG-2026-0098-20260610-1 - javac compiles a BOUND method
                    // reference (`receiver::method`) with an implicit receiver null check:
                    // `getstatic/aload receiver; dup; invokestatic Objects.requireNonNull; pop`.
                    // Materializing that popped call leaked a spurious
                    // `Objects.requireNonNull(receiver);` statement before the method reference.
                    // Skip it ONLY when it is exactly
                    // java/util/Objects.requireNonNull:(Ljava/lang/Object;)Ljava/lang/Object;
                    // with one argument REFERENCE-IDENTICAL to stack.peek() — the dup twin
                    // (dup pushes the same Expression object). Identity + exact owner/descriptor
                    // preserves user-written requireNonNull calls, which never leave their
                    // argument aliased on the stack.
                    boolean syntheticNullCheck = false;
                    if (popped instanceof StaticMethodInvocationExpression && !stack.isEmpty()) {
                        StaticMethodInvocationExpression rnn = (StaticMethodInvocationExpression) popped;
                        if ("java/util/Objects".equals(rnn.getOwnerInternalName())
                                && "requireNonNull".equals(rnn.getMethodName())
                                && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(rnn.getDescriptor())
                                && rnn.getArguments() != null && rnn.getArguments().size() == 1
                                && stack.peek() == rnn.getArguments().get(0)) {
                            syntheticNullCheck = true;
                        }
                    }
                    // If the popped expression is a method call with side effects, emit it as a statement
                    if (!syntheticNullCheck
                        && (popped instanceof MethodInvocationExpression
                        || popped instanceof StaticMethodInvocationExpression
                        || popped instanceof NewExpression
                        || popped instanceof AssignmentExpression)) {
                        statements.add(new ExpressionStatement(popped));
                    }
                    // END_CHANGE: BUG-2026-0098-1
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
            // START_CHANGE: BUG-2026-0104-20260905-1 - Full JVMS 6.5 dup family.
            // The modeled stack holds ONE entry per value, while the JVM counts computational
            // slots (long/double occupy two). The forms of dup_x2/dup2/dup2_x1/dup2_x2 therefore
            // have to be selected on the operand's category, not on how deep the stack happens to
            // be. dup2_x1 and dup2_x2 used to be plain no-ops, which lost the duplicated value and
            // surfaced later as STACK_UNDERFLOW (or, when the stack was deep enough, as a silently
            // wrong duplication): `long nextState = null;` in StampedLock.releaseWrite, and the
            // dastore pair in FdLibm$RemPio2.
            case 0x5B: { // dup_x2
                if (stack.size() < 2) break;
                Expression v1 = stack.pop();
                Expression v2 = stack.pop();
                if (!isCategory2(v2) && !stack.isEmpty()) {
                    // Form 1: v1, v2, v3 all category 1 -> ..., v1, v3, v2, v1
                    Expression v3 = stack.pop();
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                } else {
                    // Form 2: v2 is category 2 -> ..., v1, v2, v1
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                break;
            }
            case 0x5C: { // dup2
                if (stack.isEmpty()) break;
                if (isCategory2(stack.peek()) || stack.size() < 2) {
                    // Form 2: a single category-2 value is duplicated whole
                    stack.push(stack.peek());
                } else {
                    // Form 1: two category-1 values
                    Expression v1 = stack.pop();
                    Expression v2 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                }
                break;
            }
            case 0x5D: { // dup2_x1
                if (stack.isEmpty()) break;
                Expression v1 = stack.pop();
                if (isCategory2(v1)) {
                    // Form 2: v1 category 2, v2 category 1 -> ..., v1, v2, v1
                    if (stack.isEmpty()) { stack.push(v1); break; }
                    Expression v2 = stack.pop();
                    stack.push(v1);
                    stack.push(v2);
                    stack.push(v1);
                } else {
                    // Form 1: v1, v2, v3 all category 1 -> ..., v2, v1, v3, v2, v1
                    if (stack.size() < 2) { stack.push(v1); break; }
                    Expression v2 = stack.pop();
                    Expression v3 = stack.pop();
                    stack.push(v2);
                    stack.push(v1);
                    stack.push(v3);
                    stack.push(v2);
                    stack.push(v1);
                }
                break;
            }
            case 0x5E: { // dup2_x2
                if (stack.isEmpty()) break;
                Expression v1 = stack.pop();
                if (isCategory2(v1)) {
                    if (stack.isEmpty()) { stack.push(v1); break; }
                    Expression v2 = stack.pop();
                    if (isCategory2(v2) || stack.isEmpty()) {
                        // Form 4: v1 and v2 both category 2 -> ..., v1, v2, v1
                        stack.push(v1);
                        stack.push(v2);
                        stack.push(v1);
                    } else {
                        // Form 2: v1 category 2, v2 and v3 category 1 -> ..., v1, v3, v2, v1
                        Expression v3 = stack.pop();
                        stack.push(v1);
                        stack.push(v3);
                        stack.push(v2);
                        stack.push(v1);
                    }
                } else {
                    if (stack.size() < 2) { stack.push(v1); break; }
                    Expression v2 = stack.pop();
                    Expression v3 = stack.pop();
                    if (isCategory2(v3) || stack.isEmpty()) {
                        // Form 3: v1, v2 category 1, v3 category 2 -> ..., v2, v1, v3, v2, v1
                        stack.push(v2);
                        stack.push(v1);
                        stack.push(v3);
                        stack.push(v2);
                        stack.push(v1);
                    } else {
                        // Form 1: v1..v4 all category 1 -> ..., v2, v1, v4, v3, v2, v1
                        Expression v4 = stack.pop();
                        stack.push(v2);
                        stack.push(v1);
                        stack.push(v4);
                        stack.push(v3);
                        stack.push(v2);
                        stack.push(v1);
                    }
                }
                break;
            }
            // END_CHANGE: BUG-2026-0104-1
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
                // START_CHANGE: BUG-2026-0082-20260610-4 - Decode through the shared helper that
                // preserves post/pre-increment value semantics (was: always a `var++;` statement,
                // which made `return a++` return the NEW value after recompilation).
                decodeIinc(varIdx, incr, stack, statements, localVarNames, line);
                // END_CHANGE: BUG-2026-0082-4
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
                Expression b = popOrUnderflowInt(stack, line);
                Expression a = popOrUnderflowInt(stack, line);
                stack.push(new BinaryOperatorExpression(line, PrimitiveType.INT, a, "<=>", b));
                break;
            }

            // Conditional branches - single operand compared to 0 (ifeq..ifle)
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val = popOrUnderflowInt(stack, line);
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
                    Expression val2 = popOrUnderflowInt(stack, line);
                    Expression val1 = popOrUnderflowInt(stack, line);
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
                    Expression val2 = popOrUnderflowRef(stack);
                    Expression val1 = popOrUnderflowRef(stack);
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
                Expression val = popOrUnderflowInt(stack, line);
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
                Expression val = popOrUnderflowRef(stack);
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
                Expression value = popOrUnderflowRef(stack);
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
                Expression value = popOrUnderflowRef(stack);
                Expression obj = stack.isEmpty() ? new ThisExpression(line, ObjectType.OBJECT) : stack.pop();
                Expression field = new FieldAccessExpression(line, fieldType, obj, className, fieldName, desc);
                statements.add(new ExpressionStatement(
                    new AssignmentExpression(line, fieldType, field, "=", value)));
                // START_CHANGE: BUG-2026-0104-20260905-3 - Same aliasing rule as the dup;store pair
                // (BUG-2026-0087), for the dup;putfield;store shape that the category-2 dup forms
                // produce (`long nextState = state = unlockWriteState(s);`). The duplicate left on
                // the stack is the SAME expression object that was just written to the field;
                // re-materializing it would evaluate the right-hand side a second time. Replace it
                // with a read of the field just assigned.
                if (value != null && !stack.isEmpty() && stack.peek() == value) {
                    stack.pop();
                    stack.push(new FieldAccessExpression(line, fieldType, obj, className, fieldName, desc));
                }
                // END_CHANGE: BUG-2026-0104-3
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
                    Expression arg = popOrUnderflowRef(stack);
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
                    // START_CHANGE: BUG-2026-0055-20260421-1 - Unwrap cast-wrapped NewExpression
                    // receivers that appear after multi-value stack inheritance across blocks,
                    // and recognise the special case where the receiver is below-top on the
                    // stack (checkcast can leave a CastExpression on top while the original
                    // NewExpression is below). Mid-method ctor invocations targeting a class
                    // other than `this` / `super` must render as `new X(args)` -- never `super(args)`,
                    // which would be illegal outside a constructor's first position.
                    Expression newExprTarget = obj;
                    if (newExprTarget instanceof CastExpression) {
                        newExprTarget = ((CastExpression) newExprTarget).getExpression();
                    }
                    boolean isLocalCtor = newExprTarget instanceof NewExpression;
                    // END_CHANGE: BUG-2026-0055-1
                    if (isLocalCtor) {
                        Expression newExpr = new NewExpression(line, new ObjectType(className), className, desc, args);
                        // Remove the original NewExpression that dup placed (it's still on stack)
                        // The dup pushed a copy - invokespecial consumed it (obj).
                        // The original is still below. Pop it and replace with the fully-constructed version.
                        if (!stack.isEmpty() && stack.peek() instanceof NewExpression) {
                            stack.pop(); // remove the original placeholder from dup
                        }
                        stack.push(newExpr);
                    // START_CHANGE: BUG-2026-0055-20260421-2 - Cross-class invokespecial whose
                    // target is neither the current class (this() call) nor the declared super
                    // class (super() call) is a `new X(args)` -- never super(). This fires both
                    // inside and outside constructors: inside a ctor it handles embedded
                    // `new JComboBox<>(...)` where the NewExpression receiver was lost due to
                    // block-boundary stack inheritance; outside a ctor super() is plainly illegal.
                    } else if (currentClassInternalName != null
                            && !className.equals(currentClassInternalName)
                            && (currentSuperClassInternalName == null
                                || !className.equals(currentSuperClassInternalName))) {
                        Expression newExpr = new NewExpression(line, new ObjectType(className), className, desc, args);
                        stack.push(newExpr);
                    // END_CHANGE: BUG-2026-0055-2
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
                    // START_CHANGE: BUG-2026-0081-20260610-2 - invokespecial on `this` targeting a
                    // class other than the current one is a SUPER call (non-virtual dispatch).
                    // Emitting it as `this.m()` redispatches virtually to the subclass override,
                    // producing infinite recursion in the recompiled code. Emit `super.m()` instead.
                    // Same-class targets (private/this methods) keep the `this.` receiver, and
                    // CONSTANT_InterfaceMethodref targets (Interface.super.m() default-method calls)
                    // are excluded because plain `super.` would resolve to the wrong type.
                    Expression receiver = obj;
                    if (opcode == 0xB7 && obj instanceof ThisExpression
                            && currentClassInternalName != null
                            && !className.equals(currentClassInternalName)
                            && pool.getTag(index) == ConstantPool.CONSTANT_Methodref) {
                        receiver = buildSuperReceiver(line);
                    }
                    Expression invocation = new MethodInvocationExpression(
                        line, retType, receiver, className, methodName, desc, args);
                    // END_CHANGE: BUG-2026-0081-2
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
                    Expression arg = popOrUnderflowRef(stack);
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
                    args.add(0, popOrUnderflowRef(stack));
                }

                // START_CHANGE: BUG-2026-0058-20260608-1 - Dispatch invokedynamic on the
                // bootstrap FACTORY class, not just the dynamic call-site name. Previously only
                // `makeConcatWithConstants`/`typeSwitch` were recognised and every other bootstrap
                // (most importantly java.lang.runtime.ObjectMethods, used for record
                // toString/hashCode/equals) fell into the null-body lambda fallback and rendered
                // as `arg0 -> { }`. Resolve the factory owner up front.
                String bsmOwner = null;
                if (bootstrapMethodsAttr != null) {
                    BootstrapMethodsAttribute.BootstrapMethod[] bsmAll = bootstrapMethodsAttr.getBootstrapMethods();
                    int bsmIdx0 = indyEntry[0];
                    if (bsmIdx0 >= 0 && bsmIdx0 < bsmAll.length) {
                        int mhIndex = bsmAll[bsmIdx0].bootstrapMethodRef;
                        if (pool.getTag(mhIndex) == ConstantPool.CONSTANT_MethodHandle) {
                            int[] mh0 = pool.getValue(mhIndex);
                            bsmOwner = pool.getMemberClassName(mh0[1]);
                        }
                    }
                }

                // Record component bootstrap: toString/hashCode/equals synthesised by the compiler
                // via java.lang.runtime.ObjectMethods. Emit a recognisable sentinel invocation so
                // the writer can suppress the implicit record member (BUG-2026-0059); if for some
                // reason the member is NOT suppressed, a `super.<method>()`-shaped call still
                // compiles, unlike the previous `arg0 -> { }` garbage.
                if ("java/lang/runtime/ObjectMethods".equals(bsmOwner)) {
                    Expression objMethods = new StaticMethodInvocationExpression(
                        line, retType, "java/lang/runtime/ObjectMethods", methodName, desc, args);
                    if ("V".equals(retDesc)) {
                        statements.add(new ExpressionStatement(objMethods));
                    } else {
                        stack.push(objMethods);
                    }
                    break;
                }
                // END_CHANGE: BUG-2026-0058-1

                // Detect string concatenation pattern (Java 9+)
                // BUG-2026-0058: also accept the constant-free `makeConcat` recipe (gated on the
                // StringConcatFactory owner so a user method named `makeConcat` is not hijacked).
                if (("makeConcatWithConstants".equals(methodName)
                        || ("makeConcat".equals(methodName) && "java/lang/invoke/StringConcatFactory".equals(bsmOwner)))
                        && args.size() > 0) {
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
                            // START_CHANGE: BUG-2026-0053-20260610-3 - A recipe that STARTS with a
                            // placeholder (javac folds the leading "" of `"" + i + j` away) makes the
                            // emitted source evaluate its leftmost operands as primitive arithmetic.
                            // If the leftmost leaf of the `+` chain is not a String, prefix `"" +` at
                            // the LEAF (not the root: `"" + (i + j)` would still sum ints first),
                            // mirroring the constant-free fallback below.
                            concat = forceStringContextOnLeftLeaf(line, concat);
                            // END_CHANGE: BUG-2026-0053-3
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
                                        // LVT-based names, ONLY present when the LVT named every param.
                                        List<String> lvtNames = syntheticParamNames != null
                                            ? syntheticParamNames.get(implMethodName) : null;
                                        List<Statement> lambdaBodyFinal = lambdaBody;
                                        if (implDesc != null) {
                                            String[] implParamDescs = TypeNameUtil.parseMethodParameterDescriptors(implDesc);
                                            // REF_invokeVirtual (5), REF_invokeSpecial (7), REF_invokeInterface (9)
                                            // are instance invocations: the first captured arg is the implicit
                                            // `this` and does NOT appear in the impl method descriptor.
                                            int refKind = handleEntry[0];
                                            int thisCapture = (refKind == 5 || refKind == 7 || refKind == 9) ? 1 : 0;
                                            int capturedCount = Math.max(0, args.size() - thisCapture);
                                            if (lvtNames != null) {
                                                // LVT named every param; the synthetic body already uses those
                                                // names. Keep the existing behavior.
                                                for (int pi = capturedCount; pi < implParamDescs.length && pi < lvtNames.size(); pi++) {
                                                    lambdaParamNames.add(lvtNames.get(pi));
                                                    lambdaParamTypes.add(parseType(implParamDescs[pi]));
                                                }
                                            } else {
                                                // START_CHANGE: BUG-2026-0065-20260608-3 - No LVT: the body uses
                                                // `argN`. Substitute captured arguments into the body and rename
                                                // the lambda's OWN parameters to fresh `pN` so they cannot shadow
                                                // the enclosing method's `argN`. (`argN` rename without capture
                                                // substitution would break captured-variable references.)
                                                final Map<String, Expression> subst = new HashMap<String, Expression>();
                                                for (int i = 0; i < capturedCount; i++) {
                                                    int ai = i + thisCapture;
                                                    if (ai >= 0 && ai < args.size()) {
                                                        subst.put("arg" + i, args.get(ai));
                                                    }
                                                }
                                                for (int pi = capturedCount; pi < implParamDescs.length; pi++) {
                                                    String fresh = "p" + (lambdaVarCounter++);
                                                    Type pt = parseType(implParamDescs[pi]);
                                                    subst.put("arg" + pi, new LocalVariableExpression(line, pt, fresh, -1));
                                                    lambdaParamNames.add(fresh);
                                                    lambdaParamTypes.add(pt);
                                                }
                                                if (!subst.isEmpty() && lambdaBody != null) {
                                                    it.denzosoft.javadecompiler.service.converter.transform.AstLocalRewriter rw =
                                                        new it.denzosoft.javadecompiler.service.converter.transform.AstLocalRewriter() {
                                                            protected Expression onLocal(LocalVariableExpression lv) {
                                                                Expression r = subst.get(lv.getName());
                                                                return r != null ? r : lv;
                                                            }
                                                        };
                                                    List<Statement> rewritten = new ArrayList<Statement>(lambdaBody.size());
                                                    for (Statement st : lambdaBody) rewritten.add(rw.rewrite(st));
                                                    lambdaBodyFinal = rewritten;
                                                }
                                                // END_CHANGE: BUG-2026-0065-3
                                            }
                                        }
                                        Statement body = new BlockStatement(line, lambdaBodyFinal);
                                        // START_CHANGE: BUG-2026-0069-20260610-9 - Erasure-generics
                                        // Stage C: read the bootstrap's samMethodType (args[0]) and
                                        // instantiatedMethodType (args[2]) and unify the SPECIALIZED
                                        // types against the functional interface's SAM shape (built-in
                                        // java.util.function table, or a non-JDK interface's class
                                        // Signature when loadable). The parameterized signature rides
                                        // on the LambdaExpression so storeLocal can type the
                                        // declaration (`Function<Integer, Integer> f = n -> ...`);
                                        // lambda parameters stay inferred — explicitly typing them
                                        // against a raw target type does not compile.
                                        LambdaExpression lambda = new LambdaExpression(line, retType, lambdaParamNames, lambdaParamTypes, body);
                                        if ("java/lang/invoke/LambdaMetafactory".equals(bsmOwner)) {
                                            String ifaceSig = inferLambdaInterfaceSignature(bsm, retDesc, methodName, pool);
                                            if (ifaceSig != null) {
                                                lambda.setInterfaceGenericSignature(ifaceSig);
                                            }
                                        }
                                        // END_CHANGE: BUG-2026-0069-9
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
                                        // START_CHANGE: BUG-2026-0074-20260608-1 - Bound instance method
                                        // reference: for REF_invokeVirtual/Special/Interface with a
                                        // captured receiver, emit `receiver::method` (the captured value
                                        // is args[0]) instead of dropping it to an unbound `Type::method`.
                                        Expression boundReceiver = null;
                                        if ((refKind == 5 || refKind == 7 || refKind == 9) && args.size() > 0) {
                                            boundReceiver = args.get(0);
                                        }
                                        MethodReferenceExpression methodRef = new MethodReferenceExpression(
                                            line, retType, boundReceiver,
                                            boundReceiver != null ? "" : ownerName, refMethodName, implDesc);
                                        // END_CHANGE: BUG-2026-0074-1
                                        // START_CHANGE: BUG-2026-0069-20260610-15 - Erasure-generics
                                        // Stage C: same instantiatedMethodType unification as for
                                        // lambdas. A method reference against a RAW target often
                                        // does not even compile (`Consumer c = list::add`), so the
                                        // parameterized signature must reach the declaration.
                                        if ("java/lang/invoke/LambdaMetafactory".equals(bsmOwner)) {
                                            String mrIfaceSig = inferLambdaInterfaceSignature(bsm, retDesc, methodName, pool);
                                            if (mrIfaceSig != null) {
                                                methodRef.setInterfaceGenericSignature(mrIfaceSig);
                                            }
                                        }
                                        // END_CHANGE: BUG-2026-0069-15
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
                Expression count = popOrUnderflowInt(stack, line);
                stack.push(new NewArrayExpression(line, primitiveArrayType(atype), Collections.singletonList(count)));
                break;
            }

            // anewarray
            case 0xBD: {
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                Expression count = popOrUnderflowInt(stack, line);
                // START_CHANGE: BUG-2026-0066-20260608-1 - `anewarray X` creates an array whose
                // ELEMENT type is X, so the constructed array is X with ONE extra dimension. When X is
                // itself an array descriptor ("[I"), the result is `int[][]`, not `int[]`. The
                // NewArrayExpression must carry the full ARRAY type so the writer prints the right
                // number of `[]`; otherwise `int[][] m = {{..}}` decompiles to `new int[]{..}`
                // ("int[] cannot be converted to int[][]") and `new int[n][]` loses its trailing `[]`.
                Type arrType;
                if (className != null && className.startsWith("[")) {
                    Type elem = parseType(className); // e.g. "[I" -> ArrayType(int, 1)
                    if (elem instanceof ArrayType) {
                        ArrayType at = (ArrayType) elem;
                        arrType = new ArrayType(at.getElementType(), at.getDimension() + 1);
                    } else {
                        arrType = new ArrayType(elem, 1);
                    }
                } else {
                    arrType = new ArrayType(
                        new ObjectType(className != null ? className : "java/lang/Object"), 1);
                }
                stack.push(new NewArrayExpression(line, arrType, Collections.singletonList(count)));
                break;
                // END_CHANGE: BUG-2026-0066-1
            }

            // Misc
            case 0xBE: { // arraylength
                Expression arr = popOrUnderflowRef(stack);
                stack.push(new FieldAccessExpression(line, PrimitiveType.INT, arr, "", "length", "I"));
                break;
            }

            // Throw
            case 0xBF: { // athrow
                Expression exception = popOrUnderflowRef(stack);
                statements.add(new ThrowStatement(line, exception));
                break;
            }

            // Type checking
            case 0xC0: { // checkcast
                int index = reader.readUnsignedShort();
                String className = pool.getClassName(index);
                Expression expr = popOrUnderflowRef(stack);
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
                Expression expr = popOrUnderflowRef(stack);
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
                Expression monExpr = popOrUnderflowRef(stack);
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
                // START_CHANGE: BUG-2026-0084-20260610-1 - Decode the wide forms instead of
                // silently discarding their operands (e.g. `i += 1000` compiles to `wide iinc`
                // because 1000 exceeds the s1 increment range, and the statement vanished from
                // the output). Wide iinc shares the BUG-2026-0082 helper; wide loads/stores
                // (u2 slot index) delegate to the same pushLocal/storeLocal paths the narrow
                // forms use.
                int wideOpcode = reader.readUnsignedByte();
                switch (wideOpcode) {
                    case 0x84: { // wide iinc: u2 index, s2 const
                        int wVarIdx = reader.readUnsignedShort();
                        int wIncr = reader.readShort();
                        decodeIinc(wVarIdx, wIncr, stack, statements, localVarNames, line);
                        break;
                    }
                    case 0x15: // wide iload
                        pushLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, line, PrimitiveType.INT);
                        break;
                    case 0x16: // wide lload
                        pushLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, line, PrimitiveType.LONG);
                        break;
                    case 0x17: // wide fload
                        pushLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, line, PrimitiveType.FLOAT);
                        break;
                    case 0x18: // wide dload
                        pushLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, line, PrimitiveType.DOUBLE);
                        break;
                    case 0x19: // wide aload
                        pushLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, line, ObjectType.OBJECT);
                        break;
                    case 0x36: // wide istore
                        storeLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.INT);
                        break;
                    case 0x37: // wide lstore
                        storeLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.LONG);
                        break;
                    case 0x38: // wide fstore
                        storeLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.FLOAT);
                        break;
                    case 0x39: // wide dstore
                        storeLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, statements, line, PrimitiveType.DOUBLE);
                        break;
                    case 0x3A: // wide astore
                        storeLocal(stack, reader.readUnsignedShort(), localVarNames, localVarDescriptors, statements, line, ObjectType.OBJECT);
                        break;
                    case 0xA9: // wide ret (jsr/ret legacy) - keep reader aligned, no statement
                    default:
                        reader.readUnsignedShort();
                        break;
                }
                // END_CHANGE: BUG-2026-0084-1
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
                // BUG-2026-0080 (RC-5): keep the FULL array type (e.g. int[][]) on the NewArrayExpression so
                // its static type matches the declaration and the writer derives the dimension count from it
                // (mirrors anewarray/BUG-2026-0066). Extracting the element type lost a dimension, producing
                // `new int[][d1][d2]` and `int[][] = <int[]>` type-mismatch errors.
                Type fullType = parseType(className != null ? className : "Ljava/lang/Object;");
                stack.push(new NewArrayExpression(line, fullType, dimExprs));
                break;
            }

            // ifnull, ifnonnull
            case 0xC6: case 0xC7: {
                int offset = reader.readShort();
                int targetPc = pc + offset;
                if (!suppressBranchComments) {
                    Expression val = popOrUnderflowRef(stack);
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
        // START_CHANGE: BUG-2026-0096-20260610-6 - Loads of a split slot resolve to the fresh
        // variable; the slot's LVT/LVTT info belongs to the pre-split variable, so skip it.
        boolean slotRenamed = slotRenames != null && slotRenames.containsKey(Integer.valueOf(index));
        if (slotRenamed) {
            name = (String) slotRenames.get(Integer.valueOf(index));
        }
        // END_CHANGE: BUG-2026-0096-6
        // START_CHANGE: BUG-2026-0103-20260905-3 - On a slot shared by several variables, the one
        // live at this pc decides the name and type, instead of whichever LVT entry happened to be
        // written last into the flattened map.
        LvtRange lvr103 = slotRenamed ? null : resolveSlotRange(index, currentDecodePc);
        if (lvr103 != null) {
            name = lvr103.name;
        }
        // END_CHANGE: BUG-2026-0103-3
        // Prefer generic signature type over erased descriptor
        Type type = defaultType;
        // START_CHANGE: BUG-2026-0103-20260905-4 - Prefer the resolved entry's own signature/descriptor.
        if (lvr103 != null) {
            Type t103 = null;
            if (lvr103.signature != null) t103 = parseSignatureType(lvr103.signature);
            if (t103 == null && lvr103.descriptor != null) t103 = parseType(lvr103.descriptor);
            if (t103 != null) type = t103;
        }
        if (lvr103 == null && !slotRenamed && currentLocalVarSignatures != null) {
            String sig = (String) currentLocalVarSignatures.get(index);
            if (sig != null) {
                Type sigType = parseSignatureType(sig);
                if (sigType != null) {
                    type = sigType;
                }
            }
        }
        // END_CHANGE: BUG-2026-0103-4
        if (lvr103 == null && !slotRenamed && type == defaultType) {
            String desc = descriptors.get(index);
            type = desc != null ? parseType(desc) : defaultType;
        }
        stack.push(new LocalVariableExpression(line, type, name, index));
    }

    private void storeLocal(Deque<Expression> stack, int index,
                             Map<Integer, String> names, Map<Integer, String> descriptors,
                             List<Statement> statements, int line, Type defaultType) {
        String name = names.containsKey(index) ? (String) names.get(index) : "var" + index;
        // START_CHANGE: BUG-2026-0096-20260610-7 - Per-slot declared-type conflict map: javac
        // reuses dead slots for unrelated typed temporaries (e.g. astore 5 of a Point, later
        // istore 5 of an int). Without LVT the decoder identified variables purely by slot, so
        // the later store emitted `var5 = <int expr>` against a Point-typed declaration. When a
        // store's verifier category (int-family/long/float/double/reference) conflicts with the
        // category recorded at the slot's declaration, split the slot: allocate a fresh name,
        // route all subsequent loads/stores/iincs to it, and emit a new declaration. The whole
        // int family (int/byte/short/char/boolean) shares one category — boolean stores int
        // constants and byte/short/char widen to int, so those must NOT split. long/double
        // occupy two slots but are always addressed through their first slot, so categories are
        // tracked per declaration slot only.
        boolean slotRenamed = slotRenames != null && slotRenames.containsKey(Integer.valueOf(index));
        if (slotRenamed) {
            name = (String) slotRenames.get(Integer.valueOf(index));
        }
        // START_CHANGE: BUG-2026-0103-20260905-5 - Store into a shared slot names the variable whose
        // scope this store opens, not the last LVT entry for the slot.
        LvtRange lvr103s = slotRenamed ? null : resolveSlotRange(index, currentDecodePc);
        if (lvr103s != null) {
            name = lvr103s.name;
        }
        // END_CHANGE: BUG-2026-0103-5
        int storeCat = storeCategory(defaultType);
        // Never split on the synthetic `$exception` handler seed (BUG-2026-0050): the handler's
        // opening astore is consumed by the try/catch reconstruction, which names the catch
        // variable itself — a split here would leave a dangling `Type varNa = $exception;`.
        boolean exceptionSeedStore = !stack.isEmpty()
            && stack.peek() instanceof LocalVariableExpression
            && "$exception".equals(((LocalVariableExpression) stack.peek()).getName());
        if (declaredVars != null && declaredVars.contains(index) && slotDeclCategories != null
            && !exceptionSeedStore) {
            Integer prevCat = (Integer) slotDeclCategories.get(Integer.valueOf(index));
            if (prevCat != null && prevCat.intValue() != storeCat) {
                int splits = 0;
                if (slotSplitCounts != null) {
                    Integer prev = (Integer) slotSplitCounts.get(Integer.valueOf(index));
                    splits = prev == null ? 0 : prev.intValue();
                    slotSplitCounts.put(Integer.valueOf(index), Integer.valueOf(splits + 1));
                }
                name = "var" + index + (char) ('a' + (splits % 26));
                if (slotRenames != null) {
                    slotRenames.put(Integer.valueOf(index), name);
                }
                slotRenamed = true;
                declaredVars.remove(Integer.valueOf(index));
            }
        }
        // END_CHANGE: BUG-2026-0096-7
        // Prefer generic signature type (e.g., "TT;" -> GenericType "T") over erased descriptor
        Type type = defaultType;
        // START_CHANGE: BUG-2026-0103-20260905-7 - On a shared slot the declaration must take the
        // type of the variable live here, not of whichever LVT entry was flattened into the map
        // last: that is what produced `Integer label = "yes";` for a String local.
        if (lvr103s != null) {
            Type t103 = null;
            if (lvr103s.signature != null) t103 = parseSignatureType(lvr103s.signature);
            if (t103 == null && lvr103s.descriptor != null) t103 = parseType(lvr103s.descriptor);
            if (t103 != null) type = t103;
        }
        // END_CHANGE: BUG-2026-0103-7
        if (lvr103s == null && !slotRenamed && currentLocalVarSignatures != null) {
            String sig = (String) currentLocalVarSignatures.get(index);
            if (sig != null) {
                Type sigType = parseSignatureType(sig);
                if (sigType != null) {
                    type = sigType;
                }
            }
        }
        boolean typeFromDebugInfo = type != defaultType;
        if (lvr103s == null && !slotRenamed && type == defaultType) {
            String desc = (String) descriptors.get(index);
            if (desc != null) {
                type = parseType(desc);
                typeFromDebugInfo = true;
            }
        }
        Expression value = popOrUnderflowRef(stack);

        // START_CHANGE: BUG-2026-0096-20260610-8 - With no LVT/LVTT info `istore` defaults the
        // declaration to int, but the stored value may be boolean-typed (e.g. `List.add` returns
        // Z: `boolean ok = list.add(1)` decompiled to `int var3 = list.add(1)`). boolean is the
        // only int-category verifier type not assignable to int in source; byte/short/char widen
        // fine, so only the BOOLEAN case is corrected.
        if (!typeFromDebugInfo && defaultType == PrimitiveType.INT
            && value != null && value.getType() == PrimitiveType.BOOLEAN) {
            type = PrimitiveType.BOOLEAN;
        }
        // END_CHANGE: BUG-2026-0096-8

        // START_CHANGE: LIM-0002-20260324-1 - Infer type from RHS when descriptor is unavailable (e.g., TWR temp vars)
        if (type == ObjectType.OBJECT && value != null && value.getType() != null
            && value.getType() != ObjectType.OBJECT && !(value instanceof NullExpression)) {
            // START_CHANGE: BUG-2026-0063-20260608-1 - The declared local must be the ARRAY type,
            // otherwise `int[] a = {..}` decompiled to `int a = new int[..]` ("int[] cannot be
            // converted to int").
            // BUG-2026-0066: `anewarray` now stores the full ARRAY type on the NewArrayExpression
            // (e.g. `int[][]` for `anewarray [I`), so use it directly. `newarray` (primitive) still
            // stores a dimension-0 ELEMENT type, so add the single bracket from the count operand.
            if (value instanceof NewArrayExpression) {
                Type vt = value.getType();
                if (vt instanceof ArrayType) {
                    type = vt;
                } else {
                    type = new ArrayType(vt, 1);
                }
            } else {
                type = value.getType();
            }
            // END_CHANGE: BUG-2026-0063-1
        }
        // END_CHANGE: LIM-0002-1

        // START_CHANGE: BUG-2026-0087-20260610-1 - A `dup; istore` pair (assignment used as a value,
        // e.g. `while ((b = in.read()) != -1)`) leaves the ORIGINAL expression aliased on the stack:
        // re-materializing it would duplicate its side effect (the stream would be read twice and the
        // stale first byte written). Replace the alias with a reference to the just-stored local, so
        // the consumer sees `b != -1` — which the StructuredFlowBuilder assignment-into-condition
        // merge (BUG-2026-0016) then folds back into `(b = in.read()) != -1`. The reference-identity
        // check keeps `new;dup;invokespecial;astore` (alias already consumed by invokespecial) and
        // other dup idioms untouched; `i = j = k` degrades gracefully to `j = k; i = j;`.
        if (value != null && !stack.isEmpty() && stack.peek() == value) {
            stack.pop();
            stack.push(new LocalVariableExpression(line, type, name, index));
        }
        // END_CHANGE: BUG-2026-0087-1

        if (declaredVars != null && !declaredVars.contains(index)) {
            // First assignment - emit as variable declaration
            declaredVars.add(index);
            // START_CHANGE: BUG-2026-0096-20260610-9 - Record the slot's verifier category so a
            // later store of a conflicting category triggers the slot split above.
            if (slotDeclCategories != null) {
                slotDeclCategories.put(Integer.valueOf(index), Integer.valueOf(storeCat));
            }
            // END_CHANGE: BUG-2026-0096-9
            VariableDeclarationStatement vdsNew = new VariableDeclarationStatement(line, type, name, value, false, false);
            // START_CHANGE: BUG-2026-0065-20260421-3 - Propagate LVTT signature to declaration
            // (BUG-2026-0096: unless the slot was split — the LVTT entry is the old variable's)
            if (currentLocalVarSignatures != null && !slotRenamed) {
                String sig = currentLocalVarSignatures.get(index);
                if (sig != null) vdsNew.setGenericSignature(sig);
            }
            // END_CHANGE: BUG-2026-0065-3
            // START_CHANGE: BUG-2026-0069-20260610-3 - Erasure-generics Stage B: a local without
            // LVTT info initialized from a known JDK generic factory (Arrays.asList, List.of,
            // Optional.of, Stream.of, ...) gets its parameterized signature synthesized from the
            // arguments' static types when they are homogeneous (heterogeneous/unknown stay
            // erased). `List var1 = Arrays.asList(new String[]{...})` -> `List<String> var1`.
            if (vdsNew.getGenericSignature() == null) {
                String factorySig = inferFactoryGenericSignature(value, type);
                if (factorySig != null) vdsNew.setGenericSignature(factorySig);
            }
            // END_CHANGE: BUG-2026-0069-3
            // START_CHANGE: BUG-2026-0069-20260610-10 - Erasure-generics Stage C: a local without
            // LVTT info initialized with a decoded lambda (or method reference) gets the
            // parameterized declaration unified from the indy bootstrap's instantiatedMethodType
            // (carried on the LambdaExpression/MethodReferenceExpression). Only when the declared
            // type IS the erased interface — the signature must describe the type actually being
            // declared.
            if (vdsNew.getGenericSignature() == null
                    && type instanceof ObjectType && type.getDimension() == 0) {
                String lambdaSig = null;
                if (value instanceof LambdaExpression) {
                    lambdaSig = ((LambdaExpression) value).getInterfaceGenericSignature();
                } else if (value instanceof MethodReferenceExpression) {
                    lambdaSig = ((MethodReferenceExpression) value).getInterfaceGenericSignature();
                }
                if (lambdaSig != null
                        && lambdaSig.startsWith("L" + ((ObjectType) type).getInternalName() + "<")) {
                    vdsNew.setGenericSignature(lambdaSig);
                }
            }
            // END_CHANGE: BUG-2026-0069-10
            statements.add(vdsNew);
        } else {
            Expression var = new LocalVariableExpression(line, type, name, index);
            statements.add(new ExpressionStatement(
                new AssignmentExpression(line, type, var, "=", value)));
        }
    }

    // START_CHANGE: BUG-2026-0069-20260610-7 - Component type of an array expression's static
    // type, used to type aaload/baload results. Falls back to Object when the array type is
    // unknown (better than the previous hardcoded int: Object stays assignable in source).
    private static Type arrayComponentType(Type t) {
        if (t instanceof ArrayType) {
            ArrayType at = (ArrayType) t;
            if (at.getDimension() <= 1) return at.getElementType();
            return new ArrayType(at.getElementType(), at.getDimension() - 1);
        }
        if (t instanceof ObjectType && t.getDimension() > 0) {
            return ((ObjectType) t).createArrayType(t.getDimension() - 1);
        }
        return ObjectType.OBJECT;
    }
    // END_CHANGE: BUG-2026-0069-7

    // START_CHANGE: BUG-2026-0096-20260610-10 - Verifier type category of a store opcode's
    // default type: 0 = int family (istore: int/byte/short/char/boolean), 1 = long, 2 = float,
    // 3 = double, 4 = reference (astore: objects and arrays).
    private static int storeCategory(Type t) {
        if (t == PrimitiveType.LONG) return 1;
        if (t == PrimitiveType.FLOAT) return 2;
        if (t == PrimitiveType.DOUBLE) return 3;
        if (t instanceof PrimitiveType) return 0;
        return 4;
    }
    // END_CHANGE: BUG-2026-0096-10

    // START_CHANGE: BUG-2026-0069-20260610-4 - Erasure-generics Stage B: known-generic-factory
    // table. When a declaration's initializer is a call to a JDK factory whose return type is
    // generic in its arguments (Arrays.asList/List.of/Set.of/Map.of/Collections.singletonList/
    // Optional.of/Optional.ofNullable/Stream.of), synthesize the parameterized field signature
    // (e.g. `Ljava/util/List<Ljava/lang/String;>;`) from the arguments' static types. Rules:
    // a single `new E[]{...}`-style reference-array argument matching the factory's sole varargs
    // parameter contributes its component type; otherwise all arguments must be homogeneous
    // (same erased reference type). Heterogeneous/unknown/Object-typed arguments and zero-arg
    // factories (Collections.emptyList, Optional.empty, List.of()) stay erased — conservative.
    private String inferFactoryGenericSignature(Expression value, Type declaredType) {
        if (!(value instanceof StaticMethodInvocationExpression)) return null;
        if (!(declaredType instanceof ObjectType) || declaredType.getDimension() != 0) return null;
        StaticMethodInvocationExpression call = (StaticMethodInvocationExpression) value;
        String owner = call.getOwnerInternalName();
        String mname = call.getMethodName();
        if (owner == null || mname == null) return null;
        String container = null;
        boolean keyValue = false;
        if ("java/util/Arrays".equals(owner) && "asList".equals(mname)) {
            container = "java/util/List";
        } else if ("java/util/List".equals(owner) && "of".equals(mname)) {
            container = "java/util/List";
        } else if ("java/util/Set".equals(owner) && "of".equals(mname)) {
            container = "java/util/Set";
        } else if ("java/util/Map".equals(owner) && "of".equals(mname)) {
            container = "java/util/Map";
            keyValue = true;
        } else if ("java/util/Collections".equals(owner) && "singletonList".equals(mname)) {
            container = "java/util/List";
        } else if ("java/util/Optional".equals(owner)
                   && ("of".equals(mname) || "ofNullable".equals(mname))) {
            container = "java/util/Optional";
        } else if ("java/util/stream/Stream".equals(owner) && "of".equals(mname)) {
            container = "java/util/stream/Stream";
        }
        if (container == null) return null;
        // Only parameterize when the declared type IS the factory's container — the typical
        // no-LVT case where the declared type was inferred from the call's erased return type.
        if (!container.equals(((ObjectType) declaredType).getInternalName())) return null;
        List<Expression> args = call.getArguments();
        if (args == null || args.isEmpty()) return null;
        if (!keyValue && args.size() == 1) {
            // Varargs form: the sole argument is a one-dimensional reference array passed to the
            // factory's single array parameter — the element type is the array component type.
            Expression a0 = args.get(0);
            String[] pds = TypeNameUtil.parseMethodParameterDescriptors(call.getDescriptor());
            if (pds.length == 1 && pds[0].startsWith("[")
                && a0.getType() instanceof ArrayType
                && ((ArrayType) a0.getType()).getDimension() == 1) {
                String comp = referenceTypeSig(((ArrayType) a0.getType()).getElementType());
                return comp == null ? null : "L" + container + "<" + comp + ">;";
            }
        }
        if (keyValue) {
            if (args.size() < 2 || args.size() % 2 != 0) return null;
            String k = homogeneousArgSig(args, 0, 2);
            String v = homogeneousArgSig(args, 1, 2);
            if (k == null || v == null) return null;
            return "L" + container + "<" + k + v + ">;";
        }
        String e = homogeneousArgSig(args, 0, 1);
        return e == null ? null : "L" + container + "<" + e + ">;";
    }

    /** Common erased reference-type signature of every step-th argument, or null if mixed/unknown. */
    private String homogeneousArgSig(List<Expression> args, int from, int step) {
        String sig = null;
        for (int i = from; i < args.size(); i += step) {
            String s = referenceTypeSig(args.get(i).getType());
            if (s == null) return null;
            if (sig == null) {
                sig = s;
            } else if (!sig.equals(s)) {
                return null;
            }
        }
        return sig;
    }

    /** Field-signature form of a plain (non-array, non-Object) reference type, else null. */
    private String referenceTypeSig(Type t) {
        if (!(t instanceof ObjectType) || t.getDimension() != 0) return null;
        String internal = ((ObjectType) t).getInternalName();
        if (internal == null || "java/lang/Object".equals(internal)) return null;
        return "L" + internal + ";";
    }
    // END_CHANGE: BUG-2026-0069-4

    // START_CHANGE: BUG-2026-0069-20260610-11 - Erasure-generics Stage C: unify a
    // LambdaMetafactory bootstrap's instantiatedMethodType (bootstrapArguments[2] — the
    // SPECIALIZED method type, already-boxed reference types for generic SAMs) against the
    // functional interface's SAM shape to recover the parameterized interface type
    // (e.g. "Ljava/util/function/Function<Ljava/lang/Integer;Ljava/lang/Integer;>;").
    //
    // Shape table entry format: "ifaceInternalName|typeParamCount|paramTokens|returnToken".
    // Each token is a digit (index of the interface type parameter appearing at that position
    // of the SAM's generic signature) or '.' (fixed/primitive position, ignored by unification —
    // how primitive-specialized SAMs like IntFunction keep their raw int slot). Interfaces with
    // no type parameters (Runnable, IntPredicate, ...) are intentionally absent: their raw form
    // IS their exact type. Non-JDK interfaces are resolved from their class file's Signature
    // attribute when the pipeline loader can load them (see inferCustomSamShape).
    private static final String[] KNOWN_SAM_SHAPES = {
        "java/util/function/Function|2|0|1",
        "java/util/function/BiFunction|3|01|2",
        "java/util/function/Supplier|1||0",
        "java/util/function/Consumer|1|0|.",
        "java/util/function/BiConsumer|2|01|.",
        "java/util/function/Predicate|1|0|.",
        "java/util/function/BiPredicate|2|01|.",
        "java/util/function/UnaryOperator|1|0|0",
        "java/util/function/BinaryOperator|1|00|0",
        "java/util/concurrent/Callable|1||0",
        "java/util/Comparator|1|00|.",
        "java/util/function/IntFunction|1|.|0",
        "java/util/function/LongFunction|1|.|0",
        "java/util/function/DoubleFunction|1|.|0",
        "java/util/function/ToIntFunction|1|0|.",
        "java/util/function/ToLongFunction|1|0|.",
        "java/util/function/ToDoubleFunction|1|0|.",
        "java/util/function/ToIntBiFunction|2|01|.",
        "java/util/function/ToLongBiFunction|2|01|.",
        "java/util/function/ToDoubleBiFunction|2|01|.",
        "java/util/function/ObjIntConsumer|1|0.|.",
        "java/util/function/ObjLongConsumer|1|0.|.",
        "java/util/function/ObjDoubleConsumer|1|0.|."
    };

    /** Loader used to read a non-JDK functional interface's class Signature (set by the pipeline). */
    private Loader samSignatureLoader;
    /** Cache of resolved non-JDK SAM shapes: iface -> {nTypeParams, paramTokens, retToken}; empty = negative. */
    private Map<String, String[]> customSamShapeCache;

    public void setSamSignatureLoader(Loader loader) {
        this.samSignatureLoader = loader;
    }

    /**
     * Parameterized field signature of a lambda call site's functional interface, or null when
     * not derivable. `retDesc` is the indy descriptor's return type (the erased interface),
     * `samName` the dynamic call-site name (the SAM's method name).
     */
    private String inferLambdaInterfaceSignature(BootstrapMethodsAttribute.BootstrapMethod bsm,
                                                 String retDesc, String samName, ConstantPool pool) {
        if (bsm == null || retDesc == null || !retDesc.startsWith("L") || !retDesc.endsWith(";")) return null;
        if (bsm.bootstrapArguments.length < 3) return null;
        String samDesc = methodTypeDescriptor(pool, bsm.bootstrapArguments[0]);
        String instDesc = methodTypeDescriptor(pool, bsm.bootstrapArguments[2]);
        if (samDesc == null || instDesc == null) return null;
        String iface = retDesc.substring(1, retDesc.length() - 1);
        for (int i = 0; i < KNOWN_SAM_SHAPES.length; i++) {
            String[] parts = KNOWN_SAM_SHAPES[i].split("\\|", -1);
            if (parts[0].equals(iface)) {
                return unifySamShape(iface, Integer.parseInt(parts[1]), parts[2], parts[3], instDesc);
            }
        }
        if (iface.startsWith("java/") || iface.startsWith("javax/")) return null;
        String[] shape = inferCustomSamShape(iface, samName);
        if (shape == null || shape.length != 3) return null;
        return unifySamShape(iface, Integer.parseInt(shape[0]), shape[1], shape[2], instDesc);
    }

    /** Descriptor string of a CONSTANT_MethodType pool entry, else null. */
    private static String methodTypeDescriptor(ConstantPool pool, int index) {
        if (index <= 0 || index >= pool.getSize()) return null;
        if (pool.getTag(index) != ConstantPool.CONSTANT_MethodType) return null;
        Integer utf8Index = (Integer) pool.getValue(index);
        return utf8Index != null ? pool.getUtf8(utf8Index.intValue()) : null;
    }

    /**
     * Unify the instantiated (specialized) method type against the SAM shape tokens and build
     * the parameterized interface signature. Conservative: every interface type parameter must
     * bind to a reference type, conflicting bindings bail, and an all/partial-Object binding
     * bails too (Object at a type-variable position usually means an ERASED enclosing type
     * variable, where parameterizing would be wrong — raw stays, which always compiles).
     */
    private static String unifySamShape(String iface, int nTypeParams, String paramTokens,
                                        String retToken, String instDesc) {
        if (nTypeParams <= 0 || nTypeParams > 9) return null;
        String[] instParams = TypeNameUtil.parseMethodParameterDescriptors(instDesc);
        String instRet = TypeNameUtil.parseMethodReturnDescriptor(instDesc);
        if (instParams.length != paramTokens.length()) return null;
        String[] bind = new String[nTypeParams];
        for (int i = 0; i < instParams.length; i++) {
            char t = paramTokens.charAt(i);
            if (t == '.') continue;
            if (!bindSamTypeArg(bind, t - '0', instParams[i])) return null;
        }
        if (retToken.length() == 1 && retToken.charAt(0) != '.') {
            if (!bindSamTypeArg(bind, retToken.charAt(0) - '0', instRet)) return null;
        }
        StringBuilder sb = new StringBuilder("L").append(iface).append('<');
        for (int i = 0; i < nTypeParams; i++) {
            if (bind[i] == null || "Ljava/lang/Object;".equals(bind[i])) return null;
            sb.append(bind[i]);
        }
        return sb.append(">;").toString();
    }

    /** Bind interface type parameter `idx` to the reference-type descriptor `desc` (consistently). */
    private static boolean bindSamTypeArg(String[] bind, int idx, String desc) {
        if (idx < 0 || idx >= bind.length) return false;
        if (desc == null || !(desc.startsWith("L") || desc.startsWith("["))) return false;
        if (bind[idx] == null) {
            bind[idx] = desc;
            return true;
        }
        return bind[idx].equals(desc);
    }

    /**
     * Resolve a non-JDK functional interface's SAM shape from its class file (loaded via the
     * pipeline loader): {typeParamCount, paramTokens, retToken}, or null when the interface is
     * not loadable / not generic / its SAM cannot be unified positionally (nested type-variable
     * uses, method-level type parameters, overloaded abstract names).
     */
    private String[] inferCustomSamShape(String iface, String samName) {
        if (samSignatureLoader == null || samName == null) return null;
        if (customSamShapeCache == null) {
            customSamShapeCache = new HashMap<String, String[]>();
        }
        String[] cached = customSamShapeCache.get(iface);
        if (cached != null) return cached.length == 3 ? cached : null;
        String[] shape = computeCustomSamShape(iface, samName);
        customSamShapeCache.put(iface, shape != null ? shape : new String[0]);
        return shape;
    }

    private String[] computeCustomSamShape(String iface, String samName) {
        try {
            if (!samSignatureLoader.canLoad(iface)) return null;
            byte[] data = samSignatureLoader.load(iface);
            if (data == null) return null;
            ClassFile cf = new ClassFileDeserializer().deserialize(data);
            if (cf == null || !cf.isInterface()) return null;
            SignatureAttribute clsSig = cf.findAttribute("Signature");
            if (clsSig == null) return null;
            List<String> typeParams = parseSigTypeParamNames(clsSig.getSignature());
            if (typeParams == null || typeParams.isEmpty() || typeParams.size() > 9) return null;
            MethodInfo sam = null;
            for (MethodInfo m : cf.getMethods()) {
                if (m.isAbstract() && samName.equals(m.getName())) {
                    if (sam != null) return null; // overloaded abstract name: ambiguous
                    sam = m;
                }
            }
            if (sam == null) return null;
            SignatureAttribute mSig = sam.findAttribute("Signature");
            if (mSig == null) return null; // SAM not generic in the interface type parameters
            String[] retSig = new String[1];
            List<String> paramSigs = splitMethodSigTopLevel(mSig.getSignature(), retSig);
            if (paramSigs == null) return null;
            StringBuilder paramTokens = new StringBuilder();
            for (int i = 0; i < paramSigs.size(); i++) {
                char t = samShapeToken(paramSigs.get(i), typeParams);
                if (t == 0) return null;
                paramTokens.append(t);
            }
            char retTok = samShapeToken(retSig[0], typeParams);
            if (retTok == 0) return null;
            return new String[]{String.valueOf(typeParams.size()),
                paramTokens.toString(), String.valueOf(retTok)};
        } catch (Exception e) {
            return null;
        }
    }

    /** Type-parameter names of a class Signature ("<T:...U:...>L...;"), or null. */
    private static List<String> parseSigTypeParamNames(String sig) {
        if (sig == null || !sig.startsWith("<")) return null;
        List<String> names = new ArrayList<String>();
        int i = 1;
        while (i < sig.length() && sig.charAt(i) != '>') {
            int colon = sig.indexOf(':', i);
            if (colon < 0) return null;
            names.add(sig.substring(i, colon));
            i = colon;
            while (i < sig.length() && sig.charAt(i) == ':') {
                i++; // a bound follows; the class bound may be empty (next char is ':' again)
                if (i < sig.length() && sig.charAt(i) != ':' && sig.charAt(i) != '>') {
                    i = scanTypeSig(sig, i);
                    if (i < 0) return null;
                }
            }
        }
        return names;
    }

    /** Top-level parameter type signatures of a method Signature; return type via retOut[0]. */
    private static List<String> splitMethodSigTopLevel(String sig, String[] retOut) {
        if (sig == null || sig.length() == 0) return null;
        if (sig.charAt(0) == '<') return null; // method-level type parameters: not unifiable
        if (sig.charAt(0) != '(') return null;
        int i = 1;
        List<String> out = new ArrayList<String>();
        while (i < sig.length() && sig.charAt(i) != ')') {
            int start = i;
            i = scanTypeSig(sig, i);
            if (i < 0) return null;
            out.add(sig.substring(start, i));
        }
        if (i >= sig.length()) return null;
        i++; // ')'
        int end = scanTypeSig(sig, i);
        if (end < 0) return null;
        retOut[0] = sig.substring(i, end);
        return out;
    }

    /** End index (exclusive) of one type signature starting at `i`, or -1. */
    private static int scanTypeSig(String sig, int i) {
        if (i >= sig.length()) return -1;
        char c = sig.charAt(i);
        switch (c) {
            case 'B': case 'C': case 'D': case 'F': case 'I':
            case 'J': case 'S': case 'Z': case 'V':
                return i + 1;
            case '[':
                return scanTypeSig(sig, i + 1);
            case 'T': {
                int semi = sig.indexOf(';', i);
                return semi < 0 ? -1 : semi + 1;
            }
            case 'L': {
                int depth = 0;
                i++;
                while (i < sig.length()) {
                    char ch = sig.charAt(i);
                    if (ch == '<') depth++;
                    else if (ch == '>') depth--;
                    else if (ch == ';' && depth == 0) return i + 1;
                    i++;
                }
                return -1;
            }
            case '*':
                return i + 1;
            case '+': case '-':
                return scanTypeSig(sig, i + 1);
            default:
                return -1;
        }
    }

    /**
     * Shape token for one SAM position: digit = index of the interface type parameter used
     * DIRECTLY at that position ("TT;"), '.' = type-variable-free position (ignored), 0 (NUL)
     * = type-variable-dependent in a nested/array position — not unifiable from an erased
     * MethodType, the caller bails.
     */
    private static char samShapeToken(String typeSig, List<String> typeParams) {
        if (typeSig == null || typeSig.length() == 0) return 0;
        if (typeSig.charAt(0) == 'T' && typeSig.endsWith(";")) {
            int idx = typeParams.indexOf(typeSig.substring(1, typeSig.length() - 1));
            return idx >= 0 && idx <= 9 ? (char) ('0' + idx) : (char) 0;
        }
        int d = 0;
        while (d < typeSig.length() && typeSig.charAt(d) == '[') d++;
        String s = typeSig.substring(d);
        if (s.startsWith("T") || s.indexOf('<') >= 0) return 0;
        return '.';
    }
    // END_CHANGE: BUG-2026-0069-11

    /**
     * BUG-2026-0077: promote the first bare `v = expr;` of a local that has NO declaration anywhere in the
     * method (and is not a parameter) into a `Type v = expr;` declaration.
     */
    private void promoteUndeclaredAssignments(List<Statement> stmts, java.util.Set<String> paramNames) {
        java.util.Set<String> declared = new java.util.HashSet<String>(paramNames);
        collectDeclaredNames(stmts, declared);
        java.util.Set<String> undeclared = new java.util.HashSet<String>();
        collectAssignedUndeclared(stmts, declared, undeclared);
        if (!undeclared.isEmpty()) promoteFirst(stmts, undeclared);
    }

    private void collectDeclaredNames(List<Statement> stmts, java.util.Set<String> d) {
        if (stmts == null) return;
        for (Statement s : stmts) collectDeclaredNames(s, d);
    }
    private void collectDeclaredNames(Statement s, java.util.Set<String> d) {
        if (s == null) return;
        if (s instanceof VariableDeclarationStatement) d.add(((VariableDeclarationStatement) s).getName());
        else if (s instanceof ForEachStatement) { d.add(((ForEachStatement) s).getVariableName()); collectDeclaredNames(((ForEachStatement) s).getBody(), d); }
        else if (s instanceof BlockStatement) collectDeclaredNames(((BlockStatement) s).getStatements(), d);
        else if (s instanceof IfStatement) { collectPatternBindings(((IfStatement) s).getCondition(), d); collectDeclaredNames(((IfStatement) s).getThenBody(), d); }
        else if (s instanceof IfElseStatement) { collectPatternBindings(((IfElseStatement) s).getCondition(), d); collectDeclaredNames(((IfElseStatement) s).getThenBody(), d); collectDeclaredNames(((IfElseStatement) s).getElseBody(), d); }
        else if (s instanceof WhileStatement) { collectPatternBindings(((WhileStatement) s).getCondition(), d); collectDeclaredNames(((WhileStatement) s).getBody(), d); }
        else if (s instanceof DoWhileStatement) collectDeclaredNames(((DoWhileStatement) s).getBody(), d);
        else if (s instanceof ForStatement) { collectDeclaredNames(((ForStatement) s).getInit(), d); collectDeclaredNames(((ForStatement) s).getBody(), d); }
        else if (s instanceof LabelStatement) collectDeclaredNames(((LabelStatement) s).getBody(), d);
        else if (s instanceof SynchronizedStatement) collectDeclaredNames(((SynchronizedStatement) s).getBody(), d);
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            collectDeclaredNames(t.getResources(), d);
            collectDeclaredNames(t.getTryBody(), d);
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) { if (cc.variableName != null) d.add(cc.variableName); collectDeclaredNames(cc.body, d); }
            collectDeclaredNames(t.getFinallyBody(), d);
        }
        else if (s instanceof SwitchStatement) for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) collectDeclaredNames(c.getStatements(), d);
    }
    private void collectPatternBindings(Expression e, java.util.Set<String> d) {
        if (e instanceof InstanceOfExpression) { String n = ((InstanceOfExpression) e).getPatternVariableName(); if (n != null) d.add(n); }
        else if (e instanceof BinaryOperatorExpression) { collectPatternBindings(((BinaryOperatorExpression) e).getLeft(), d); collectPatternBindings(((BinaryOperatorExpression) e).getRight(), d); }
        else if (e instanceof UnaryOperatorExpression) collectPatternBindings(((UnaryOperatorExpression) e).getExpression(), d);
    }
    private void collectAssignedUndeclared(List<Statement> stmts, java.util.Set<String> declared, java.util.Set<String> out) {
        if (stmts == null) return;
        for (Statement s : stmts) collectAssignedUndeclared(s, declared, out);
    }
    private void collectAssignedUndeclared(Statement s, java.util.Set<String> declared, java.util.Set<String> out) {
        if (s == null) return;
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression && "=".equals(((AssignmentExpression) e).getOperator())
                    && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                String n = ((LocalVariableExpression) ((AssignmentExpression) e).getLeft()).getName();
                if (!declared.contains(n)) out.add(n);
            }
        } else if (s instanceof BlockStatement) collectAssignedUndeclared(((BlockStatement) s).getStatements(), declared, out);
        else if (s instanceof IfStatement) collectAssignedUndeclared(((IfStatement) s).getThenBody(), declared, out);
        else if (s instanceof IfElseStatement) { collectAssignedUndeclared(((IfElseStatement) s).getThenBody(), declared, out); collectAssignedUndeclared(((IfElseStatement) s).getElseBody(), declared, out); }
        else if (s instanceof WhileStatement) collectAssignedUndeclared(((WhileStatement) s).getBody(), declared, out);
        else if (s instanceof DoWhileStatement) collectAssignedUndeclared(((DoWhileStatement) s).getBody(), declared, out);
        else if (s instanceof ForStatement) { collectAssignedUndeclared(((ForStatement) s).getInit(), declared, out); collectAssignedUndeclared(((ForStatement) s).getBody(), declared, out); }
        else if (s instanceof ForEachStatement) collectAssignedUndeclared(((ForEachStatement) s).getBody(), declared, out);
        else if (s instanceof LabelStatement) collectAssignedUndeclared(((LabelStatement) s).getBody(), declared, out);
        else if (s instanceof SynchronizedStatement) collectAssignedUndeclared(((SynchronizedStatement) s).getBody(), declared, out);
        else if (s instanceof TryCatchStatement) { TryCatchStatement t = (TryCatchStatement) s; collectAssignedUndeclared(t.getTryBody(), declared, out); for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) collectAssignedUndeclared(cc.body, declared, out); collectAssignedUndeclared(t.getFinallyBody(), declared, out); }
        else if (s instanceof SwitchStatement) for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) collectAssignedUndeclared(c.getStatements(), declared, out);
    }
    private void promoteFirst(List<Statement> stmts, java.util.Set<String> undeclared) {
        if (stmts == null) return;
        for (int i = 0; i < stmts.size() && !undeclared.isEmpty(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ExpressionStatement) {
                Expression e = ((ExpressionStatement) s).getExpression();
                if (e instanceof AssignmentExpression && "=".equals(((AssignmentExpression) e).getOperator())
                        && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                    LocalVariableExpression lv = (LocalVariableExpression) ((AssignmentExpression) e).getLeft();
                    if (undeclared.contains(lv.getName())) {
                        stmts.set(i, new VariableDeclarationStatement(s.getLineNumber(), lv.getType(), lv.getName(),
                            ((AssignmentExpression) e).getRight(), false, false));
                        undeclared.remove(lv.getName());
                        continue;
                    }
                }
            }
            if (s instanceof BlockStatement) promoteFirst(((BlockStatement) s).getStatements(), undeclared);
            else if (s instanceof IfStatement && ((IfStatement) s).getThenBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((IfStatement) s).getThenBody()).getStatements(), undeclared);
            else if (s instanceof IfElseStatement) {
                if (((IfElseStatement) s).getThenBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((IfElseStatement) s).getThenBody()).getStatements(), undeclared);
                if (((IfElseStatement) s).getElseBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((IfElseStatement) s).getElseBody()).getStatements(), undeclared);
            }
            else if (s instanceof WhileStatement && ((WhileStatement) s).getBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((WhileStatement) s).getBody()).getStatements(), undeclared);
            else if (s instanceof ForStatement && ((ForStatement) s).getBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((ForStatement) s).getBody()).getStatements(), undeclared);
            else if (s instanceof ForEachStatement && ((ForEachStatement) s).getBody() instanceof BlockStatement) promoteFirst(((BlockStatement) ((ForEachStatement) s).getBody()).getStatements(), undeclared);
            // START_CHANGE: BUG-2026-0056-20260610-15 - An undeclared variable whose first
            // assignment lives inside a try statement (try body, catch clause or finally) must
            // NOT be promoted in place: the assignment's scope would not cover the uses in the
            // other clauses or after the try (e.g. `r = call();` in the try, `r = fallback;` in
            // the catch, `return r;` after - the slot's first store was consumed by the catch's
            // exception-store elision, so no declaration survived). Insert a bare declaration
            // immediately BEFORE the try instead; both clauses assign it, so definite
            // assignment holds at the after-try uses.
            else if (s instanceof TryCatchStatement) {
                java.util.List<String> hoisted = null;
                for (String un : undeclared) {
                    Type unType = findAssignedVarType(s, un);
                    if (unType != null) {
                        stmts.add(i, new VariableDeclarationStatement(
                            s.getLineNumber(), unType, un, null, false, false));
                        i++;
                        if (hoisted == null) hoisted = new java.util.ArrayList<String>();
                        hoisted.add(un);
                    }
                }
                if (hoisted != null) undeclared.removeAll(hoisted);
            }
            // END_CHANGE: BUG-2026-0056-15
        }
    }

    // START_CHANGE: BUG-2026-0056-20260610-16 - Find the declared type for a variable assigned
    // somewhere inside a statement tree: the type of the first plain assignment's RHS when it is
    // a concrete reference type, otherwise the LHS slot type. Returns null when no assignment
    // to the variable exists inside the tree.
    private Type findAssignedVarType(Statement s, String name) {
        if (s == null) return null;
        if (s instanceof ExpressionStatement) {
            Expression e = ((ExpressionStatement) s).getExpression();
            if (e instanceof AssignmentExpression && "=".equals(((AssignmentExpression) e).getOperator())
                    && ((AssignmentExpression) e).getLeft() instanceof LocalVariableExpression) {
                LocalVariableExpression lv = (LocalVariableExpression) ((AssignmentExpression) e).getLeft();
                if (name.equals(lv.getName())) {
                    Expression rhs = ((AssignmentExpression) e).getRight();
                    Type rhsType = rhs == null ? null : rhs.getType();
                    if (rhsType instanceof ObjectType && !(rhs instanceof NullExpression)) {
                        return rhsType;
                    }
                    if (lv.getType() != null) return lv.getType();
                    return ObjectType.OBJECT;
                }
            }
            return null;
        }
        if (s instanceof BlockStatement) {
            for (Statement c : ((BlockStatement) s).getStatements()) {
                Type t = findAssignedVarType(c, name);
                if (t != null) return t;
            }
            return null;
        }
        if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            Type r = findAssignedVarType(t.getTryBody(), name);
            if (r != null) return r;
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                r = findAssignedVarType(cc.body, name);
                if (r != null) return r;
            }
            return findAssignedVarType(t.getFinallyBody(), name);
        }
        if (s instanceof IfStatement) {
            return findAssignedVarType(((IfStatement) s).getThenBody(), name);
        }
        if (s instanceof IfElseStatement) {
            Type r = findAssignedVarType(((IfElseStatement) s).getThenBody(), name);
            if (r != null) return r;
            return findAssignedVarType(((IfElseStatement) s).getElseBody(), name);
        }
        if (s instanceof WhileStatement) {
            return findAssignedVarType(((WhileStatement) s).getBody(), name);
        }
        if (s instanceof DoWhileStatement) {
            return findAssignedVarType(((DoWhileStatement) s).getBody(), name);
        }
        if (s instanceof ForStatement) {
            Type r = findAssignedVarType(((ForStatement) s).getInit(), name);
            if (r != null) return r;
            return findAssignedVarType(((ForStatement) s).getBody(), name);
        }
        if (s instanceof SynchronizedStatement) {
            return findAssignedVarType(((SynchronizedStatement) s).getBody(), name);
        }
        if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase sc : ((SwitchStatement) s).getCases()) {
                for (Statement c : sc.getStatements()) {
                    Type t = findAssignedVarType(c, name);
                    if (t != null) return t;
                }
            }
        }
        return null;
    }
    // END_CHANGE: BUG-2026-0056-16

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
        Expression right = popOrUnderflowInt(stack, line);
        Expression left = popOrUnderflowInt(stack, line);
        stack.push(new BinaryOperatorExpression(line, type, left, op, right));
    }

    private void unaryOp(Deque<Expression> stack, String op, Type type, int line) {
        Expression expr = popOrUnderflowInt(stack, line);
        stack.push(new UnaryOperatorExpression(line, type, op, expr, true));
    }

    private void castTop(Deque<Expression> stack, Type targetType, int line) {
        Expression expr = popOrUnderflowInt(stack, line);
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
        // START_CHANGE: BUG-2026-0101-20260905-1 - This pass used to run only on the top-level
        // statement list, so a monitor region nested inside an if/loop/try/switch/label kept its
        // raw `/* __MONITORENTER__ */` marker: the lock silently disappeared from the output while
        // the body still compiled (98 of 3,376 java.base classes). Nested bodies are now processed
        // too, but ONLY AFTER this list has consumed its own markers -- descending first would eat
        // the `__MONITOREXIT__` inside an enclosing region's try-body and leave that region with no
        // end, yielding an empty `synchronized {}` with the body hoisted out of the lock.
        if (statements == null) return statements;
        if (statements.size() < 2) {
            // A lone if/loop/try can still contain a complete nested region.
            for (int ri101 = 0; ri101 < statements.size(); ri101++) recurseSynchronized(statements.get(ri101));
            return statements;
        }
        // END_CHANGE: BUG-2026-0101-1
        // First pass: strip monitor markers from inside try-finally and unwrap synthetic try-finally
        List<Statement> cleaned = stripMonitorFromTryFinally(statements);
        // START_CHANGE: BUG-2026-0092-20260610-1 - Balanced __MONITORENTER__/__MONITOREXIT__ pairing:
        // recurse on nested enters (instead of skipping them) so nested synchronized blocks are
        // preserved, and hoist a value-Return/Throw that immediately follows the closing exit into
        // the innermost body (its value was computed under the lock before monitorexit).
        List<Statement> result = new ArrayList<Statement>(cleaned.size());
        int i = 0;
        while (i < cleaned.size()) {
            // Look for __MONITORENTER__ marker
            if (isMonitorMarker(cleaned.get(i), "MONITORENTER")) {
                // Find the lock expression: the statement before monitorenter should be
                // varX = lockExpr (the dup+astore pattern)
                Expression lockExpr = extractLockFromTail(result);
                if (lockExpr == null) {
                    i++;
                    continue;
                }
                int[] pos = new int[] { i + 1 };
                SynchronizedStatement sync = collectSynchronizedRegion(cleaned, pos, lockExpr);
                i = pos[0];
                // A Return-with-value/Throw immediately after the final monitorexit was computed
                // inside the monitor (value carried across monitorexit on the operand stack):
                // move it inside the innermost reconstructed body.
                if (i < cleaned.size()) {
                    Statement next = cleaned.get(i);
                    boolean hoistable = (next instanceof ReturnStatement && ((ReturnStatement) next).hasExpression())
                        || next instanceof ThrowStatement;
                    if (hoistable) {
                        SynchronizedStatement hoisted = hoistTailIntoSync(sync, next);
                        if (hoisted != null) {
                            sync = hoisted;
                            i++;
                        }
                    }
                }
                result.add(sync);
                continue;
            }
            if (isMonitorMarker(cleaned.get(i), "MONITOREXIT")) {
                i++;
                continue;
            }
            result.add(cleaned.get(i));
            i++;
        }
        // START_CHANGE: BUG-2026-0101-20260905-3 - Now that this list's own MONITORENTER/EXIT pairs
        // are consumed, reconstruct the regions nested inside the statements that survived.
        for (int ri101 = 0; ri101 < result.size(); ri101++) {
            recurseSynchronized(result.get(ri101));
        }
        // END_CHANGE: BUG-2026-0101-3
        return result;
    }

    // START_CHANGE: BUG-2026-0101-20260905-2 - Recursion helpers for nested monitor regions.
    /**
     * Reconstruct synchronized regions inside every body reachable from {@code s}.
     * Mirrors {@code BranchVarHoister.recurse}; bodies are rewritten in place because
     * {@link #reconstructSynchronized} returns a new list.
     */
    private void recurseSynchronized(Statement s) {
        if (s == null) return;
        if (s instanceof BlockStatement) rebuildSynchronizedBody(((BlockStatement) s).getStatements());
        else if (s instanceof IfStatement) recurseSynchronized(((IfStatement) s).getThenBody());
        else if (s instanceof IfElseStatement) {
            recurseSynchronized(((IfElseStatement) s).getThenBody());
            recurseSynchronized(((IfElseStatement) s).getElseBody());
        }
        else if (s instanceof WhileStatement) recurseSynchronized(((WhileStatement) s).getBody());
        else if (s instanceof DoWhileStatement) recurseSynchronized(((DoWhileStatement) s).getBody());
        else if (s instanceof ForStatement) recurseSynchronized(((ForStatement) s).getBody());
        else if (s instanceof ForEachStatement) recurseSynchronized(((ForEachStatement) s).getBody());
        else if (s instanceof LabelStatement) recurseSynchronized(((LabelStatement) s).getBody());
        else if (s instanceof SynchronizedStatement) recurseSynchronized(((SynchronizedStatement) s).getBody());
        else if (s instanceof TryCatchStatement) {
            TryCatchStatement t = (TryCatchStatement) s;
            recurseSynchronized(t.getTryBody());
            for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) recurseSynchronized(cc.body);
            if (t.getFinallyBody() != null) recurseSynchronized(t.getFinallyBody());
        }
        else if (s instanceof SwitchStatement) {
            for (SwitchStatement.SwitchCase c : ((SwitchStatement) s).getCases()) {
                rebuildSynchronizedBody(c.getStatements());
            }
        }
    }

    /**
     * Run {@link #reconstructSynchronized} over a body list, replacing its contents in place.
     * A nested body gets exactly the same treatment as the top-level list (unwrap the synthetic
     * monitor try-finally, then pair the markers); running it after the enclosing list has been
     * reconstructed guarantees any marker still present here belongs to this body.
     */
    private void rebuildSynchronizedBody(List<Statement> body) {
        if (body == null || body.isEmpty()) return;
        List<Statement> rebuilt = reconstructSynchronized(body);
        if (rebuilt != body) {
            body.clear();
            body.addAll(rebuilt);
        }
    }
    // END_CHANGE: BUG-2026-0101-2

    /**
     * Extract the lock expression from the trailing statement of {@code list}
     * (the dup+astore pattern: varX = lockExpr just before __MONITORENTER__).
     * On success the trailing statement is removed from the list.
     */
    private Expression extractLockFromTail(List<Statement> list) {
        if (list.isEmpty()) return null;
        Statement prev = list.get(list.size() - 1);
        Expression lockExpr = null;
        if (prev instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement vds = (VariableDeclarationStatement) prev;
            if (vds.hasInitializer()) {
                lockExpr = vds.getInitializer();
            }
        } else if (prev instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) prev).getExpression();
            if (expr instanceof AssignmentExpression) {
                lockExpr = ((AssignmentExpression) expr).getRight();
            }
        }
        if (lockExpr != null) {
            list.remove(list.size() - 1);
        }
        return lockExpr;
    }

    /**
     * Collect the body of a synchronized region starting just after its __MONITORENTER__
     * marker ({@code pos[0]}). Markers are paired like balanced parentheses: a nested
     * __MONITORENTER__ opens an inner SynchronizedStatement (consuming the preceding
     * lock-temp declaration as its monitor expression) and the region terminates at its
     * own depth-matched __MONITOREXIT__. On return {@code pos[0]} points just past the
     * consumed closing marker (or end of list).
     */
    private SynchronizedStatement collectSynchronizedRegion(List<Statement> cleaned, int[] pos, Expression lockExpr) {
        List<Statement> syncBody = new ArrayList<Statement>();
        while (pos[0] < cleaned.size()) {
            Statement s = cleaned.get(pos[0]);
            if (isMonitorMarker(s, "MONITOREXIT")) {
                pos[0]++;
                break;
            }
            if (isMonitorMarker(s, "MONITORENTER")) {
                Expression innerLock = extractLockFromTail(syncBody);
                if (innerLock == null) {
                    // Unpaired nested marker without a lock-temp declaration: drop it
                    pos[0]++;
                    continue;
                }
                pos[0]++;
                SynchronizedStatement inner = collectSynchronizedRegion(cleaned, pos, innerLock);
                // The enclosing region's monitorexit may have been stripped already (try-finally
                // unwrap), leaving a Return/Throw right after the inner region's exit: hoist it
                // into the inner body as well (its value was computed under both locks).
                if (pos[0] < cleaned.size()) {
                    Statement next = cleaned.get(pos[0]);
                    boolean hoistable = (next instanceof ReturnStatement && ((ReturnStatement) next).hasExpression())
                        || next instanceof ThrowStatement;
                    if (hoistable) {
                        SynchronizedStatement hoisted = hoistTailIntoSync(inner, next);
                        if (hoisted != null) {
                            inner = hoisted;
                            pos[0]++;
                        }
                    }
                }
                syncBody.add(inner);
                continue;
            }
            syncBody.add(s);
            pos[0]++;
        }
        // Remove any remaining monitor markers from collected body (nested blocks)
        syncBody = removeMonitorMarkers(syncBody);
        int line = lockExpr instanceof AbstractExpression
            ? ((AbstractExpression) lockExpr).getLineNumber() : 0;
        Statement body;
        if (syncBody.size() == 1) {
            body = syncBody.get(0);
        } else {
            body = new BlockStatement(line, syncBody);
        }
        return new SynchronizedStatement(line, lockExpr, body);
    }

    /**
     * Rebuild {@code sync} with {@code tail} appended to the innermost body, descending
     * through a trailing chain of nested SynchronizedStatements. Returns null when the
     * innermost body already ends abruptly (appending would create unreachable code).
     */
    private SynchronizedStatement hoistTailIntoSync(SynchronizedStatement sync, Statement tail) {
        Statement body = sync.getBody();
        if (body instanceof SynchronizedStatement) {
            SynchronizedStatement inner = hoistTailIntoSync((SynchronizedStatement) body, tail);
            if (inner == null) return null;
            return new SynchronizedStatement(sync.getLineNumber(), sync.getMonitor(), inner);
        }
        List<Statement> stmts;
        int bodyLine;
        if (body instanceof BlockStatement) {
            stmts = new ArrayList<Statement>(((BlockStatement) body).getStatements());
            bodyLine = body.getLineNumber();
        } else {
            stmts = new ArrayList<Statement>();
            stmts.add(body);
            bodyLine = sync.getLineNumber();
        }
        if (!stmts.isEmpty()) {
            Statement last = stmts.get(stmts.size() - 1);
            if (last instanceof SynchronizedStatement) {
                SynchronizedStatement inner = hoistTailIntoSync((SynchronizedStatement) last, tail);
                if (inner == null) return null;
                stmts.set(stmts.size() - 1, inner);
                return new SynchronizedStatement(sync.getLineNumber(), sync.getMonitor(),
                    new BlockStatement(bodyLine, stmts));
            }
            if (last instanceof ReturnStatement || last instanceof ThrowStatement) {
                return null;
            }
        }
        stmts.add(tail);
        return new SynchronizedStatement(sync.getLineNumber(), sync.getMonitor(),
            new BlockStatement(bodyLine, stmts));
    }
    // END_CHANGE: BUG-2026-0092-1

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
