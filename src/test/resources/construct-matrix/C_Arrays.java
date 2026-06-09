// Covers: 1-D, multi-dim, jagged, array initializers, array of objects
public class C_Arrays {

    int[] makeOneDim() {
        int[] a = new int[5];
        a[0] = 10;
        a[1] = 20;
        return a;
    }

    int[] arrayInitializer() {
        return new int[] { 1, 2, 3, 4, 5 };
    }

    int[] arrayInitializerShort() {
        int[] a = { 7, 8, 9 };
        return a;
    }

    int[][] makeTwoDim() {
        int[][] m = new int[3][4];
        m[0][0] = 1;
        m[2][3] = 99;
        return m;
    }

    int[][][] makeThreeDim() {
        return new int[2][3][4];
    }

    int[][] makeJagged() {
        int[][] jagged = new int[3][];
        jagged[0] = new int[1];
        jagged[1] = new int[2];
        jagged[2] = new int[3];
        return jagged;
    }

    int[][] twoDimInitializer() {
        return new int[][] {
            { 1, 2, 3 },
            { 4, 5, 6 },
            { 7, 8, 9 }
        };
    }

    String[] arrayOfObjects() {
        String[] names = new String[3];
        names[0] = "alpha";
        names[1] = "beta";
        names[2] = "gamma";
        return names;
    }

    Object[] arrayOfObjectsInit() {
        return new Object[] { "text", new Integer(5), new int[] { 1 } };
    }

    int arrayLength(int[] a) {
        return a.length;
    }

    int sumArray(int[] a) {
        int sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i];
        }
        return sum;
    }

    char[] charArray() {
        return new char[] { 'h', 'i' };
    }

    boolean[] boolArray() {
        return new boolean[] { true, false, true };
    }

    double[] doubleArray() {
        return new double[] { 1.1, 2.2, 3.3 };
    }
}
