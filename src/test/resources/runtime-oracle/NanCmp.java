public class NanCmp {
    static boolean a(double x, double y) { return !(x < y); }
    static boolean b(double x, double y) { return !(x > y); }
    static boolean c(float x, float y) { return !(x <= y); }
    static boolean d(float x, float y) { return !(x >= y); }
    static boolean e(double x, double y) { return x < y; }
    static boolean f(double x, double y) { return x >= y; }
    static int g(double x, double y) { if (!(x < y)) return 1; return 2; }
    static int h(double x, double y) { if (x > y) return 1; else if (x != y) return 3; return 2; }
    public static void main(String[] z) {
        StringBuilder sb = new StringBuilder();
        double[] v = {1, 2, Double.NaN};
        for (double x : v) for (double y : v) {
            sb.append(a(x, y) ? 'T' : 'F'); sb.append(b(x, y) ? 'T' : 'F');
            sb.append(c((float) x, (float) y) ? 'T' : 'F'); sb.append(d((float) x, (float) y) ? 'T' : 'F');
            sb.append(e(x, y) ? 'T' : 'F'); sb.append(f(x, y) ? 'T' : 'F');
            sb.append(g(x, y)); sb.append(h(x, y)); sb.append(' ');
        }
        System.out.println(sb);
    }
}
