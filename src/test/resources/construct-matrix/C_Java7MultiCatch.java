import java.io.IOException;

/** Java 7 multi-catch: catch (A | B e). */
public class C_Java7MultiCatch {

    static class FirstException extends Exception {
    }

    static class SecondException extends Exception {
    }

    static void risky(int mode) throws FirstException, SecondException {
        if (mode == 1) {
            throw new FirstException();
        }
        if (mode == 2) {
            throw new SecondException();
        }
    }

    // Multi-catch of two checked exceptions.
    public static String handle(int mode) {
        try {
            risky(mode);
            return "ok";
        } catch (FirstException | SecondException e) {
            return "failed:" + e.getClass().getSimpleName();
        }
    }

    // Multi-catch mixing runtime and IO exceptions.
    public static int parseOrZero(String text) {
        try {
            if (text == null) {
                throw new IOException("null");
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException | IOException e) {
            return 0;
        }
    }
}
