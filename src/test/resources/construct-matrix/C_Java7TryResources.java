import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Java 7 try-with-resources: single and multiple resources. */
public class C_Java7TryResources {

    // Single resource.
    public static int readFirst(byte[] data) throws IOException {
        try (InputStream in = new ByteArrayInputStream(data)) {
            return in.read();
        }
    }

    // Multiple resources in one statement.
    public static byte[] copy(byte[] data) throws IOException {
        try (InputStream in = new ByteArrayInputStream(data);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return out.toByteArray();
        }
    }

    // Try-with-resources combined with catch and finally.
    public static int readOrDefault(byte[] data, int fallback) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            return in.read();
        } catch (IOException e) {
            return fallback;
        }
    }

    // A custom AutoCloseable resource.
    static class Resource implements AutoCloseable {
        private final OutputStream sink;

        Resource(OutputStream sink) {
            this.sink = sink;
        }

        void write(int b) throws IOException {
            sink.write(b);
        }

        public void close() throws IOException {
            sink.close();
        }
    }

    public static byte[] useCustom() throws IOException {
        ByteArrayOutputStream backing = new ByteArrayOutputStream();
        try (Resource r = new Resource(backing)) {
            r.write(42);
        }
        return backing.toByteArray();
    }
}
