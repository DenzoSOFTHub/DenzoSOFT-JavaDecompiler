import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Java 11 'var' in lambda parameters (allows annotations / consistency). */
public class C_VarLambdaParams {

    // var in single-param lambda
    public int singleVarParam() {
        java.util.function.Function<Integer, Integer> f = (var n) -> n + 1;
        return f.apply(10);
    }

    // var in multi-param lambda
    public int multiVarParam() {
        BiFunction<Integer, Integer, Integer> f = (var a, var b) -> a + b;
        return f.apply(3, 4);
    }

    // var in lambda used by a BinaryOperator
    public int binaryOpVar() {
        BinaryOperator<Integer> max = (var a, var b) -> a > b ? a : b;
        return max.apply(7, 12);
    }

    // var lambda params inside a stream pipeline
    public List<String> streamVarParam() {
        List<String> words = Arrays.asList("alpha", "beta", "gamma");
        return words.stream()
                .map((var w) -> w.toUpperCase())
                .collect(Collectors.toList());
    }

    // var params in a reduce
    public int reduceVar() {
        List<Integer> nums = Arrays.asList(1, 2, 3, 4);
        return nums.stream().reduce(0, (var a, var b) -> a + b);
    }
}
