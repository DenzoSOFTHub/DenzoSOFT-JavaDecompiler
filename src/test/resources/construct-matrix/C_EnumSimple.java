/** Simple enum, enum with fields+constructor+methods, enum in switch. */
public class C_EnumSimple {

    // Simple enum.
    public enum Direction {
        NORTH, SOUTH, EAST, WEST
    }

    // Enum with fields, constructor and a method.
    public enum Planet {
        MERCURY(3.303e+23, 2.4397e6),
        EARTH(5.976e+24, 6.37814e6);

        private final double mass;
        private final double radius;

        Planet(double mass, double radius) {
            this.mass = mass;
            this.radius = radius;
        }

        public double surfaceGravity() {
            final double G = 6.67300E-11;
            return G * mass / (radius * radius);
        }
    }

    // Enum used in a switch.
    public static String opposite(Direction d) {
        switch (d) {
            case NORTH:
                return "SOUTH";
            case SOUTH:
                return "NORTH";
            case EAST:
                return "WEST";
            case WEST:
                return "EAST";
            default:
                return "UNKNOWN";
        }
    }
}
