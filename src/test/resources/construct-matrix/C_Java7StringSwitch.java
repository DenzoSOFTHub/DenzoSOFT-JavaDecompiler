/** Java 7 switch on String. */
public class C_Java7StringSwitch {

    public static int dayNumber(String day) {
        switch (day) {
            case "MON":
                return 1;
            case "TUE":
                return 2;
            case "WED":
                return 3;
            case "THU":
                return 4;
            case "FRI":
                return 5;
            case "SAT":
            case "SUN":
                return 0;
            default:
                return -1;
        }
    }

    public static String category(String command) {
        String result;
        switch (command) {
            case "add":
            case "sub":
                result = "arithmetic";
                break;
            case "and":
            case "or":
                result = "logical";
                break;
            default:
                result = "unknown";
                break;
        }
        return result;
    }
}
