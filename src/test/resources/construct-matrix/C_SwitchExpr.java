/** Switch expressions: arrow form, yield form, multi-label, on int/String/enum, exhaustive. */
public class C_SwitchExpr {

    public enum Day {
        MON, TUE, WED, THU, FRI, SAT, SUN
    }

    /** Arrow form on enum, exhaustive (no default). */
    public boolean isWeekend(Day d) {
        return switch (d) {
            case SAT, SUN -> true;
            case MON, TUE, WED, THU, FRI -> false;
        };
    }

    /** Arrow form on int with default. */
    public String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "?";
        };
    }

    /** Multi-label arrow case. */
    public int quarter(int month) {
        return switch (month) {
            case 1, 2, 3 -> 1;
            case 4, 5, 6 -> 2;
            case 7, 8, 9 -> 3;
            case 10, 11, 12 -> 4;
            default -> 0;
        };
    }

    /** Yield form with a block body. */
    public int describeLength(String s) {
        return switch (s.length()) {
            case 0 -> 0;
            case 1, 2 -> {
                int base = s.length() * 10;
                yield base + 1;
            }
            default -> {
                yield s.length() * 100;
            }
        };
    }

    /** Switch expression on String. */
    public int direction(String s) {
        return switch (s) {
            case "north" -> 0;
            case "east" -> 90;
            case "south" -> 180;
            case "west" -> 270;
            default -> -1;
        };
    }

    /** Colon (legacy-style) labels inside a switch expression with yield. */
    public String grade(int score) {
        return switch (score / 10) {
            case 10:
            case 9:
                yield "A";
            case 8:
                yield "B";
            case 7:
                yield "C";
            default:
                yield "F";
        };
    }
}
