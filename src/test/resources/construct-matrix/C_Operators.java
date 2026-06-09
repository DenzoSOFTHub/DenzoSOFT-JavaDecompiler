// Covers: all operators - arithmetic, bitwise, shift (incl >>>), logical short-circuit,
// ternary, compound-assignment, pre/post inc/dec, instanceof
public class C_Operators {

    int add(int a, int b) { return a + b; }
    int sub(int a, int b) { return a - b; }
    int mul(int a, int b) { return a * b; }
    int div(int a, int b) { return a / b; }
    int mod(int a, int b) { return a % b; }
    int neg(int a) { return -a; }
    int pos(int a) { return +a; }

    int bitAnd(int a, int b) { return a & b; }
    int bitOr(int a, int b) { return a | b; }
    int bitXor(int a, int b) { return a ^ b; }
    int bitNot(int a) { return ~a; }

    int shiftLeft(int a, int n) { return a << n; }
    int shiftRight(int a, int n) { return a >> n; }
    int shiftRightUnsigned(int a, int n) { return a >>> n; }

    boolean logicalAnd(boolean a, boolean b) { return a && b; }
    boolean logicalOr(boolean a, boolean b) { return a || b; }
    boolean logicalNot(boolean a) { return !a; }

    boolean lt(int a, int b) { return a < b; }
    boolean le(int a, int b) { return a <= b; }
    boolean gt(int a, int b) { return a > b; }
    boolean ge(int a, int b) { return a >= b; }
    boolean eq(int a, int b) { return a == b; }
    boolean ne(int a, int b) { return a != b; }

    int ternary(int a, int b) { return a > b ? a : b; }
    int nestedTernary(int a) { return a < 0 ? -1 : a == 0 ? 0 : 1; }

    int compoundAdd(int a, int b) { a += b; return a; }
    int compoundSub(int a, int b) { a -= b; return a; }
    int compoundMul(int a, int b) { a *= b; return a; }
    int compoundDiv(int a, int b) { a /= b; return a; }
    int compoundMod(int a, int b) { a %= b; return a; }
    int compoundShl(int a, int b) { a <<= b; return a; }
    int compoundShr(int a, int b) { a >>= b; return a; }
    int compoundUshr(int a, int b) { a >>>= b; return a; }
    int compoundAnd(int a, int b) { a &= b; return a; }
    int compoundOr(int a, int b) { a |= b; return a; }
    int compoundXor(int a, int b) { a ^= b; return a; }

    int preIncrement(int a) { return ++a; }
    int preDecrement(int a) { return --a; }
    int postIncrement(int a) { return a++; }
    int postDecrement(int a) { return a--; }

    int incInExpression(int a) {
        int r = a++ + ++a;
        return r;
    }

    boolean isString(Object o) { return o instanceof String; }
    boolean isNumber(Object o) { return o instanceof Number; }
}
