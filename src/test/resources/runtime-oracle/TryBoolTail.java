public class TryBoolTail {
    static boolean flag;
    static boolean check(String s) { if (s.length() > 3) throw new IllegalStateException(); return s.startsWith("1"); }
    static String run(String p) {
        flag = false;
        try {
            String form = p.trim();
            flag = form != null && check(form);
        } catch (RuntimeException e) {
            return "caught:" + flag;
        }
        return "ok:" + flag;
    }
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (String p : new String[]{"1.0", "2", "12345", null}) sb.append(run(p)).append(';');
        System.out.println(sb);
    }
}
