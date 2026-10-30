public class OrAndChain {
    static boolean en(long m, int id) {
        return ((m & 1L) != 0 && id >= 0 && id <= 3)
            || ((m & 2L) != 0 && id >= 100 && id <= 103)
            || ((m & 4L) != 0 && id >= 200 && id <= 203)
            || ((m & 8L) != 0 && id >= 300 && id <= 303)
            || ((m & 16L) != 0 && id >= 400 && id <= 403);
    }
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (long m = 0; m < 32; m += 7) for (int id = -1; id < 450; id += 50) sb.append(en(m, id) ? '1' : '0');
        System.out.println(sb);
    }
}
