public class RTwr {
    static int closed = 0;
    static class R implements AutoCloseable {
        public void close() { closed++; }
        int use() { return 7; }
    }
    static int run() { closed = 0; try (R r = new R()) { return r.use(); } }
    public static void main(String[] a) {
        int v = run();
        System.out.println("value=" + v + " closed=" + closed);
    }
}
