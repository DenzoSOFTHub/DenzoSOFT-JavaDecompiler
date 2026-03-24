/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

/**
 * Base interface for all Java types in the AST.
 */
public interface Type {
    String getDescriptor();
    String getName();
    int getDimension();

    void accept(TypeVisitor visitor);
}
