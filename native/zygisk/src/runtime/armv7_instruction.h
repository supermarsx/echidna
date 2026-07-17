#pragma once

/**
 * @file armv7_instruction.h
 * @brief ARM (A32) + Thumb-2 (T16/T32) instruction decoder and a
 * position-independent prologue relocator for armeabi-v7a inline hooks.
 *
 * This is the armv7 sibling of @c aarch64_instruction.h. It provides the decode
 * + relocation *logic* used by @c inline_hook.cpp's `__arm__` path, factored out
 * so it can be exercised on any host (no ARM execution required) by
 * @c tests/armv7_instruction_test.cpp.
 *
 * Design goals (in priority order):
 *   1. CORRECTNESS OVER COVERAGE. The failure mode of a mis-relocated prologue
 *      is a crash inside a system audio process, so the relocator FAILS CLOSED
 *      on anything it cannot provably relocate: it emits nothing and the caller
 *      leaves the target untouched (mirrors the x86_64 length-decoder discipline).
 *   2. POSITION INDEPENDENCE. The emitted trampoline body uses absolute literal
 *      pools (LDR PC,[PC] veneers, MOVW/MOVT materialisation) and only
 *      trampoline-internal relative offsets, so it is valid at whatever address
 *      `mmap` returns (assumed 4-byte aligned, which page alignment guarantees).
 *      There are therefore no base-dependent fixups to apply after copy.
 *
 * What is relocated:
 *   - Thumb/ARM state is chosen from bit0 of the target pointer (interworking).
 *   - PC-relative literal loads: LDR (word) + LDRB/LDRH/LDRSB/LDRSH, both the
 *     16-bit Thumb LDR-literal and the 32-bit `.W` forms and the ARM word form.
 *   - ADR / ADR.W / ADD(SUB) Rd,PC,#imm  (PC-relative address materialisation).
 *   - B / B<cond> / BL / BLX(imm), Thumb 16 + 32 and ARM, via absolute veneers
 *     with correct Thumb<->ARM interworking and LR semantics.
 *   - CBZ / CBNZ (Thumb) via a short forward branch to an absolute veneer.
 *   - IT blocks: the IT instruction and all of its guarded instructions are
 *     copied verbatim as one unit, and only when every guarded instruction is a
 *     provably PC-free single instruction (expanding one inside an IT shadow
 *     would corrupt the block, so that fails closed).
 *
 * What fails closed (declines the hook, writes nothing):
 *   - TBB/TBH table branches, LDRD/PLD/PLI literal, LDR PC,[PC] literal.
 *   - Any instruction that references PC in a field this decoder does not model.
 *   - Any encoding not on the conservative allow-list.
 *   - A prologue shorter than the patch, or an IT block that would be split.
 *   - A veneer/branch whose displacement does not fit its encoding.
 */

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace echidna::runtime::armv7
{
    // ---------------------------------------------------------------------------
    // Small bit helpers
    // ---------------------------------------------------------------------------

    constexpr int32_t SignExtend(uint32_t value, unsigned bits)
    {
        const uint32_t mask = (bits >= 32) ? 0xFFFFFFFFu : ((1u << bits) - 1u);
        value &= mask;
        const uint32_t sign = (bits == 0) ? 0u : (1u << (bits - 1));
        if (value & sign)
        {
            value |= ~mask;
        }
        return static_cast<int32_t>(value);
    }

    constexpr uint32_t AlignDown4(uint32_t v) { return v & ~3u; }

    // Count trailing zeros of a 4-bit IT mask (mask in 1..15).
    constexpr unsigned CountTrailingZeros4(uint8_t mask)
    {
        for (unsigned i = 0; i < 4; ++i)
        {
            if (mask & (1u << i))
            {
                return i;
            }
        }
        return 4;
    }

    // ARM data-processing modified immediate expansion (imm12 = rot:imm8).
    constexpr uint32_t ArmExpandImm(uint32_t imm12)
    {
        const uint32_t imm8 = imm12 & 0xFFu;
        const uint32_t rot = (imm12 >> 8) & 0xFu;
        if (rot == 0)
        {
            return imm8;
        }
        const unsigned amount = rot * 2u;
        return (imm8 >> amount) | (imm8 << (32u - amount));
    }

    // ---------------------------------------------------------------------------
    // Decode result
    // ---------------------------------------------------------------------------

    enum class Kind : uint8_t
    {
        kUnrelocatable = 0, // fail closed
        kPassThrough,       // copy `length` bytes verbatim (proven PC-free)
        kLdrLiteral,        // PC-relative load into Rt
        kAdr,               // Rd = PC-relative address
        kBranch,            // B / B<cond> (no link)
        kBranchLink,        // BL / BLX (imm) (sets LR)
        kCompareBranch,     // CBZ / CBNZ (Thumb only)
        kIt,                // IT block header
    };

    struct Decoded
    {
        bool valid = false;
        uint8_t length = 0; // instruction length in bytes (2 or 4)
        Kind kind = Kind::kUnrelocatable;

        // kLdrLiteral
        uint8_t rt = 0;
        uint8_t load_bytes = 0; // 1/2/4
        bool load_signed = false;
        uint32_t literal_addr = 0;

        // kAdr
        uint8_t rd = 0;
        uint32_t adr_value = 0;

        // kBranch / kBranchLink
        uint32_t target = 0;   // absolute; bit0 set iff the target is Thumb state
        uint8_t cond = 14;     // 0..13 conditional, 14 = AL/unconditional
        bool exchange = false; // BLX: switches instruction set

        // kCompareBranch
        uint8_t rn = 0;
        bool nonzero = false; // CBNZ

        // kIt
        uint8_t it_count = 0; // number of guarded instructions (1..4)
    };

    // ---------------------------------------------------------------------------
    // Thumb width detection
    // ---------------------------------------------------------------------------

    constexpr bool IsThumb32(uint16_t hw0)
    {
        const uint16_t op = static_cast<uint16_t>(hw0 >> 11);
        return op == 0b11101u || op == 0b11110u || op == 0b11111u;
    }

    // ---------------------------------------------------------------------------
    // Thumb 16-bit decode
    // ---------------------------------------------------------------------------

    inline Decoded DecodeThumb16(uint16_t hw, uint32_t addr)
    {
        Decoded d;
        d.length = 2;
        const uint32_t pc = addr + 4; // Thumb reads PC as this instruction + 4

        // LDR (literal) T1: 01001 Rt imm8
        if ((hw & 0xF800u) == 0x4800u)
        {
            d.kind = Kind::kLdrLiteral;
            d.rt = (hw >> 8) & 0x7u;
            d.load_bytes = 4;
            d.literal_addr = AlignDown4(pc) + ((hw & 0xFFu) << 2);
            d.valid = true;
            return d;
        }

        // ADR T1: 10100 Rd imm8  (ADD Rd, PC, #imm)
        if ((hw & 0xF800u) == 0xA000u)
        {
            d.kind = Kind::kAdr;
            d.rd = (hw >> 8) & 0x7u;
            d.adr_value = AlignDown4(pc) + ((hw & 0xFFu) << 2);
            d.valid = true;
            return d;
        }

        // CBZ / CBNZ: 1011 op 0 i 1 imm5 Rn  -> (hw & 0xF500)==0xB100
        if ((hw & 0xF500u) == 0xB100u)
        {
            d.kind = Kind::kCompareBranch;
            d.nonzero = ((hw >> 11) & 1u) != 0;
            d.rn = hw & 0x7u;
            const uint32_t imm = (((hw >> 9) & 1u) << 5) | ((hw >> 3) & 0x1Fu);
            d.target = (pc + (imm << 1)) | 1u; // forward only, Thumb target
            d.valid = true;
            return d;
        }

        // IT and hints: 1011 1111 xxxx xxxx
        if ((hw & 0xFF00u) == 0xBF00u)
        {
            const uint8_t mask = hw & 0x0Fu;
            if (mask == 0)
            {
                // NOP / YIELD / WFE / WFI / SEV hint: PC-free, copy verbatim.
                d.kind = Kind::kPassThrough;
                d.valid = true;
                return d;
            }
            d.kind = Kind::kIt;
            d.it_count = static_cast<uint8_t>(4u - CountTrailingZeros4(mask));
            d.valid = true;
            return d;
        }

        // B<cond> T1: 1101 cond imm8
        if ((hw & 0xF000u) == 0xD000u)
        {
            const uint8_t cond = (hw >> 8) & 0xFu;
            if (cond == 0xEu || cond == 0xFu)
            {
                // 0xE = UDF (permanently undefined), 0xF = SVC. Not a branch.
                return d; // fail closed (invalid)
            }
            d.kind = Kind::kBranch;
            d.cond = cond;
            const int32_t off = SignExtend((hw & 0xFFu) << 1, 9);
            d.target = (static_cast<uint32_t>(static_cast<int32_t>(pc) + off)) | 1u;
            d.valid = true;
            return d;
        }

        // B T2 (unconditional): 11100 imm11
        if ((hw & 0xF800u) == 0xE000u)
        {
            d.kind = Kind::kBranch;
            d.cond = 14;
            const int32_t off = SignExtend((hw & 0x7FFu) << 1, 12);
            d.target = (static_cast<uint32_t>(static_cast<int32_t>(pc) + off)) | 1u;
            d.valid = true;
            return d;
        }

        // BX / BLX (register) T1: 0100 0111 L Rm 000
        if ((hw & 0xFF00u) == 0x4700u)
        {
            const uint8_t rm = (hw >> 3) & 0xFu;
            if (rm == 15u)
            {
                return d; // BX/BLX PC reads PC -> fail closed
            }
            // Register-indirect branch: the destination is a runtime register
            // value, unaffected by relocation. Copy verbatim.
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }

        // High-register ADD/CMP/MOV: 0100 01xx ...  (can name PC)
        if ((hw & 0xFC00u) == 0x4400u)
        {
            const uint8_t opsub = (hw >> 8) & 0x3u; // 00 ADD, 01 CMP, 10 MOV, 11 (BX handled above)
            if (opsub == 0x0u)
            { // ADD Rdn, Rm
                const uint8_t rm = (hw >> 3) & 0xFu;
                const uint8_t rdn = ((hw >> 4) & 0x8u) | (hw & 0x7u);
                if (rm == 15u || rdn == 15u)
                {
                    return d; // PC read/write -> fail closed
                }
            }
            else if (opsub == 0x1u)
            { // CMP Rn, Rm
                const uint8_t rm = (hw >> 3) & 0xFu;
                const uint8_t rn = ((hw >> 4) & 0x8u) | (hw & 0x7u);
                if (rm == 15u || rn == 15u)
                {
                    return d;
                }
            }
            else if (opsub == 0x2u)
            { // MOV Rd, Rm
                const uint8_t rm = (hw >> 3) & 0xFu;
                const uint8_t rd = ((hw >> 4) & 0x8u) | (hw & 0x7u);
                if (rm == 15u || rd == 15u)
                {
                    return d;
                }
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }

        // Everything else in the 16-bit space addresses only r0-r7 / SP (data
        // processing, shifts, PUSH/POP, load/store [SP or low reg], extends,
        // etc.) and cannot encode PC. POP {..,PC} is a verbatim-safe return.
        d.kind = Kind::kPassThrough;
        d.valid = true;
        return d;
    }

    // ---------------------------------------------------------------------------
    // Thumb 32-bit decode
    // ---------------------------------------------------------------------------

    inline Decoded DecodeThumb32(uint16_t hw1, uint16_t hw2, uint32_t addr)
    {
        Decoded d;
        d.length = 4;
        const uint32_t pc = addr + 4;

        // ---- Branches / control (11110 xxxxxxxxxx | 1 xx x xxxxxxxxxxx) ----
        if ((hw1 & 0xF800u) == 0xF000u && (hw2 & 0x8000u) == 0x8000u)
        {
            const uint32_t klass = hw2 & 0x5000u; // bits14,12
            const uint32_t S = (hw1 >> 10) & 1u;
            const uint32_t J1 = (hw2 >> 13) & 1u;
            const uint32_t J2 = (hw2 >> 11) & 1u;
            const uint32_t imm11 = hw2 & 0x7FFu;
            const uint32_t imm10 = hw1 & 0x3FFu;
            const uint32_t I1 = 1u ^ (J1 ^ S);
            const uint32_t I2 = 1u ^ (J2 ^ S);

            if (klass == 0x0000u)
            {
                // B<cond>.W T3: 11110 S cond imm6 | 10 J1 0 J2 imm11
                const uint8_t cond = (hw1 >> 6) & 0xFu;
                if (cond >= 0xEu)
                {
                    // cond 14/15 slot => MSR/misc control, not a branch.
                    return d; // fail closed (not modelled)
                }
                const uint32_t imm6 = hw1 & 0x3Fu;
                const uint32_t off = (S << 20) | (J2 << 19) | (J1 << 18) |
                                     (imm6 << 12) | (imm11 << 1);
                d.kind = Kind::kBranch;
                d.cond = cond;
                d.target = (static_cast<uint32_t>(static_cast<int32_t>(pc) +
                                                  SignExtend(off, 21))) |
                           1u;
                d.valid = true;
                return d;
            }
            if (klass == 0x1000u)
            {
                // B.W T4 (unconditional)
                const uint32_t off = (S << 24) | (I1 << 23) | (I2 << 22) |
                                     (imm10 << 12) | (imm11 << 1);
                d.kind = Kind::kBranch;
                d.cond = 14;
                d.target = (static_cast<uint32_t>(static_cast<int32_t>(pc) +
                                                  SignExtend(off, 25))) |
                           1u;
                d.valid = true;
                return d;
            }
            if (klass == 0x5000u)
            {
                // BL T1 (Thumb -> Thumb)
                const uint32_t off = (S << 24) | (I1 << 23) | (I2 << 22) |
                                     (imm10 << 12) | (imm11 << 1);
                d.kind = Kind::kBranchLink;
                d.cond = 14;
                d.exchange = false;
                d.target = (static_cast<uint32_t>(static_cast<int32_t>(pc) +
                                                  SignExtend(off, 25))) |
                           1u;
                d.valid = true;
                return d;
            }
            // klass == 0x4000u : BLX T2 (Thumb -> ARM)
            const uint32_t imm10L = (hw2 >> 1) & 0x3FFu;
            const uint32_t off = (S << 24) | (I1 << 23) | (I2 << 22) |
                                 (imm10 << 12) | (imm10L << 2);
            d.kind = Kind::kBranchLink;
            d.cond = 14;
            d.exchange = true;
            d.target = static_cast<uint32_t>(
                static_cast<int32_t>(AlignDown4(pc)) + SignExtend(off, 25));
            // ARM target: bit0 stays 0.
            d.valid = true;
            return d;
        }

        // ---- PC-relative literal loads (Rn == 1111) ----
        // LDR.W / LDRB / LDRH / LDRSB / LDRSH (literal). Masks drop the U bit.
        {
            const uint16_t base = hw1 & 0xFF7Fu;
            uint8_t load_bytes = 0;
            bool load_signed = false;
            bool is_literal_load = true;
            switch (base)
            {
            case 0xF85Fu:
                load_bytes = 4;
                break; // LDR.W
            case 0xF81Fu:
                load_bytes = 1;
                break; // LDRB
            case 0xF83Fu:
                load_bytes = 2;
                break; // LDRH
            case 0xF91Fu:
                load_bytes = 1;
                load_signed = true;
                break; // LDRSB
            case 0xF93Fu:
                load_bytes = 2;
                load_signed = true;
                break; // LDRSH
            default:
                is_literal_load = false;
                break;
            }
            if (is_literal_load)
            {
                const uint8_t rt = (hw2 >> 12) & 0xFu;
                if (rt == 15u)
                {
                    return d; // LDR PC,[PC] -> computed jump, fail closed
                }
                const uint32_t U = (hw1 >> 7) & 1u;
                const uint32_t imm12 = hw2 & 0xFFFu;
                d.kind = Kind::kLdrLiteral;
                d.rt = rt;
                d.load_bytes = load_bytes;
                d.load_signed = load_signed;
                d.literal_addr = U ? (AlignDown4(pc) + imm12)
                                   : (AlignDown4(pc) - imm12);
                d.valid = true;
                return d;
            }
        }

        // ---- ADR.W (ADD/SUB Rd, PC, #imm12) ----
        if ((hw1 & 0xFBFFu) == 0xF20Fu && (hw2 & 0x8000u) == 0u)
        { // ADR.W add (T3)
            const uint8_t rd = (hw2 >> 8) & 0xFu;
            if (rd == 15u)
            {
                return d;
            }
            const uint32_t i = (hw1 >> 10) & 1u;
            const uint32_t imm3 = (hw2 >> 12) & 0x7u;
            const uint32_t imm8 = hw2 & 0xFFu;
            const uint32_t imm12 = (i << 11) | (imm3 << 8) | imm8;
            d.kind = Kind::kAdr;
            d.rd = rd;
            d.adr_value = AlignDown4(pc) + imm12;
            d.valid = true;
            return d;
        }
        if ((hw1 & 0xFBFFu) == 0xF2AFu && (hw2 & 0x8000u) == 0u)
        { // ADR.W sub (T2)
            const uint8_t rd = (hw2 >> 8) & 0xFu;
            if (rd == 15u)
            {
                return d;
            }
            const uint32_t i = (hw1 >> 10) & 1u;
            const uint32_t imm3 = (hw2 >> 12) & 0x7u;
            const uint32_t imm8 = hw2 & 0xFFu;
            const uint32_t imm12 = (i << 11) | (imm3 << 8) | imm8;
            d.kind = Kind::kAdr;
            d.rd = rd;
            d.adr_value = AlignDown4(pc) - imm12;
            d.valid = true;
            return d;
        }

        // ---- Fail closed: TBB/TBH table branches ----
        if ((hw1 & 0xFFF0u) == 0xE8D0u && (hw2 & 0xFFE0u) == 0xF000u)
        {
            return d; // table branch: PC-relative data table, not relocated
        }

        // ---- Conservative pass-through allow-list for common prologue T32 ----
        // Only encodings proven PC-free are copied verbatim; everything else
        // fails closed.
        //
        // PUSH.W / STMDB SP! : 1110 1001 0010 1101 (0xE92D)  reglist can hold LR
        // (bit14) but not PC (bit15 must be 0 for STM); enforce that.
        if (hw1 == 0xE92Du)
        {
            if ((hw2 & 0x8000u) == 0u)
            {
                d.kind = Kind::kPassThrough;
                d.valid = true;
                return d;
            }
            return d; // STM with PC in list is unpredictable -> fail closed
        }
        // POP.W / LDMIA SP! : 1110 1000 1011 1101 (0xE8BD). PC in list is a
        // verbatim-safe return.
        if (hw1 == 0xE8BDu)
        {
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        // MOVW / MOVT (imm): 1111 0i10 0100 xxxx (MOVW) / 1111 0i10 1100 xxxx
        // (MOVT). Rd = hw2[11:8]; no PC source, reject Rd == 15.
        if ((hw1 & 0xFBF0u) == 0xF240u || (hw1 & 0xFBF0u) == 0xF2C0u)
        {
            const uint8_t rd = (hw2 >> 8) & 0xFu;
            if (rd == 15u)
            {
                return d;
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        // ADD.W / SUB.W (imm), any Rn, Rd != PC. Covers frame set-up
        // (`SUB.W sp,sp,#imm` / `ADD.W sp,sp,#imm`, `ADD Rd,sp,#imm`) whether the
        // assembler picks the T3 (modified-immediate) or T4 (plain "ADDW"/"SUBW")
        // form. T32 data-processing cannot read the PC value (unlike A32), and
        // Rn==15 is either an ADR (handled above) or unpredictable, so require
        // Rn != 15 and Rd != 15 for a verbatim copy.
        {
            const uint8_t rn = hw1 & 0xFu;
            const uint8_t rd = (hw2 >> 8) & 0xFu;
            const bool add_t3 = (hw1 & 0xF9E0u) == 0xF100u; // ADD (imm) T3
            const bool sub_t3 = (hw1 & 0xF9E0u) == 0xF1A0u; // SUB (imm) T3
            const bool add_t4 = (hw1 & 0xFBF0u) == 0xF200u; // ADDW (imm) T4
            const bool sub_t4 = (hw1 & 0xFBF0u) == 0xF2A0u; // SUBW (imm) T4
            if ((add_t3 || sub_t3 || add_t4 || sub_t4) && rn != 15u && rd != 15u)
            {
                d.kind = Kind::kPassThrough;
                d.valid = true;
                return d;
            }
        }

        // Load/store single (11111 00x ...): STR(B/H)/LDR(B/H).W [Rn,#imm|,Rm].
        // The PC-relative literal forms (Rn==1111) were handled above; a remaining
        // Rn==15 fails closed, as does a PC destination/source. Memory hints
        // (PLD/PLI) carry Rt==1111 and are rejected here (safe).
        if ((hw1 & 0xFE00u) == 0xF800u)
        {
            const uint8_t rn = hw1 & 0xFu;
            const uint8_t rt = (hw2 >> 12) & 0xFu;
            if (rn != 15u && rt != 15u)
            {
                d.kind = Kind::kPassThrough;
                d.valid = true;
                return d;
            }
            return d;
        }

        // Not on the allow-list: fail closed.
        return d;
    }

    // ---------------------------------------------------------------------------
    // ARM (A32) decode
    // ---------------------------------------------------------------------------

    inline Decoded DecodeArm(uint32_t insn, uint32_t addr)
    {
        Decoded d;
        d.length = 4;
        const uint32_t pc = addr + 8; // ARM reads PC as this instruction + 8
        const uint32_t cond = (insn >> 28) & 0xFu;

        // ---- B / BL / BLX(imm): cond 101 L imm24, or 1111 101H (BLX) ----
        if ((insn & 0x0E000000u) == 0x0A000000u)
        {
            const int32_t off = SignExtend((insn & 0x00FFFFFFu) << 2, 26);
            if (cond == 0xFu)
            {
                // BLX (imm), unconditional, target is Thumb.
                const uint32_t h = (insn >> 24) & 1u;
                const uint32_t target =
                    static_cast<uint32_t>(static_cast<int32_t>(pc) + off) | (h << 1);
                d.kind = Kind::kBranchLink;
                d.cond = 14;
                d.exchange = true;
                d.target = target | 1u; // Thumb target
                d.valid = true;
                return d;
            }
            const bool link = ((insn >> 24) & 1u) != 0;
            d.kind = link ? Kind::kBranchLink : Kind::kBranch;
            d.cond = static_cast<uint8_t>(cond);
            d.exchange = false;
            d.target = static_cast<uint32_t>(static_cast<int32_t>(pc) + off);
            // ARM target: bit0 stays 0.
            d.valid = true;
            return d;
        }

        if (cond == 0xFu)
        {
            // Other unconditional-space encodings (PLD, misc) are not modelled.
            return d; // fail closed
        }

        // ---- LDR (literal, word): cond 010 P U 0 W 1 1111 Rt imm12 ----
        if ((insn & 0x0E500000u) == 0x04100000u && ((insn >> 16) & 0xFu) == 0xFu)
        {
            const uint8_t rt = (insn >> 12) & 0xFu;
            if (rt == 15u)
            {
                return d;
            }
            const uint32_t U = (insn >> 23) & 1u;
            const uint32_t imm12 = insn & 0xFFFu;
            d.kind = Kind::kLdrLiteral;
            d.rt = rt;
            d.load_bytes = 4;
            d.literal_addr = U ? (pc + imm12) : (pc - imm12);
            d.valid = true;
            return d;
        }

        // ---- ADR (ARM): ADD/SUB Rd, PC, #modimm ----
        if ((insn & 0x0FFF0000u) == 0x028F0000u)
        { // ADD Rd, PC, #imm
            const uint8_t rd = (insn >> 12) & 0xFu;
            if (rd == 15u)
            {
                return d;
            }
            d.kind = Kind::kAdr;
            d.rd = rd;
            d.adr_value = pc + ArmExpandImm(insn & 0xFFFu);
            d.valid = true;
            return d;
        }
        if ((insn & 0x0FFF0000u) == 0x024F0000u)
        { // SUB Rd, PC, #imm
            const uint8_t rd = (insn >> 12) & 0xFu;
            if (rd == 15u)
            {
                return d;
            }
            d.kind = Kind::kAdr;
            d.rd = rd;
            d.adr_value = pc - ArmExpandImm(insn & 0xFFFu);
            d.valid = true;
            return d;
        }

        // ---- Conservative PC-free pass-through allow-list ----
        const uint8_t rn = (insn >> 16) & 0xFu;
        const uint8_t rd = (insn >> 12) & 0xFu;
        const uint8_t rs = (insn >> 8) & 0xFu;
        const uint8_t rm = insn & 0xFu;
        const uint32_t op = (insn >> 25) & 0x7u; // bits[27:25]

        if (op == 0b100u)
        {
            // Load/store multiple (STM/LDM, incl. PUSH/POP). PC may appear only
            // in an LDM reglist (a verbatim-safe return); the base Rn must not
            // be PC. Reject STM (bit20==0) with PC in list (bit15).
            const bool load = ((insn >> 20) & 1u) != 0;
            const bool pc_in_list = ((insn >> 15) & 1u) != 0;
            if (rn == 15u)
            {
                return d;
            }
            if (!load && pc_in_list)
            {
                return d; // STM including PC is unpredictable
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        if (op == 0b001u)
        {
            // Data-processing (immediate). Reads Rn, writes Rd. Rn==15 would be
            // an ADR-class read (handled above only for ADD/SUB) -> fail closed.
            if (rn == 15u || rd == 15u)
            {
                return d;
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        if (op == 0b000u)
        {
            // Data-processing (register / register-shifted register).
            const bool reg_shift = ((insn >> 4) & 1u) != 0 && ((insn >> 7) & 1u) == 0;
            if (rn == 15u || rd == 15u || rm == 15u || (reg_shift && rs == 15u))
            {
                return d;
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        if (op == 0b010u)
        {
            // Load/store, immediate offset. Rn==15 is a literal (handled above
            // for word loads) -> anything left with Rn==15 fails closed.
            if (rn == 15u || rd == 15u)
            {
                return d;
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }
        if (op == 0b011u && ((insn >> 4) & 1u) == 0u)
        {
            // Load/store, register offset (bit4==0 excludes media/undefined).
            if (rn == 15u || rd == 15u || rm == 15u)
            {
                return d;
            }
            d.kind = Kind::kPassThrough;
            d.valid = true;
            return d;
        }

        // Unknown / unmodelled class: fail closed.
        return d;
    }

    // ---------------------------------------------------------------------------
    // Encoders (append little-endian bytes)
    // ---------------------------------------------------------------------------

    inline void EmitHalf(std::vector<uint8_t> &out, uint16_t hw)
    {
        out.push_back(static_cast<uint8_t>(hw & 0xFFu));
        out.push_back(static_cast<uint8_t>((hw >> 8) & 0xFFu));
    }

    inline void EmitWord(std::vector<uint8_t> &out, uint32_t w)
    {
        out.push_back(static_cast<uint8_t>(w & 0xFFu));
        out.push_back(static_cast<uint8_t>((w >> 8) & 0xFFu));
        out.push_back(static_cast<uint8_t>((w >> 16) & 0xFFu));
        out.push_back(static_cast<uint8_t>((w >> 24) & 0xFFu));
    }

    inline void EmitThumb32(std::vector<uint8_t> &out, uint16_t hw1, uint16_t hw2)
    {
        EmitHalf(out, hw1);
        EmitHalf(out, hw2);
    }

    // MOVW/MOVT (Thumb) into Rd (Rd != 15).
    inline void EmitThumbMovwMovt(std::vector<uint8_t> &out, uint8_t rd, uint32_t value)
    {
        const uint32_t lo = value & 0xFFFFu;
        const uint32_t hi = (value >> 16) & 0xFFFFu;
        auto enc = [&](uint16_t op, uint32_t imm16)
        {
            const uint32_t i = (imm16 >> 11) & 1u;
            const uint32_t imm4 = (imm16 >> 12) & 0xFu;
            const uint32_t imm3 = (imm16 >> 8) & 0x7u;
            const uint32_t imm8 = imm16 & 0xFFu;
            const uint16_t hw1 = static_cast<uint16_t>(op | (i << 10) | imm4);
            const uint16_t hw2 = static_cast<uint16_t>((imm3 << 12) | (rd << 8) | imm8);
            EmitThumb32(out, hw1, hw2);
        };
        enc(0xF240u, lo); // MOVW
        enc(0xF2C0u, hi); // MOVT
    }

    // MOVW/MOVT (ARM) into Rd, conditional (cond nibble in bits[31:28]).
    inline void EmitArmMovwMovt(std::vector<uint8_t> &out, uint8_t rd, uint32_t value,
                                uint8_t cond)
    {
        const uint32_t lo = value & 0xFFFFu;
        const uint32_t hi = (value >> 16) & 0xFFFFu;
        auto enc = [&](uint32_t opc, uint32_t imm16)
        {
            const uint32_t imm4 = (imm16 >> 12) & 0xFu;
            const uint32_t imm12 = imm16 & 0xFFFu;
            const uint32_t insn = (static_cast<uint32_t>(cond) << 28) | opc |
                                  (imm4 << 16) | (static_cast<uint32_t>(rd) << 12) | imm12;
            EmitWord(out, insn);
        };
        enc(0x03000000u, lo); // MOVW
        enc(0x03400000u, hi); // MOVT
    }

    // Emit a single Thumb load of `load_bytes` (signed/unsigned) from [Rt] into
    // Rt, i.e. `LDR<sz> Rt, [Rt]`. Uses the 16-bit forms where possible; the base
    // register equals the destination register and both are in r0-r7.
    inline bool EmitThumbLoadFromSelf(std::vector<uint8_t> &out, uint8_t rt,
                                      uint8_t load_bytes, bool load_signed)
    {
        if (rt <= 7u && !load_signed)
        {
            // 16-bit LDR/LDRH/LDRB Rt,[Rt,#0]
            switch (load_bytes)
            {
            case 4:
                EmitHalf(out, static_cast<uint16_t>(0x6800u | (rt << 3) | rt));
                return true;
            case 2:
                EmitHalf(out, static_cast<uint16_t>(0x8800u | (rt << 3) | rt));
                return true;
            case 1:
                EmitHalf(out, static_cast<uint16_t>(0x7800u | (rt << 3) | rt));
                return true;
            default:
                return false;
            }
        }
        // 32-bit T3 load forms: LDR/LDRB/LDRH/LDRSB/LDRSH Rt,[Rt,#0]
        uint16_t hw1 = 0;
        switch (load_bytes)
        {
        case 4:
            hw1 = 0xF8D0u; // LDR.W
            break;
        case 2:
            hw1 = load_signed ? 0xF9B0u : 0xF8B0u; // LDRSH / LDRH
            break;
        case 1:
            hw1 = load_signed ? 0xF990u : 0xF890u; // LDRSB / LDRB
            break;
        default:
            return false;
        }
        hw1 = static_cast<uint16_t>(hw1 | rt); // Rn = Rt
        const uint16_t hw2 = static_cast<uint16_t>((static_cast<uint16_t>(rt) << 12));
        EmitThumb32(out, hw1, hw2);
        return true;
    }

    // Emit an ARM load `LDR<sz> Rt,[Rt]` (word only supported here), conditional.
    inline void EmitArmWordLoadFromSelf(std::vector<uint8_t> &out, uint8_t rt, uint8_t cond)
    {
        // LDR Rt,[Rt,#0] : cond 0101 1001 Rt Rt 0000 0000 0000
        const uint32_t insn = (static_cast<uint32_t>(cond) << 28) | 0x05900000u |
                              (static_cast<uint32_t>(rt) << 16) |
                              (static_cast<uint32_t>(rt) << 12);
        EmitWord(out, insn);
    }

    // ---------------------------------------------------------------------------
    // Relocation builder
    // ---------------------------------------------------------------------------

    struct RelocationResult
    {
        bool ok = false;
        const char *reject_reason = "not_attempted";
        std::vector<uint8_t> code; // position-independent trampoline body
        size_t covered = 0;        // original bytes consumed (>= min_patch)
        bool thumb = false;        // instruction set of the trampoline
    };

    // Number of target bytes the inline patch overwrites (and hence the minimum
    // prologue length that must be relocatable).
    //   ARM   : 8  (LDR PC,[PC,#-4] + word)
    //   Thumb : 8 when 4-aligned, else 10 (a NOP aligns the LDR.W PC literal)
    inline size_t Armv7PatchSize(uint32_t addr, bool thumb)
    {
        if (!thumb)
        {
            return 8;
        }
        return ((addr & 3u) == 0u) ? 8u : 10u;
    }

    // Emit the inline patch that overwrites the target prologue with an absolute
    // interworking jump to `dest` (dest carries its Thumb bit). Byte length must
    // equal Armv7PatchSize(addr, thumb).
    inline void EmitArmv7Patch(std::vector<uint8_t> &out, uint32_t addr, bool thumb,
                               uint32_t dest)
    {
        if (!thumb)
        {
            EmitWord(out, 0xE51FF004u); // LDR PC,[PC,#-4]
            EmitWord(out, dest);
            return;
        }
        if ((addr & 3u) != 0u)
        {
            EmitHalf(out, 0xBF00u); // NOP to 4-align the LDR.W PC literal
        }
        EmitThumb32(out, 0xF8DFu, 0xF000u); // LDR.W PC,[PC,#0]
        EmitWord(out, dest);
    }

    namespace detail
    {
        // Append an interworking absolute jump to `target` (with its Thumb bit),
        // 4-aligning the literal as required. `thumb` selects the trampoline ISA.
        inline void EmitAbsoluteJump(std::vector<uint8_t> &out, bool thumb, uint32_t target)
        {
            if (!thumb)
            {
                EmitWord(out, 0xE51FF004u); // LDR PC,[PC,#-4]
                EmitWord(out, target);
                return;
            }
            if ((out.size() & 3u) != 0u)
            {
                EmitHalf(out, 0xBF00u); // NOP: align LDR.W PC so its literal is 4-aligned
            }
            EmitThumb32(out, 0xF8DFu, 0xF000u); // LDR.W PC,[PC,#0]
            EmitWord(out, target);
        }

        // A pending veneer for a conditional / compare branch: a short branch in
        // the main body jumps forward to an absolute veneer appended afterwards.
        struct PendingVeneer
        {
            size_t patch_offset;  // where the short branch halfword lives
            bool thumb_cbz;       // CBZ/CBNZ (needs +imm5 to veneer)
            bool nonzero;         // CBNZ
            uint8_t rn;           // CBZ/CBNZ register
            uint8_t cond;         // conditional branch condition (for B<cond>)
            bool is_cbz;          // distinguishes CBZ vs conditional branch
            uint32_t target;      // absolute branch target (with Thumb bit)
            size_t veneer_offset; // resolved: where the veneer starts
        };
    } // namespace detail

    // Build a position-independent trampoline that relocates the prologue of the
    // function at `addr` (its first `avail` bytes provided in `code`) and jumps
    // back to `addr + covered`. `thumb` selects the instruction set (from bit0 of
    // the original function pointer). Returns ok=false (writes nothing) if any
    // instruction cannot be provably relocated.
    inline RelocationResult BuildTrampoline(const uint8_t *code, size_t avail,
                                            uint32_t addr, bool thumb)
    {
        RelocationResult result;
        result.thumb = thumb;
        const size_t min_patch = Armv7PatchSize(addr, thumb);

        std::vector<detail::PendingVeneer> veneers;

        auto read_u16 = [&](size_t o) -> uint16_t
        {
            return static_cast<uint16_t>(code[o] | (code[o + 1] << 8));
        };
        auto read_u32 = [&](size_t o) -> uint32_t
        {
            return static_cast<uint32_t>(code[o]) |
                   (static_cast<uint32_t>(code[o + 1]) << 8) |
                   (static_cast<uint32_t>(code[o + 2]) << 16) |
                   (static_cast<uint32_t>(code[o + 3]) << 24);
        };

        auto fail = [&](const char *reason) -> RelocationResult
        {
            RelocationResult r;
            r.ok = false;
            r.reject_reason = reason;
            r.thumb = thumb;
            return r;
        };

        // Copy a proven-PC-free instruction verbatim.
        auto emit_verbatim = [&](size_t off, uint8_t len)
        {
            for (uint8_t i = 0; i < len; ++i)
            {
                result.code.push_back(code[off + i]);
            }
        };

        // A relocated branch veneer jumps to the branch's *original* absolute
        // target. If that target lands inside the bytes we overwrite with the
        // patch [addr, addr+min_patch), the veneer would jump onto the patch
        // (i.e. straight back into the replacement) rather than the intended
        // instruction. This relocator does not redirect such intra-prologue
        // branches (unlike the aarch64 path), so it fails closed on them. A
        // target at or beyond addr+min_patch is intact original code and is safe.
        auto branch_into_patch = [&](uint32_t target_with_thumb_bit) -> bool
        {
            const uint32_t t = target_with_thumb_bit & ~1u;
            return t >= addr && t < addr + static_cast<uint32_t>(min_patch);
        };

        size_t offset = 0;
        while (offset < min_patch)
        {
            if (thumb)
            {
                if (offset + 2 > avail)
                {
                    return fail("prologue_shorter_than_patch");
                }
                const uint16_t hw0 = read_u16(offset);
                const bool is32 = IsThumb32(hw0);
                if (is32 && offset + 4 > avail)
                {
                    return fail("prologue_shorter_than_patch");
                }
                const uint16_t hw1 = is32 ? read_u16(offset + 2) : 0;
                Decoded d = is32 ? DecodeThumb32(hw0, hw1, addr + offset)
                                 : DecodeThumb16(hw0, addr + offset);
                if (!d.valid)
                {
                    return fail("thumb_unrelocatable_instruction");
                }
                if ((d.kind == Kind::kBranch || d.kind == Kind::kBranchLink ||
                     d.kind == Kind::kCompareBranch) &&
                    branch_into_patch(d.target))
                {
                    return fail("thumb_branch_into_patched_region");
                }

                switch (d.kind)
                {
                case Kind::kPassThrough:
                    emit_verbatim(offset, d.length);
                    offset += d.length;
                    break;

                case Kind::kIt:
                {
                    // Copy the IT header + all guarded instructions as one unit;
                    // every guarded instruction must be a single PC-free op.
                    emit_verbatim(offset, 2);
                    size_t g = offset + 2;
                    for (uint8_t n = 0; n < d.it_count; ++n)
                    {
                        if (g + 2 > avail)
                        {
                            return fail("it_block_truncated");
                        }
                        const uint16_t ghw0 = read_u16(g);
                        const bool g32 = IsThumb32(ghw0);
                        if (g32 && g + 4 > avail)
                        {
                            return fail("it_block_truncated");
                        }
                        const uint16_t ghw1 = g32 ? read_u16(g + 2) : 0;
                        Decoded gd = g32 ? DecodeThumb32(ghw0, ghw1, addr + g)
                                         : DecodeThumb16(ghw0, addr + g);
                        if (!gd.valid || gd.kind != Kind::kPassThrough)
                        {
                            return fail("it_guarded_instruction_not_relocatable");
                        }
                        emit_verbatim(g, gd.length);
                        g += gd.length;
                    }
                    offset = g;
                    break;
                }

                case Kind::kLdrLiteral:
                {
                    // Materialise the absolute literal address, then load from it.
                    EmitThumbMovwMovt(result.code, d.rt, d.literal_addr);
                    if (!EmitThumbLoadFromSelf(result.code, d.rt, d.load_bytes,
                                               d.load_signed))
                    {
                        return fail("thumb_ldr_literal_unsupported_form");
                    }
                    offset += d.length;
                    break;
                }

                case Kind::kAdr:
                    EmitThumbMovwMovt(result.code, d.rd, d.adr_value);
                    offset += d.length;
                    break;

                case Kind::kBranch:
                    if (d.cond == 14)
                    {
                        detail::EmitAbsoluteJump(result.code, true, d.target);
                    }
                    else
                    {
                        // Short B<cond> to a veneer appended later. Placeholder
                        // encoded as B<cond> .+0 (T1); patched once layout known.
                        detail::PendingVeneer v{};
                        v.patch_offset = result.code.size();
                        v.is_cbz = false;
                        v.cond = d.cond;
                        v.target = d.target;
                        EmitHalf(result.code,
                                 static_cast<uint16_t>(0xD000u | (d.cond << 8)));
                        veneers.push_back(v);
                    }
                    offset += d.length;
                    break;

                case Kind::kBranchLink:
                {
                    // ADR.W LR, resume ; (Thumb: set LR bit0) ; absolute jump.
                    // resume = trampoline offset just past this whole sequence.
                    // We size the sequence first, then patch the ADR.W imm.
                    const size_t adr_pos = result.code.size();
                    EmitThumb32(result.code, 0xF20Fu, 0x0E00u); // ADR.W LR,#0 (patched)
                    EmitThumb32(result.code, 0xF04Eu, 0x0E01u); // ORR LR,LR,#1 (Thumb bit)
                    detail::EmitAbsoluteJump(result.code, true, d.target);
                    const size_t resume = result.code.size();
                    // Patch ADR.W LR immediate: value = Align(pc,4) + imm12,
                    // pc = (adr_pos + 4). Trampoline base is 4-aligned so use
                    // trampoline offsets directly.
                    const uint32_t adr_pc = AlignDown4(static_cast<uint32_t>(adr_pos) + 4u);
                    if (resume < adr_pc)
                    {
                        return fail("thumb_bl_veneer_range");
                    }
                    const uint32_t imm12 = static_cast<uint32_t>(resume) - adr_pc;
                    if (imm12 > 0xFFFu)
                    {
                        return fail("thumb_bl_veneer_range");
                    }
                    const uint32_t i = (imm12 >> 11) & 1u;
                    const uint32_t imm3 = (imm12 >> 8) & 0x7u;
                    const uint32_t imm8 = imm12 & 0xFFu;
                    const uint16_t hw1p = static_cast<uint16_t>(0xF20Fu | (i << 10));
                    const uint16_t hw2p =
                        static_cast<uint16_t>((imm3 << 12) | (14u << 8) | imm8);
                    result.code[adr_pos] = static_cast<uint8_t>(hw1p & 0xFFu);
                    result.code[adr_pos + 1] = static_cast<uint8_t>(hw1p >> 8);
                    result.code[adr_pos + 2] = static_cast<uint8_t>(hw2p & 0xFFu);
                    result.code[adr_pos + 3] = static_cast<uint8_t>(hw2p >> 8);
                    offset += d.length;
                    break;
                }

                case Kind::kCompareBranch:
                {
                    detail::PendingVeneer v{};
                    v.patch_offset = result.code.size();
                    v.is_cbz = true;
                    v.nonzero = d.nonzero;
                    v.rn = d.rn;
                    v.target = d.target;
                    // Placeholder CBZ/CBNZ Rn, .+0 ; patched once layout known.
                    EmitHalf(result.code,
                             static_cast<uint16_t>(0xB100u | (d.nonzero ? 0x0800u : 0u) |
                                                   d.rn));
                    veneers.push_back(v);
                    offset += d.length;
                    break;
                }

                default:
                    return fail("thumb_unrelocatable_instruction");
                }
            }
            else // ARM
            {
                if (offset + 4 > avail)
                {
                    return fail("prologue_shorter_than_patch");
                }
                const uint32_t insn = read_u32(offset);
                Decoded d = DecodeArm(insn, addr + offset);
                if (!d.valid)
                {
                    return fail("arm_unrelocatable_instruction");
                }
                if ((d.kind == Kind::kBranch || d.kind == Kind::kBranchLink) &&
                    branch_into_patch(d.target))
                {
                    return fail("arm_branch_into_patched_region");
                }

                switch (d.kind)
                {
                case Kind::kPassThrough:
                    emit_verbatim(offset, 4);
                    offset += 4;
                    break;

                case Kind::kLdrLiteral:
                    EmitArmMovwMovt(result.code, d.rt, d.literal_addr, d.cond);
                    EmitArmWordLoadFromSelf(result.code, d.rt, d.cond);
                    offset += 4;
                    break;

                case Kind::kAdr:
                    EmitArmMovwMovt(result.code, d.rd, d.adr_value, d.cond);
                    offset += 4;
                    break;

                case Kind::kBranch:
                    if (d.cond == 14)
                    {
                        detail::EmitAbsoluteJump(result.code, false, d.target);
                    }
                    else
                    {
                        // Conditional absolute jump in one instruction:
                        // LDR<cond> PC,[PC,#-4] ; .word target
                        const uint32_t insn2 =
                            (static_cast<uint32_t>(d.cond) << 28) | 0x051FF004u;
                        EmitWord(result.code, insn2);
                        EmitWord(result.code, d.target);
                    }
                    offset += 4;
                    break;

                case Kind::kBranchLink:
                {
                    // ADD LR,PC,#imm (resume) ; LDR PC,[PC,#-4] ; .word target
                    const size_t add_pos = result.code.size();
                    EmitWord(result.code, 0xE28FE000u); // ADD LR,PC,#0 (patched)
                    EmitWord(result.code, 0xE51FF004u); // LDR PC,[PC,#-4]
                    EmitWord(result.code, d.target);
                    const size_t resume = result.code.size();
                    // ADD LR,PC,#imm : LR = (add_pos + 8) + imm.
                    const uint32_t add_pc = static_cast<uint32_t>(add_pos) + 8u;
                    if (resume < add_pc)
                    {
                        return fail("arm_bl_veneer_range");
                    }
                    const uint32_t delta = static_cast<uint32_t>(resume) - add_pc;
                    if (delta > 0xFFu)
                    {
                        // Keep it simple: resume is a few words away, always < 256.
                        return fail("arm_bl_veneer_range");
                    }
                    const uint32_t patched = 0xE28FE000u | delta;
                    result.code[add_pos] = static_cast<uint8_t>(patched & 0xFFu);
                    result.code[add_pos + 1] = static_cast<uint8_t>((patched >> 8) & 0xFFu);
                    result.code[add_pos + 2] = static_cast<uint8_t>((patched >> 16) & 0xFFu);
                    result.code[add_pos + 3] = static_cast<uint8_t>((patched >> 24) & 0xFFu);
                    offset += 4;
                    break;
                }

                default:
                    return fail("arm_unrelocatable_instruction");
                }
            }
        }

        // Jump back to the original function just past the relocated prologue.
        const uint32_t resume_target =
            (addr + static_cast<uint32_t>(offset)) | (thumb ? 1u : 0u);
        detail::EmitAbsoluteJump(result.code, thumb, resume_target);

        // Append veneers for conditional / compare branches and patch the short
        // branches to reach them.
        for (auto &v : veneers)
        {
            if (thumb && (result.code.size() & 1u) != 0u)
            {
                EmitHalf(result.code, 0xBF00u); // keep halfword alignment
            }
            v.veneer_offset = result.code.size();
            detail::EmitAbsoluteJump(result.code, true, v.target);

            if (v.is_cbz)
            {
                // CBZ/CBNZ imm5: dest = Align(pc,4)+ (imm<<1)? No: CB(N)Z uses
                // pc = branch_addr + 4, dest = pc + (imm5<<1), forward only.
                const uint32_t branch_pc = static_cast<uint32_t>(v.patch_offset) + 4u;
                if (v.veneer_offset < branch_pc)
                {
                    return fail("cbz_veneer_backward");
                }
                const uint32_t dist = static_cast<uint32_t>(v.veneer_offset) - branch_pc;
                if ((dist & 1u) != 0u || (dist >> 1) > 0x3Fu)
                {
                    return fail("cbz_veneer_out_of_range");
                }
                const uint32_t imm5 = (dist >> 1) & 0x1Fu;
                const uint32_t i = (dist >> 6) & 1u;
                uint16_t hw = static_cast<uint16_t>(0xB100u | (v.nonzero ? 0x0800u : 0u) |
                                                    (i << 9) | (imm5 << 3) | v.rn);
                result.code[v.patch_offset] = static_cast<uint8_t>(hw & 0xFFu);
                result.code[v.patch_offset + 1] = static_cast<uint8_t>(hw >> 8);
            }
            else
            {
                // B<cond> T1 imm8: pc = branch_addr + 4, dest = pc + (imm8<<1).
                const uint32_t branch_pc = static_cast<uint32_t>(v.patch_offset) + 4u;
                const int32_t dist =
                    static_cast<int32_t>(v.veneer_offset) - static_cast<int32_t>(branch_pc);
                if ((dist & 1) != 0 || dist < -256 || dist > 254)
                {
                    return fail("cond_veneer_out_of_range");
                }
                const uint32_t imm8 = static_cast<uint32_t>((dist >> 1) & 0xFF);
                uint16_t hw = static_cast<uint16_t>(0xD000u | (v.cond << 8) | imm8);
                result.code[v.patch_offset] = static_cast<uint8_t>(hw & 0xFFu);
                result.code[v.patch_offset + 1] = static_cast<uint8_t>(hw >> 8);
            }
        }

        result.ok = true;
        result.reject_reason = "";
        result.covered = offset;
        return result;
    }

} // namespace echidna::runtime::armv7
