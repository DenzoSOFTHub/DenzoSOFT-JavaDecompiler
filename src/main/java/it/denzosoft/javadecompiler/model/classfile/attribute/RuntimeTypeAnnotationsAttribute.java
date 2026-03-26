/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

// START_CHANGE: LIM-0004-20260326-2 - Type annotations attribute
/**
 * Represents RuntimeVisibleTypeAnnotations or RuntimeInvisibleTypeAnnotations attribute.
 */
public class RuntimeTypeAnnotationsAttribute extends Attribute {
    private final TypeAnnotationInfo[] typeAnnotations;
    private final boolean visible;

    public RuntimeTypeAnnotationsAttribute(String name, int length,
                                            TypeAnnotationInfo[] typeAnnotations, boolean visible) {
        super(name, length);
        this.typeAnnotations = typeAnnotations;
        this.visible = visible;
    }

    public TypeAnnotationInfo[] getTypeAnnotations() { return typeAnnotations; }
    public boolean isVisible() { return visible; }
}
// END_CHANGE: LIM-0004-2
