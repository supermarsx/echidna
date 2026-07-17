#include "runtime/armv7_instruction.h"

/**
 * @file armv7_instruction_test.cpp
 * @brief Host harness for the ARM (A32) / Thumb-2 inline-hook decoder and
 * position-independent prologue relocator (runtime/armv7_instruction.h).
 *
 * No ARM execution is required: the decode logic and the relocation *builder*
 * are pure functions over instruction bytes, so this runs on any host (mirrors
 * tests/aarch64_instruction_test.cpp). It asserts, for a corpus of Thumb-16,
 * Thumb-32 and ARM prologues, that the relocator either (a) relocates the
 * prologue correctly, or (b) safely rejects it (ok=false, emits nothing). It
 * also unit-tests the individual decoders and the inline patch encoder.
 */

#include <cstdint>
#include <cstdio>
#include <vector>

namespace
{
    using namespace echidna::runtime::armv7;

    int g_failures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++g_failures;
        }
    }

    // Byte-builders (little-endian) for building fixtures.
    void T16(std::vector<uint8_t> &v, uint16_t hw)
    {
        v.push_back(static_cast<uint8_t>(hw & 0xFF));
        v.push_back(static_cast<uint8_t>(hw >> 8));
    }
    void T32(std::vector<uint8_t> &v, uint16_t hw1, uint16_t hw2)
    {
        T16(v, hw1);
        T16(v, hw2);
    }
    void A32(std::vector<uint8_t> &v, uint32_t insn)
    {
        v.push_back(static_cast<uint8_t>(insn & 0xFF));
        v.push_back(static_cast<uint8_t>((insn >> 8) & 0xFF));
        v.push_back(static_cast<uint8_t>((insn >> 16) & 0xFF));
        v.push_back(static_cast<uint8_t>((insn >> 24) & 0xFF));
    }

    // Does `hay` contain the byte subsequence `needle`?
    bool Contains(const std::vector<uint8_t> &hay, const std::vector<uint8_t> &needle)
    {
        if (needle.empty() || needle.size() > hay.size())
        {
            return false;
        }
        for (size_t i = 0; i + needle.size() <= hay.size(); ++i)
        {
            bool match = true;
            for (size_t j = 0; j < needle.size(); ++j)
            {
                if (hay[i + j] != needle[j])
                {
                    match = false;
                    break;
                }
            }
            if (match)
            {
                return true;
            }
        }
        return false;
    }

    std::vector<uint8_t> WordBytes(uint32_t w)
    {
        std::vector<uint8_t> v;
        EmitWord(v, w);
        return v;
    }

    // ------------------------------------------------------------------
    // Width detection
    // ------------------------------------------------------------------
    void TestWidth()
    {
        Check(!IsThumb32(0x4801), "16-bit LDR literal must be Thumb-16");
        Check(!IsThumb32(0xB580), "PUSH must be Thumb-16");
        Check(!IsThumb32(0xE7FE), "B T2 (0xE000-0xE7FF) must be Thumb-16");
        Check(IsThumb32(0xF8DF), "0xF8xx must be Thumb-32");
        Check(IsThumb32(0xE92D), "0xE92D (STMDB.W) must be Thumb-32");
        Check(IsThumb32(0xF000), "0xF000 (branch/data-proc) must be Thumb-32");
    }

    // ------------------------------------------------------------------
    // Thumb-16 decode
    // ------------------------------------------------------------------
    void TestThumb16Decode()
    {
        const uint32_t addr = 0x1000;
        const uint32_t pc = addr + 4;

        Decoded ldr = DecodeThumb16(0x4801, addr); // LDR r0,[pc,#4]
        Check(ldr.valid && ldr.kind == Kind::kLdrLiteral && ldr.length == 2 &&
                  ldr.rt == 0 && ldr.load_bytes == 4 &&
                  ldr.literal_addr == AlignDown4(pc) + 4,
              "Thumb-16 LDR literal decode");

        Decoded adr = DecodeThumb16(0xA102, addr); // ADR r1,#8
        Check(adr.valid && adr.kind == Kind::kAdr && adr.rd == 1 &&
                  adr.adr_value == AlignDown4(pc) + 8,
              "Thumb-16 ADR decode");

        Decoded cbz = DecodeThumb16(0xB128, addr); // CBZ r0,#(5<<1)
        Check(cbz.valid && cbz.kind == Kind::kCompareBranch && !cbz.nonzero &&
                  cbz.rn == 0 && cbz.target == ((pc + 10) | 1u),
              "Thumb-16 CBZ decode");
        Decoded cbnz = DecodeThumb16(0xB910, addr); // CBNZ r0,#(2<<1)
        Check(cbnz.valid && cbnz.kind == Kind::kCompareBranch && cbnz.nonzero &&
                  cbnz.rn == 0,
              "Thumb-16 CBNZ decode");

        Decoded it1 = DecodeThumb16(0xBF08, addr); // IT EQ (mask 1000)
        Check(it1.valid && it1.kind == Kind::kIt && it1.it_count == 1,
              "Thumb-16 IT (1 guarded) decode");
        Decoded it2 = DecodeThumb16(0xBF04, addr); // ITx (mask 0100)
        Check(it2.valid && it2.kind == Kind::kIt && it2.it_count == 2,
              "Thumb-16 IT (2 guarded) decode");
        Decoded it4 = DecodeThumb16(0xBF01, addr); // ITxxx (mask 0001)
        Check(it4.valid && it4.kind == Kind::kIt && it4.it_count == 4,
              "Thumb-16 IT (4 guarded) decode");

        Decoded nop = DecodeThumb16(0xBF00, addr); // NOP
        Check(nop.valid && nop.kind == Kind::kPassThrough, "Thumb-16 NOP pass-through");

        Decoded bcond = DecodeThumb16(0xD0FE, addr); // BEQ .-4
        Check(bcond.valid && bcond.kind == Kind::kBranch && bcond.cond == 0 &&
                  bcond.target == ((pc - 4) | 1u),
              "Thumb-16 B<cond> decode");
        Check(!DecodeThumb16(0xDE00, addr).valid, "Thumb-16 UDF must fail closed");
        Check(!DecodeThumb16(0xDF01, addr).valid, "Thumb-16 SVC must fail closed");

        Decoded b = DecodeThumb16(0xE7FE, addr); // B .-2? -> pc-4
        Check(b.valid && b.kind == Kind::kBranch && b.cond == 14, "Thumb-16 B T2 decode");

        Decoded bx = DecodeThumb16(0x4770, addr); // BX lr
        Check(bx.valid && bx.kind == Kind::kPassThrough, "Thumb-16 BX reg pass-through");
        Check(!DecodeThumb16(0x4778, addr).valid, "Thumb-16 BX PC must fail closed");

        Decoded movsp = DecodeThumb16(0x466F, addr); // MOV r7, sp
        Check(movsp.valid && movsp.kind == Kind::kPassThrough, "Thumb-16 MOV r7,sp");
        Check(!DecodeThumb16(0x4687, addr).valid, "Thumb-16 MOV PC,r0 must fail closed"); // MOV pc,r0
        Check(!DecodeThumb16(0x447F, addr).valid, "Thumb-16 ADD PC,PC must fail closed"); // ADD pc,pc

        Decoded push = DecodeThumb16(0xB5F0, addr); // PUSH {r4-r7,lr}
        Check(push.valid && push.kind == Kind::kPassThrough, "Thumb-16 PUSH pass-through");
        Decoded addsp = DecodeThumb16(0xAF00, addr); // ADD r7,sp,#0
        Check(addsp.valid && addsp.kind == Kind::kPassThrough, "Thumb-16 ADD Rd,sp");
    }

    // ------------------------------------------------------------------
    // Thumb-32 decode
    // ------------------------------------------------------------------
    void TestThumb32Decode()
    {
        const uint32_t addr = 0x1000;
        const uint32_t pc = addr + 4;

        Decoded ldrw = DecodeThumb32(0xF8DF, 0x3010, addr); // LDR.W r3,[pc,#0x10]
        Check(ldrw.valid && ldrw.kind == Kind::kLdrLiteral && ldrw.rt == 3 &&
                  ldrw.load_bytes == 4 && ldrw.literal_addr == AlignDown4(pc) + 0x10,
              "Thumb-32 LDR.W literal decode");
        Decoded ldrbw = DecodeThumb32(0xF81F, 0x2008, addr); // LDRB.W r2,[pc,#8]
        Check(ldrbw.valid && ldrbw.kind == Kind::kLdrLiteral && ldrbw.load_bytes == 1 &&
                  !ldrbw.load_signed,
              "Thumb-32 LDRB.W literal decode");
        Decoded ldrshw = DecodeThumb32(0xF93F, 0x4004, addr); // LDRSH.W r4,[pc,#4]
        Check(ldrshw.valid && ldrshw.kind == Kind::kLdrLiteral && ldrshw.load_bytes == 2 &&
                  ldrshw.load_signed,
              "Thumb-32 LDRSH.W literal decode");
        Check(!DecodeThumb32(0xF8DF, 0xF000, addr).valid,
              "Thumb-32 LDR PC,[pc] literal must fail closed");

        Decoded adrw = DecodeThumb32(0xF20F, 0x0308, addr); // ADR.W r3, pc+8
        Check(adrw.valid && adrw.kind == Kind::kAdr && adrw.rd == 3 &&
                  adrw.adr_value == AlignDown4(pc) + 8,
              "Thumb-32 ADR.W (add) decode");
        Decoded adrws = DecodeThumb32(0xF2AF, 0x0308, addr); // ADR.W r3, pc-8
        Check(adrws.valid && adrws.kind == Kind::kAdr && adrws.adr_value == AlignDown4(pc) - 8,
              "Thumb-32 ADR.W (sub) decode");

        Decoded bl = DecodeThumb32(0xF000, 0xF800, addr); // BL .+4
        Check(bl.valid && bl.kind == Kind::kBranchLink && !bl.exchange &&
                  (bl.target & 1u) == 1u,
              "Thumb-32 BL decode (Thumb target)");
        Decoded blx = DecodeThumb32(0xF000, 0xE800, addr); // BLX .+? (ARM target)
        Check(blx.valid && blx.kind == Kind::kBranchLink && blx.exchange &&
                  (blx.target & 1u) == 0u,
              "Thumb-32 BLX decode (ARM target)");
        Decoded bw = DecodeThumb32(0xF000, 0x9000, addr); // B.W T4
        Check(bw.valid && bw.kind == Kind::kBranch && bw.cond == 14,
              "Thumb-32 B.W (T4) decode");
        Decoded bcondw = DecodeThumb32(0xF000, 0x8000, addr); // B<cond>.W (cond 0 = EQ)
        Check(bcondw.valid && bcondw.kind == Kind::kBranch && bcondw.cond == 0,
              "Thumb-32 B<cond>.W (T3) decode");

        Decoded pushw = DecodeThumb32(0xE92D, 0x4FF0, addr); // PUSH.W {r4-r11,lr}
        Check(pushw.valid && pushw.kind == Kind::kPassThrough, "Thumb-32 PUSH.W pass-through");
        Check(!DecodeThumb32(0xE92D, 0xC000, addr).valid,
              "Thumb-32 STMDB with PC in list must fail closed");
        Decoded popw = DecodeThumb32(0xE8BD, 0x8FF0, addr); // POP.W {r4-r11,pc}
        Check(popw.valid && popw.kind == Kind::kPassThrough, "Thumb-32 POP.W {..,pc} pass-through");

        Decoded movw = DecodeThumb32(0xF241, 0x2034, addr); // MOVW r0,#0x1234
        Check(movw.valid && movw.kind == Kind::kPassThrough, "Thumb-32 MOVW pass-through");
        Decoded movt = DecodeThumb32(0xF2C1, 0x2034, addr); // MOVT r0,#0x1234
        Check(movt.valid && movt.kind == Kind::kPassThrough, "Thumb-32 MOVT pass-through");

        Decoded subw = DecodeThumb32(0xF1AD, 0x0D08, addr); // SUB.W sp,sp,#8
        Check(subw.valid && subw.kind == Kind::kPassThrough, "Thumb-32 SUB.W sp pass-through");
        Decoded addw = DecodeThumb32(0xF10D, 0x0D08, addr); // ADD.W sp,sp,#8
        Check(addw.valid && addw.kind == Kind::kPassThrough, "Thumb-32 ADD.W sp pass-through");
        Decoded subwt4 = DecodeThumb32(0xF2AD, 0x0D08, addr); // SUBW sp,sp,#8
        Check(subwt4.valid && subwt4.kind == Kind::kPassThrough, "Thumb-32 SUBW sp pass-through");
        Decoded strw = DecodeThumb32(0xF8CD, 0x1008, addr); // STR.W r1,[sp,#8]
        Check(strw.valid && strw.kind == Kind::kPassThrough, "Thumb-32 STR.W [sp] pass-through");

        Check(!DecodeThumb32(0xE8DF, 0xF000, addr).valid, "Thumb-32 TBB must fail closed");
        Check(!DecodeThumb32(0xE8DF, 0xF010, addr).valid, "Thumb-32 TBH must fail closed");
    }

    // ------------------------------------------------------------------
    // ARM (A32) decode
    // ------------------------------------------------------------------
    void TestArmDecode()
    {
        const uint32_t addr = 0x2000;
        const uint32_t pc = addr + 8;

        Decoded ldr = DecodeArm(0xE59F3008, addr); // LDR r3,[pc,#8]
        Check(ldr.valid && ldr.kind == Kind::kLdrLiteral && ldr.rt == 3 &&
                  ldr.load_bytes == 4 && ldr.literal_addr == pc + 8,
              "ARM LDR literal decode");
        Decoded ldrneg = DecodeArm(0xE51F3008, addr); // LDR r3,[pc,#-8]
        Check(ldrneg.valid && ldrneg.kind == Kind::kLdrLiteral &&
                  ldrneg.literal_addr == pc - 8,
              "ARM LDR literal (U=0) decode");
        Check(!DecodeArm(0xE59FF008, addr).valid, "ARM LDR PC,[pc] must fail closed");

        Decoded adr = DecodeArm(0xE28F3008, addr); // ADD r3,pc,#8
        Check(adr.valid && adr.kind == Kind::kAdr && adr.rd == 3 && adr.adr_value == pc + 8,
              "ARM ADR (add) decode");
        Decoded adrs = DecodeArm(0xE24F3008, addr); // SUB r3,pc,#8
        Check(adrs.valid && adrs.kind == Kind::kAdr && adrs.adr_value == pc - 8,
              "ARM ADR (sub) decode");

        Decoded b = DecodeArm(0xEA000000, addr); // B .+8 -> pc
        Check(b.valid && b.kind == Kind::kBranch && b.cond == 14 && b.target == pc,
              "ARM B decode");
        Decoded bl = DecodeArm(0xEB000000, addr); // BL .+8
        Check(bl.valid && bl.kind == Kind::kBranchLink && !bl.exchange && b.target == pc,
              "ARM BL decode");
        Decoded beq = DecodeArm(0x0A000000, addr); // BEQ .+8
        Check(beq.valid && beq.kind == Kind::kBranch && beq.cond == 0,
              "ARM B<cond> decode");
        Decoded blx = DecodeArm(0xFA000001, addr); // BLX (imm) -> Thumb
        Check(blx.valid && blx.kind == Kind::kBranchLink && blx.exchange &&
                  (blx.target & 1u) == 1u,
              "ARM BLX (imm) decode -> Thumb target");

        Decoded push = DecodeArm(0xE92D4FF0, addr); // PUSH {r4-r11,lr}
        Check(push.valid && push.kind == Kind::kPassThrough, "ARM PUSH pass-through");
        Check(!DecodeArm(0xE92D8000, addr).valid, "ARM STM with PC in list must fail closed");
        Decoded pop = DecodeArm(0xE8BD8FF0, addr); // POP {r4-r11,pc}
        Check(pop.valid && pop.kind == Kind::kPassThrough, "ARM POP {..,pc} pass-through");
        Decoded movip = DecodeArm(0xE1A0C00D, addr); // MOV ip,sp
        Check(movip.valid && movip.kind == Kind::kPassThrough, "ARM MOV ip,sp pass-through");
        Decoded subsp = DecodeArm(0xE24DD008, addr); // SUB sp,sp,#8
        Check(subsp.valid && subsp.kind == Kind::kPassThrough, "ARM SUB sp pass-through");
        Decoded strsp = DecodeArm(0xE58D1008, addr); // STR r1,[sp,#8]
        Check(strsp.valid && strsp.kind == Kind::kPassThrough, "ARM STR [sp] pass-through");

        Check(!DecodeArm(0xE1A0F00D, addr).valid, "ARM MOV pc,sp must fail closed"); // Rd=PC
        Check(!DecodeArm(0xE790000F, addr).valid || DecodeArm(0xE790000F, addr).kind ==
                                                        Kind::kUnrelocatable,
              "ARM LDR with Rm=PC must fail closed");
    }

    // ------------------------------------------------------------------
    // Inline patch encoder
    // ------------------------------------------------------------------
    void TestPatchEncoder()
    {
        Check(Armv7PatchSize(0x1000, false) == 8, "ARM patch is 8 bytes");
        Check(Armv7PatchSize(0x1000, true) == 8, "Thumb 4-aligned patch is 8 bytes");
        Check(Armv7PatchSize(0x1002, true) == 10, "Thumb 2-aligned patch is 10 bytes");

        std::vector<uint8_t> arm;
        EmitArmv7Patch(arm, 0x1000, false, 0xDEADBEE1);
        // LDR PC,[PC,#-4] (E51FF004) + word
        Check(arm.size() == 8 && arm == std::vector<uint8_t>({0x04, 0xF0, 0x1F, 0xE5,
                                                              0xE1, 0xBE, 0xAD, 0xDE}),
              "ARM patch bytes = LDR PC,[PC,#-4] + dest word");

        std::vector<uint8_t> th;
        EmitArmv7Patch(th, 0x1000, true, 0xDEADBEE1);
        // LDR.W PC,[PC,#0] (F8DF F000) + word
        Check(th.size() == 8 && th[0] == 0xDF && th[1] == 0xF8 && th[2] == 0x00 &&
                  th[3] == 0xF0 && Contains(th, WordBytes(0xDEADBEE1)),
              "Thumb patch bytes = LDR.W PC,[PC] + dest word");

        std::vector<uint8_t> th2;
        EmitArmv7Patch(th2, 0x1002, true, 0xDEADBEE1);
        Check(th2.size() == 10 && th2[0] == 0x00 && th2[1] == 0xBF, // leading NOP
              "Thumb 2-aligned patch begins with an alignment NOP");
    }

    // ------------------------------------------------------------------
    // Relocation builder — correct relocation
    // ------------------------------------------------------------------
    void TestBuildThumbPassthrough()
    {
        // push {r4,r5,r7,lr}; sub sp,#8; add r7,sp,#0   (all pass-through)
        std::vector<uint8_t> code;
        T16(code, 0xB5B0); // push {r4,r5,r7,lr}
        T16(code, 0xB082); // sub sp,#8
        T16(code, 0xAF00); // add r7,sp,#0
        T16(code, 0x4770); // bx lr (padding beyond patch)
        const uint32_t addr = 0x1000;

        RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
        Check(r.ok && r.thumb, "Thumb pass-through prologue relocates");
        Check(r.covered == 8, "Thumb pass-through covers exactly the 8-byte patch");
        // First 8 bytes copied verbatim.
        bool verbatim = true;
        for (size_t i = 0; i < 8; ++i)
        {
            if (r.code[i] != code[i])
            {
                verbatim = false;
            }
        }
        Check(verbatim, "pass-through instructions copied verbatim");
        // Trailing interworking jump back to addr+covered | 1.
        Check(Contains(r.code, WordBytes((addr + 8) | 1u)),
              "trailing jump targets addr+covered with Thumb bit set");
    }

    void TestBuildThumbLdrLiteral()
    {
        // ldr r0,[pc,#4] ; nop ; nop ; nop
        std::vector<uint8_t> code;
        const uint32_t addr = 0x1000;
        T16(code, 0x4801); // ldr r0,[pc,#4]
        T16(code, 0xBF00);
        T16(code, 0xBF00);
        T16(code, 0xBF00);
        RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
        Check(r.ok, "Thumb LDR-literal prologue relocates");
        // Expected materialisation: MOVW/MOVT of the absolute literal address.
        const uint32_t lit = AlignDown4(addr + 4) + 4;
        std::vector<uint8_t> movseq;
        EmitThumbMovwMovt(movseq, 0, lit);
        Check(Contains(r.code, movseq), "LDR-literal materialises the absolute literal address");
        Check(Contains(r.code, WordBytes((addr + r.covered) | 1u)),
              "trailing jump present after LDR-literal relocation");
    }

    void TestBuildThumbBranches()
    {
        const uint32_t addr = 0x1000;
        // beq <far> ; nop ; nop ; nop
        {
            std::vector<uint8_t> code;
            T16(code, 0xD020); // beq .+ (pc + 0x40) forward
            T16(code, 0xBF00);
            T16(code, 0xBF00);
            T16(code, 0xBF00);
            const uint32_t pc = addr + 4;
            const uint32_t target = (pc + 0x40) | 1u;
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(r.ok, "Thumb B<cond> prologue relocates via veneer");
            Check(Contains(r.code, WordBytes(target)),
                  "conditional-branch veneer carries the absolute target");
        }
        // b.w (unconditional) as first instruction
        {
            std::vector<uint8_t> code;
            T32(code, 0xF000, 0x9010); // B.W .+? forward
            T32(code, 0xBF00, 0xBF00); // filler (nop.w-ish; two 16-bit nops)
            Decoded bd = DecodeThumb32(0xF000, 0x9010, addr);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(r.ok, "Thumb B.W prologue relocates");
            Check(Contains(r.code, WordBytes(bd.target)),
                  "B.W relocated as absolute interworking jump to its target");
        }
        // bl <far>
        {
            std::vector<uint8_t> code;
            T32(code, 0xF000, 0xF810); // BL forward
            T32(code, 0xBF00, 0xBF00);
            Decoded bd = DecodeThumb32(0xF000, 0xF810, addr);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(r.ok, "Thumb BL prologue relocates");
            Check(Contains(r.code, WordBytes(bd.target)),
                  "BL veneer carries the absolute call target");
        }
    }

    void TestBuildThumbCbz()
    {
        const uint32_t addr = 0x1000;
        std::vector<uint8_t> code;
        T16(code, 0xB130); // cbz r0, .+? forward
        T16(code, 0xBF00);
        T16(code, 0xBF00);
        T16(code, 0xBF00);
        Decoded cd = DecodeThumb16(0xB130, addr);
        RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
        Check(r.ok, "Thumb CBZ prologue relocates via veneer");
        Check(Contains(r.code, WordBytes(cd.target)),
              "CBZ veneer carries the absolute target");
    }

    void TestBuildThumbIt()
    {
        const uint32_t addr = 0x1000;
        // IT EQ ; moveq r0,r1  (guarded pass-through) ; then padding
        {
            std::vector<uint8_t> code;
            T16(code, 0xBF08); // IT EQ (1 guarded)
            T16(code, 0x4608); // mov r0, r1 (executed if EQ)
            T16(code, 0xB5F0); // push
            T16(code, 0x4770); // bx lr
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(r.ok, "IT block with PC-free guarded instruction relocates");
            // IT + guarded copied verbatim as a unit.
            Check(r.code.size() >= 4 && r.code[0] == 0x08 && r.code[1] == 0xBF &&
                      r.code[2] == 0x08 && r.code[3] == 0x46,
                  "IT header + guarded instruction copied verbatim as a unit");
        }
        // IT EQ guarding a PC-relative LDR literal -> must fail closed (expanding
        // it would corrupt the IT count).
        {
            std::vector<uint8_t> code;
            T16(code, 0xBF08); // IT EQ
            T16(code, 0x4801); // ldreq r0,[pc,#4]  (needs expansion)
            T16(code, 0xBF00);
            T16(code, 0xBF00);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok && r.code.empty(),
                  "IT guarding a PC-relative load fails closed (no half-write)");
        }
    }

    void TestBuildArm()
    {
        const uint32_t addr = 0x2000;
        // push {r4-r11,lr} ; mov ip,sp ; sub sp,sp,#16 ; ...
        {
            std::vector<uint8_t> code;
            A32(code, 0xE92D4FF0); // push
            A32(code, 0xE1A0C00D); // mov ip,sp
            A32(code, 0xE24DD010); // sub sp,sp,#16
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, false);
            Check(r.ok && !r.thumb && r.covered == 8, "ARM pass-through prologue relocates");
            Check(r.code[0] == 0xF0 && r.code[1] == 0x4F && r.code[2] == 0x2D &&
                      r.code[3] == 0xE9,
                  "ARM push copied verbatim");
            Check(Contains(r.code, WordBytes(addr + 8)),
                  "ARM trailing jump targets addr+covered (ARM state)");
        }
        // ldr r3,[pc,#imm] ; ...
        {
            std::vector<uint8_t> code;
            A32(code, 0xE59F3010); // ldr r3,[pc,#0x10]
            A32(code, 0xE1A0C00D); // mov ip,sp
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, false);
            Check(r.ok, "ARM LDR-literal prologue relocates");
            std::vector<uint8_t> movseq;
            EmitArmMovwMovt(movseq, 3, addr + 8 + 0x10, 14);
            Check(Contains(r.code, movseq),
                  "ARM LDR-literal materialises the absolute literal address");
        }
        // conditional b + bl
        {
            std::vector<uint8_t> code;
            A32(code, 0x0A000010); // beq forward
            A32(code, 0xE1A0C00D);
            Decoded bd = DecodeArm(0x0A000010, addr);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, false);
            Check(r.ok, "ARM conditional branch prologue relocates");
            Check(Contains(r.code, WordBytes(bd.target)),
                  "ARM conditional branch relocated as LDR<cond> PC + target word");
        }
        {
            std::vector<uint8_t> code;
            A32(code, 0xEB000010); // bl forward
            A32(code, 0xE1A0C00D);
            Decoded bd = DecodeArm(0xEB000010, addr);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, false);
            Check(r.ok, "ARM BL prologue relocates");
            Check(Contains(r.code, WordBytes(bd.target)),
                  "ARM BL veneer carries the absolute call target");
        }
    }

    // ------------------------------------------------------------------
    // Relocation builder — safe rejection (fail closed)
    // ------------------------------------------------------------------
    void TestRejections()
    {
        const uint32_t addr = 0x1000;
        // TBB in the prologue -> reject.
        {
            std::vector<uint8_t> code;
            T32(code, 0xE8DF, 0xF001); // TBB [pc, r1]
            T32(code, 0xBF00, 0xBF00);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok && r.code.empty() && r.reject_reason[0] != '\0',
                  "TBB prologue is rejected with a reason and no output");
        }
        // Unmodelled Thumb-32 (e.g. AND.W r0,r1,#imm) -> reject (conservative).
        {
            std::vector<uint8_t> code;
            T32(code, 0xF001, 0x0002); // AND.W r0,r1,#imm (not on allow-list)
            T32(code, 0xBF00, 0xBF00);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok && r.code.empty(),
                  "unmodelled Thumb-32 data-processing fails closed");
        }
        // Prologue shorter than the patch window -> reject.
        {
            std::vector<uint8_t> code;
            T16(code, 0xB580); // push {r7,lr}  (only 2 bytes available)
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok, "prologue shorter than the patch window fails closed");
        }
        // Truncated IT block (guarded instruction runs past the buffer) -> reject.
        {
            std::vector<uint8_t> code;
            T16(code, 0xBF01); // IT ... (4 guarded) but no instructions follow
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok, "truncated IT block fails closed");
        }
        // ARM unknown/unmodelled (coprocessor) -> reject.
        {
            std::vector<uint8_t> code;
            A32(code, 0xEE070F9A); // mcr p15,... (coprocessor)
            A32(code, 0xE1A0C00D);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, false);
            Check(!r.ok && r.code.empty(), "ARM coprocessor instruction fails closed");
        }
        // Thumb branch whose target lands inside the overwritten patch bytes ->
        // reject (jumping there would hit the patch, not the intended code).
        {
            std::vector<uint8_t> code;
            T16(code, 0xD0FF); // BEQ .-2 -> 0x1002, inside [0x1000,0x1008)
            T16(code, 0xBF00);
            T16(code, 0xBF00);
            T16(code, 0xBF00);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), addr, true);
            Check(!r.ok && r.code.empty(),
                  "Thumb branch into the patched region fails closed");
        }
        // ARM branch into the patched region -> reject.
        {
            const uint32_t arm_addr = 0x2000;
            std::vector<uint8_t> code;
            A32(code, 0xEAFFFFFF); // B .-4 -> 0x2004, inside [0x2000,0x2008)
            A32(code, 0xE1A0C00D);
            RelocationResult r = BuildTrampoline(code.data(), code.size(), arm_addr, false);
            Check(!r.ok && r.code.empty(),
                  "ARM branch into the patched region fails closed");
        }
    }
} // namespace

int main()
{
    TestWidth();
    TestThumb16Decode();
    TestThumb32Decode();
    TestArmDecode();
    TestPatchEncoder();
    TestBuildThumbPassthrough();
    TestBuildThumbLdrLiteral();
    TestBuildThumbBranches();
    TestBuildThumbCbz();
    TestBuildThumbIt();
    TestBuildArm();
    TestRejections();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "armv7_instruction_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "armv7_instruction_test: all checks passed\n");
    return 0;
}
