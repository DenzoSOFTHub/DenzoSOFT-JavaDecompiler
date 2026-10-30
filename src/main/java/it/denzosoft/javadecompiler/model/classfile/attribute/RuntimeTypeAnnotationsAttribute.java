/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
