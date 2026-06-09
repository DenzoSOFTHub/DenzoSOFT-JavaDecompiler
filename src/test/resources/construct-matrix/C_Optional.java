import java.util.Optional;
import java.util.List;
import java.util.Arrays;

/** Optional usage: creation, map, filter, orElse, ifPresent, flatMap. */
public class C_Optional {

    // Optional.of and get
    public String optionalOf() {
        Optional<String> opt = Optional.of("value");
        return opt.get();
    }

    // Optional.ofNullable + orElse
    public String orElse(String maybe) {
        return Optional.ofNullable(maybe).orElse("default");
    }

    // Optional.empty + isPresent
    public boolean emptyCheck() {
        Optional<String> opt = Optional.empty();
        return opt.isPresent();
    }

    // map
    public Optional<Integer> mapLength(String s) {
        return Optional.ofNullable(s).map(String::length);
    }

    // filter + map chained
    public Optional<String> filterMap(String s) {
        return Optional.ofNullable(s)
                .filter(v -> v.length() > 2)
                .map(String::toUpperCase);
    }

    // ifPresent
    public void ifPresentPrint(String s) {
        Optional.ofNullable(s).ifPresent(System.out::println);
    }

    // orElseGet with supplier
    public String orElseGet(String maybe) {
        return Optional.ofNullable(maybe).orElseGet(() -> "computed");
    }

    // orElseThrow
    public String orElseThrow(String maybe) {
        return Optional.ofNullable(maybe)
                .orElseThrow(() -> new IllegalStateException("missing"));
    }

    // flatMap
    public Optional<String> flatMapChain(String s) {
        return Optional.ofNullable(s)
                .flatMap(v -> v.isEmpty() ? Optional.empty() : Optional.of(v.trim()));
    }

    // Optional from stream findFirst
    public Optional<String> findFirst() {
        List<String> list = Arrays.asList("a", "bb", "ccc");
        return list.stream().filter(v -> v.length() == 2).findFirst();
    }
}
