import java.util.ArrayList;
import java.util.List;

/** Wildcards: ? extends, ? super, unbounded ?. */
public class C_Wildcards {

    // Upper-bounded wildcard (producer).
    public static double sumAll(List<? extends Number> numbers) {
        double total = 0.0;
        for (Number n : numbers) {
            total += n.doubleValue();
        }
        return total;
    }

    // Lower-bounded wildcard (consumer).
    public static void addIntegers(List<? super Integer> sink) {
        sink.add(Integer.valueOf(1));
        sink.add(Integer.valueOf(2));
    }

    // Unbounded wildcard.
    public static int sizeOf(List<?> list) {
        return list.size();
    }

    public static List<Integer> demo() {
        List<Integer> out = new ArrayList<Integer>();
        addIntegers(out);
        return out;
    }
}
