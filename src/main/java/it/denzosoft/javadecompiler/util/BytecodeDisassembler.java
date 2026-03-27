/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.util;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.LineNumberTableAttribute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disassembles bytecode into human-readable instruction strings with Java-level explanations.
 * Groups instructions by source line number using the LineNumberTable.
 */
// START_CHANGE: IMP-2026-0008-20260327-1 - Bytecode disassembler for --show-bytecode
public final class BytecodeDisassembler {

    private BytecodeDisassembler() {}

    /**
     * Disassemble bytecode and group by source line number.
     * @return map from source line number to list of "pc: opcode operands  // explanation" strings
     */
    public static Map<Integer, List<String>> disassemble(byte[] bytecode, ConstantPool pool,
            LineNumberTableAttribute lnt, Map<Integer, String> localVarNames) {
        Map<Integer, List<String>> result = new HashMap<Integer, List<String>>();
        if (bytecode == null || bytecode.length == 0) return result;

        // Build PC -> line number map
        Map<Integer, Integer> pcToLine = new HashMap<Integer, Integer>();
        if (lnt != null) {
            for (LineNumberTableAttribute.LineNumber ln : lnt.getLineNumbers()) {
                pcToLine.put(ln.startPc, ln.lineNumber);
            }
        }

        int currentLine = 0;
        int pc = 0;
        while (pc < bytecode.length) {
            // Update current line from LineNumberTable
            Integer lineAtPc = pcToLine.get(pc);
            if (lineAtPc != null) {
                currentLine = lineAtPc.intValue();
            }

            int startPc = pc;
            int opcode = bytecode[pc] & 0xFF;
            String[] decoded = decodeInstruction(bytecode, pc, pool, localVarNames);
            String mnemonic = decoded[0];
            String explanation = decoded[1];
            int nextPc = Integer.parseInt(decoded[2]);

            String line = String.valueOf(startPc) + ": " + mnemonic;
            if (explanation.length() > 0) {
                line = line + "  // " + explanation;
            }

            if (currentLine > 0) {
                List<String> list = result.get(currentLine);
                if (list == null) {
                    list = new ArrayList<String>();
                    result.put(currentLine, list);
                }
                list.add(line);
            }

            pc = nextPc;
        }

        return result;
    }

    /**
     * Decode a single instruction.
     * @return [mnemonic+operands, explanation, nextPc]
     */
    private static String[] decodeInstruction(byte[] code, int pc, ConstantPool pool,
            Map<Integer, String> localVarNames) {
        int op = code[pc] & 0xFF;
        int next = pc + 1;
        String mnemonic;
        String explain = "";

        switch (op) {
            // Constants
            case 0x00: mnemonic = "nop"; break;
            case 0x01: mnemonic = "aconst_null"; explain = "push null"; break;
            case 0x02: mnemonic = "iconst_m1"; explain = "push int -1"; break;
            case 0x03: mnemonic = "iconst_0"; explain = "push int 0"; break;
            case 0x04: mnemonic = "iconst_1"; explain = "push int 1"; break;
            case 0x05: mnemonic = "iconst_2"; explain = "push int 2"; break;
            case 0x06: mnemonic = "iconst_3"; explain = "push int 3"; break;
            case 0x07: mnemonic = "iconst_4"; explain = "push int 4"; break;
            case 0x08: mnemonic = "iconst_5"; explain = "push int 5"; break;
            case 0x09: mnemonic = "lconst_0"; explain = "push long 0"; break;
            case 0x0A: mnemonic = "lconst_1"; explain = "push long 1"; break;
            case 0x0B: mnemonic = "fconst_0"; explain = "push float 0.0"; break;
            case 0x0C: mnemonic = "fconst_1"; explain = "push float 1.0"; break;
            case 0x0D: mnemonic = "fconst_2"; explain = "push float 2.0"; break;
            case 0x0E: mnemonic = "dconst_0"; explain = "push double 0.0"; break;
            case 0x0F: mnemonic = "dconst_1"; explain = "push double 1.0"; break;
            case 0x10: { // bipush
                int val = code[pc + 1];
                mnemonic = "bipush " + val;
                explain = "push byte " + val;
                next = pc + 2;
                break;
            }
            case 0x11: { // sipush
                int val = (short)((code[pc+1] & 0xFF) << 8 | (code[pc+2] & 0xFF));
                mnemonic = "sipush " + val;
                explain = "push short " + val;
                next = pc + 3;
                break;
            }
            case 0x12: { // ldc
                int idx = code[pc+1] & 0xFF;
                mnemonic = "ldc #" + idx;
                explain = "push constant " + poolString(pool, idx);
                next = pc + 2;
                break;
            }
            case 0x13: case 0x14: { // ldc_w, ldc2_w
                int idx = readU2(code, pc+1);
                mnemonic = (op == 0x13 ? "ldc_w" : "ldc2_w") + " #" + idx;
                explain = "push constant " + poolString(pool, idx);
                next = pc + 3;
                break;
            }

            // Loads
            case 0x15: { int idx = code[pc+1] & 0xFF; mnemonic = "iload " + idx; explain = "push int " + varName(localVarNames, idx); next = pc+2; break; }
            case 0x16: { int idx = code[pc+1] & 0xFF; mnemonic = "lload " + idx; explain = "push long " + varName(localVarNames, idx); next = pc+2; break; }
            case 0x17: { int idx = code[pc+1] & 0xFF; mnemonic = "fload " + idx; explain = "push float " + varName(localVarNames, idx); next = pc+2; break; }
            case 0x18: { int idx = code[pc+1] & 0xFF; mnemonic = "dload " + idx; explain = "push double " + varName(localVarNames, idx); next = pc+2; break; }
            case 0x19: { int idx = code[pc+1] & 0xFF; mnemonic = "aload " + idx; explain = "push ref " + varName(localVarNames, idx); next = pc+2; break; }
            case 0x1A: mnemonic = "iload_0"; explain = "push int " + varName(localVarNames, 0); break;
            case 0x1B: mnemonic = "iload_1"; explain = "push int " + varName(localVarNames, 1); break;
            case 0x1C: mnemonic = "iload_2"; explain = "push int " + varName(localVarNames, 2); break;
            case 0x1D: mnemonic = "iload_3"; explain = "push int " + varName(localVarNames, 3); break;
            case 0x1E: mnemonic = "lload_0"; explain = "push long " + varName(localVarNames, 0); break;
            case 0x1F: mnemonic = "lload_1"; explain = "push long " + varName(localVarNames, 1); break;
            case 0x20: mnemonic = "lload_2"; explain = "push long " + varName(localVarNames, 2); break;
            case 0x21: mnemonic = "lload_3"; explain = "push long " + varName(localVarNames, 3); break;
            case 0x22: mnemonic = "fload_0"; explain = "push float " + varName(localVarNames, 0); break;
            case 0x23: mnemonic = "fload_1"; explain = "push float " + varName(localVarNames, 1); break;
            case 0x24: mnemonic = "fload_2"; explain = "push float " + varName(localVarNames, 2); break;
            case 0x25: mnemonic = "fload_3"; explain = "push float " + varName(localVarNames, 3); break;
            case 0x26: mnemonic = "dload_0"; explain = "push double " + varName(localVarNames, 0); break;
            case 0x27: mnemonic = "dload_1"; explain = "push double " + varName(localVarNames, 1); break;
            case 0x28: mnemonic = "dload_2"; explain = "push double " + varName(localVarNames, 2); break;
            case 0x29: mnemonic = "dload_3"; explain = "push double " + varName(localVarNames, 3); break;
            case 0x2A: mnemonic = "aload_0"; explain = "push ref this"; break;
            case 0x2B: mnemonic = "aload_1"; explain = "push ref " + varName(localVarNames, 1); break;
            case 0x2C: mnemonic = "aload_2"; explain = "push ref " + varName(localVarNames, 2); break;
            case 0x2D: mnemonic = "aload_3"; explain = "push ref " + varName(localVarNames, 3); break;

            // Array loads
            case 0x2E: mnemonic = "iaload"; explain = "push array[index] (int)"; break;
            case 0x2F: mnemonic = "laload"; explain = "push array[index] (long)"; break;
            case 0x30: mnemonic = "faload"; explain = "push array[index] (float)"; break;
            case 0x31: mnemonic = "daload"; explain = "push array[index] (double)"; break;
            case 0x32: mnemonic = "aaload"; explain = "push array[index] (ref)"; break;
            case 0x33: mnemonic = "baload"; explain = "push array[index] (byte)"; break;
            case 0x34: mnemonic = "caload"; explain = "push array[index] (char)"; break;
            case 0x35: mnemonic = "saload"; explain = "push array[index] (short)"; break;

            // Stores
            case 0x36: { int idx = code[pc+1] & 0xFF; mnemonic = "istore " + idx; explain = varName(localVarNames, idx) + " = pop (int)"; next = pc+2; break; }
            case 0x37: { int idx = code[pc+1] & 0xFF; mnemonic = "lstore " + idx; explain = varName(localVarNames, idx) + " = pop (long)"; next = pc+2; break; }
            case 0x38: { int idx = code[pc+1] & 0xFF; mnemonic = "fstore " + idx; explain = varName(localVarNames, idx) + " = pop (float)"; next = pc+2; break; }
            case 0x39: { int idx = code[pc+1] & 0xFF; mnemonic = "dstore " + idx; explain = varName(localVarNames, idx) + " = pop (double)"; next = pc+2; break; }
            case 0x3A: { int idx = code[pc+1] & 0xFF; mnemonic = "astore " + idx; explain = varName(localVarNames, idx) + " = pop (ref)"; next = pc+2; break; }
            case 0x3B: mnemonic = "istore_0"; explain = varName(localVarNames, 0) + " = pop (int)"; break;
            case 0x3C: mnemonic = "istore_1"; explain = varName(localVarNames, 1) + " = pop (int)"; break;
            case 0x3D: mnemonic = "istore_2"; explain = varName(localVarNames, 2) + " = pop (int)"; break;
            case 0x3E: mnemonic = "istore_3"; explain = varName(localVarNames, 3) + " = pop (int)"; break;
            case 0x3F: mnemonic = "lstore_0"; explain = varName(localVarNames, 0) + " = pop (long)"; break;
            case 0x40: mnemonic = "lstore_1"; explain = varName(localVarNames, 1) + " = pop (long)"; break;
            case 0x41: mnemonic = "lstore_2"; explain = varName(localVarNames, 2) + " = pop (long)"; break;
            case 0x42: mnemonic = "lstore_3"; explain = varName(localVarNames, 3) + " = pop (long)"; break;
            case 0x43: mnemonic = "fstore_0"; explain = varName(localVarNames, 0) + " = pop (float)"; break;
            case 0x44: mnemonic = "fstore_1"; explain = varName(localVarNames, 1) + " = pop (float)"; break;
            case 0x45: mnemonic = "fstore_2"; explain = varName(localVarNames, 2) + " = pop (float)"; break;
            case 0x46: mnemonic = "fstore_3"; explain = varName(localVarNames, 3) + " = pop (float)"; break;
            case 0x47: mnemonic = "dstore_0"; explain = varName(localVarNames, 0) + " = pop (double)"; break;
            case 0x48: mnemonic = "dstore_1"; explain = varName(localVarNames, 1) + " = pop (double)"; break;
            case 0x49: mnemonic = "dstore_2"; explain = varName(localVarNames, 2) + " = pop (double)"; break;
            case 0x4A: mnemonic = "dstore_3"; explain = varName(localVarNames, 3) + " = pop (double)"; break;
            case 0x4B: mnemonic = "astore_0"; explain = varName(localVarNames, 0) + " = pop (ref)"; break;
            case 0x4C: mnemonic = "astore_1"; explain = varName(localVarNames, 1) + " = pop (ref)"; break;
            case 0x4D: mnemonic = "astore_2"; explain = varName(localVarNames, 2) + " = pop (ref)"; break;
            case 0x4E: mnemonic = "astore_3"; explain = varName(localVarNames, 3) + " = pop (ref)"; break;

            // Stack
            case 0x57: mnemonic = "pop"; explain = "discard top"; break;
            case 0x58: mnemonic = "pop2"; explain = "discard top 2"; break;
            case 0x59: mnemonic = "dup"; explain = "duplicate top"; break;
            case 0x5A: mnemonic = "dup_x1"; explain = "dup and insert below 2nd"; break;
            case 0x5B: mnemonic = "dup_x2"; explain = "dup and insert below 3rd"; break;
            case 0x5C: mnemonic = "dup2"; explain = "duplicate top 2"; break;
            case 0x5F: mnemonic = "swap"; explain = "swap top two"; break;

            // Arithmetic
            case 0x60: mnemonic = "iadd"; explain = "int add"; break;
            case 0x61: mnemonic = "ladd"; explain = "long add"; break;
            case 0x62: mnemonic = "fadd"; explain = "float add"; break;
            case 0x63: mnemonic = "dadd"; explain = "double add"; break;
            case 0x64: mnemonic = "isub"; explain = "int subtract"; break;
            case 0x65: mnemonic = "lsub"; explain = "long subtract"; break;
            case 0x66: mnemonic = "fsub"; explain = "float subtract"; break;
            case 0x67: mnemonic = "dsub"; explain = "double subtract"; break;
            case 0x68: mnemonic = "imul"; explain = "int multiply"; break;
            case 0x69: mnemonic = "lmul"; explain = "long multiply"; break;
            case 0x6A: mnemonic = "fmul"; explain = "float multiply"; break;
            case 0x6B: mnemonic = "dmul"; explain = "double multiply"; break;
            case 0x6C: mnemonic = "idiv"; explain = "int divide"; break;
            case 0x6D: mnemonic = "ldiv"; explain = "long divide"; break;
            case 0x6E: mnemonic = "fdiv"; explain = "float divide"; break;
            case 0x6F: mnemonic = "ddiv"; explain = "double divide"; break;
            case 0x70: mnemonic = "irem"; explain = "int remainder"; break;
            case 0x71: mnemonic = "lrem"; explain = "long remainder"; break;
            case 0x74: mnemonic = "ineg"; explain = "int negate"; break;
            case 0x78: mnemonic = "ishl"; explain = "int shift left"; break;
            case 0x7A: mnemonic = "ishr"; explain = "int shift right"; break;
            case 0x7C: mnemonic = "iushr"; explain = "int unsigned shift right"; break;
            case 0x7E: mnemonic = "iand"; explain = "int bitwise AND"; break;
            case 0x80: mnemonic = "ior"; explain = "int bitwise OR"; break;
            case 0x82: mnemonic = "ixor"; explain = "int bitwise XOR"; break;
            case 0x84: { // iinc
                int idx = code[pc+1] & 0xFF;
                int inc = code[pc+2];
                mnemonic = "iinc " + idx + " " + inc;
                explain = varName(localVarNames, idx) + " += " + inc;
                next = pc + 3;
                break;
            }

            // Conversions
            case 0x85: mnemonic = "i2l"; explain = "int -> long"; break;
            case 0x86: mnemonic = "i2f"; explain = "int -> float"; break;
            case 0x87: mnemonic = "i2d"; explain = "int -> double"; break;
            case 0x88: mnemonic = "l2i"; explain = "long -> int"; break;
            case 0x8B: mnemonic = "f2i"; explain = "float -> int"; break;
            case 0x8E: mnemonic = "d2i"; explain = "double -> int"; break;
            case 0x91: mnemonic = "i2b"; explain = "int -> byte"; break;
            case 0x92: mnemonic = "i2c"; explain = "int -> char"; break;
            case 0x93: mnemonic = "i2s"; explain = "int -> short"; break;

            // Comparisons
            case 0x94: mnemonic = "lcmp"; explain = "compare long"; break;
            case 0x95: mnemonic = "fcmpl"; explain = "compare float (<)"; break;
            case 0x96: mnemonic = "fcmpg"; explain = "compare float (>)"; break;
            case 0x97: mnemonic = "dcmpl"; explain = "compare double (<)"; break;
            case 0x98: mnemonic = "dcmpg"; explain = "compare double (>)"; break;

            // Branches
            case 0x99: { int off = readS2(code, pc+1); mnemonic = "ifeq " + (pc+off); explain = "if == 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9A: { int off = readS2(code, pc+1); mnemonic = "ifne " + (pc+off); explain = "if != 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9B: { int off = readS2(code, pc+1); mnemonic = "iflt " + (pc+off); explain = "if < 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9C: { int off = readS2(code, pc+1); mnemonic = "ifge " + (pc+off); explain = "if >= 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9D: { int off = readS2(code, pc+1); mnemonic = "ifgt " + (pc+off); explain = "if > 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9E: { int off = readS2(code, pc+1); mnemonic = "ifle " + (pc+off); explain = "if <= 0 goto " + (pc+off); next = pc+3; break; }
            case 0x9F: { int off = readS2(code, pc+1); mnemonic = "if_icmpeq " + (pc+off); explain = "if int == goto " + (pc+off); next = pc+3; break; }
            case 0xA0: { int off = readS2(code, pc+1); mnemonic = "if_icmpne " + (pc+off); explain = "if int != goto " + (pc+off); next = pc+3; break; }
            case 0xA1: { int off = readS2(code, pc+1); mnemonic = "if_icmplt " + (pc+off); explain = "if int < goto " + (pc+off); next = pc+3; break; }
            case 0xA2: { int off = readS2(code, pc+1); mnemonic = "if_icmpge " + (pc+off); explain = "if int >= goto " + (pc+off); next = pc+3; break; }
            case 0xA3: { int off = readS2(code, pc+1); mnemonic = "if_icmpgt " + (pc+off); explain = "if int > goto " + (pc+off); next = pc+3; break; }
            case 0xA4: { int off = readS2(code, pc+1); mnemonic = "if_icmple " + (pc+off); explain = "if int <= goto " + (pc+off); next = pc+3; break; }
            case 0xA5: { int off = readS2(code, pc+1); mnemonic = "if_acmpeq " + (pc+off); explain = "if ref == goto " + (pc+off); next = pc+3; break; }
            case 0xA6: { int off = readS2(code, pc+1); mnemonic = "if_acmpne " + (pc+off); explain = "if ref != goto " + (pc+off); next = pc+3; break; }
            case 0xA7: { int off = readS2(code, pc+1); mnemonic = "goto " + (pc+off); explain = "jump to " + (pc+off); next = pc+3; break; }
            case 0xC6: { int off = readS2(code, pc+1); mnemonic = "ifnull " + (pc+off); explain = "if null goto " + (pc+off); next = pc+3; break; }
            case 0xC7: { int off = readS2(code, pc+1); mnemonic = "ifnonnull " + (pc+off); explain = "if not null goto " + (pc+off); next = pc+3; break; }

            // Switch
            case 0xAA: { // tableswitch
                int pad = (4 - ((pc + 1) % 4)) % 4;
                mnemonic = "tableswitch";
                explain = "switch (table)";
                next = pc + 1 + pad + 12; // minimum: default + low + high + 1 target
                // Skip past the entire table
                int base = pc + 1 + pad;
                if (base + 8 <= code.length) {
                    int low = readS4(code, base + 4);
                    int high = readS4(code, base + 8);
                    int count = high - low + 1;
                    next = base + 12 + count * 4;
                }
                break;
            }
            case 0xAB: { // lookupswitch
                int pad = (4 - ((pc + 1) % 4)) % 4;
                mnemonic = "lookupswitch";
                explain = "switch (lookup)";
                int base = pc + 1 + pad;
                if (base + 4 <= code.length) {
                    int npairs = readS4(code, base + 4);
                    next = base + 8 + npairs * 8;
                } else {
                    next = code.length;
                }
                break;
            }

            // Returns
            case 0xAC: mnemonic = "ireturn"; explain = "return int"; break;
            case 0xAD: mnemonic = "lreturn"; explain = "return long"; break;
            case 0xAE: mnemonic = "freturn"; explain = "return float"; break;
            case 0xAF: mnemonic = "dreturn"; explain = "return double"; break;
            case 0xB0: mnemonic = "areturn"; explain = "return ref"; break;
            case 0xB1: mnemonic = "return"; explain = "return void"; break;

            // Field access
            case 0xB2: { int idx = readU2(code, pc+1); mnemonic = "getstatic #" + idx; explain = "get static " + memberString(pool, idx); next = pc+3; break; }
            case 0xB3: { int idx = readU2(code, pc+1); mnemonic = "putstatic #" + idx; explain = "set static " + memberString(pool, idx); next = pc+3; break; }
            case 0xB4: { int idx = readU2(code, pc+1); mnemonic = "getfield #" + idx; explain = "get field " + memberString(pool, idx); next = pc+3; break; }
            case 0xB5: { int idx = readU2(code, pc+1); mnemonic = "putfield #" + idx; explain = "set field " + memberString(pool, idx); next = pc+3; break; }

            // Method invocation
            case 0xB6: { int idx = readU2(code, pc+1); mnemonic = "invokevirtual #" + idx; explain = "call " + memberString(pool, idx); next = pc+3; break; }
            case 0xB7: { int idx = readU2(code, pc+1); mnemonic = "invokespecial #" + idx; explain = "call special " + memberString(pool, idx); next = pc+3; break; }
            case 0xB8: { int idx = readU2(code, pc+1); mnemonic = "invokestatic #" + idx; explain = "call static " + memberString(pool, idx); next = pc+3; break; }
            case 0xB9: { int idx = readU2(code, pc+1); mnemonic = "invokeinterface #" + idx; explain = "call interface " + memberString(pool, idx); next = pc+5; break; }
            case 0xBA: { int idx = readU2(code, pc+1); mnemonic = "invokedynamic #" + idx; explain = "call dynamic"; next = pc+5; break; }

            // Object creation
            case 0xBB: { int idx = readU2(code, pc+1); mnemonic = "new #" + idx; explain = "new " + classString(pool, idx); next = pc+3; break; }
            case 0xBC: { int atype = code[pc+1] & 0xFF; mnemonic = "newarray " + atype; explain = "new " + arrayTypeName(atype) + "[]"; next = pc+2; break; }
            case 0xBD: { int idx = readU2(code, pc+1); mnemonic = "anewarray #" + idx; explain = "new " + classString(pool, idx) + "[]"; next = pc+3; break; }
            case 0xBE: mnemonic = "arraylength"; explain = "array.length"; break;

            // Type operations
            case 0xBF: mnemonic = "athrow"; explain = "throw exception"; break;
            case 0xC0: { int idx = readU2(code, pc+1); mnemonic = "checkcast #" + idx; explain = "cast to " + classString(pool, idx); next = pc+3; break; }
            case 0xC1: { int idx = readU2(code, pc+1); mnemonic = "instanceof #" + idx; explain = "instanceof " + classString(pool, idx); next = pc+3; break; }
            case 0xC2: mnemonic = "monitorenter"; explain = "enter synchronized"; break;
            case 0xC3: mnemonic = "monitorexit"; explain = "exit synchronized"; break;

            // Multianewarray
            case 0xC5: { int idx = readU2(code, pc+1); int dims = code[pc+3] & 0xFF; mnemonic = "multianewarray #" + idx + " " + dims; explain = "new " + classString(pool, idx) + " (" + dims + " dims)"; next = pc+4; break; }

            // Wide
            case 0xC4: {
                int wideOp = code[pc+1] & 0xFF;
                int idx = readU2(code, pc+2);
                if (wideOp == 0x84) { // wide iinc
                    int inc = readS2(code, pc+4);
                    mnemonic = "wide iinc " + idx + " " + inc;
                    explain = varName(localVarNames, idx) + " += " + inc;
                    next = pc + 6;
                } else {
                    mnemonic = "wide " + wideOp + " " + idx;
                    explain = "wide operation on " + varName(localVarNames, idx);
                    next = pc + 4;
                }
                break;
            }

            // Array stores
            case 0x4F: mnemonic = "iastore"; explain = "array[index] = int"; break;
            case 0x50: mnemonic = "lastore"; explain = "array[index] = long"; break;
            case 0x51: mnemonic = "fastore"; explain = "array[index] = float"; break;
            case 0x52: mnemonic = "dastore"; explain = "array[index] = double"; break;
            case 0x53: mnemonic = "aastore"; explain = "array[index] = ref"; break;
            case 0x54: mnemonic = "bastore"; explain = "array[index] = byte"; break;
            case 0x55: mnemonic = "castore"; explain = "array[index] = char"; break;
            case 0x56: mnemonic = "sastore"; explain = "array[index] = short"; break;

            default:
                mnemonic = "opcode_" + op;
                explain = "";
                // Try to advance past unknown opcodes
                next = pc + OpcodeInfo.operandSize(op, code, pc) + 1;
                break;
        }

        if (next <= pc) next = pc + 1; // Safety: always advance
        return new String[]{mnemonic, explain, String.valueOf(next)};
    }

    private static int readU2(byte[] code, int offset) {
        return ((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF);
    }

    private static int readS2(byte[] code, int offset) {
        return (short)((code[offset] & 0xFF) << 8 | (code[offset + 1] & 0xFF));
    }

    private static int readS4(byte[] code, int offset) {
        return (code[offset] & 0xFF) << 24 | (code[offset+1] & 0xFF) << 16 |
               (code[offset+2] & 0xFF) << 8 | (code[offset+3] & 0xFF);
    }

    private static String varName(Map<Integer, String> names, int idx) {
        if (names != null && names.containsKey(idx)) {
            return (String) names.get(idx);
        }
        return idx == 0 ? "this" : "var" + idx;
    }

    private static String poolString(ConstantPool pool, int idx) {
        try {
            String s = pool.getUtf8(idx);
            if (s != null) return "\"" + s + "\"";
        } catch (Exception e) { /* ignore */ }
        try {
            // Try to get as a numeric constant from the constant pool tag
            return "#" + idx;
        } catch (Exception e) { /* ignore */ }
        return "#" + idx;
    }

    private static String memberString(ConstantPool pool, int idx) {
        try {
            String cls = pool.getMemberClassName(idx);
            String name = pool.getMemberName(idx);
            String simpleCls = cls;
            int slash = cls.lastIndexOf('/');
            if (slash >= 0) simpleCls = cls.substring(slash + 1);
            return simpleCls + "." + name;
        } catch (Exception e) {
            return "#" + idx;
        }
    }

    private static String classString(ConstantPool pool, int idx) {
        try {
            String cls = pool.getClassName(idx);
            int slash = cls.lastIndexOf('/');
            return slash >= 0 ? cls.substring(slash + 1) : cls;
        } catch (Exception e) {
            return "#" + idx;
        }
    }

    private static String arrayTypeName(int atype) {
        switch (atype) {
            case 4: return "boolean";
            case 5: return "char";
            case 6: return "float";
            case 7: return "double";
            case 8: return "byte";
            case 9: return "short";
            case 10: return "int";
            case 11: return "long";
            default: return "type_" + atype;
        }
    }
}
// END_CHANGE: IMP-2026-0008-1
