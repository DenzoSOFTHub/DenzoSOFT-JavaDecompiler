/** Recursive generic bound: T extends Comparable<T>, plus a generic interface. */
public class C_RecursiveGenerics {

    // Generic interface declaration.
    public interface Container<E> {
        E get();
        void set(E value);
    }

    // Implementation of a generic interface.
    public static class Box<E> implements Container<E> {
        private E value;

        public E get() {
            return value;
        }

        public void set(E value) {
            this.value = value;
        }
    }

    // Recursive generic bound (the classic Enum<E extends Enum<E>> shape).
    public static <T extends Comparable<T>> T maxOf(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static <T extends Comparable<? super T>> T minOf(T a, T b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
