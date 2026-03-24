/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public class ArrayType implements Type {
    private final Type elementType;
    private final int dimension;

    public ArrayType(Type elementType, int dimension) {
        this.elementType = elementType;
        this.dimension = dimension;
    }

    public Type getElementType() { return elementType; }

    @Override public String getDescriptor() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dimension; i++) { sb.append("["); }
        sb.append(elementType.getDescriptor());
        return sb.toString();
    }
    @Override public String getName() {
        StringBuilder sb = new StringBuilder(elementType.getName());
        for (int i = 0; i < dimension; i++) { sb.append("[]"); }
        return sb.toString();
    }
    @Override public int getDimension() { return dimension; }
    @Override public void accept(TypeVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return getName(); }
}
