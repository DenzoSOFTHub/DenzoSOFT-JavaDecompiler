/** Pattern switch (Java 21): type patterns, when guards, case null, sealed-exhaustive. */
public class C_PatternSwitch {

    public sealed interface Shape permits Circle, Rect, Tri {
    }

    public record Circle(double r) implements Shape {
    }

    public record Rect(double w, double h) implements Shape {
    }

    public record Tri(double base, double height) implements Shape {
    }

    /** Exhaustive switch over a sealed type with record patterns, no default needed. */
    public double area(Shape s) {
        return switch (s) {
            case Circle(double r) -> Math.PI * r * r;
            case Rect(double w, double h) -> w * h;
            case Tri(double b, double h) -> 0.5 * b * h;
        };
    }

    /** Type patterns with a when guard and case null. */
    public String classify(Object o) {
        return switch (o) {
            case null -> "null";
            case Integer i when i < 0 -> "negative";
            case Integer i -> "int:" + i;
            case String str when str.isEmpty() -> "empty-string";
            case String str -> "string:" + str.length();
            default -> "other";
        };
    }

    /** Combined null + default in one label. */
    public String label(Object o) {
        return switch (o) {
            case Integer i -> "I" + i;
            case null, default -> "fallback";
        };
    }

    /** Guarded patterns ordered from specific to general. */
    public String size(Shape s) {
        return switch (s) {
            case Circle c when c.r() > 10 -> "big-circle";
            case Circle c -> "circle";
            case Rect r when r.w() == r.h() -> "square";
            case Rect r -> "rect";
            case Tri t -> "tri";
        };
    }
}
