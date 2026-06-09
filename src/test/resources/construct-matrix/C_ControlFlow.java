// Covers: if/else chains, while, do-while, for, classic switch with fall-through + default,
// labeled break/continue, nested loops
public class C_ControlFlow {

    String classify(int n) {
        if (n < 0) {
            return "negative";
        } else if (n == 0) {
            return "zero";
        } else if (n < 10) {
            return "small";
        } else {
            return "large";
        }
    }

    int sumWhile(int n) {
        int sum = 0;
        int i = 1;
        while (i <= n) {
            sum += i;
            i++;
        }
        return sum;
    }

    int countDoWhile(int n) {
        int count = 0;
        do {
            count++;
            n--;
        } while (n > 0);
        return count;
    }

    int sumFor(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    int forNoBody(int n) {
        int i;
        for (i = 0; i < n; i++) ;
        return i;
    }

    int forMultipleVars(int n) {
        int total = 0;
        for (int i = 0, j = n; i < j; i++, j--) {
            total += i + j;
        }
        return total;
    }

    String switchFallthrough(int day) {
        String result;
        switch (day) {
            case 0:
            case 6:
                result = "weekend";
                break;
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
                result = "weekday";
                break;
            default:
                result = "invalid";
        }
        return result;
    }

    int switchWithFallAccumulate(int n) {
        int x = 0;
        switch (n) {
            case 3: x += 3;
            case 2: x += 2;
            case 1: x += 1;
            default: x += 100;
        }
        return x;
    }

    int labeledBreak(int[][] grid, int target) {
        int found = -1;
        outer:
        for (int i = 0; i < grid.length; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                if (grid[i][j] == target) {
                    found = i * 100 + j;
                    break outer;
                }
            }
        }
        return found;
    }

    int labeledContinue(int n) {
        int count = 0;
        loop:
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (j > i) {
                    continue loop;
                }
                count++;
            }
        }
        return count;
    }

    int nestedLoops(int n) {
        int product = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                product += i * j;
            }
        }
        return product;
    }
}
