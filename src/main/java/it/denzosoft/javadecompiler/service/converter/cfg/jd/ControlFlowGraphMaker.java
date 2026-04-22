/*
 * This project is distributed under the GPLv3 license.
 * START_CHANGE: IMP-2026-0062-20260422-5 - Port ControlFlowGraphMaker from JD-Core 1.1.3.
 *
 * Builds the initial ControlFlowGraph from raw bytecode with the JD-Core rich
 * type set (CONDITIONAL_BRANCH, GOTO, SWITCH_DECLARATION, TRY_DECLARATION, ...).
 * Downstream the ControlFlowGraphReducer collapses these into structured forms
 * (IF, IF_ELSE, LOOP, TRY, SWITCH, CONDITION_AND, CONDITION_OR, ...).
 *
 * Ported with only cosmetic adjustments:
 *   - DefaultList -> ArrayList
 *   - ConstantPool API adapted to our wrapper's accessor names
 *   - CodeException/ExceptionEntry name substitution
 *   - Line-number table access via our LineNumberTableAttribute
 */
package it.denzosoft.javadecompiler.service.converter.cfg.jd;

import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.LineNumberTableAttribute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class ControlFlowGraphMaker {
    protected static final BasicBlock MARK = BasicBlock.END;
    protected static final Comparator<CodeAttribute.ExceptionEntry> CODE_EXCEPTION_COMPARATOR =
        new Comparator<CodeAttribute.ExceptionEntry>() {
            public int compare(CodeAttribute.ExceptionEntry a, CodeAttribute.ExceptionEntry b) {
                int c = a.startPc - b.startPc;
                if (c == 0) c = a.endPc - b.endPc;
                return c;
            }
        };

    public static ControlFlowGraph make(MethodInfo method, ConstantPool constants) {
        CodeAttribute attributeCode = method.findAttribute("Code");
        if (attributeCode == null) return null;

        byte[] code = attributeCode.getCode();
        int length = code.length;
        BasicBlock[] map = new BasicBlock[length];
        char[] types = new char[length];
        int[] nextOffsets = new int[length];
        int[] branchOffsets = new int[length];
        int[][] switchValues = new int[length][];
        int[][] switchOffsets = new int[length][];
        map[0] = MARK;
        int lastOffset = 0;
        int lastStatementOffset = -1;

        // ----- Pass 1: walk opcodes, identify block leaders ("marks") -----
        for (int offset = 0; offset < length; ++offset) {
            nextOffsets[lastOffset] = offset;
            lastOffset = offset;
            int opcode = code[offset] & 0xFF;
            switch (opcode) {
                // BIPUSH, SIPUSH byte-operand, ILOAD/LLOAD/FLOAD/DLOAD/ALOAD, NEWARRAY
                case 16: case 18: case 21: case 22: case 23: case 24: case 25: case 188:
                    offset += 1;
                    break;
                // ISTORE..ASTORE (with byte index): these are statements
                case 54: case 55: case 56: case 57: case 58:
                    lastStatementOffset = ++offset;
                    break;
                // ISTORE_N..ASTORE_N (59..78), array-store (79..86), POP (87..88),
                // IINC is case 132 separate; MONITORENTER/MONITOREXIT (194,195)
                case 59: case 60: case 61: case 62: case 63: case 64: case 65: case 66:
                case 67: case 68: case 69: case 70: case 71: case 72: case 73: case 74:
                case 75: case 76: case 77: case 78: case 79: case 80: case 81: case 82:
                case 83: case 84: case 85: case 86: case 87: case 88: case 194: case 195:
                    lastStatementOffset = offset;
                    break;
                // RET
                case 169:
                    types[++offset] = 'R';
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                // PUTSTATIC / PUTFIELD: advance past 2-byte operand, mark as statement
                case 179: case 181:
                    lastStatementOffset = offset += 2;
                    break;
                // INVOKEVIRTUAL / INVOKESPECIAL / INVOKESTATIC: parse descriptor,
                // only count as statement when return type is V (void)
                case 182: case 183: case 184: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String desc = constants.getMemberDescriptor(idx);
                    if (desc != null && desc.charAt(desc.length() - 1) == 'V') {
                        lastStatementOffset = offset;
                    }
                    break;
                }
                // INVOKEINTERFACE / INVOKEDYNAMIC: 2 byte refs + 2 extra bytes
                case 185: case 186: {
                    int idx = ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF);
                    String desc = constants.getMemberDescriptor(idx);
                    offset += 2;
                    if (desc != null && desc.charAt(desc.length() - 1) == 'V') {
                        lastStatementOffset = offset;
                    }
                    break;
                }
                // IINC byte byte
                case 132: {
                    int oldStart = lastStatementOffset;
                    offset += 2;
                    if (oldStart + 3 == offset && !checkILOADForIINC(code, offset, code[offset - 1] & 0xFF)) {
                        lastStatementOffset = offset;
                    }
                    break;
                }
                // LDC/LDC_W/LDC2_W short index, GETSTATIC/GETFIELD, NEW, ANEWARRAY,
                // CHECKCAST, INSTANCEOF (all 2-byte operand, NOT statements by themselves)
                case 17: case 19: case 20: case 178: case 180: case 187: case 189:
                case 192: case 193:
                    offset += 2;
                    break;
                // GOTO
                case 167: {
                    int type = lastStatementOffset + 1 == offset ? 'g' : 'G';
                    if (lastStatementOffset != -1) map[lastStatementOffset + 1] = MARK;
                    types[offset] = (char) type;
                    int branchOffset = offset++ + (short)(((code[offset] & 0xFF) << 8) | (code[++offset] & 0xFF));
                    map[branchOffset] = MARK;
                    types[offset] = (char) type;
                    branchOffsets[offset] = branchOffset;
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                }
                // JSR
                case 168: {
                    if (lastStatementOffset != -1) map[lastStatementOffset + 1] = MARK;
                    types[offset] = 'j';
                    int branchOffset = offset++ + (short)(((code[offset] & 0xFF) << 8) | (code[++offset] & 0xFF));
                    map[branchOffset] = MARK;
                    types[offset] = 'j';
                    branchOffsets[offset] = branchOffset;
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                }
                // Conditional branches: IFEQ..IFNONNULL (153-166, 198, 199)
                case 153: case 154: case 155: case 156: case 157: case 158:
                case 159: case 160: case 161: case 162: case 163: case 164:
                case 165: case 166: case 198: case 199: {
                    if (lastStatementOffset != -1) map[lastStatementOffset + 1] = MARK;
                    int branchOffset = offset++ + (short)(((code[offset] & 0xFF) << 8) | (code[++offset] & 0xFF));
                    map[branchOffset] = MARK;
                    types[offset] = 'c';
                    branchOffsets[offset] = branchOffset;
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                }
                // TABLESWITCH
                case 170: {
                    int i = (offset + 4) & 0xFFFC;
                    int defaultOffset = offset + (((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF));
                    map[defaultOffset] = MARK;
                    int low = ((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF);
                    int high = ((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF);
                    int cases = high - low + 2;
                    int[] values = new int[cases];
                    int[] offsets = new int[cases];
                    offsets[0] = defaultOffset;
                    for (int j = 1; j < cases; ++j) {
                        values[j] = low + j - 1;
                        int bo = offset + (((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF));
                        offsets[j] = bo;
                        map[bo] = MARK;
                    }
                    offset = i - 1;
                    types[offset] = 's';
                    switchValues[offset] = values;
                    switchOffsets[offset] = offsets;
                    lastStatementOffset = offset;
                    break;
                }
                // LOOKUPSWITCH
                case 171: {
                    int i = (offset + 4) & 0xFFFC;
                    int defaultOffset = offset + (((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF));
                    map[defaultOffset] = MARK;
                    int npairs = ((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF);
                    int[] values = new int[npairs + 1];
                    int[] offsets = new int[npairs + 1];
                    offsets[0] = defaultOffset;
                    for (int j = 1; j <= npairs; ++j) {
                        values[j] = ((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF);
                        int bo = offset + (((code[i++] & 0xFF) << 24) | ((code[i++] & 0xFF) << 16) | ((code[i++] & 0xFF) << 8) | (code[i++] & 0xFF));
                        offsets[j] = bo;
                        map[bo] = MARK;
                    }
                    offset = i - 1;
                    types[offset] = 's';
                    switchValues[offset] = values;
                    switchOffsets[offset] = offsets;
                    lastStatementOffset = offset;
                    break;
                }
                // IRETURN/LRETURN/FRETURN/DRETURN/ARETURN
                case 172: case 173: case 174: case 175: case 176:
                    types[offset] = 'v';
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                // RETURN (void)
                case 177:
                    if (lastStatementOffset != -1) map[lastStatementOffset + 1] = MARK;
                    types[offset] = 'r';
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                // ATHROW
                case 191:
                    types[offset] = 't';
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                // WIDE
                case 196: {
                    int op2 = code[++offset] & 0xFF;
                    switch (op2) {
                        case 132: { // WIDE IINC
                            int oldStart = lastStatementOffset;
                            offset += 4;
                            if (oldStart + 6 == offset
                                    && !checkILOADForIINC(code, offset, ((code[offset - 3] & 0xFF) << 8) | (code[offset - 2] & 0xFF))) {
                                lastStatementOffset = offset;
                            }
                            break;
                        }
                        case 169: // WIDE RET
                            types[offset += 2] = 'R';
                            if (offset + 1 < length) map[offset + 1] = MARK;
                            lastStatementOffset = offset;
                            break;
                        case 54: case 55: case 56: case 57: case 58:
                            lastStatementOffset = offset + 2;
                            offset += 2;
                            break;
                        default:
                            offset += 2;
                    }
                    break;
                }
                // MULTIANEWARRAY: 3-byte operand
                case 197:
                    offset += 3;
                    break;
                // GOTO_W
                case 200: {
                    int type = lastStatementOffset + 1 == offset ? 'g' : 'G';
                    types[offset] = (char) type;
                    int branchOffset = offset++ + (((code[offset] & 0xFF) << 24) | ((code[++offset] & 0xFF) << 16) | ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF));
                    map[branchOffset] = MARK;
                    types[offset] = (char) type;
                    branchOffsets[offset] = branchOffset;
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                }
                // JSR_W
                case 201: {
                    if (lastStatementOffset != -1) map[lastStatementOffset + 1] = MARK;
                    types[offset] = 'j';
                    int branchOffset = offset++ + (((code[offset] & 0xFF) << 24) | ((code[++offset] & 0xFF) << 16) | ((code[++offset] & 0xFF) << 8) | (code[++offset] & 0xFF));
                    map[branchOffset] = MARK;
                    types[offset] = 'j';
                    branchOffsets[offset] = branchOffset;
                    if (offset + 1 < length) map[offset + 1] = MARK;
                    lastStatementOffset = offset;
                    break;
                }
                default:
                    // All other single-byte opcodes: nothing.
                    break;
            }
        }
        nextOffsets[lastOffset] = length;

        // Exception handler endpoints are also block leaders.
        CodeAttribute.ExceptionEntry[] codeExceptions = attributeCode.getExceptionTable();
        if (codeExceptions != null) {
            for (CodeAttribute.ExceptionEntry entry : codeExceptions) {
                map[entry.startPc] = MARK;
                map[entry.handlerPc] = MARK;
            }
        }

        ControlFlowGraph cfg = new ControlFlowGraph(method);

        // ----- Pass 2: build offset->line map; line transitions can split blocks -----
        LineNumberTableAttribute lnt = null;
        for (Attribute a : attributeCode.getAttributes()) {
            if (a instanceof LineNumberTableAttribute) { lnt = (LineNumberTableAttribute) a; break; }
        }
        if (lnt != null) {
            int[] offsetToLineNumbers = new int[length];
            LineNumberTableAttribute.LineNumber[] entries = lnt.getLineNumbers();
            if (entries != null && entries.length > 0) {
                int offset = 0;
                int lineNumber = entries[0].lineNumber;
                for (int i = 1; i < entries.length; ++i) {
                    LineNumberTableAttribute.LineNumber e = entries[i];
                    int toIndex = e.startPc;
                    while (offset < toIndex) offsetToLineNumbers[offset++] = lineNumber;
                    if (lineNumber > e.lineNumber) map[offset] = MARK;
                    lineNumber = e.lineNumber;
                }
                while (offset < length) offsetToLineNumbers[offset++] = lineNumber;
            }
            cfg.setOffsetToLineNumbers(offsetToLineNumbers);
        }

        // ----- Pass 3: materialise BasicBlocks from the marks -----
        lastOffset = 0;
        BasicBlock startBasicBlock = cfg.newBasicBlock(BasicBlock.TYPE_START, 0, 0);
        int offset = nextOffsets[0];
        while (offset < length) {
            if (map[offset] != null) {
                map[lastOffset] = cfg.newBasicBlock(lastOffset, offset);
                lastOffset = offset;
            }
            offset = nextOffsets[offset];
        }
        map[lastOffset] = cfg.newBasicBlock(lastOffset, length);

        List<BasicBlock> list = cfg.getBasicBlocks();
        List<BasicBlock> basicBlocks = new ArrayList<BasicBlock>(list.size());
        BasicBlock successor = list.get(1);
        startBasicBlock.setNext(successor);
        successor.getPredecessors().add(startBasicBlock);

        int basicBlockLength = list.size();
        for (int i = 1; i < basicBlockLength; ++i) {
            BasicBlock basicBlock = list.get(i);
            int lastInstructionOffset = basicBlock.getToOffset() - 1;
            switch (types[lastInstructionOffset]) {
                case 'g':
                    basicBlock.setType(BasicBlock.TYPE_GOTO);
                    successor = map[branchOffsets[lastInstructionOffset]];
                    basicBlock.setNext(successor);
                    successor.getPredecessors().add(basicBlock);
                    break;
                case 'G':
                    basicBlock.setType(BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR);
                    successor = map[branchOffsets[lastInstructionOffset]];
                    basicBlock.setNext(successor);
                    successor.getPredecessors().add(basicBlock);
                    break;
                case 't':
                    basicBlock.setType(BasicBlock.TYPE_THROW);
                    basicBlock.setNext(BasicBlock.END);
                    break;
                case 'r':
                    basicBlock.setType(BasicBlock.TYPE_RETURN);
                    basicBlock.setNext(BasicBlock.END);
                    break;
                case 'c':
                    basicBlock.setType(BasicBlock.TYPE_CONDITIONAL_BRANCH);
                    successor = map[basicBlock.getToOffset()];
                    basicBlock.setNext(successor);
                    successor.getPredecessors().add(basicBlock);
                    successor = map[branchOffsets[lastInstructionOffset]];
                    basicBlock.setBranch(successor);
                    successor.getPredecessors().add(basicBlock);
                    break;
                case 's': {
                    basicBlock.setType(BasicBlock.TYPE_SWITCH_DECLARATION);
                    int[] values = switchValues[lastInstructionOffset];
                    int[] offsets = switchOffsets[lastInstructionOffset];
                    List<SwitchCase> switchCases = new ArrayList<SwitchCase>(offsets.length);
                    int defaultOffset = offsets[0];
                    BasicBlock bb = map[defaultOffset];
                    switchCases.add(new SwitchCase(bb));
                    bb.getPredecessors().add(basicBlock);
                    for (int j = 1; j < offsets.length; ++j) {
                        int off2 = offsets[j];
                        if (off2 == defaultOffset) continue;
                        bb = map[off2];
                        switchCases.add(new SwitchCase(values[j], bb));
                        bb.getPredecessors().add(basicBlock);
                    }
                    basicBlock.setSwitchCases(switchCases);
                    break;
                }
                case 'j':
                    basicBlock.setType(BasicBlock.TYPE_JSR);
                    successor = map[basicBlock.getToOffset()];
                    basicBlock.setNext(successor);
                    successor.getPredecessors().add(basicBlock);
                    successor = map[branchOffsets[lastInstructionOffset]];
                    basicBlock.setBranch(successor);
                    successor.getPredecessors().add(basicBlock);
                    break;
                case 'R':
                    basicBlock.setType(BasicBlock.TYPE_RET);
                    basicBlock.setNext(BasicBlock.END);
                    break;
                case 'v':
                    basicBlock.setType(BasicBlock.TYPE_RETURN_VALUE);
                    basicBlock.setNext(BasicBlock.END);
                    break;
                default:
                    basicBlock.setType(BasicBlock.TYPE_STATEMENTS);
                    successor = map[basicBlock.getToOffset()];
                    basicBlock.setNext(successor);
                    successor.getPredecessors().add(basicBlock);
                    basicBlocks.add(basicBlock);
                    break;
            }
        }

        // ----- Pass 4: wrap try-regions into TRY_DECLARATION blocks -----
        if (codeExceptions != null) {
            HashMap<CodeAttribute.ExceptionEntry, BasicBlock> cache = new HashMap<CodeAttribute.ExceptionEntry, BasicBlock>();
            int[] handlePcToStartPc = branchOffsets;
            char[] handlePcMarks = types;
            Arrays.sort(codeExceptions, CODE_EXCEPTION_COMPARATOR);
            for (CodeAttribute.ExceptionEntry ce : codeExceptions) {
                int startPc = ce.startPc;
                int handlerPc = ce.handlerPc;
                if (startPc == handlerPc) continue;
                if (handlePcMarks[handlerPc] == 'T'
                        && startPc > map[handlePcToStartPc[handlerPc]].getFromOffset()) continue;
                int catchType = ce.catchType;
                BasicBlock tcf = cache.get(ce);
                if (tcf == null) {
                    int endPc = ce.endPc;
                    BasicBlock start = map[startPc];
                    tcf = cfg.newBasicBlock(BasicBlock.TYPE_TRY_DECLARATION, startPc, endPc);
                    tcf.setNext(start);
                    HashSet<BasicBlock> tcfPredecessors = tcf.getPredecessors();
                    HashSet<BasicBlock> startPredecessors = start.getPredecessors();
                    Iterator<BasicBlock> it = startPredecessors.iterator();
                    while (it.hasNext()) {
                        BasicBlock predecessor = it.next();
                        if (start.contains(predecessor)) continue;
                        predecessor.replace(start, tcf);
                        tcfPredecessors.add(predecessor);
                        it.remove();
                    }
                    startPredecessors.add(tcf);
                    map[startPc] = tcf;
                    cache.put(ce, tcf);
                }
                String internalThrowableName = catchType == 0 ? null : constants.getClassName(catchType);
                BasicBlock handlerBB = map[handlerPc];
                tcf.addExceptionHandler(internalThrowableName, handlerBB);
                handlerBB.getPredecessors().add(tcf);
                handlePcToStartPc[handlerPc] = startPc;
                handlePcMarks[handlerPc] = 'T';
            }
        }

        // ----- Pass 5: trivial statement+goto/conditional coalescing when the
        // successor is only reached from here and there's a live stack value -----
        for (BasicBlock bb : basicBlocks) {
            BasicBlock next = bb.getNext();
            if (bb.getType() != BasicBlock.TYPE_STATEMENTS || next.getPredecessors().size() != 1) continue;
            if (next.getType() == BasicBlock.TYPE_GOTO && ByteCodeParser.evalStackDepth(constants, code, bb) > 0) {
                bb.setType(BasicBlock.TYPE_GOTO_IN_TERNARY_OPERATOR);
                bb.setToOffset(next.getToOffset());
                bb.setNext(next.getNext());
                HashSet<BasicBlock> predecessors = next.getNext().getPredecessors();
                predecessors.remove(next);
                predecessors.add(bb);
                next.setType(BasicBlock.TYPE_DELETED);
                continue;
            }
            if (next.getType() != BasicBlock.TYPE_CONDITIONAL_BRANCH) continue;
            if (ByteCodeParser.evalStackDepth(constants, code, bb) <= 0) continue;
            bb.setType(BasicBlock.TYPE_CONDITIONAL_BRANCH);
            bb.setToOffset(next.getToOffset());
            bb.setNext(next.getNext());
            HashSet<BasicBlock> predecessors = next.getNext().getPredecessors();
            predecessors.remove(next);
            predecessors.add(bb);
            bb.setBranch(next.getBranch());
            predecessors = next.getBranch().getPredecessors();
            predecessors.remove(next);
            predecessors.add(bb);
            next.setType(BasicBlock.TYPE_DELETED);
        }

        return cfg;
    }

    /**
     * Heuristic used by JD-Core to decide whether an IINC is part of a
     * loop update (`for (; ; i++)`) vs a standalone statement:
     * an IINC immediately followed by an ILOAD of the same slot is treated
     * as NOT a standalone statement (the load is consuming the incremented value).
     */
    protected static boolean checkILOADForIINC(byte[] code, int offset, int index) {
        if (++offset >= code.length) return false;
        int nextOpcode = code[offset] & 0xFF;
        if (nextOpcode == 21) { // ILOAD index
            return index == (code[offset + 1] & 0xFF);
        }
        // ILOAD_0..ILOAD_3 (26..29) with index matching (0..3)
        return nextOpcode == 26 + index;
    }
}
