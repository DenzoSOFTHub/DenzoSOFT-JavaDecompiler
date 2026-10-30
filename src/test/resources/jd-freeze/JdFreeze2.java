public class JdFreeze2 {
    record Point(int x, int y) {}
    record Circle(Point c, int r) {}

    static int flatWithDefault(Object o) {
        return switch (o) {
            case Circle(Point p, int r) -> p.x() * 100 + p.y() * 10 + r;
            default -> -1;
        };
    }
    static int unnamedComponent(Object o) {
        return switch (o) {
            case Circle(Point _, int r) -> r * 7;
            default -> -2;
        };
    }
    public static void main(String[] a) {
        System.out.println("flat/hit="   + flatWithDefault(new Circle(new Point(3,4),5)));
        System.out.println("flat/miss="  + flatWithDefault("nope"));
        System.out.println("unnamed/hit="+ unnamedComponent(new Circle(new Point(9,9),6)));
        System.out.println("unnamed/miss="+unnamedComponent(Integer.valueOf(1)));
    }
}
