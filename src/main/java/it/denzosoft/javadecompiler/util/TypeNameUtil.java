/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.util;

/**
 * Utilities for converting between internal and source-level type names.
 */
public final class TypeNameUtil {

    private TypeNameUtil() {}

    /**
     * Convert internal name (e.g., "java/lang/String") to qualified name (e.g., "java.lang.String").
     */
    public static String internalToQualified(String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * Convert qualified name to internal name.
     */
    public static String qualifiedToInternal(String qualifiedName) {
        return qualifiedName.replace('.', '/');
    }

    /**
     * Get the simple class name from an internal name.
     */
    public static String simpleNameFromInternal(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        int lastDollar = internalName.lastIndexOf('$');
        int start = Math.max(lastSlash, lastDollar) + 1;
        return internalName.substring(start);
    }

    /**
     * Get the package name from an internal name.
     */
    public static String packageFromInternal(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return internalName.substring(0, lastSlash).replace('/', '.');
    }

    /**
     * Check if the internal name represents an inner class.
     */
    public static boolean isInnerClass(String internalName) {
        return internalName.indexOf('$') >= 0;
    }

    /**
     * Get the outer class internal name from an inner class name.
     */
    public static String outerClassFromInternal(String internalName) {
        int dollar = internalName.lastIndexOf('$');
        if (dollar < 0) return null;
        return internalName.substring(0, dollar);
    }

    /**
     * Convert a field descriptor to a human-readable type name.
     * Examples: "I" -> "int", "Ljava/lang/String;" -> "String", "[I" -> "int[]"
     */
    public static String descriptorToTypeName(String descriptor) {
        return descriptorToTypeName(descriptor, true);
    }

    public static String descriptorToTypeName(String descriptor, boolean simpleName) {
        if (descriptor == null || descriptor.isEmpty()) return "void";

        int arrayDimension = 0;
        int index = 0;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') {
            arrayDimension++;
            index++;
        }

        String baseType;
        if (index >= descriptor.length()) {
            baseType = "unknown";
        } else {
            char c = descriptor.charAt(index);
            switch (c) {
                case 'B': baseType = "byte"; break;
                case 'C': baseType = "char"; break;
                case 'D': baseType = "double"; break;
                case 'F': baseType = "float"; break;
                case 'I': baseType = "int"; break;
                case 'J': baseType = "long"; break;
                case 'S': baseType = "short"; break;
                case 'Z': baseType = "boolean"; break;
                case 'V': baseType = "void"; break;
                case 'L': {
                    int semi = descriptor.indexOf(';', index);
                    String internalName = descriptor.substring(index + 1, semi);
                    baseType = simpleName ? simpleNameFromInternal(internalName) : internalToQualified(internalName);
                    break;
                }
                default: baseType = "unknown"; break;
            }
        }

        if (arrayDimension > 0) {
            StringBuilder sb = new StringBuilder(baseType);
            for (int i = 0; i < arrayDimension; i++) {
                sb.append("[]");
            }
            return sb.toString();
        }
        return baseType;
    }

    /**
     * Parse a method descriptor and return parameter type descriptors.
     */
    public static String[] parseMethodParameterDescriptors(String methodDescriptor) {
        if (methodDescriptor == null || !methodDescriptor.startsWith("(")) return new String[0];

        java.util.List<String> params = new java.util.ArrayList<String>();
        int index = 1; // skip '('
        while (index < methodDescriptor.length() && methodDescriptor.charAt(index) != ')') {
            int start = index;
            while (methodDescriptor.charAt(index) == '[') index++;
            if (methodDescriptor.charAt(index) == 'L') {
                index = methodDescriptor.indexOf(';', index) + 1;
            } else {
                index++;
            }
            params.add(methodDescriptor.substring(start, index));
        }
        return params.toArray(new String[0]);
    }

    /**
     * Parse a method descriptor and return the return type descriptor.
     */
    public static String parseMethodReturnDescriptor(String methodDescriptor) {
        if (methodDescriptor == null) return "V";
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0) return "V";
        return methodDescriptor.substring(closeParen + 1);
    }
}
