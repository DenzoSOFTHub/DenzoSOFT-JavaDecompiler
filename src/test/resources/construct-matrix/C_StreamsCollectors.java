import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Collectors: toList, groupingBy, joining, counting, partitioningBy, toMap. */
public class C_StreamsCollectors {

    private final List<String> items = Arrays.asList("ant", "bee", "bear", "cat", "cow", "dog");

    // Collectors.toList
    public List<String> toList() {
        return items.stream()
                .filter(s -> s.length() == 3)
                .collect(Collectors.toList());
    }

    // Collectors.groupingBy
    public Map<Integer, List<String>> groupByLength() {
        return items.stream()
                .collect(Collectors.groupingBy(String::length));
    }

    // groupingBy with downstream counting
    public Map<Character, Long> groupByFirstCharCount() {
        return items.stream()
                .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.counting()));
    }

    // Collectors.joining (no args, with delimiter, with prefix/suffix)
    public String joinSimple() {
        return items.stream().collect(Collectors.joining());
    }

    public String joinDelimited() {
        return items.stream().collect(Collectors.joining(", "));
    }

    public String joinWrapped() {
        return items.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    // Collectors.partitioningBy
    public Map<Boolean, List<String>> partitionByLength() {
        return items.stream()
                .collect(Collectors.partitioningBy(s -> s.length() > 3));
    }

    // Collectors.toMap
    public Map<String, Integer> toMapLengths() {
        return items.stream()
                .distinct()
                .collect(Collectors.toMap(s -> s, String::length));
    }

    // Collectors.mapping as downstream
    public Map<Integer, List<Character>> firstCharsByLength() {
        return items.stream()
                .collect(Collectors.groupingBy(
                        String::length,
                        Collectors.mapping(s -> s.charAt(0), Collectors.toList())));
    }
}
