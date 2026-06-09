import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** IntStream, flatMap, primitive stream conversions, Stream factory methods. */
public class C_StreamsAdvanced {

    // IntStream.range + sum
    public int intStreamSum() {
        return IntStream.range(0, 10).sum();
    }

    // IntStream.rangeClosed + map + boxed + collect
    public List<Integer> intStreamSquares() {
        return IntStream.rangeClosed(1, 5)
                .map(n -> n * n)
                .boxed()
                .collect(Collectors.toList());
    }

    // IntStream from array + average
    public double intStreamAverage() {
        int[] data = {2, 4, 6, 8};
        return IntStream.of(data).average().orElse(0.0);
    }

    // mapToInt then statistics
    public int mapToIntSum() {
        List<String> words = Arrays.asList("a", "bb", "ccc");
        return words.stream().mapToInt(String::length).sum();
    }

    // flatMap: list of lists -> flat stream
    public List<Integer> flatMapLists() {
        List<List<Integer>> nested = Arrays.asList(
                Arrays.asList(1, 2),
                Arrays.asList(3, 4),
                Arrays.asList(5));
        return nested.stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    // flatMap over split words
    public List<String> flatMapSplit() {
        List<String> lines = Arrays.asList("hello world", "foo bar");
        return lines.stream()
                .flatMap(line -> Arrays.stream(line.split(" ")))
                .collect(Collectors.toList());
    }

    // Stream.of + filter
    public long streamOf() {
        return Stream.of("x", "y", "z").filter(s -> !s.equals("y")).count();
    }

    // Stream.iterate (Java 8 form) with limit
    public List<Integer> streamIterate() {
        return Stream.iterate(1, n -> n * 2)
                .limit(5)
                .collect(Collectors.toList());
    }

    // Stream.generate with limit
    public long streamGenerate() {
        return Stream.generate(() -> "tick").limit(3).count();
    }

    // mapToObj from IntStream
    public List<String> mapToObj() {
        return IntStream.range(0, 3)
                .mapToObj(i -> "item" + i)
                .collect(Collectors.toList());
    }
}
