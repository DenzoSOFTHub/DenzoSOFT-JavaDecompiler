// Covers: try/catch/finally, multiple catches, nested try, custom exceptions,
// throws, finally-with-return, rethrow
public class C_Exceptions {

    int simpleTryCatch(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    int tryCatchFinally(int[] a, int idx) {
        int result = 0;
        try {
            result = a[idx];
        } catch (ArrayIndexOutOfBoundsException e) {
            result = -1;
        } finally {
            result += 1000;
        }
        return result;
    }

    String multipleCatches(Object o) {
        try {
            String s = (String) o;
            return s.substring(0, 3);
        } catch (ClassCastException e) {
            return "cast";
        } catch (StringIndexOutOfBoundsException e) {
            return "index";
        } catch (NullPointerException e) {
            return "null";
        }
    }

    int nestedTry(int a, int b, int c) {
        try {
            int x = a / b;
            try {
                return x / c;
            } catch (ArithmeticException inner) {
                return -2;
            }
        } catch (ArithmeticException outer) {
            return -1;
        }
    }

    int finallyWithReturn(int n) {
        try {
            return n;
        } finally {
            return n * 2;
        }
    }

    void throwsClause(int n) throws Exception {
        if (n < 0) {
            throw new Exception("negative: " + n);
        }
    }

    void throwCustom(int n) throws C_CustomException {
        if (n == 0) {
            throw new C_CustomException("zero not allowed");
        }
    }

    int rethrow(int a, int b) throws ArithmeticException {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            throw e;
        }
    }

    int wrapAndThrow(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new RuntimeException("bad number", e);
        }
    }

    int catchSuperclass(Object o) {
        try {
            return o.hashCode();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
