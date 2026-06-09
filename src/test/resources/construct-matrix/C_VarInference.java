import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;

/** Java 10 'var' local-variable type inference in various positions. */
public class C_VarInference {

    // var with simple literals
    public int varLiterals() {
        var count = 10;
        var name = "text";
        var ratio = 3.14;
        return count + name.length() + (int) ratio;
    }

    // var with constructor on the right
    public int varConstructor() {
        var list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        return list.size();
    }

    // var with generic map
    public int varGenericMap() {
        var map = new HashMap<String, Integer>();
        map.put("one", 1);
        map.put("two", 2);
        return map.size();
    }

    // var in traditional for loop
    public int varForLoop() {
        var sum = 0;
        for (var i = 0; i < 5; i++) {
            sum += i;
        }
        return sum;
    }

    // var in enhanced for loop
    public int varForEach() {
        var words = Arrays.asList("aa", "bbb", "c");
        var total = 0;
        for (var w : words) {
            total += w.length();
        }
        return total;
    }

    // var holding a method result
    public int varMethodResult() {
        var values = makeList();
        return values.size();
    }

    private List<Integer> makeList() {
        var result = new ArrayList<Integer>();
        result.add(1);
        result.add(2);
        return result;
    }

    // var with cast
    public String varWithCast(Object obj) {
        var str = (String) obj;
        return str.trim();
    }

    // var referencing a nested generic type
    public int varNestedGeneric() {
        var entries = new ArrayList<Map.Entry<String, Integer>>();
        return entries.size();
    }
}
