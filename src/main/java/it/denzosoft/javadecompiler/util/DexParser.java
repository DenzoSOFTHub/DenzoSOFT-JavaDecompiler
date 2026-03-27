/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DEX (Dalvik Executable) parser.
 * Reads class names, method names, and field names from a DEX file.
 * Does NOT decompile Dalvik bytecode — shows structure only.
 */
// START_CHANGE: IMP-2026-0011-20260327-1 - DEX parser for APK support
public final class DexParser {

    private DexParser() {}

    /** Parsed DEX file info */
    public static class DexInfo {
        public int version;
        public int classCount;
        public int methodCount;
        public int fieldCount;
        public int stringCount;
        public List classNames = new ArrayList(); // List<String>
    }

    /**
     * Parse a DEX file and extract class names.
     * @param data raw DEX file bytes
     * @return DexInfo with class names and counts
     */
    public static DexInfo parse(byte[] data) {
        DexInfo info = new DexInfo();
        if (data == null || data.length < 112) return info; // header too small

        // Magic: "dex\n035\0" or "dex\n039\0" etc.
        if (data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n') {
            return info; // not a DEX file
        }

        // Version: bytes 4-6 (ASCII digits)
        info.version = (data[4] - '0') * 100 + (data[5] - '0') * 10 + (data[6] - '0');

        // String IDs count: offset 56 (little-endian)
        info.stringCount = readInt32LE(data, 56);
        int stringIdsOff = readInt32LE(data, 60);

        // Type IDs count: offset 64
        int typeCount = readInt32LE(data, 64);
        int typeIdsOff = readInt32LE(data, 68);

        // Field IDs: offset 80
        info.fieldCount = readInt32LE(data, 80);

        // Method IDs: offset 88
        info.methodCount = readInt32LE(data, 88);

        // Class definitions: offset 96
        info.classCount = readInt32LE(data, 96);
        int classDefsOff = readInt32LE(data, 100);

        // Read class names
        for (int i = 0; i < info.classCount && i < 100000; i++) {
            int classDefOff = classDefsOff + (i * 32);
            if (classDefOff + 4 > data.length) break;

            int classIdx = readInt32LE(data, classDefOff);
            if (classIdx < 0 || classIdx >= typeCount) continue;

            // Type ID → string index
            int typeIdOff = typeIdsOff + (classIdx * 4);
            if (typeIdOff + 4 > data.length) continue;
            int descriptorIdx = readInt32LE(data, typeIdOff);

            // String ID → string data offset
            String className = readDexString(data, descriptorIdx, stringIdsOff);
            if (className != null) {
                // Convert DEX descriptor: Lcom/example/Foo; → com.example.Foo
                if (className.startsWith("L") && className.endsWith(";")) {
                    className = className.substring(1, className.length() - 1).replace('/', '.');
                }
                info.classNames.add(className);
            }
        }

        return info;
    }

    private static String readDexString(byte[] data, int stringIdx, int stringIdsOff) {
        int stringIdOff = stringIdsOff + (stringIdx * 4);
        if (stringIdOff + 4 > data.length) return null;

        int stringDataOff = readInt32LE(data, stringIdOff);
        if (stringDataOff < 0 || stringDataOff >= data.length) return null;

        // MUTF-8 encoded: first byte(s) = length (ULEB128), then UTF-8 data
        int pos = stringDataOff;
        // Read ULEB128 length
        int len = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }

        if (pos + len > data.length) len = data.length - pos;
        if (len <= 0 || len > 10000) return null;

        // Read MUTF-8 string (simplified: treat as UTF-8)
        try {
            return new String(data, pos, len, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static int readInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8) |
               ((data[offset+2] & 0xFF) << 16) | ((data[offset+3] & 0xFF) << 24);
    }

    /**
     * Read all bytes from an InputStream.
     */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
// END_CHANGE: IMP-2026-0011-1
