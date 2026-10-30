/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

// START_CHANGE: LIM-0004-20260326-1 - Type annotation model (JVM spec 4.7.20)
/**
 * Represents a type annotation from RuntimeVisibleTypeAnnotations / RuntimeInvisibleTypeAnnotations.
 * Type annotations (Java 8+, JSR 308) annotate types in declarations and expressions.
 */
public class TypeAnnotationInfo {
    /** Target type byte from JVM spec Table 4.7.20-A/B/C */
    private final int targetType;
    /** The annotation itself (type descriptor + element value pairs) */
    private final AnnotationInfo annotation;

    public TypeAnnotationInfo(int targetType, AnnotationInfo annotation) {
        this.targetType = targetType;
        this.annotation = annotation;
    }

    public int getTargetType() { return targetType; }
    public AnnotationInfo getAnnotation() { return annotation; }

    // Target type constants (JVM spec Table 4.7.20-A/B/C)
    public static final int CLASS_TYPE_PARAMETER = 0x00;
    public static final int METHOD_TYPE_PARAMETER = 0x01;
    public static final int CLASS_EXTENDS = 0x10;
    public static final int CLASS_TYPE_PARAMETER_BOUND = 0x11;
    public static final int METHOD_TYPE_PARAMETER_BOUND = 0x12;
    public static final int FIELD = 0x13;
    public static final int METHOD_RETURN = 0x14;
    public static final int METHOD_RECEIVER = 0x15;
    public static final int METHOD_FORMAL_PARAMETER = 0x16;
    public static final int THROWS = 0x17;
}
// END_CHANGE: LIM-0004-1
