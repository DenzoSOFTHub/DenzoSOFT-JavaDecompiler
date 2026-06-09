import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares @interface annotations: marker, single-value, multi-value, with defaults. */
public class C_AnnotationDecl {

    // Marker annotation.
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Marker {
    }

    // Single-value annotation (the special 'value' element).
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER })
    public @interface Name {
        String value();
    }

    // Multi-value annotation with defaults and an array element.
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER })
    public @interface Config {
        String author() default "unknown";

        int version() default 1;

        String[] tags() default {};

        ElementType targetKind() default ElementType.METHOD;
    }
}
