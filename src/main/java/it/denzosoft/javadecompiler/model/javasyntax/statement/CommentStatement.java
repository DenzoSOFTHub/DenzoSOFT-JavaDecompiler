/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.javasyntax.statement;

/**
 * A comment to be emitted as a line in the decompiled output.
 * Used by deobfuscation transformers to annotate detected patterns.
 */
// START_CHANGE: IMP-2026-0009-20260327-2 - Comment statement for deobfuscation annotations
public class CommentStatement implements Statement {
    private final int lineNumber;
    private final String comment;

    public CommentStatement(int lineNumber, String comment) {
        this.lineNumber = lineNumber;
        this.comment = comment;
    }

    public String getComment() { return comment; }
    @Override public int getLineNumber() { return lineNumber; }
    @Override public void accept(StatementVisitor visitor) { /* no-op */ }
}
// END_CHANGE: IMP-2026-0009-2
