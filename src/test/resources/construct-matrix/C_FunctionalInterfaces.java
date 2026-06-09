import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** Custom @FunctionalInterface plus standard java.util.function interfaces. */
public class C_FunctionalInterfaces {

    // Custom functional interface
    @FunctionalInterface
    public interface Transformer {
        String transform(String input);
    }

    // Custom functional interface with generics
    @FunctionalInterface
    public interface Combiner<T> {
        T combine(T first, T second);
    }

    public String useCustom(Transformer t, String value) {
        return t.transform(value);
    }

    public String upperCase() {
        Transformer t = s -> s.toUpperCase();
        return useCustom(t, "abc");
    }

    public <T> T useCombiner(Combiner<T> c, T a, T b) {
        return c.combine(a, b);
    }

    public String concat() {
        return useCombiner((a, b) -> a + b, "x", "y");
    }

    // Predicate
    public boolean usePredicate() {
        Predicate<Integer> isEven = n -> n % 2 == 0;
        return isEven.test(4);
    }

    // Function
    public int useFunction() {
        Function<Integer, Integer> square = n -> n * n;
        return square.apply(5);
    }

    // Supplier
    public String useSupplier() {
        Supplier<String> s = () -> "supplied";
        return s.get();
    }

    // Consumer
    public void useConsumer() {
        Consumer<String> printer = System.out::println;
        printer.accept("consumed");
    }

    // BiFunction
    public int useBiFunction() {
        BiFunction<Integer, Integer, Integer> mult = (a, b) -> a * b;
        return mult.apply(3, 4);
    }

    // Composing predicates and functions
    public boolean composedPredicate() {
        Predicate<Integer> positive = n -> n > 0;
        Predicate<Integer> small = n -> n < 10;
        return positive.and(small).test(5);
    }

    public int composedFunction() {
        Function<Integer, Integer> plusOne = n -> n + 1;
        Function<Integer, Integer> times2 = n -> n * 2;
        return plusOne.andThen(times2).apply(3);
    }
}
