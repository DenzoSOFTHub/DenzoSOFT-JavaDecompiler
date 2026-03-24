/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public class GenericType implements Type {
    private final String name;
    private final int dimension;

    public GenericType(String name) {
        this(name, 0);
    }

    public GenericType(String name, int dimension) {
        this.name = name;
        this.dimension = dimension;
    }

    @Override public String getDescriptor() { return "T" + name + ";"; }
    @Override public String getName() { return name; }
    @Override public int getDimension() { return dimension; }
    @Override public void accept(TypeVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return name; }
}
