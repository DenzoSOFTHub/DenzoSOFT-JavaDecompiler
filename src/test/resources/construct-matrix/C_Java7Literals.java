/** Java 7 numeric literals: binary 0b, underscores in literals. */
public class C_Java7Literals {

    public static final int BINARY_MASK = 0b1010_1010;
    public static final int MILLION = 1_000_000;
    public static final long CARD_NUMBER = 1234_5678_9012_3456L;
    public static final long HEX = 0xCAFE_BABEL;
    public static final int OCTAL = 0_777;
    public static final double PI_DIGITS = 3.141_592_653;
    public static final byte BYTE_FLAGS = (byte) 0b0000_1111;

    public static int maskedValue(int input) {
        return input & BINARY_MASK;
    }

    public static long bigValue() {
        return MILLION * 1_000L;
    }

    public static boolean hasFlag(int value, int bit) {
        return (value & (0b1 << bit)) != 0;
    }
}
