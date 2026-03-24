/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

public interface TypeVisitor {
    void visit(PrimitiveType type);
    void visit(ObjectType type);
    void visit(ArrayType type);
    void visit(VoidType type);
    void visit(GenericType type);
}
