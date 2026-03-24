/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.util;

/**
 * Shared utility for bytecode opcode classification and operand size computation.
 * Consolidates duplicated logic from ControlFlowGraph and ClassFileToJavaSyntaxConverter.
 */
public final class OpcodeInfo {

    private OpcodeInfo() {}

    public static boolean isBranch(int opcode) {
        return isConditionalBranch(opcode) || opcode == 0xA7 || opcode == 0xC8;
    }

    public static boolean isConditionalBranch(int opcode) {
        return (opcode >= 0x99 && opcode <= 0xA6) || opcode == 0xC6 || opcode == 0xC7;
    }

    public static boolean isReturn(int opcode) {
        return opcode >= 0xAC && opcode <= 0xB1;
    }

    public static boolean isThrow(int opcode) {
        return opcode == 0xBF;
    }

    /**
     * Compute the number of operand bytes that follow the given opcode at the
     * specified position in {@code bytecode}.  Variable-length instructions
     * (tableswitch, lookupswitch, wide) are handled by reading from the
     * bytecode array directly.
     *
     * @param opcode   the opcode value (0x00 .. 0xFF)
     * @param bytecode the full method bytecode
     * @param pc       the position of the opcode byte in {@code bytecode}
     * @return the number of bytes to skip after the opcode byte
     */
    public static int operandSize(int opcode, byte[] bytecode, int pc) {
        // No operands
        if (opcode <= 0x0F) return 0;
        if (opcode >= 0x1A && opcode <= 0x35) return 0;
        if (opcode >= 0x3B && opcode <= 0x83) return 0;
        if (opcode >= 0x85 && opcode <= 0x98) return 0;
        if (opcode >= 0xAC && opcode <= 0xB1) return 0;
        if (opcode == 0xBE || opcode == 0xBF) return 0;
        if (opcode == 0xC2 || opcode == 0xC3) return 0;

        // 1-byte operand
        if (opcode >= 0x15 && opcode <= 0x19) return 1; // iload..aload
        if (opcode >= 0x36 && opcode <= 0x3A) return 1; // istore..astore
        if (opcode == 0x10) return 1; // bipush
        if (opcode == 0xBC) return 1; // newarray
        if (opcode == 0x12) return 1; // ldc
        if (opcode == 0xA9) return 1; // ret

        // 2-byte operand
        if (opcode == 0x11) return 2; // sipush
        if (opcode == 0x13 || opcode == 0x14) return 2; // ldc_w, ldc2_w
        if (opcode >= 0x99 && opcode <= 0xA8) return 2; // if*, goto, jsr
        if (opcode == 0xB2 || opcode == 0xB3 || opcode == 0xB4 || opcode == 0xB5) return 2; // get/putstatic, get/putfield
        if (opcode == 0xB6 || opcode == 0xB7 || opcode == 0xB8) return 2; // invokevirtual, invokespecial, invokestatic
        if (opcode == 0xBB || opcode == 0xBD) return 2; // new, anewarray
        if (opcode == 0xC0 || opcode == 0xC1) return 2; // checkcast, instanceof
        if (opcode == 0xC6 || opcode == 0xC7) return 2; // ifnull, ifnonnull

        // iinc: 2 bytes (index + const)
        if (opcode == 0x84) return 2;

        // multianewarray: 3 bytes
        if (opcode == 0xC5) return 3;

        // 4-byte operand
        if (opcode == 0xB9) return 4; // invokeinterface
        if (opcode == 0xBA) return 4; // invokedynamic
        if (opcode == 0xC8 || opcode == 0xC9) return 4; // goto_w, jsr_w

        // wide
        if (opcode == 0xC4) {
            if (pc + 1 < bytecode.length) {
                int wideOp = bytecode[pc + 1] & 0xFF;
                if (wideOp == 0x84) {
                    return 5; // wide iinc: sub-opcode(1) + index(2) + const(2)
                }
                return 3; // wide load/store: sub-opcode(1) + index(2)
            }
            return 1;
        }

        // tableswitch
        if (opcode == 0xAA) {
            int afterOpcode = pc + 1;
            int pad = (4 - (afterOpcode % 4)) % 4;
            int pos = afterOpcode + pad;
            // default(4) + low(4) + high(4) then (high-low+1)*4
            if (pos + 12 <= bytecode.length) {
                int low = getInt(bytecode, pos + 4);
                int high = getInt(bytecode, pos + 8);
                int count = high - low + 1;
                return pad + 12 + count * 4;
            }
            return pad + 12;
        }

        // lookupswitch
        if (opcode == 0xAB) {
            int afterOpcode = pc + 1;
            int pad = (4 - (afterOpcode % 4)) % 4;
            int pos = afterOpcode + pad;
            // default(4) + npairs(4) then npairs*8
            if (pos + 8 <= bytecode.length) {
                int npairs = getInt(bytecode, pos + 4);
                return pad + 8 + npairs * 8;
            }
            return pad + 8;
        }

        return 0;
    }

    private static int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }
}
