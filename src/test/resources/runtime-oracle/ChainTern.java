public class ChainTern {
    static boolean f(int a) { return a > 0; }
    static boolean g(int a) { return a > 1; }
    public static void main(String[] z) {
        StringBuilder sb = new StringBuilder();
        for (int a = -1; a <= 2; a++) sb.append(f(a) ? 1 : 0).append(g(a) ? 1 : 0).append(';');
        System.out.println(sb);
    }
}
