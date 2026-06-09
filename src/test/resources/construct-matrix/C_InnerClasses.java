// Covers: static nested, member inner, local class, anonymous class with capture
public class C_InnerClasses {

    private int outerField = 42;
    private static int staticField = 7;

    // static nested class
    static class StaticNested {
        int value;
        StaticNested(int v) {
            this.value = v;
        }
        int doubled() {
            return value * 2 + staticField;
        }
    }

    // member inner class (non-static)
    class MemberInner {
        int offset;
        MemberInner(int offset) {
            this.offset = offset;
        }
        int compute() {
            return outerField + offset;
        }
    }

    MemberInner makeInner(int offset) {
        return new MemberInner(offset);
    }

    // local class
    int useLocalClass(final int multiplier) {
        class Local {
            int run(int x) {
                return x * multiplier + outerField;
            }
        }
        Local local = new Local();
        return local.run(5);
    }

    // anonymous class with capture
    Runnable makeRunnable(final int captured) {
        return new Runnable() {
            public void run() {
                int result = captured + outerField;
                System.out.println(result);
            }
        };
    }

    // anonymous class implementing an interface, capturing a local
    C_Computable makeComputable(final int base) {
        return new C_Computable() {
            public int compute(int x) {
                return base + x + outerField;
            }
        };
    }
}

interface C_Computable {
    int compute(int x);
}
