/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile;

import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;

import java.util.List;

/**
 * Represents a parsed Java class file, supporting class file format
 * versions from 45.0 (Java 1.0) through 69.0 (Java 25).
 */
public class ClassFile {
    private final int minorVersion;
    private final int majorVersion;
    private final ConstantPool constantPool;
    private final int accessFlags;
    private final String thisClassName;
    private final String superClassName;
    private final String[] interfaces;
    private final FieldInfo[] fields;
    private final MethodInfo[] methods;
    private final List<Attribute> attributes;

    public ClassFile(int minorVersion, int majorVersion, ConstantPool constantPool,
                     int accessFlags, String thisClassName, String superClassName,
                     String[] interfaces, FieldInfo[] fields, MethodInfo[] methods,
                     List<Attribute> attributes) {
        this.minorVersion = minorVersion;
        this.majorVersion = majorVersion;
        this.constantPool = constantPool;
        this.accessFlags = accessFlags;
        this.thisClassName = thisClassName;
        this.superClassName = superClassName;
        this.interfaces = interfaces;
        this.fields = fields;
        this.methods = methods;
        this.attributes = attributes;
    }

    public int getMinorVersion() { return minorVersion; }
    public int getMajorVersion() { return majorVersion; }
    public ConstantPool getConstantPool() { return constantPool; }
    public int getAccessFlags() { return accessFlags; }
    public String getThisClassName() { return thisClassName; }
    public String getSuperClassName() { return superClassName; }
    public String[] getInterfaces() { return interfaces; }
    public FieldInfo[] getFields() { return fields; }
    public MethodInfo[] getMethods() { return methods; }
    public List<Attribute> getAttributes() { return attributes; }

    public boolean isInterface() { return (accessFlags & 0x0200) != 0; }
    public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
    public boolean isEnum() { return (accessFlags & 0x4000) != 0; }
    public boolean isAnnotation() { return (accessFlags & 0x2000) != 0; }
    public boolean isModule() { return (accessFlags & 0x8000) != 0; }
    public boolean isSynthetic() { return (accessFlags & 0x1000) != 0; }
    public boolean isFinal() { return (accessFlags & 0x0010) != 0; }
    public boolean isPublic() { return (accessFlags & 0x0001) != 0; }

    public boolean isRecord() {
        return "java/lang/Record".equals(superClassName);
    }

    public boolean isSealed() {
        for (Attribute attr : attributes) {
            if ("PermittedSubclasses".equals(attr.getName())) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public <T extends Attribute> T findAttribute(String name) {
        for (Attribute attr : attributes) {
            if (name.equals(attr.getName())) return (T) attr;
        }
        return null;
    }
}
