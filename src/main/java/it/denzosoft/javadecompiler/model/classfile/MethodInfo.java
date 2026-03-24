/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile;

import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;

import java.util.List;

/**
 * Represents a method in a class file.
 */
public class MethodInfo {
    private final int accessFlags;
    private final String name;
    private final String descriptor;
    private final List<Attribute> attributes;

    public MethodInfo(int accessFlags, String name, String descriptor, List<Attribute> attributes) {
        this.accessFlags = accessFlags;
        this.name = name;
        this.descriptor = descriptor;
        this.attributes = attributes;
    }

    public int getAccessFlags() { return accessFlags; }
    public String getName() { return name; }
    public String getDescriptor() { return descriptor; }
    public List<Attribute> getAttributes() { return attributes; }

    public boolean isPublic() { return (accessFlags & 0x0001) != 0; }
    public boolean isPrivate() { return (accessFlags & 0x0002) != 0; }
    public boolean isProtected() { return (accessFlags & 0x0004) != 0; }
    public boolean isStatic() { return (accessFlags & 0x0008) != 0; }
    public boolean isFinal() { return (accessFlags & 0x0010) != 0; }
    public boolean isSynchronized() { return (accessFlags & 0x0020) != 0; }
    public boolean isBridge() { return (accessFlags & 0x0040) != 0; }
    public boolean isVarargs() { return (accessFlags & 0x0080) != 0; }
    public boolean isNative() { return (accessFlags & 0x0100) != 0; }
    public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
    public boolean isStrict() { return (accessFlags & 0x0800) != 0; }
    public boolean isSynthetic() { return (accessFlags & 0x1000) != 0; }

    public boolean isConstructor() { return "<init>".equals(name); }
    public boolean isClassInit() { return "<clinit>".equals(name); }

    @SuppressWarnings("unchecked")
    public <T extends Attribute> T findAttribute(String attrName) {
        for (Attribute attr : attributes) {
            if (attrName.equals(attr.getName())) return (T) attr;
        }
        return null;
    }
}
