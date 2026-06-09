/** Enum-rich switch expressions plus enum with body — breadth for switch coverage. */
public class C_EnumSwitch {

    /** Enum with constructor, field, and abstract method bodies per constant. */
    public enum Op {
        ADD("+") {
            public int apply(int a, int b) {
                return a + b;
            }
        },
        SUB("-") {
            public int apply(int a, int b) {
                return a - b;
            }
        },
        MUL("*") {
            public int apply(int a, int b) {
                return a * b;
            }
        };

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public abstract int apply(int a, int b);
    }

    /** Switch expression mapping enum to a value, exhaustive. */
    public int precedence(Op op) {
        return switch (op) {
            case ADD, SUB -> 1;
            case MUL -> 2;
        };
    }

    /** Use the enum's per-constant behavior. */
    public int compute(Op op, int a, int b) {
        return op.apply(a, b);
    }

    /** Switch expression returning a different type, with yield blocks. */
    public String render(Op op, int a, int b) {
        return switch (op) {
            case ADD -> a + " + " + b;
            case SUB -> a + " - " + b;
            case MUL -> {
                String s = a + " * " + b;
                yield s + " = " + (a * b);
            }
        };
    }
}
