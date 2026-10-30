public class TryBoolClinit {
    static String prop = System.getProperty("user.dir") != null ? "1.0" : "x";
    private static boolean compat = false;
    static String mode;
    static {
        try {
            String form = prop.trim();
            compat = (form != null && form.equals("1.0"));
        } catch (Exception e) {
        }
        if (compat) mode = "old"; else mode = "new";
    }
    public static void main(String[] a) { System.out.println(compat + mode); }
}
