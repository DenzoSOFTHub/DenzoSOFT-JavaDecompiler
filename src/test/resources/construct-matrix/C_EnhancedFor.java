import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enhanced for over arrays, Iterable, and Map.entrySet. */
public class C_EnhancedFor {

    public static int sumArray(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    public static String concatArray(String[] words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            sb.append(w);
        }
        return sb.toString();
    }

    public static int sumIterable(Iterable<Integer> items) {
        int total = 0;
        for (Integer i : items) {
            total += i;
        }
        return total;
    }

    public static String dumpEntries(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    public static Map<String, Integer> sample() {
        Map<String, Integer> map = new HashMap<String, Integer>();
        map.put("a", 1);
        List<Integer> ignored = new ArrayList<Integer>();
        ignored.add(0);
        return map;
    }
}
