/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Sanitizes identifiers from obfuscated bytecode to produce compilable Java source.
 * Handles: Java keywords used as names, illegal characters, empty names.
 */
// START_CHANGE: IMP-2026-0005-20260327-1 - Identifier sanitizer for deobfuscation
public final class IdentifierSanitizer {

    private IdentifierSanitizer() {}

    private static final Set<String> JAVA_KEYWORDS = new HashSet<String>();
    static {
        // Java keywords (all versions)
        String[] kw = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            // Literals that cannot be identifiers
            "true", "false", "null",
            // Contextual keywords (restricted in some contexts)
            "var", "yield", "record", "sealed", "permits", "non-sealed"
        };
        for (int i = 0; i < kw.length; i++) {
            JAVA_KEYWORDS.add(kw[i]);
        }
    }

    /**
     * Returns true if the name is a Java keyword or reserved literal.
     */
    public static boolean isKeyword(String name) {
        return JAVA_KEYWORDS.contains(name);
    }

    /**
     * Returns true if the name needs sanitization to be a valid Java identifier.
     */
    public static boolean needsSanitization(String name) {
        if (name == null || name.length() == 0) {
            return true;
        }
        if (JAVA_KEYWORDS.contains(name)) {
            return true;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return true;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitize an identifier to be a valid Java identifier.
     * - Java keywords get a '_' prefix: do -> _do, int -> _int
     * - Illegal characters are replaced with '_'
     * - Empty/null names get a generated name
     *
     * @param name the original identifier
     * @param category prefix for generated names: "cls", "mtd", "fld", "var"
     * @param index disambiguator for generated names
     * @return a valid Java identifier
     */
    public static String sanitize(String name, String category, int index) {
        if (name == null || name.length() == 0) {
            return category + "_" + index;
        }

        if (!needsSanitization(name)) {
            return name;
        }

        // Java keyword: prefix with _
        if (JAVA_KEYWORDS.contains(name)) {
            return "_" + name;
        }

        // Contains illegal characters: replace them
        StringBuilder sb = new StringBuilder(name.length());
        char first = name.charAt(0);
        if (Character.isJavaIdentifierStart(first)) {
            sb.append(first);
        } else if (Character.isJavaIdentifierPart(first)) {
            sb.append('_');
            sb.append(first);
        } else {
            sb.append('_');
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }

        String result = sb.toString();
        // If after replacement it's a keyword (unlikely but possible), prefix again
        if (JAVA_KEYWORDS.contains(result)) {
            return "_" + result;
        }
        return result;
    }

    /**
     * Sanitize an identifier. Shorthand without index.
     */
    public static String sanitize(String name, String category) {
        return sanitize(name, category, 0);
    }
}
// END_CHANGE: IMP-2026-0005-1
