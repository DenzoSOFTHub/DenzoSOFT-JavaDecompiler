import java.util.ArrayList;
import java.util.List;

/** Generic record and a record implementing an interface. */
public class C_GenericRecord {

    /** Generic record with a bounded type parameter. */
    public record Pair<A, B>(A first, B second) {
        public <C> Pair<A, C> withSecond(C value) {
            return new Pair<A, C>(first, value);
        }
    }

    /** Generic record with two unbounded params used in a method. */
    public record Box<T>(T value) {
        public boolean isPresent() {
            return value != null;
        }
    }

    public interface Shape {
        double area();

        default String describe() {
            return "area=" + area();
        }
    }

    /** Record implementing an interface. */
    public record Circle(double radius) implements Shape {
        public double area() {
            return Math.PI * radius * radius;
        }
    }

    /** Generic record implementing a generic interface. */
    public interface Named<T> {
        T value();

        String label();
    }

    public record Tagged<T>(T value, String label) implements Named<T> {
    }

    public List<String> demo() {
        List<String> out = new ArrayList<String>();
        Pair<String, Integer> p = new Pair<String, Integer>("a", 1);
        out.add(p.withSecond(true).toString());
        out.add(new Box<String>("x").isPresent() ? "yes" : "no");
        out.add(new Circle(2.0).describe());
        out.add(new Tagged<Integer>(42, "answer").label());
        return out;
    }
}
