public class RTwrB {
    static StringBuilder log = new StringBuilder();
    static class R implements AutoCloseable {
        R(){ log.append("open;"); }
        public void close() { log.append("close;"); }
        void boom() { throw new RuntimeException("x"); }
    }
    static void run() {
        log.setLength(0);
        try (R r = new R()) { r.boom(); }
        catch (RuntimeException e) { log.append("caught;"); }
        finally { log.append("fin;"); }
    }
    public static void main(String[] x) { run(); System.out.println(log); }
}
