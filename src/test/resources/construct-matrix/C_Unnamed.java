import java.util.ArrayList;
import java.util.List;

/** Unnamed variables and patterns (var _, case Type(_)) — finalized in Java 22. */
public class C_Unnamed {

    public record Point(int x, int y) {
    }

    public record Pair(Point a, Point b) {
    }

    /** Unnamed local variable in an enhanced for: count without using the element. */
    public int count(List<String> items) {
        int n = 0;
        for (var _ : items) {
            n++;
        }
        return n;
    }

    /** Unnamed variable for an ignored try-with-resources / catch parameter. */
    public boolean parses(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** Unnamed variable as an assignment target for a side-effecting call. */
    public int sideEffect(List<Integer> list) {
        int total = 0;
        var _ = list.add(1);
        total += list.size();
        return total;
    }

    /** Unnamed pattern component: bind only the part you need. */
    public int onlyX(Object o) {
        if (o instanceof Point(int x, int _)) {
            return x;
        }
        return -1;
    }

    /** Unnamed type pattern in a switch case. */
    public String kind(Object o) {
        return switch (o) {
            case Integer _ -> "int";
            case String _ -> "string";
            default -> "other";
        };
    }

    /** Nested unnamed pattern. */
    public int firstX(Object o) {
        if (o instanceof Pair(Point(int x, var _), Point _)) {
            return x;
        }
        return -1;
    }

    public List<String> demo() {
        List<String> out = new ArrayList<String>();
        out.add(String.valueOf(count(List.of("a", "b"))));
        out.add(String.valueOf(parses("12")));
        out.add(kind("x"));
        return out;
    }
}
