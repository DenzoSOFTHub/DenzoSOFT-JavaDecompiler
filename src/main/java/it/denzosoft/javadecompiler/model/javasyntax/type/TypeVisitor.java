/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public interface TypeVisitor {
    void visit(PrimitiveType type);
    void visit(ObjectType type);
    void visit(ArrayType type);
    void visit(VoidType type);
    void visit(GenericType type);
}
