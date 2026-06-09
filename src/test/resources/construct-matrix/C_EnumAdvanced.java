/** Enum with abstract method (per-constant body) and implementing an interface. */
public class C_EnumAdvanced {

    public interface Described {
        String describe();
    }

    // Enum with constant-specific method bodies implementing an abstract method,
    // and also implementing an interface.
    public enum Operation implements Described {
        PLUS {
            public int apply(int a, int b) {
                return a + b;
            }
        },
        MINUS {
            public int apply(int a, int b) {
                return a - b;
            }
        },
        TIMES {
            public int apply(int a, int b) {
                return a * b;
            }
        };

        public abstract int apply(int a, int b);

        public String describe() {
            return "Operation:" + name();
        }
    }

    public static int compute(Operation op, int a, int b) {
        return op.apply(a, b);
    }
}
