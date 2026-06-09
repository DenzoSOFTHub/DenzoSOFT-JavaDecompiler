import java.util.ArrayList;
import java.util.List;

/** Varargs and autoboxing/unboxing. */
public class C_VarargsBoxing {

    // Varargs of primitives.
    public static int sum(int... values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    // Varargs of objects.
    public static String join(String separator, Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    // Autoboxing: int -> Integer when added to a List<Integer>.
    public static List<Integer> boxed(int a, int b) {
        List<Integer> list = new ArrayList<Integer>();
        list.add(a);
        list.add(b);
        return list;
    }

    // Unboxing: Integer -> int in arithmetic.
    public static int unbox(Integer a, Integer b) {
        return a + b;
    }

    // Mixed boxing through a wrapper.
    public static long total(Long base, int delta) {
        Long result = base + delta;
        return result;
    }
}
