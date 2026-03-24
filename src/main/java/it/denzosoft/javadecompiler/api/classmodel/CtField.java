/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.FieldInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.AnnotationInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.ConstantValueAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.RuntimeAnnotationsAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.SignatureAttribute;
import it.denzosoft.javadecompiler.util.TypeNameUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a field in a class. Similar to javassist.CtField.
 */
public class CtField {

    private final FieldInfo fieldInfo;
    private final CtClass declaringClass;

    CtField(FieldInfo fieldInfo, CtClass declaringClass) {
        this.fieldInfo = fieldInfo;
        this.declaringClass = declaringClass;
    }

    /**
     * Get the name of this field.
     */
    public String getName() {
        return fieldInfo.getName();
    }

    /**
     * Get the field descriptor (e.g., "I", "Ljava/lang/String;").
     */
    public String getDescriptor() {
        return fieldInfo.getDescriptor();
    }

    /**
     * Get the human-readable type name (e.g., "int", "java.lang.String").
     */
    public String getTypeName() {
        return TypeNameUtil.descriptorToTypeName(fieldInfo.getDescriptor(), false);
    }

    /**
     * Get the generic signature, or null if none.
     */
    public String getGenericSignature() {
        SignatureAttribute sa = fieldInfo.findAttribute("Signature");
        if (sa != null) {
            return sa.getSignature();
        }
        return null;
    }

    /**
     * Get the raw access flags for this field.
     */
    public int getModifiers() {
        return fieldInfo.getAccessFlags();
    }

    public boolean isPublic() {
        return fieldInfo.isPublic();
    }

    public boolean isPrivate() {
        return fieldInfo.isPrivate();
    }

    public boolean isProtected() {
        return fieldInfo.isProtected();
    }

    public boolean isStatic() {
        return fieldInfo.isStatic();
    }

    public boolean isFinal() {
        return fieldInfo.isFinal();
    }

    public boolean isVolatile() {
        return fieldInfo.isVolatile();
    }

    public boolean isTransient() {
        return fieldInfo.isTransient();
    }

    public boolean isSynthetic() {
        return fieldInfo.isSynthetic();
    }

    public boolean isEnum() {
        return fieldInfo.isEnum();
    }

    /**
     * Get the constant value for static final fields with a ConstantValue attribute.
     * Returns null if there is no constant value.
     * The returned object may be Integer, Long, Float, Double, or String.
     */
    public Object getConstantValue() {
        ConstantValueAttribute cva = fieldInfo.findAttribute("ConstantValue");
        if (cva == null) {
            return null;
        }
        int idx = cva.getConstantValueIndex();
        ConstantPool cp = declaringClass.getClassFile().getConstantPool();
        int tag = cp.getTag(idx);
        switch (tag) {
            case ConstantPool.CONSTANT_Integer:
                return cp.getValue(idx);
            case ConstantPool.CONSTANT_Long:
                return cp.getValue(idx);
            case ConstantPool.CONSTANT_Float:
                return cp.getValue(idx);
            case ConstantPool.CONSTANT_Double:
                return cp.getValue(idx);
            case ConstantPool.CONSTANT_String:
                return cp.getStringConstant(idx);
            default:
                return null;
        }
    }

    /**
     * Get runtime annotations on this field.
     */
    public AnnotationInfo[] getAnnotations() {
        List<AnnotationInfo> result = new ArrayList<AnnotationInfo>();
        RuntimeAnnotationsAttribute visible = fieldInfo.findAttribute("RuntimeVisibleAnnotations");
        if (visible != null) {
            AnnotationInfo[] anns = visible.getAnnotations();
            for (int i = 0; i < anns.length; i++) {
                result.add(anns[i]);
            }
        }
        RuntimeAnnotationsAttribute invisible = fieldInfo.findAttribute("RuntimeInvisibleAnnotations");
        if (invisible != null) {
            AnnotationInfo[] anns = invisible.getAnnotations();
            for (int i = 0; i < anns.length; i++) {
                result.add(anns[i]);
            }
        }
        return result.toArray(new AnnotationInfo[result.size()]);
    }

    /**
     * Get the declaring class.
     */
    public CtClass getDeclaringClass() {
        return declaringClass;
    }

    /**
     * Get the type of this field as a CtClass, loaded from the pool.
     *
     * @throws NotFoundException if the type class cannot be found
     */
    public CtClass getType() throws NotFoundException {
        String desc = fieldInfo.getDescriptor();
        String internalName = descriptorToInternalName(desc);
        if (internalName == null) {
            throw new NotFoundException("Cannot resolve type for descriptor: " + desc);
        }
        return declaringClass.getPool().get(internalName);
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
        // primitive types don't have a class in the pool
        return null;
    }

    public String toString() {
        return getTypeName() + " " + getName();
    }
}
