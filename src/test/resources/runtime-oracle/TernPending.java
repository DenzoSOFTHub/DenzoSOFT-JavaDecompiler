public class TernPending {
    int op; boolean coalesce, report, other;
    void swap() { this.op = this.op == 2 ? 3 : this.op == 3 ? 2 : this.op == 4 ? 5 : 4; }
    void rep() { this.report = this.coalesce ? false : this.other; }
    void rep2() { this.report = this.coalesce ? false : true; }
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder();
        TernPending t = new TernPending();
        for (int i = 1; i <= 5; i++) { t.op = i; t.swap(); sb.append(t.op); }
        for (int i = 0; i < 4; i++) { t.coalesce = (i & 1) != 0; t.other = (i & 2) != 0; t.rep(); sb.append(t.report ? 'T' : 'F'); t.rep2(); sb.append(t.report ? 't' : 'f'); }
        System.out.println(sb);
    }
}
