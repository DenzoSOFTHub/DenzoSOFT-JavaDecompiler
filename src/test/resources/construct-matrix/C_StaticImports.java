import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.util.Arrays.asList;

import java.util.List;

/** Static imports of fields and methods. */
public class C_StaticImports {

    public static double circleArea(double radius) {
        return PI * radius * radius;
    }

    public static int largest(int a, int b) {
        return max(a, b);
    }

    public static int distance(int a, int b) {
        return abs(a - b);
    }

    public static List<String> words() {
        return asList("one", "two", "three");
    }
}
