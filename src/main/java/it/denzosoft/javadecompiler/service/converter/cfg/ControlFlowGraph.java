/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.service.converter.cfg;

import it.denzosoft.javadecompiler.model.classfile.attribute.CodeAttribute;
import it.denzosoft.javadecompiler.util.ByteReader;
import it.denzosoft.javadecompiler.util.OpcodeInfo;

import java.util.*;

/**
 * Builds a Control Flow Graph (CFG) from Java bytecode.
 * Identifies basic blocks and their connections (edges).
 */
public class ControlFlowGraph {

    private final byte[] bytecode;
    private final CodeAttribute.ExceptionEntry[] exceptionTable;

    /** All basic blocks, ordered by startPc */
    private List<BasicBlock> blocks = new ArrayList<BasicBlock>();

    /** Map from PC to the basic block starting at that PC */
    private Map<Integer, BasicBlock> blockMap = new TreeMap<Integer, BasicBlock>();

    /** Entry block */
    private BasicBlock entryBlock;

    public ControlFlowGraph(byte[] bytecode, CodeAttribute.ExceptionEntry[] exceptionTable) {
        this.bytecode = bytecode;
        this.exceptionTable = exceptionTable;
    }

    public List<BasicBlock> getBlocks() { return blocks; }
    public BasicBlock getEntryBlock() { return entryBlock; }
    public BasicBlock getBlockAtPc(int pc) { return blockMap.get(pc); }

    /**
     * Build the CFG by scanning bytecode for branch instructions.
     */
    public void build() {
        // Step 1: Find all block boundaries (leader PCs)
        Set<Integer> leaders = new TreeSet<Integer>();
        leaders.add(0); // First instruction is always a leader

        // Add exception handler starts as leaders
        if (exceptionTable != null) {
            for (CodeAttribute.ExceptionEntry entry : exceptionTable) {
                leaders.add(entry.startPc);
                leaders.add(entry.handlerPc);
                if (entry.endPc < bytecode.length) {
                    leaders.add(entry.endPc);
                }
            }
        }

        // Scan bytecode for branch instructions
        ByteReader reader = new ByteReader(bytecode);
        while (reader.remaining() > 0) {
            int pc = reader.getOffset();
            int opcode = reader.readUnsignedByte();
            int nextPc = skipOperands(opcode, reader, pc);

            if (isBranch(opcode)) {
                int target = getBranchTarget(opcode, bytecode, pc);
                if (target >= 0 && target < bytecode.length) {
                    leaders.add(target);
                }
                // Instruction after branch is a leader (fall-through)
                if (nextPc < bytecode.length) {
                    leaders.add(nextPc);
                }
            }
            if (opcode == 0xAA || opcode == 0xAB) {
                // tableswitch / lookupswitch: add all targets as leaders
                int spos = pc + 1;
                int spad = (4 - (spos % 4)) % 4;
                spos += spad;
                int defaultTarget = pc + getInt(bytecode, spos); spos += 4;
                if (defaultTarget >= 0 && defaultTarget < bytecode.length) {
                    leaders.add(defaultTarget);
                }
                if (opcode == 0xAA) { // tableswitch
                    int low = getInt(bytecode, spos); spos += 4;
                    int high = getInt(bytecode, spos); spos += 4;
                    for (int si = 0; si <= high - low; si++) {
                        int tgt = pc + getInt(bytecode, spos); spos += 4;
                        if (tgt >= 0 && tgt < bytecode.length) {
                            leaders.add(tgt);
                        }
                    }
                } else { // lookupswitch
                    int npairs = getInt(bytecode, spos); spos += 4;
                    for (int si = 0; si < npairs; si++) {
                        spos += 4; // skip key
                        int tgt = pc + getInt(bytecode, spos); spos += 4;
                        if (tgt >= 0 && tgt < bytecode.length) {
                            leaders.add(tgt);
                        }
                    }
                }
                if (nextPc < bytecode.length) {
                    leaders.add(nextPc);
                }
            }
            if (isReturn(opcode) || isThrow(opcode)) {
                if (nextPc < bytecode.length) {
                    leaders.add(nextPc);
                }
            }
        }

        // Step 2: Create basic blocks
        Integer[] leaderArray = leaders.toArray(new Integer[leaders.size()]);
        blocks = new ArrayList<BasicBlock>(leaderArray.length);
        for (int i = 0; i < leaderArray.length; i++) {
            int startPc = leaderArray[i].intValue();
            BasicBlock block = new BasicBlock(startPc);
            if (i + 1 < leaderArray.length) {
                block.endPc = leaderArray[i + 1].intValue();
            } else {
                block.endPc = bytecode.length;
            }
            blocks.add(block);
            blockMap.put(startPc, block);
        }

        if (blocks.isEmpty()) return;
        entryBlock = blocks.get(0);

        // START_CHANGE: BUG-2026-0050-20260420-2 - Tag handler-entry blocks so the decoder
        // can pre-seed the operand stack with the caught exception. Without this the
        // `astore` at handler start underflows and the exception ref is replaced with null.
        if (exceptionTable != null) {
            for (CodeAttribute.ExceptionEntry entry : exceptionTable) {
                BasicBlock handler = blockMap.get(entry.handlerPc);
                if (handler != null) {
                    handler.isExceptionHandler = true;
                    // Prefer a concrete type; `catchType == 0` means catch-all (finally).
                    if (handler.exceptionHandlerType == null) {
                        handler.exceptionHandlerType = "java/lang/Throwable";
                    }
                }
            }
        }
        // END_CHANGE: BUG-2026-0050-2

        // Step 3: Determine block types and successors
        for (BasicBlock block : blocks) {
            classifyBlock(block);
        }

        // Step 4: Link predecessors
        for (BasicBlock block : blocks) {
            if (block.trueSuccessor != null) {
                block.trueSuccessor.predecessors.add(block);
            }
            if (block.falseSuccessor != null) {
                block.falseSuccessor.predecessors.add(block);
            }
        }
    }

    /**
     * Classify a block by examining its last instruction.
     */
    private void classifyBlock(BasicBlock block) {
        if (block.endPc <= block.startPc) {
            block.type = BasicBlock.FALL_THROUGH;
            return;
        }

        // Find the last instruction in the block
        ByteReader reader = new ByteReader(bytecode);
        reader.setOffset(block.startPc);
        int lastPc = block.startPc;
        int lastOpcode = 0;

        while (reader.getOffset() < block.endPc && reader.remaining() > 0) {
            lastPc = reader.getOffset();
            lastOpcode = reader.readUnsignedByte();
            skipOperands(lastOpcode, reader, lastPc);
        }

        if (isConditionalBranch(lastOpcode)) {
            block.type = BasicBlock.CONDITIONAL;
            block.branchOpcode = lastOpcode;
            block.branchTargetPc = getBranchTarget(lastOpcode, bytecode, lastPc);
            block.fallThroughPc = block.endPc;

            block.trueSuccessor = blockMap.get(block.branchTargetPc);
            block.falseSuccessor = blockMap.get(block.fallThroughPc);
        } else if (lastOpcode == 0xA7) { // goto
            block.type = BasicBlock.GOTO;
            block.branchTargetPc = getBranchTarget(lastOpcode, bytecode, lastPc);
            block.trueSuccessor = blockMap.get(block.branchTargetPc);
        } else if (lastOpcode == 0xC8) { // goto_w
            block.type = BasicBlock.GOTO;
            block.branchTargetPc = lastPc + getInt(bytecode, lastPc + 1);
            block.trueSuccessor = blockMap.get(block.branchTargetPc);
        } else if (isReturn(lastOpcode)) {
            block.type = BasicBlock.RETURN;
        } else if (isThrow(lastOpcode)) {
            block.type = BasicBlock.THROW;
        } else if (lastOpcode == 0xAA || lastOpcode == 0xAB) { // tableswitch/lookupswitch
            block.type = BasicBlock.SWITCH;
            parseSwitchTargets(block, lastPc, lastOpcode);
        } else {
            // Falls through to next block
            block.type = BasicBlock.FALL_THROUGH;
            if (block.endPc < bytecode.length) {
                block.trueSuccessor = blockMap.get(block.endPc);
            }
        }
    }

    private void parseSwitchTargets(BasicBlock block, int switchPc, int opcode) {
        int pos = switchPc + 1;
        int pad = (4 - (pos % 4)) % 4;
        pos += pad;
        int defaultOffset = getInt(bytecode, pos); pos += 4;
        block.switchDefaultTarget = switchPc + defaultOffset;

        if (opcode == 0xAA) { // tableswitch
            int low = getInt(bytecode, pos); pos += 4;
            int high = getInt(bytecode, pos); pos += 4;
            int count = high - low + 1;
            block.switchKeys = new int[count];
            block.switchTargets = new int[count];
            for (int i = 0; i < count; i++) {
                block.switchKeys[i] = low + i;
                block.switchTargets[i] = switchPc + getInt(bytecode, pos);
                pos += 4;
            }
        } else { // lookupswitch
            int npairs = getInt(bytecode, pos); pos += 4;
            block.switchKeys = new int[npairs];
            block.switchTargets = new int[npairs];
            for (int i = 0; i < npairs; i++) {
                block.switchKeys[i] = getInt(bytecode, pos); pos += 4;
                block.switchTargets[i] = switchPc + getInt(bytecode, pos); pos += 4;
            }
        }
    }

    // Utility methods — delegate to shared OpcodeInfo

    private static boolean isBranch(int opcode) {
        return OpcodeInfo.isBranch(opcode);
    }

    private static boolean isConditionalBranch(int opcode) {
        return OpcodeInfo.isConditionalBranch(opcode);
    }

    private static boolean isReturn(int opcode) {
        return OpcodeInfo.isReturn(opcode);
    }

    private static boolean isThrow(int opcode) {
        return OpcodeInfo.isThrow(opcode);
    }

    static int getBranchTarget(int opcode, byte[] bytecode, int pc) {
        if (opcode == 0xC8) { // goto_w
            return pc + getInt(bytecode, pc + 1);
        }
        return pc + getShort(bytecode, pc + 1);
    }

    private static int getShort(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }

    /**
     * Skip the operands of an instruction and return the PC after it.
     */
    private int skipOperands(int opcode, ByteReader reader, int pc) {
        switch (opcode) {
            // 0 operands
            case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D: case 0x0E: case 0x0F:
            case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E: case 0x1F: case 0x20: case 0x21:
            case 0x22: case 0x23: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: case 0x29:
            case 0x2A: case 0x2B: case 0x2C: case 0x2D: case 0x2E: case 0x2F:
            case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: case 0x35:
            case 0x3B: case 0x3C: case 0x3D: case 0x3E: case 0x3F:
            case 0x40: case 0x41: case 0x42: case 0x43: case 0x44: case 0x45: case 0x46:
            case 0x47: case 0x48: case 0x49: case 0x4A: case 0x4B: case 0x4C: case 0x4D: case 0x4E:
            case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56:
            case 0x57: case 0x58: case 0x59: case 0x5A: case 0x5B: case 0x5C: case 0x5D: case 0x5E: case 0x5F:
            case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
            case 0x68: case 0x69: case 0x6A: case 0x6B: case 0x6C: case 0x6D: case 0x6E: case 0x6F:
            case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
            case 0x78: case 0x79: case 0x7A: case 0x7B: case 0x7C: case 0x7D: case 0x7E: case 0x7F:
            case 0x80: case 0x81: case 0x82: case 0x83:
            case 0x85: case 0x86: case 0x87: case 0x88: case 0x89: case 0x8A: case 0x8B: case 0x8C:
            case 0x8D: case 0x8E: case 0x8F: case 0x90: case 0x91: case 0x92: case 0x93:
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98:
            case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1:
            case 0xBE: case 0xBF: case 0xC2: case 0xC3:
                break;

            // 1 byte operand
            case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xA9: case 0xBC:
                reader.skip(1);
                break;

            // 2 byte operand
            case 0x11: case 0x13: case 0x14:
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4:
            case 0xA5: case 0xA6: case 0xA7: case 0xA8:
            case 0xB2: case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7: case 0xB8:
            case 0xBB: case 0xBD: case 0xC0: case 0xC1: case 0xC6: case 0xC7:
                reader.skip(2);
                break;

            // 2 byte operand + 1 byte
            case 0x84: // iinc
                reader.skip(2);
                break;

            // 4 byte operand
            case 0xB9: // invokeinterface
                reader.skip(4);
                break;

            case 0xBA: // invokedynamic
                reader.skip(4);
                break;

            case 0xC5: // multianewarray
                reader.skip(3);
                break;

            case 0xC8: case 0xC9: // goto_w, jsr_w
                reader.skip(4);
                break;

            case 0xC4: { // wide
                int wideOpcode = reader.readUnsignedByte();
                if (wideOpcode == 0x84) {
                    reader.skip(4); // wide iinc: index(2) + const(2)
                } else {
                    reader.skip(2); // wide load/store: index(2)
                }
                break;
            }

            case 0xAA: { // tableswitch
                int pad = (4 - ((reader.getOffset()) % 4)) % 4;
                reader.skip(pad);
                reader.skip(4); // default
                int low = reader.readInt();
                int high = reader.readInt();
                reader.skip((high - low + 1) * 4);
                break;
            }

            case 0xAB: { // lookupswitch
                int pad = (4 - ((reader.getOffset()) % 4)) % 4;
                reader.skip(pad);
                reader.skip(4); // default
                int npairs = reader.readInt();
                reader.skip(npairs * 8);
                break;
            }

            default:
                break;
        }
        return reader.getOffset();
    }
}
