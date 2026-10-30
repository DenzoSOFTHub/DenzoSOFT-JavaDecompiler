public class IfElseLoopTail {
    int type; int[] map = new int[8]; int[] ranges; int nonMapIndex;
    boolean match(int ch) {
        boolean ret;
        if (this.type == 4) {
            if (ch < 256) return (this.map[ch / 32] & (1 << (ch & 0x1f))) != 0;
            ret = false;
            for (int i = this.nonMapIndex; i < this.ranges.length; i += 2) {
                if (this.ranges[i] <= ch && ch <= this.ranges[i + 1]) return true;
            }
        } else {
            if (ch < 256) return (this.map[ch / 32] & (1 << (ch & 0x1f))) == 0;
            ret = true;
            for (int i = this.nonMapIndex; i < this.ranges.length; i += 2) {
                if (this.ranges[i] <= ch && ch <= this.ranges[i + 1]) return false;
            }
        }
        return ret;
    }
    public static void main(String[] a) {
        IfElseLoopTail t = new IfElseLoopTail();
        t.map[1] = 0x5; t.ranges = new int[]{300, 310, 400, 410};
        StringBuilder sb = new StringBuilder();
        for (int ty = 3; ty <= 4; ty++) { t.type = ty;
            for (int ch : new int[]{32, 33, 34, 305, 350, 405, 500}) sb.append(t.match(ch) ? '1' : '0');
            sb.append(';'); }
        System.out.println(sb);
    }
}
