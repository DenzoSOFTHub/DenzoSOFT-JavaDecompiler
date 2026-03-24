/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

public interface Statement {
    int getLineNumber();
    void accept(StatementVisitor visitor);
}
