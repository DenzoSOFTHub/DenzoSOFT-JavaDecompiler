import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Basic stream pipeline operations: filter, map, collect, reduce, forEach. */
public class C_StreamsBasic {

    private final List<String> words = Arrays.asList("apple", "banana", "cherry", "date");

    // filter + map + collect to list
    public List<String> filterMapCollect() {
        return words.stream()
                .filter(w -> w.length() > 4)
                .map(String::toUpperCase)
                .collect(Collectors.toList());
    }

    // map to int + reduce
    public int reduceSum() {
        return words.stream()
                .map(String::length)
                .reduce(0, Integer::sum);
    }

    // reduce with identity and accumulator producing a string
    public String reduceConcat() {
        return words.stream()
                .reduce("", (a, b) -> a + b);
    }

    // forEach with a consumer
    public void forEachPrint() {
        words.stream()
                .filter(w -> w.startsWith("b"))
                .forEach(System.out::println);
    }

    // count after filter
    public long countLong() {
        return words.stream()
                .filter(w -> w.contains("a"))
                .count();
    }

    // sorted + limit + collect
    public List<String> sortedLimit() {
        return words.stream()
                .sorted()
                .limit(2)
                .collect(Collectors.toList());
    }

    // distinct after map
    public List<Integer> distinctLengths() {
        return words.stream()
                .map(String::length)
                .distinct()
                .collect(Collectors.toList());
    }

    // anyMatch / allMatch terminal operations
    public boolean anyLong() {
        return words.stream().anyMatch(w -> w.length() > 5);
    }

    public boolean allNonEmpty() {
        return words.stream().allMatch(w -> !w.isEmpty());
    }
}
