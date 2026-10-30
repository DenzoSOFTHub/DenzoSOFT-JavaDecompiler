public class JdFreeze {
    sealed interface Shape permits Circle, Rect, Tri {}
    record Point(int x, int y) {}
    record Circle(Point c, int r) implements Shape {}
    record Rect(Point a, Point b) implements Shape {}
    record Tri(Point a, Point b, Point c) implements Shape {}

    // 1. return switch + flat deconstruction + default
    static int flatWithDefault(Object o) {
        return switch (o) {
            case Circle(Point p, int r) -> p.x() + r;
            default -> -1;
        };
    }
    // 2. return switch + sealed exhaustive, NO default (MatchException scaffolding)
    static int exhaustiveNoDefault(Shape s) {
        return switch (s) {
            case Circle(Point p, int r) -> p.x() + r;
            case Rect(Point a, Point b) -> a.x() + b.y();
            case Tri(Point a, Point b, Point c) -> a.x() + b.x() + c.x();
        };
    }
    // 3. nested deconstruction
    static int nested(Object o) {
        return switch (o) {
            case Rect(Point(int ax, int ay), Point b) -> ax + ay + b.x();
            default -> 0;
        };
    }
    // 4. unnamed component
    static int unnamed(Object o) {
        return switch (o) {
            case Circle(Point _, int r) -> r;
            default -> 0;
        };
    }
    public static void main(String[] a) {
        System.out.println(flatWithDefault(new Circle(new Point(3,4),5)));
        System.out.println(exhaustiveNoDefault(new Rect(new Point(1,2), new Point(3,4))));
        System.out.println(nested(new Rect(new Point(1,2), new Point(3,4))));
        System.out.println(unnamed(new Circle(new Point(9,9),7)));
    }
}
