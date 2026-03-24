/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.model.token;

/**
 * Represents a token in the output stream.
 */
public class Token {
    public enum Kind {
        TEXT, KEYWORD, NUMERIC, STRING, DECLARATION, REFERENCE,
        NEWLINE, INDENT, UNINDENT, MARKER_START, MARKER_END
    }

    private final Kind kind;
    private final String text;
    private final int type;
    private final String internalTypeName;
    private final String name;
    private final String descriptor;
    private final String ownerInternalName;
    private final int lineNumber;

    public Token(Kind kind, String text) {
        this(kind, text, 0, null, null, null, null, 0);
    }

    public Token(Kind kind, String text, int lineNumber) {
        this(kind, text, 0, null, null, null, null, lineNumber);
    }

    public Token(Kind kind, String text, int type, String internalTypeName,
                  String name, String descriptor, String ownerInternalName, int lineNumber) {
        this.kind = kind;
        this.text = text;
        this.type = type;
        this.internalTypeName = internalTypeName;
        this.name = name;
        this.descriptor = descriptor;
        this.ownerInternalName = ownerInternalName;
        this.lineNumber = lineNumber;
    }

    public Kind getKind() { return kind; }
    public String getText() { return text; }
    public int getType() { return type; }
    public String getInternalTypeName() { return internalTypeName; }
    public String getName() { return name; }
    public String getDescriptor() { return descriptor; }
    public String getOwnerInternalName() { return ownerInternalName; }
    public int getLineNumber() { return lineNumber; }

    public static Token text(String text) { return new Token(Kind.TEXT, text); }
    public static Token keyword(String text) { return new Token(Kind.KEYWORD, text); }
    public static Token numeric(String text) { return new Token(Kind.NUMERIC, text); }
    public static Token string(String text) { return new Token(Kind.STRING, text); }
    public static Token newLine(int lineNumber) { return new Token(Kind.NEWLINE, "", lineNumber); }
    public static Token indent() { return new Token(Kind.INDENT, ""); }
    public static Token unindent() { return new Token(Kind.UNINDENT, ""); }
}
