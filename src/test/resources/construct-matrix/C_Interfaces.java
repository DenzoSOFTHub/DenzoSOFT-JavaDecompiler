// Covers: interface constants, multiple interface inheritance, implementing interfaces
public class C_Interfaces implements C_Drawable, C_Resizable {

    private int width = 10;
    private int height = 20;

    public void draw() {
        System.out.println("drawing " + width + "x" + height);
    }

    public int getColor() {
        return DEFAULT_COLOR;
    }

    public void resize(int factor) {
        width *= factor;
        height *= factor;
    }

    public int getArea() {
        return width * height;
    }

    int useConstants() {
        return MAX_SIZE + DEFAULT_COLOR;
    }
}

interface C_Drawable {
    int DEFAULT_COLOR = 0xFFFFFF;
    void draw();
    int getColor();
}

interface C_Resizable {
    int MAX_SIZE = 1000;
    void resize(int factor);
    int getArea();
}

// interface extending multiple interfaces
interface C_DrawableResizable extends C_Drawable, C_Resizable {
    void reset();
}
