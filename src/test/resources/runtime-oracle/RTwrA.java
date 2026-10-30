public class RTwrA {
    static StringBuilder log = new StringBuilder();
    static class R implements AutoCloseable {
        final String n; R(String n){ this.n=n; log.append("open:").append(n).append(";"); }
        public void close() { log.append("close:").append(n).append(";"); }
    }
    static void run() { log.setLength(0); try (R a = new R("a"); R b = new R("b")) { log.append("body;"); } }
    public static void main(String[] x) { run(); System.out.println(log); }
}
