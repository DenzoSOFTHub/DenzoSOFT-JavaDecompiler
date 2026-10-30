/*
 * A/B runtime oracle fixture for BUG-2026-0132:
 * break / continue / return out of a synchronized block that sits inside a loop.
 *
 * The oracle is the ORIGINAL SOURCE: run this class, decompile it, recompile the
 * decompilation, run it again and compare stdout. Every shape prints both the values it
 * computed (so a deleted statement changes the output) and whether the monitor was held
 * while the loop tail ran (so a statement MOVED into the lock changes the output too).
 *
 * Baseline (v1.13.0 + BUG-2026-0129) fails this fixture in all three debug modes.
 */
public class RSyncLoopExit {

    static final Object LOCK = new Object();
    static final StringBuilder LOG = new StringBuilder();
    static int stale = 0;

    static void tick(String tag) {
        LOG.append(' ').append(tag).append('=');
        LOG.append(Thread.holdsLock(LOCK));
    }

    static int size(int[] a) {
        return a.length;
    }

    /* break out of the lock, with a statement after the `if` inside the lock and
       a statement after the synchronized block inside the loop. */
    static int breakThenTail(int n) {
        int r = 0;
        for (;;) {
            synchronized (LOCK) {
                r += 1;
                if (r > n) {
                    break;
                }
                r += 10;
            }
            tick("bt");
            r += 100;
        }
        return r;
    }

    /* continue out of the lock, same layout. */
    static int continueThenTail(int n) {
        int r = 0;
        for (int i = 0; i < n; i++) {
            synchronized (LOCK) {
                r += 1;
                if (i == 2) {
                    continue;
                }
                r += 10;
            }
            tick("ct");
            r += 100;
        }
        return r;
    }

    /* return out of the lock from inside a loop. */
    static int returnFromLock(int[] a) {
        for (int i = 0; i < a.length; i++) {
            synchronized (LOCK) {
                stale += 1;
                if (a[i] < 0) {
                    return i;
                }
                a[i] = a[i] + 1;
            }
            tick("rf");
        }
        return -1;
    }

    /* sun.nio.ch.FileLockTable.add shape: retry loop, break from inside the lock,
       a statement after the break's `if` inside the lock, and a tail after the loop. */
    static int retryLike(int[] a, int v) {
        int idx = -1;
        for (;;) {
            if (idx < 0) {
                idx = 0;
                if (a[0] == 0) {
                    a[0] = v;
                    break;
                } else {
                    idx = a[0];
                }
            }
            synchronized (LOCK) {
                int current = a[1];
                if (idx == current) {
                    size(a);
                    a[2] = v;
                    break;
                }
                idx = current;
            }
        }
        stale += 7;
        return idx;
    }

    /* java.util.concurrent.Phaser.doRegister shape: two breaks out of the lock, an inner
       loop between them, and an implicit continue when the guard fails. */
    static int phaserLike(int s) {
        int phase = 0;
        int guard = 0;
        for (;;) {
            synchronized (LOCK) {
                if (stale == s) {
                    phase = size(new int[3]);
                    if (phase < 0) {
                        break;
                    }
                    while (phase < 10) {
                        phase = phase + 1;
                        stale = stale + 1;
                    }
                    break;
                }
            }
            tick("pl");
            guard += 1;
            if (guard > 3) {
                stale = s;
            }
        }
        return phase * 1000 + guard;
    }

    public static void main(String[] args) {
        System.out.println("breakThenTail=" + breakThenTail(25));
        System.out.println("continueThenTail=" + continueThenTail(5));
        int[] arr = new int[] { 1, 2, -3, 4 };
        System.out.println("returnFromLock=" + returnFromLock(arr) + " stale=" + stale);
        System.out.println("arr=" + java.util.Arrays.toString(arr));
        int[] b = new int[] { 4, 4, 0 };
        System.out.println("retryLike=" + retryLike(b, 9) + " stale=" + stale);
        System.out.println("b=" + java.util.Arrays.toString(b));
        stale = 0;
        System.out.println("phaserLike=" + phaserLike(2) + " stale=" + stale);
        System.out.println("log=" + LOG);
        System.out.println("holds=" + Thread.holdsLock(LOCK));
    }
}
