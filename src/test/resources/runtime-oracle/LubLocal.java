public class LubLocal {
    static String f(Object[] args, int i, boolean sub) {
        Object obj = args[i];
        String arg = null;
        if (obj == null) {
            arg = "null";
        } else if (sub) {
            arg = String.valueOf(obj);
            if (arg.indexOf('{') >= 0) { obj = args; arg = null; }
        }
        return arg + "/" + (obj instanceof Object[] ? "arr" : String.valueOf(obj));
    }
    public static void main(String[] a) {
        Object[] x = {"p", null, "{q"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) { sb.append(f(x, i, true)).append(';').append(f(x, i, false)).append(';'); }
        System.out.println(sb);
    }
}
