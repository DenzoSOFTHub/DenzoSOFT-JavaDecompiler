import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.concurrent.Callable;

/** Lambda expressions: no-arg, single-arg, multi-arg, block vs expression body. */
public class C_Lambdas {

    private int fieldValue = 42;

    // No-arg lambda, expression body
    public Supplier<String> noArgExpression() {
        return () -> "hello";
    }

    // No-arg lambda, block body
    public Supplier<Integer> noArgBlock() {
        return () -> {
            int x = 10;
            int y = 20;
            return x + y;
        };
    }

    // Single-arg lambda, expression body
    public Function<Integer, Integer> singleArgExpression() {
        return n -> n * 2;
    }

    // Single-arg lambda, block body
    public Function<String, Integer> singleArgBlock() {
        return s -> {
            int len = s.length();
            return len + 1;
        };
    }

    // Multi-arg lambda, expression body
    public BiFunction<Integer, Integer, Integer> multiArgExpression() {
        return (a, b) -> a + b;
    }

    // Multi-arg lambda, block body
    public BiFunction<Integer, Integer, Integer> multiArgBlock() {
        return (a, b) -> {
            int sum = a + b;
            return sum * sum;
        };
    }

    // Capturing a local variable
    public Supplier<Integer> capturingLocal() {
        int local = 7;
        return () -> local + 1;
    }

    // Capturing 'this' (instance field via this)
    public Supplier<Integer> capturingThis() {
        return () -> this.fieldValue;
    }

    // Capturing a field directly
    public Supplier<Integer> capturingField() {
        return () -> fieldValue * 2;
    }

    // Capturing local + this together
    public Supplier<Integer> capturingLocalAndThis() {
        int offset = 5;
        return () -> fieldValue + offset;
    }

    // Lambda that returns a lambda (currying)
    public Function<Integer, Function<Integer, Integer>> returningLambda() {
        return a -> b -> a + b;
    }

    // Lambda passed as argument
    public void lambdaAsArg(Consumer<String> consumer) {
        consumer.accept("invoked");
    }

    public void useLambdaAsArg() {
        lambdaAsArg(s -> System.out.println(s));
    }

    // Lambda targeting a checked-exception functional interface
    public Callable<String> checkedLambda() {
        return () -> "from callable";
    }
}
