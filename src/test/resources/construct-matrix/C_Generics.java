import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Generic classes, generic methods, bounded type params, multiple bounds. */
public class C_Generics<T> {

    private T value;

    public C_Generics(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    // Generic method with its own type parameter.
    public static <E> List<E> singletonList(E element) {
        List<E> list = new ArrayList<E>();
        list.add(element);
        return list;
    }

    // Bounded type parameter.
    public static <N extends Number> double sum(List<N> numbers) {
        double total = 0.0;
        for (N n : numbers) {
            total += n.doubleValue();
        }
        return total;
    }

    // Multiple bounds (class + interface).
    public static <U extends Number & Comparable<U>> U max(U a, U b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    // Nested generics.
    public static Map<String, List<Integer>> emptyIndex() {
        return new java.util.HashMap<String, List<Integer>>();
    }
}
