/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.util;

/**
 * Common string constants used throughout the decompiler.
 */
public final class StringConstants {

    private StringConstants() {}

    public static final String JAVA_LANG_OBJECT = "java/lang/Object";
    public static final String JAVA_LANG_STRING = "java/lang/String";
    public static final String JAVA_LANG_CLASS = "java/lang/Class";
    public static final String JAVA_LANG_ENUM = "java/lang/Enum";
    public static final String JAVA_LANG_RECORD = "java/lang/Record";
    public static final String JAVA_LANG_ANNOTATION = "java/lang/annotation/Annotation";
    public static final String JAVA_LANG_THROWABLE = "java/lang/Throwable";
    public static final String JAVA_LANG_EXCEPTION = "java/lang/Exception";
    public static final String JAVA_LANG_RUNTIME_EXCEPTION = "java/lang/RuntimeException";
    public static final String JAVA_LANG_ITERABLE = "java/lang/Iterable";
    public static final String JAVA_LANG_AUTO_CLOSEABLE = "java/lang/AutoCloseable";
    public static final String JAVA_IO_SERIALIZABLE = "java/io/Serializable";

    public static final String CONSTRUCTOR_NAME = "<init>";
    public static final String CLASS_INIT_NAME = "<clinit>";

    // Access flags
    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_PRIVATE = 0x0002;
    public static final int ACC_PROTECTED = 0x0004;
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_FINAL = 0x0010;
    public static final int ACC_SUPER = 0x0020;
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_VOLATILE = 0x0040;
    public static final int ACC_BRIDGE = 0x0040;
    public static final int ACC_TRANSIENT = 0x0080;
    public static final int ACC_VARARGS = 0x0080;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_STRICT = 0x0800;
    public static final int ACC_SYNTHETIC = 0x1000;
    public static final int ACC_ANNOTATION = 0x2000;
    public static final int ACC_ENUM = 0x4000;
    public static final int ACC_MODULE = 0x8000;
    public static final int ACC_SEALED = 0x0200; // reused for sealed classes (preview)
    public static final int ACC_RECORD = 0x0010; // identified contextually

    // Class file magic number
    public static final int MAGIC = 0xCAFEBABE;

    // Java version to class file major version mapping
    public static final int MAJOR_1_0 = 45;
    public static final int MAJOR_1_1 = 45;
    public static final int MAJOR_1_2 = 46;
    public static final int MAJOR_1_3 = 47;
    public static final int MAJOR_1_4 = 48;
    public static final int MAJOR_5 = 49;
    public static final int MAJOR_6 = 50;
    public static final int MAJOR_7 = 51;
    public static final int MAJOR_8 = 52;
    public static final int MAJOR_9 = 53;
    public static final int MAJOR_10 = 54;
    public static final int MAJOR_11 = 55;
    public static final int MAJOR_12 = 56;
    public static final int MAJOR_13 = 57;
    public static final int MAJOR_14 = 58;
    public static final int MAJOR_15 = 59;
    public static final int MAJOR_16 = 60;
    public static final int MAJOR_17 = 61;
    public static final int MAJOR_18 = 62;
    public static final int MAJOR_19 = 63;
    public static final int MAJOR_20 = 64;
    public static final int MAJOR_21 = 65;
    public static final int MAJOR_22 = 66;
    public static final int MAJOR_23 = 67;
    public static final int MAJOR_24 = 68;
    public static final int MAJOR_25 = 69;

    public static final int MAX_SUPPORTED_MAJOR_VERSION = MAJOR_25;

    /**
     * Get the Java version name for a given major class file version.
     */
    public static String javaVersionFromMajor(int majorVersion) {
        if (majorVersion <= 45) return "1.0/1.1";
        if (majorVersion <= 48) return "1." + (majorVersion - 44);
        return String.valueOf(majorVersion - 44);
    }
}
