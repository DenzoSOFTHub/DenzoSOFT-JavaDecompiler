// Covers: inheritance, abstract, polymorphism, super/this calls, overloading, overriding,
// static/instance fields + methods + initializers, constructors incl chaining
public class C_Inheritance {

    public static void main(String[] args) {
        C_Shape s = new C_Circle(2.0);
        System.out.println(s.area());
        System.out.println(s.describe());
    }
}

abstract class C_Shape {

    static int instanceCount = 0;
    static final String CATEGORY = "geometry";

    protected String name;
    private final int id;

    static {
        instanceCount = 0;
    }

    {
        id = nextId();
    }

    C_Shape() {
        this("unnamed");
    }

    C_Shape(String name) {
        this.name = name;
        instanceCount++;
    }

    private static int nextId() {
        return instanceCount + 1000;
    }

    abstract double area();

    String describe() {
        return name + "#" + id + " area=" + area();
    }

    int getId() {
        return id;
    }
}

class C_Circle extends C_Shape {

    private double radius;

    C_Circle(double radius) {
        super("circle");
        this.radius = radius;
    }

    double area() {
        return Math.PI * radius * radius;
    }

    String describe() {
        return "Circle: " + super.describe();
    }

    double perimeter() {
        return 2 * Math.PI * radius;
    }
}

class C_OverloadDemo {

    int compute(int a) {
        return a;
    }

    int compute(int a, int b) {
        return a + b;
    }

    double compute(double a) {
        return a * 2;
    }

    String compute(String a) {
        return a + "!";
    }

    int compute(int[] vals) {
        int sum = 0;
        for (int i = 0; i < vals.length; i++) {
            sum += vals[i];
        }
        return sum;
    }
}
