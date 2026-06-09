/** Sealed interface + classes: permits, sealed/non-sealed/final hierarchy. */
public class C_Sealed {

    /** Sealed interface with an explicit permits clause. */
    public sealed interface Expr permits Lit, Add, Neg, Wild {
    }

    /** final permitted implementor. */
    public record Lit(int value) implements Expr {
    }

    /** final permitted implementor (record). */
    public record Add(Expr left, Expr right) implements Expr {
    }

    /** final class permitted implementor. */
    public static final class Neg implements Expr {
        private final Expr operand;

        public Neg(Expr operand) {
            this.operand = operand;
        }

        public Expr operand() {
            return operand;
        }
    }

    /** non-sealed permitted implementor: open for further extension. */
    public static non-sealed class Wild implements Expr {
        public String tag() {
            return "wild";
        }
    }

    /** A subclass of the non-sealed class (allowed because Wild is non-sealed). */
    public static class WildChild extends Wild {
        public String tag() {
            return "wild-child";
        }
    }

    /** Sealed abstract class hierarchy with permits. */
    public sealed static abstract class Shape permits Square, Triangle {
        public abstract double area();
    }

    public static final class Square extends Shape {
        private final double side;

        public Square(double side) {
            this.side = side;
        }

        public double area() {
            return side * side;
        }
    }

    public static final class Triangle extends Shape {
        private final double base;
        private final double height;

        public Triangle(double base, double height) {
            this.base = base;
            this.height = height;
        }

        public double area() {
            return 0.5 * base * height;
        }
    }

    public double evalArea(Shape s) {
        return s.area();
    }
}
