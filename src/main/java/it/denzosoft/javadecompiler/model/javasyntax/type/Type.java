/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
