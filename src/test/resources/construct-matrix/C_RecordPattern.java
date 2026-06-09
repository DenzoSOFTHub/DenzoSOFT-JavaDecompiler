/** Record patterns (Java 21): instanceof deconstruction, nested, generic, in switch. */
public class C_RecordPattern {

    public record Point(int x, int y) {
    }

    public record Line(Point start, Point end) {
    }

    public record Box<T>(T value) {
    }

    /** Simple record deconstruction in instanceof. */
    public int sum(Object o) {
        if (o instanceof Point(int x, int y)) {
            return x + y;
        }
        return -1;
    }

    /** Nested record pattern. */
    public int span(Object o) {
        if (o instanceof Line(Point(int x1, int y1), Point(int x2, int y2))) {
            return Math.abs(x2 - x1) + Math.abs(y2 - y1);
        }
        return -1;
    }

    /** Record pattern with var components. */
    public String show(Object o) {
        if (o instanceof Point(var x, var y)) {
            return x + "," + y;
        }
        return "?";
    }

    /** Generic record pattern deconstruction. */
    public String unbox(Object o) {
        if (o instanceof Box<?>(Object v)) {
            return String.valueOf(v);
        }
        return "empty";
    }

    /** Record pattern inside a switch statement case. */
    public int describe(Object o) {
        switch (o) {
            case Point(int x, int y) -> {
                return x * y;
            }
            case Line(Point a, Point b) -> {
                return a.x() + b.x();
            }
            default -> {
                return 0;
            }
        }
    }

    /** Record pattern with a guard. */
    public boolean isDiagonal(Object o) {
        return o instanceof Point(int x, int y) && x == y;
    }
}
