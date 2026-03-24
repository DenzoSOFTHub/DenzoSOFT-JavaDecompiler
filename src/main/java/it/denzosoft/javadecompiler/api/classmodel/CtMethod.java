/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.AnnotationInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.ExceptionsAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.LocalVariableTableAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.MethodParametersAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.RuntimeAnnotationsAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.RuntimeParameterAnnotationsAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.SignatureAttribute;
import it.denzosoft.javadecompiler.util.TypeNameUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a method in a class. Similar to javassist.CtMethod.
 */
public class CtMethod {

    private final MethodInfo methodInfo;
    private final CtClass declaringClass;

    CtMethod(MethodInfo methodInfo, CtClass declaringClass) {
        this.methodInfo = methodInfo;
        this.declaringClass = declaringClass;
    }

    /**
     * Get the name of this method.
     */
    public String getName() {
        return methodInfo.getName();
    }

    /**
     * Get the method descriptor (e.g., "(ILjava/lang/String;)V").
     */
    public String getDescriptor() {
        return methodInfo.getDescriptor();
    }

    /**
     * Get the human-readable return type name (e.g., "void", "int", "java.lang.String").
     */
    public String getReturnTypeName() {
        String retDesc = TypeNameUtil.parseMethodReturnDescriptor(methodInfo.getDescriptor());
        return TypeNameUtil.descriptorToTypeName(retDesc, false);
    }

    /**
     * Get the human-readable parameter type names.
     */
    public String[] getParameterTypeNames() {
        String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(methodInfo.getDescriptor());
        String[] names = new String[paramDescs.length];
        for (int i = 0; i < paramDescs.length; i++) {
            names[i] = TypeNameUtil.descriptorToTypeName(paramDescs[i], false);
        }
        return names;
    }

    /**
     * Get parameter names from the MethodParameters attribute or LocalVariableTable.
     * Returns null if no parameter name information is available.
     */
    public String[] getParameterNames() {
        String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(methodInfo.getDescriptor());
        int paramCount = paramDescs.length;
        if (paramCount == 0) {
            return new String[0];
        }

        // Try MethodParameters attribute first
        MethodParametersAttribute mpa = methodInfo.findAttribute("MethodParameters");
        if (mpa != null) {
            MethodParametersAttribute.Parameter[] params = mpa.getParameters();
            String[] names = new String[params.length];
            for (int i = 0; i < params.length; i++) {
                names[i] = params[i].name;
            }
            return names;
        }

        // Fall back to LocalVariableTable
        CodeAttribute code = methodInfo.findAttribute("Code");
        if (code != null) {
            LocalVariableTableAttribute lvt = null;
            for (Attribute attr : code.getAttributes()) {
                if ("LocalVariableTable".equals(attr.getName()) && attr instanceof LocalVariableTableAttribute) {
                    lvt = (LocalVariableTableAttribute) attr;
                    break;
                }
            }
            if (lvt != null) {
                LocalVariableTableAttribute.LocalVariable[] vars = lvt.getLocalVariables();
                int startSlot = methodInfo.isStatic() ? 0 : 1;
                String[] names = new String[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    int slot = startSlot;
                    // Calculate slot for parameter i
                    for (int j = 0; j < i; j++) {
                        slot += slotSize(paramDescs[j]);
                    }
                    // Find the local variable at this slot
                    for (int v = 0; v < vars.length; v++) {
                        if (vars[v].index == slot) {
                            names[i] = vars[v].name;
                            break;
                        }
                    }
                    if (names[i] == null) {
                        names[i] = "arg" + i;
                    }
                }
                return names;
            }
        }

        return null;
    }

    /**
     * Get the declared exception type names from the Exceptions attribute.
     */
    public String[] getExceptionTypeNames() {
        ExceptionsAttribute ea = methodInfo.findAttribute("Exceptions");
        if (ea == null) {
            return new String[0];
        }
        String[] internal = ea.getExceptions();
        String[] result = new String[internal.length];
        for (int i = 0; i < internal.length; i++) {
            result[i] = TypeNameUtil.internalToQualified(internal[i]);
        }
        return result;
    }

    /**
     * Get the generic signature, or null if none.
     */
    public String getGenericSignature() {
        SignatureAttribute sa = methodInfo.findAttribute("Signature");
        if (sa != null) {
            return sa.getSignature();
        }
        return null;
    }

    /**
     * Get the raw access flags.
     */
    public int getModifiers() {
        return methodInfo.getAccessFlags();
    }

    public boolean isPublic() {
        return methodInfo.isPublic();
    }

    public boolean isPrivate() {
        return methodInfo.isPrivate();
    }

    public boolean isProtected() {
        return methodInfo.isProtected();
    }

    public boolean isStatic() {
        return methodInfo.isStatic();
    }

    public boolean isFinal() {
        return methodInfo.isFinal();
    }

    public boolean isAbstract() {
        return methodInfo.isAbstract();
    }

    public boolean isNative() {
        return methodInfo.isNative();
    }

    public boolean isSynchronized() {
        return methodInfo.isSynchronized();
    }

    public boolean isBridge() {
        return methodInfo.isBridge();
    }

    public boolean isVarargs() {
        return methodInfo.isVarargs();
    }

    public boolean isSynthetic() {
        return methodInfo.isSynthetic();
    }

    /**
     * Get the length of the bytecode in the Code attribute.
     * Returns 0 if there is no Code attribute (abstract/native methods).
     */
    public int getBytecodeLength() {
        CodeAttribute code = methodInfo.findAttribute("Code");
        if (code == null) {
            return 0;
        }
        return code.getCode().length;
    }

    /**
     * Get the raw bytecode from the Code attribute.
     * Returns null if there is no Code attribute.
     */
    public byte[] getBytecode() {
        CodeAttribute code = methodInfo.findAttribute("Code");
        if (code == null) {
            return null;
        }
        return code.getCode();
    }

    /**
     * Get the max stack depth from the Code attribute.
     * Returns 0 if there is no Code attribute.
     */
    public int getMaxStack() {
        CodeAttribute code = methodInfo.findAttribute("Code");
        if (code == null) {
            return 0;
        }
        return code.getMaxStack();
    }

    /**
     * Get the max locals from the Code attribute.
     * Returns 0 if there is no Code attribute.
     */
    public int getMaxLocals() {
        CodeAttribute code = methodInfo.findAttribute("Code");
        if (code == null) {
            return 0;
        }
        return code.getMaxLocals();
    }

    /**
     * Get runtime annotations on this method.
     */
    public AnnotationInfo[] getAnnotations() {
        List<AnnotationInfo> result = new ArrayList<AnnotationInfo>();
        RuntimeAnnotationsAttribute visible = methodInfo.findAttribute("RuntimeVisibleAnnotations");
        if (visible != null) {
            AnnotationInfo[] anns = visible.getAnnotations();
            for (int i = 0; i < anns.length; i++) {
                result.add(anns[i]);
            }
        }
        RuntimeAnnotationsAttribute invisible = methodInfo.findAttribute("RuntimeInvisibleAnnotations");
        if (invisible != null) {
            AnnotationInfo[] anns = invisible.getAnnotations();
            for (int i = 0; i < anns.length; i++) {
                result.add(anns[i]);
            }
        }
        return result.toArray(new AnnotationInfo[result.size()]);
    }

    /**
     * Get parameter annotations. The outer array is indexed by parameter position;
     * each inner array contains the annotations for that parameter.
     */
    public AnnotationInfo[][] getParameterAnnotations() {
        String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(methodInfo.getDescriptor());
        int paramCount = paramDescs.length;
        if (paramCount == 0) {
            return new AnnotationInfo[0][];
        }

        AnnotationInfo[][] result = new AnnotationInfo[paramCount][];
        for (int i = 0; i < paramCount; i++) {
            result[i] = new AnnotationInfo[0];
        }

        RuntimeParameterAnnotationsAttribute visible =
                methodInfo.findAttribute("RuntimeVisibleParameterAnnotations");
        if (visible != null) {
            AnnotationInfo[][] pa = visible.getParameterAnnotations();
            for (int i = 0; i < pa.length && i < paramCount; i++) {
                result[i] = pa[i];
            }
        }

        RuntimeParameterAnnotationsAttribute invisible =
                methodInfo.findAttribute("RuntimeInvisibleParameterAnnotations");
        if (invisible != null) {
            AnnotationInfo[][] pa = invisible.getParameterAnnotations();
            for (int i = 0; i < pa.length && i < paramCount; i++) {
                if (result[i].length == 0) {
                    result[i] = pa[i];
                } else {
                    // Merge visible and invisible annotations
                    AnnotationInfo[] merged = new AnnotationInfo[result[i].length + pa[i].length];
                    System.arraycopy(result[i], 0, merged, 0, result[i].length);
                    System.arraycopy(pa[i], 0, merged, result[i].length, pa[i].length);
                    result[i] = merged;
                }
            }
        }

        return result;
    }

    /**
     * Get the declaring class.
     */
    public CtClass getDeclaringClass() {
        return declaringClass;
    }

    /**
     * Get the return type as a CtClass, loaded from the pool.
     *
     * @throws NotFoundException if the return type class cannot be found
     */
    public CtClass getReturnType() throws NotFoundException {
        String retDesc = TypeNameUtil.parseMethodReturnDescriptor(methodInfo.getDescriptor());
        String internalName = descriptorToInternalName(retDesc);
        if (internalName == null) {
            throw new NotFoundException("Cannot resolve return type for descriptor: " + retDesc);
        }
        return declaringClass.getPool().get(internalName);
    }

    /**
     * Get the parameter types as CtClass instances, loaded from the pool.
     *
     * @throws NotFoundException if any parameter type class cannot be found
     */
    public CtClass[] getParameterTypes() throws NotFoundException {
        String[] paramDescs = TypeNameUtil.parseMethodParameterDescriptors(methodInfo.getDescriptor());
        List<CtClass> types = new ArrayList<CtClass>();
        for (int i = 0; i < paramDescs.length; i++) {
            String internalName = descriptorToInternalName(paramDescs[i]);
            if (internalName == null) {
                throw new NotFoundException("Cannot resolve parameter type for descriptor: " + paramDescs[i]);
            }
            types.add(declaringClass.getPool().get(internalName));
        }
        return types.toArray(new CtClass[types.size()]);
    }

    /**
     * Decompile just this method to a source string.
     * Note: this decompiles the entire class and is provided as a convenience.
     */
    public String decompile() throws Exception {
        return declaringClass.decompile();
    }

    /**
     * Get the underlying MethodInfo.
     */
    MethodInfo getMethodInfo() {
        return methodInfo;
    }

    private static String descriptorToInternalName(String descriptor) {
        int idx = 0;
        while (idx < descriptor.length() && descriptor.charAt(idx) == '[') {
            idx++;
        }
        if (idx >= descriptor.length()) {
            return null;
        }
        char c = descriptor.charAt(idx);
        if (c == 'L') {
            int semi = descriptor.indexOf(';', idx);
            if (semi < 0) return null;
            return descriptor.substring(idx + 1, semi);
        }
        return null;
    }

    private static int slotSize(String descriptor) {
        if ("J".equals(descriptor) || "D".equals(descriptor)) {
            return 2;
        }
        return 1;
    }

    public String toString() {
        return getName() + getDescriptor();
    }
}
