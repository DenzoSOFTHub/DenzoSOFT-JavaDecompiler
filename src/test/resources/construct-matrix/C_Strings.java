// Covers: String concatenation, StringBuffer
public class C_Strings {

    String simpleConcat(String a, String b) {
        return a + b;
    }

    String concatWithLiteral(String name) {
        return "Hello, " + name + "!";
    }

    String concatMixedTypes(int n, double d, boolean b, char c) {
        return "n=" + n + " d=" + d + " b=" + b + " c=" + c;
    }

    String concatInLoop(int n) {
        String result = "";
        for (int i = 0; i < n; i++) {
            result = result + i + ",";
        }
        return result;
    }

    String compoundConcat(String base) {
        String s = base;
        s += "-suffix";
        s += 42;
        return s;
    }

    String useStringBuffer(int n) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < n; i++) {
            sb.append(i);
            sb.append('-');
        }
        return sb.toString();
    }

    String stringBufferChained() {
        StringBuffer sb = new StringBuffer();
        sb.append("a").append("b").append(1).append(true);
        return sb.toString();
    }

    int stringMethods(String s) {
        return s.length() + s.indexOf('x') + s.charAt(0);
    }

    String stringBufferInsert(String s) {
        StringBuffer sb = new StringBuffer(s);
        sb.insert(0, "[");
        sb.append("]");
        sb.reverse();
        return sb.toString();
    }
}
