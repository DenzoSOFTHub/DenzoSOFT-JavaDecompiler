/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.model.classfile.MethodInfo;

/**
 * Represents a constructor in a class. Similar to javassist.CtConstructor.
 */
public class CtConstructor extends CtMethod {

    CtConstructor(MethodInfo methodInfo, CtClass declaringClass) {
        super(methodInfo, declaringClass);
    }

    /**
     * Returns true if this is a static initializer ({@code <clinit>}).
     */
    public boolean isClassInitializer() {
        return "<clinit>".equals(getRawName());
    }

    /**
     * Get the raw method name ({@code <init>} or {@code <clinit>}).
     */
    private String getRawName() {
        return super.getName();
    }

    /**
     * Returns the simple name of the declaring class (for display).
     */
    public String getName() {
        if (isClassInitializer()) return "<clinit>";
        return getDeclaringClass().getSimpleName();
    }

    public String toString() {
        return "CtConstructor[" + getName() + getDescriptor() + "]";
    }
}
