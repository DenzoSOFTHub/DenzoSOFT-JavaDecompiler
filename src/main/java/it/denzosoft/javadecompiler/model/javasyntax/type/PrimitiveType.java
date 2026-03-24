/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public class PrimitiveType implements Type {
    public static final PrimitiveType BOOLEAN = new PrimitiveType("boolean", "Z");
    public static final PrimitiveType BYTE = new PrimitiveType("byte", "B");
    public static final PrimitiveType CHAR = new PrimitiveType("char", "C");
    public static final PrimitiveType SHORT = new PrimitiveType("short", "S");
    public static final PrimitiveType INT = new PrimitiveType("int", "I");
    public static final PrimitiveType LONG = new PrimitiveType("long", "J");
    public static final PrimitiveType FLOAT = new PrimitiveType("float", "F");
    public static final PrimitiveType DOUBLE = new PrimitiveType("double", "D");

    private final String name;
    private final String descriptor;

    private PrimitiveType(String name, String descriptor) {
        this.name = name;
        this.descriptor = descriptor;
    }

    @Override public String getDescriptor() { return descriptor; }
    @Override public String getName() { return name; }
    @Override public int getDimension() { return 0; }
    @Override public void accept(TypeVisitor visitor) { visitor.visit(this); }

    public static PrimitiveType fromDescriptor(char c) {
        switch (c) {
            case 'Z': return BOOLEAN;
            case 'B': return BYTE;
            case 'C': return CHAR;
            case 'S': return SHORT;
            case 'I': return INT;
            case 'J': return LONG;
            case 'F': return FLOAT;
            case 'D': return DOUBLE;
            default: throw new IllegalArgumentException("Unknown primitive descriptor: " + c);
        }
    }

    @Override public String toString() { return name; }
}
