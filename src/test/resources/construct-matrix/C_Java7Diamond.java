import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java 7 diamond operator, including nested generics. */
public class C_Java7Diamond {

    public static List<String> names() {
        List<String> list = new ArrayList<>();
        list.add("alpha");
        list.add("beta");
        return list;
    }

    public static Map<String, List<Integer>> index() {
        Map<String, List<Integer>> map = new HashMap<>();
        map.put("ones", new ArrayList<>());
        return map;
    }

    public static List<Map<String, Integer>> nested() {
        List<Map<String, Integer>> list = new ArrayList<>();
        list.add(new HashMap<>());
        return list;
    }
}
