// Covers: synchronized blocks/methods, volatile/transient fields
public class C_Concurrency {

    private volatile boolean running = false;
    private transient int cache;
    private int counter;
    private final Object lock = new Object();

    public synchronized void incrementSync() {
        counter++;
    }

    public synchronized int getCounterSync() {
        return counter;
    }

    public static synchronized void staticSync() {
        System.out.println("static synchronized");
    }

    public void incrementBlock() {
        synchronized (this) {
            counter++;
        }
    }

    public void incrementWithLock() {
        synchronized (lock) {
            counter++;
            cache = counter * 2;
        }
    }

    public int nestedSynchronized() {
        synchronized (this) {
            synchronized (lock) {
                return counter + cache;
            }
        }
    }

    public void setRunning(boolean r) {
        running = r;
    }

    public boolean isRunning() {
        return running;
    }

    public int synchronizedOnArg(Object o) {
        synchronized (o) {
            return o.hashCode();
        }
    }
}
