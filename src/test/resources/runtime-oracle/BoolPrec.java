public class BoolPrec {
    static boolean f(int a, int b, int c) { return (a > 0 || b > 0) && c > 0; }
    static boolean g(int a, int b, int c) { return !(a > 0 && b > 0) || (c > 0 ? a > 1 : b > 1); }
    static boolean h(double x, double y) { return !(x < y) && (x == x); }
    public static void main(String[] z) {
        StringBuilder sb = new StringBuilder();
        for (int a = -1; a <= 2; a++) for (int b = -1; b <= 2; b++) for (int c = -1; c <= 1; c++) sb.append(f(a, b, c) ? 1 : 0).append(g(a, b, c) ? 1 : 0);
        double[] v = {1, 2, Double.NaN};
        for (double x : v) for (double y : v) sb.append(h(x, y) ? 'T' : 'F');
        System.out.println(sb);
    }
}
