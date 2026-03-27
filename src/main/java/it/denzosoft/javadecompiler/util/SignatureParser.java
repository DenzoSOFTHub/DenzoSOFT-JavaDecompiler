/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.util;

/**
 * Parses JVM generic signatures (JVM Spec 4.7.9.1) into human-readable type names.
 * Handles class signatures, method signatures, and field signatures.
 */
public final class SignatureParser {

    private SignatureParser() {}

    /**
     * Parse a field or return type signature into a readable string.
     * Examples: "Ljava/util/List<Ljava/lang/String;>;" -> "List<String>"
     *           "TT;" -> "T"
     *           "[Ljava/lang/String;" -> "String[]"
     */
    public static String parseFieldSignature(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        int[] pos = {0};
        return parseTypeSignature(signature, pos);
    }

    /**
     * Parse a class signature and return the type parameters declaration.
     * Example: "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;"
     *          -> "<T>"  (type parameters only)
     */
    public static String parseClassTypeParameters(String signature) {
        if (signature == null || !signature.startsWith("<")) return null;
        int[] pos = {0};
        return parseTypeParameters(signature, pos);
    }

    /**
     * Parse a class signature and return the superclass with generics.
     */
    public static String parseClassSuperType(String signature) {
        if (signature == null) return null;
        int[] pos = {0};
        // Skip type parameters
        if (signature.startsWith("<")) {
            skipTypeParameters(signature, pos);
        }
        return parseTypeSignature(signature, pos);
    }

    /**
     * Parse a class signature and return the interface types with generics.
     */
    public static String[] parseClassInterfaceTypes(String signature) {
        if (signature == null) return null;
        int[] pos = {0};
        // Skip type parameters
        if (signature.startsWith("<")) {
            skipTypeParameters(signature, pos);
        }
        // Skip super type
        parseTypeSignature(signature, pos);
        // Parse remaining interface types
        java.util.List<String> interfaces = new java.util.ArrayList<String>();
        while (pos[0] < signature.length()) {
            interfaces.add(parseTypeSignature(signature, pos));
        }
        if (interfaces.isEmpty()) return null;
        return interfaces.toArray(new String[interfaces.size()]);
    }

    /**
     * Parse a method signature and return parameter types with generics.
     */
    public static String[] parseMethodParameterTypes(String signature) {
        if (signature == null || !signature.contains("(")) return null;
        int[] pos = {0};
        // Skip type parameters
        if (signature.startsWith("<")) {
            skipTypeParameters(signature, pos);
        }
        pos[0]++; // skip '('
        java.util.List<String> params = new java.util.ArrayList<String>();
        while (pos[0] < signature.length() && signature.charAt(pos[0]) != ')') {
            params.add(parseTypeSignature(signature, pos));
        }
        pos[0]++; // skip ')'
        return params.toArray(new String[params.size()]);
    }

    /**
     * Parse a method signature and return the return type with generics.
     */
    public static String parseMethodReturnType(String signature) {
        if (signature == null || !signature.contains(")")) return null;
        int closeParen = signature.indexOf(')');
        int[] pos = {closeParen + 1};
        return parseTypeSignature(signature, pos);
    }

    /**
     * Parse method type parameters declaration.
     * Example: "<T:Ljava/lang/Object;>(TT;)TT;" -> "<T>"
     */
    public static String parseMethodTypeParameters(String signature) {
        if (signature == null || !signature.startsWith("<")) return null;
        int[] pos = {0};
        return parseTypeParameters(signature, pos);
    }

    // Internal parsing

    private static String parseTypeSignature(String sig, int[] pos) {
        if (pos[0] >= sig.length()) return "Object";
        char c = sig.charAt(pos[0]);

        switch (c) {
            case 'B': pos[0]++; return "byte";
            case 'C': pos[0]++; return "char";
            case 'D': pos[0]++; return "double";
            case 'F': pos[0]++; return "float";
            case 'I': pos[0]++; return "int";
            case 'J': pos[0]++; return "long";
            case 'S': pos[0]++; return "short";
            case 'Z': pos[0]++; return "boolean";
            case 'V': pos[0]++; return "void";
            case '[': {
                pos[0]++;
                String elementType = parseTypeSignature(sig, pos);
                return elementType + "[]";
            }
            case 'T': {
                // Type variable: T<name>;
                pos[0]++;
                int semi = sig.indexOf(';', pos[0]);
                String name = sig.substring(pos[0], semi);
                pos[0] = semi + 1;
                return name;
            }
            case 'L': {
                // Class type with optional type arguments
                // START_CHANGE: BUG-2026-0042-20260327-1 - Track package vs inner class separators
                pos[0]++;
                StringBuilder sb = new StringBuilder();
                int lastPkgDot = -1; // position of last package separator (from /)
                while (pos[0] < sig.length()) {
                    char ch = sig.charAt(pos[0]);
                    if (ch == ';') {
                        pos[0]++;
                        break;
                    } else if (ch == '<') {
                        // Type arguments
                        pos[0]++;
                        sb.append('<');
                        boolean first = true;
                        while (pos[0] < sig.length() && sig.charAt(pos[0]) != '>') {
                            if (!first) sb.append(", ");
                            first = false;
                            char argCh = sig.charAt(pos[0]);
                            if (argCh == '*') {
                                sb.append('?');
                                pos[0]++;
                            } else if (argCh == '+') {
                                pos[0]++;
                                sb.append("? extends ").append(parseTypeSignature(sig, pos));
                            } else if (argCh == '-') {
                                pos[0]++;
                                sb.append("? super ").append(parseTypeSignature(sig, pos));
                            } else {
                                sb.append(parseTypeSignature(sig, pos));
                            }
                        }
                        if (pos[0] < sig.length()) pos[0]++; // skip '>'
                        sb.append('>');
                    } else if (ch == '.' || ch == '$') {
                        // Inner class separator - use . but don't mark as package
                        sb.append('.');
                        pos[0]++;
                    } else if (ch == '/') {
                        sb.append('.');
                        lastPkgDot = sb.length() - 1;
                        pos[0]++;
                    } else {
                        sb.append(ch);
                        pos[0]++;
                    }
                }
                // Return simple name: class name after package (preserving inner class dots)
                String fullName = sb.toString();
                if (lastPkgDot >= 0) {
                    return fullName.substring(lastPkgDot + 1);
                }
                return fullName;
                // END_CHANGE: BUG-2026-0042-1
            }
            default:
                pos[0]++;
                return "Object";
        }
    }

    private static String parseTypeParameters(String sig, int[] pos) {
        if (pos[0] >= sig.length() || sig.charAt(pos[0]) != '<') return "";
        pos[0]++; // skip '<'
        StringBuilder sb = new StringBuilder("<");
        boolean first = true;
        while (pos[0] < sig.length() && sig.charAt(pos[0]) != '>') {
            if (!first) sb.append(", ");
            first = false;
            // Parse type parameter: Identifier : ClassBound InterfaceBound*
            int colonPos = sig.indexOf(':', pos[0]);
            String paramName = sig.substring(pos[0], colonPos);
            sb.append(paramName);
            pos[0] = colonPos + 1;
            // Parse class bound (may be empty)
            if (pos[0] < sig.length() && sig.charAt(pos[0]) != ':' && sig.charAt(pos[0]) != '>') {
                String bound = parseTypeSignature(sig, pos);
                if (!"Object".equals(bound) && !"java.lang.Object".equals(bound)) {
                    sb.append(" extends ").append(bound);
                }
            }
            // Parse interface bounds
            boolean hasClassBound = false;
            while (pos[0] < sig.length() && sig.charAt(pos[0]) == ':') {
                pos[0]++;
                String iBound = parseTypeSignature(sig, pos);
                if (!hasClassBound) {
                    // First interface bound after empty/Object class bound: use extends
                    sb.append(" extends ").append(iBound);
                    hasClassBound = true;
                } else {
                    sb.append(" & ").append(iBound);
                }
            }
        }
        if (pos[0] < sig.length()) pos[0]++; // skip '>'
        sb.append('>');
        return sb.toString();
    }

    private static void skipTypeParameters(String sig, int[] pos) {
        if (pos[0] >= sig.length() || sig.charAt(pos[0]) != '<') return;
        int depth = 0;
        while (pos[0] < sig.length()) {
            char c = sig.charAt(pos[0]);
            pos[0]++;
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                if (depth == 0) return;
            }
        }
    }
}
