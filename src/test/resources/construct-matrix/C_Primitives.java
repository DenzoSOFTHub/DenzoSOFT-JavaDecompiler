// Covers: all primitive types, literals, casts, numeric promotion
public class C_Primitives {

    boolean bool = true;
    byte b = (byte) 0x7F;
    short s = (short) 32000;
    char c = 'A';
    int i = 1_000_000_000;
    long l = 9000000000L;
    float f = 3.14f;
    double d = 2.718281828;

    int hex = 0xCAFE;
    int oct = 0755;
    int bin = 0b1010_1100;
    long bigHex = 0xFFFFFFFFL;
    double sci = 1.5e10;
    char tab = '\t';
    char newline = '\n';
    char quote = '\'';

    long widenIntToLong(int x) {
        return x;
    }

    double widenFloatToDouble(float x) {
        return x;
    }

    byte narrowIntToByte(int x) {
        return (byte) x;
    }

    int narrowLongToInt(long x) {
        return (int) x;
    }

    int truncateDoubleToInt(double x) {
        return (int) x;
    }

    char intToChar(int x) {
        return (char) x;
    }

    int charToInt(char x) {
        return x;
    }

    int bytePromotion(byte a, byte bb) {
        return a + bb;
    }

    long mixedPromotion(int x, long y) {
        return x + y;
    }

    double mixedFloatDouble(float x, double y) {
        return x * y;
    }

    float castDoubleToFloat(double x) {
        return (float) x;
    }
}
