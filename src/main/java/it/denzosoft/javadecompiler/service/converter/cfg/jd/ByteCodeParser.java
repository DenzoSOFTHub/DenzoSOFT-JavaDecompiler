/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-6 - Ported stack-depth evaluator from JD-Core.
 *
 * The Maker (pass 5) needs to know the stack depth at the end of a block to
 * decide whether a STATEMENTS block's successor GOTO / CONDITIONAL_BRANCH
 * should be coalesced (live stack value => the jump is part of a ternary /
 * short-circuit expression, not a statement boundary).
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;

public final class ByteCodeParser {
    private ByteCodeParser() {}

    public static int evalStackDepth(ConstantPool constants, byte[] code, BasicBlock bb) {
        int depth = 0;
        int toOffset = bb.getToOffset();
        for (int offset = bb.getFromOffset(); offset < toOffset; ++offset) {
            int opcode = code[offset] & 0xFF;
            switch (opcode) {
                // Pushes: aconst_null, const_{i,l,f,d}, iload_N..aload_N, DUP, DUP_X1, DUP_X2
                case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 9:
                case 10: case 11: case 12: case 13: case 14: case 15:
                case 26: case 27: case 28: case 29: case 30: case 31: case 32: case 33:
                case 34: case 35: case 36: case 37: case 38: case 39: case 40:
                case 41: case 42: case 43: case 44: case 45:
                case 89: case 90: case 91:
                    ++depth;
                    break;
                // BIPUSH, SIPUSH, ILOAD..ALOAD (byte index)
                case 16: case 18: case 21: case 22: case 23: case 24: case 25:
                    ++offset;
                    ++depth;
                    break;
                // LDC / LDC_W / LDC2_W, JSR, GETSTATIC, NEW (push one)
                case 17: case 19: case 20: case 168: case 178: case 187:
                    offset += 2;
                    ++depth;
                    break;
                // Pops / binary ops / stores (consume one)
                case 46: case 47: case 48: case 49: case 50: case 51: case 52: case 53:
                case 59: case 60: case 61: case 62: case 63: case 64: case 65: case 66:
                case 67: case 68: case 69: case 70: case 71: case 72: case 73: case 74:
                case 75: case 76: case 77: case 78: case 87:
                case 96: case 97: case 98: case 99: case 100: case 101: case 102: case 103:
                case 104: case 105: case 106: case 107: case 108: case 109: case 110:
                case 111: case 112: case 113: case 114: case 115:
                case 120: case 121: case 122: case 123: case 124: case 125: case 126:
                case 127: case 128: case 129: case 130: case 131:
                case 148: case 149: case 150: case 151: case 152:
                case 172: case 173: case 174: case 175: case 176:
                case 194: case 195:
                    --depth;
                    break;
                // Conditional branches comparing against 0 + PUTSTATIC
                case 153: case 154: case 155: case 156: case 157: case 158:
                case 179: case 198: case 199:
                    offset += 2;
                    --depth;
                    break;
                // ISTORE..ASTORE with byte index
                case 54: case 55: case 56: case 57: case 58:
                    ++offset;
                    --depth;
                    break;
                // Array stores
                case 79: case 80: case 81: case 82: case 83: case 84: case 85: case 86:
                    depth -= 3;
                    break;
                // DUP2 / DUP2_X1 / DUP2_X2
                case 92: case 93: case 94:
                    depth += 2;
                    break;
                // IINC (no stack change), GOTO (no stack change), GETFIELD, ANEWARRAY,
                // CHECKCAST, INSTANCEOF
                case 132: case 167: case 180: case 189: case 192: case 193:
                    offset += 2;
                    break;
                // Binary compares (IF_ICMP_*, PUTFIELD): pop 2
                case 159: case 160: case 161: case 162: case 163: case 164: case 165:
                case 166: case 181:
                    offset += 2;
                    depth -= 2;
                    break;
                // POP2
                case 88:
                    depth -= 2;
                    break;
                // RET (1-byte local index), NEWARRAY (1-byte type)
                case 169: case 188:
                    ++offset;
                    break;
                // TABLESWITCH
                case 170: {
                    offset = (offset + 4) & 0xFFFC;
                    offset += 4;
                    int low = ((code[offset++] & 0xFF) << 24) | ((code[offset++] & 0xFF) << 16) | ((code[offset++] & 0xFF) << 8) | (code[offset++] & 0xFF);
                    int high = ((code[offset++] & 0xFF) << 24) | ((code[offset++] & 0xFF) << 16) | ((code[offset++] & 0xFF) << 8) | (code[offset++] & 0xFF);
                    offset += 4 * (high - low + 1) - 1;
                    --depth;
                    break;
                }
                // LOOKUPSWITCH
                case 171: {
                    offset = (offset + 4) & 0xFFFC;
                    offset += 4;
                    int count = ((code[offset++] & 0xFF) << 24) | ((code[offset++] & 0xFF) << 16) | ((code[offset++] & 0xFF) << 8) | (code[offset++] & 0xFF);
                    offset += 8 * count - 1;
                    --depth;
                    break;
                }
                // INVOKEVIRTUAL / INVOKESPECIAL
                case 182: case 183: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String d = constants.getMemberDescriptor(idx);
                    if (d == null) { --depth; break; }
                    depth -= 1 + countMethodParameters(d);
                    if (d.charAt(d.length() - 1) != 'V') ++depth;
                    break;
                }
                // INVOKESTATIC
                case 184: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String d = constants.getMemberDescriptor(idx);
                    if (d == null) break;
                    depth -= countMethodParameters(d);
                    if (d.charAt(d.length() - 1) != 'V') ++depth;
                    break;
                }
                // INVOKEINTERFACE
                case 185: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String d = constants.getMemberDescriptor(idx);
                    offset += 2;
                    if (d == null) { --depth; break; }
                    depth -= 1 + countMethodParameters(d);
                    if (d.charAt(d.length() - 1) != 'V') ++depth;
                    break;
                }
                // INVOKEDYNAMIC
                case 186: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String d = constants.getMemberDescriptor(idx);
                    offset += 2;
                    if (d == null) break;
                    depth -= countMethodParameters(d);
                    if (d.charAt(d.length() - 1) != 'V') ++depth;
                    break;
                }
                // WIDE
                case 196: {
                    int op2 = code[++offset] & 0xFF;
                    if (op2 == 132) { // WIDE IINC
                        offset += 4;
                        break;
                    }
                    offset += 2;
                    if (op2 >= 21 && op2 <= 25) ++depth;
                    else if (op2 >= 54 && op2 <= 58) --depth;
                    break;
                }
                // MULTIANEWARRAY: 1 - dimensions per JD-Core formula
                case 197:
                    depth += 1 - (code[offset += 3] & 0xFF);
                    break;
                // JSR_W
                case 201:
                    offset += 4;
                    ++depth;
                    break;
                // GOTO_W
                case 200:
                    offset += 4;
                    break;
                // NOP and all other instructions with no stack effect: no-op
                default:
                    break;
            }
        }
        return depth;
    }

    /**
     * Count method parameters from a descriptor like "(ILjava/lang/String;[I)V".
     * Array dims and `L...;` object refs are collapsed to one parameter each.
     */
    private static int countMethodParameters(String descriptor) {
        int count = 0;
        int i = 2;
        if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != '(') return 0;
        char c = descriptor.charAt(1);
        while (c != ')') {
            while (c == '[') {
                c = descriptor.charAt(i++);
            }
            if (c == 'L') {
                while ((c = descriptor.charAt(i++)) != ';') { /* skip class name */ }
            }
            c = descriptor.charAt(i++);
            ++count;
        }
        return count;
    }
}
