import java.util.List;
import java.util.Objects;

/** Records: simple, compact canonical ctor, custom canonical ctor, extra methods, static factory, nested. */
public class C_Records {

    /** Simple record. */
    public record Point(int x, int y) {
    }

    /** Compact canonical constructor with validation/normalization. */
    public record Range(int lo, int hi) {
        public Range {
            if (lo > hi) {
                int t = lo;
                lo = hi;
                hi = t;
            }
        }
    }

    /** Custom (non-compact) canonical constructor. */
    public record Temperature(double celsius) {
        public Temperature(double celsius) {
            this.celsius = Math.max(-273.15, celsius);
        }

        public double fahrenheit() {
            return celsius * 9.0 / 5.0 + 32.0;
        }
    }

    /** Record with extra instance methods and a static factory. */
    public record Money(long cents, String currency) {
        public Money plus(Money other) {
            return new Money(cents + other.cents, currency);
        }

        public String display() {
            return String.format("%d.%02d %s", cents / 100, Math.abs(cents % 100), currency);
        }

        public static Money ofDollars(long dollars) {
            return new Money(dollars * 100, "USD");
        }
    }

    /** Record with extra static field and overridden accessor. */
    public record Named(String name) {
        public static final Named ANONYMOUS = new Named("anon");

        public String name() {
            return name == null ? "anon" : name;
        }
    }

    /** Nested record inside a record. */
    public record Line(Point start, Point end) {
        public record Slope(double value) {
        }

        public Slope slope() {
            int dx = end.x() - start.x();
            int dy = end.y() - start.y();
            return new Slope(dx == 0 ? Double.NaN : (double) dy / dx);
        }
    }

    public List<String> demo() {
        Range r = new Range(5, 1);
        Money m = Money.ofDollars(3).plus(new Money(50, "USD"));
        return List.of(
                new Point(1, 2).toString(),
                r.toString(),
                new Temperature(-500).toString(),
                m.display(),
                Named.ANONYMOUS.name(),
                new Line(new Point(0, 0), new Point(2, 4)).slope().toString(),
                Objects.toString(new Temperature(0).fahrenheit()));
    }
}
