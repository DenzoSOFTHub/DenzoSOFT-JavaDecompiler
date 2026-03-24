/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.util;

import java.io.UnsupportedEncodingException;

/**
 * Utility for reading binary data from a byte array (big-endian).
 */
public class ByteReader {
    private final byte[] data;
    private int offset;

    public ByteReader(byte[] data) {
        this.data = data;
        this.offset = 0;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public void skip(int count) {
        offset += count;
    }

    public int remaining() {
        return data.length - offset;
    }

    /**
     * Returns true if at least n bytes remain to be read.
     */
    public boolean hasRemaining(int n) {
        return offset + n <= data.length;
    }

    private void checkBounds(int bytesNeeded) {
        if (offset + bytesNeeded > data.length) {
            throw new IndexOutOfBoundsException(
                "ByteReader: attempt to read " + bytesNeeded + " bytes at offset " + offset +
                " but data length is " + data.length);
        }
    }

    public int readUnsignedByte() {
        checkBounds(1);
        return data[offset++] & 0xFF;
    }

    public int readByte() {
        checkBounds(1);
        return data[offset++];
    }

    public int readUnsignedShort() {
        checkBounds(2);
        int value = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        offset += 2;
        return value;
    }

    public short readShort() {
        checkBounds(2);
        short value = (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
        offset += 2;
        return value;
    }

    public int readInt() {
        checkBounds(4);
        int value = ((data[offset] & 0xFF) << 24) |
                    ((data[offset + 1] & 0xFF) << 16) |
                    ((data[offset + 2] & 0xFF) << 8) |
                    (data[offset + 3] & 0xFF);
        offset += 4;
        return value;
    }

    public long readLong() {
        checkBounds(8);
        long value = ((long) (data[offset] & 0xFF) << 56) |
                     ((long) (data[offset + 1] & 0xFF) << 48) |
                     ((long) (data[offset + 2] & 0xFF) << 40) |
                     ((long) (data[offset + 3] & 0xFF) << 32) |
                     ((long) (data[offset + 4] & 0xFF) << 24) |
                     ((long) (data[offset + 5] & 0xFF) << 16) |
                     ((long) (data[offset + 6] & 0xFF) << 8) |
                     ((long) (data[offset + 7] & 0xFF));
        offset += 8;
        return value;
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public byte[] readBytes(int length) {
        checkBounds(length);
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        offset += length;
        return result;
    }

    public String readUTF8(int length) {
        checkBounds(length);
        try {
            String result = new String(data, offset, length, "UTF-8");
            offset += length;
            return result;
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported
            throw new RuntimeException(e);
        }
    }
}
