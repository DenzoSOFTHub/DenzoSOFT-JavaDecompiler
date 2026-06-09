import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.List;
import java.util.ArrayList;

/** Method references: static, bound instance, unbound instance, constructor, array constructor. */
public class C_MethodRefs {

    // Static method reference: Type::staticMethod
    public Function<String, Integer> staticRef() {
        return Integer::parseInt;
    }

    // Bound instance method reference: obj::instanceMethod
    public Supplier<Integer> boundInstanceRef() {
        String text = "hello world";
        return text::length;
    }

    // Bound instance method reference on 'this'
    private String prefix = "P-";

    public Function<String, String> boundThisRef() {
        return this::decorate;
    }

    private String decorate(String s) {
        return prefix + s;
    }

    // Unbound instance method reference: Type::instanceMethod
    public Function<String, Integer> unboundInstanceRef() {
        return String::length;
    }

    // Unbound instance method reference with an argument: Type::instanceMethod (BiFunction)
    public BiFunction<String, String, Boolean> unboundInstanceRefArg() {
        return String::startsWith;
    }

    // Constructor reference: Type::new
    public Supplier<List<String>> constructorRef() {
        return ArrayList::new;
    }

    // Constructor reference taking an argument: Type::new
    public Function<String, StringBuilder> constructorRefArg() {
        return StringBuilder::new;
    }

    // Array constructor reference: Type[]::new
    public IntFunction<String[]> arrayConstructorRef() {
        return String[]::new;
    }

    public String[] buildArray() {
        return arrayConstructorRef().apply(3);
    }
}
