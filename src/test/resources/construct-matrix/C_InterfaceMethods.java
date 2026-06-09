/** Java 8 default + static interface methods, Java 9 private interface methods. */
public class C_InterfaceMethods {

    public interface Greeter {

        // Abstract method
        String name();

        // Java 8 default method
        default String greet() {
            return "Hello, " + name() + buildSuffix();
        }

        // Java 8 default method calling another default
        default String greetLoud() {
            return greet().toUpperCase();
        }

        // Java 8 static method
        static Greeter of(final String n) {
            return new Greeter() {
                public String name() {
                    return n;
                }
            };
        }

        // Java 9 private instance method (helper for defaults)
        private String buildSuffix() {
            return punctuation();
        }

        // Java 9 private static method
        private static String fixed() {
            return "!";
        }

        // Java 9 private instance method used by private method
        private String punctuation() {
            return fixed();
        }
    }

    public String runDefault() {
        Greeter g = Greeter.of("World");
        return g.greet();
    }

    public String runDefaultLoud() {
        return Greeter.of("there").greetLoud();
    }

    // Interface with a static factory and default combination
    public interface Calculator {

        int base();

        default int addBase(int x) {
            return x + base();
        }

        static Calculator zero() {
            return () -> 0;
        }
    }

    public int runCalculator() {
        return Calculator.zero().addBase(10);
    }
}
