/** instanceof type patterns: binding, with &&, negated/guard, in if-else. */
public class C_InstanceofPattern {

    /** Simple pattern binding. */
    public int length(Object o) {
        if (o instanceof String s) {
            return s.length();
        }
        return -1;
    }

    /** Pattern with && guard using the binding. */
    public boolean isLongString(Object o) {
        return o instanceof String s && s.length() > 5;
    }

    /** Negated pattern: binding flows to the else path. */
    public String describe(Object o) {
        if (!(o instanceof Number n)) {
            return "not-a-number";
        }
        return "number:" + n.intValue();
    }

    /** Pattern with combined conditions and a second binding. */
    public boolean bothNonEmpty(Object a, Object b) {
        if (a instanceof String x && b instanceof String y) {
            return !x.isEmpty() && !y.isEmpty();
        }
        return false;
    }

    /** Pattern binding used in chained else-if. */
    public String classify(Object o) {
        if (o instanceof Integer i && i > 0) {
            return "positive-int";
        } else if (o instanceof Integer i) {
            return "non-positive-int";
        } else if (o instanceof String s) {
            return "string-len-" + s.length();
        } else {
            return "other";
        }
    }
}
