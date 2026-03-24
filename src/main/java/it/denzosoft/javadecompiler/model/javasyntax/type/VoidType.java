/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public class VoidType implements Type {
    public static final VoidType INSTANCE = new VoidType();

    private VoidType() {}

    @Override public String getDescriptor() { return "V"; }
    @Override public String getName() { return "void"; }
    @Override public int getDimension() { return 0; }
    @Override public void accept(TypeVisitor visitor) { visitor.visit(this); }
    @Override public String toString() { return "void"; }
}
