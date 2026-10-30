/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
