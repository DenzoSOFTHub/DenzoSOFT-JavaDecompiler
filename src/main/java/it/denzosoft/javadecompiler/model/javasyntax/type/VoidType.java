/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
