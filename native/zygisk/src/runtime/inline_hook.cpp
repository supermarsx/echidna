#include "runtime/inline_hook.h"
#include "runtime/aarch64_instruction.h"

/**
 * @file inline_hook.cpp
 * @brief Inline hook machinery and relocation helpers used for installing
 * small function trampolines on AArch64.
 */

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <optional>
#include <vector>
#include <utility>

#include <sys/mman.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 0
#endif

namespace echidna
{
    namespace runtime
    {

        namespace
        {
            /**
             * @brief Emit the explicit, greppable "hook_unsupported_abi" signal used
             * when the current build target has no inline-hook trampoline
             * implementation.
             *
             * This is deliberately not a silent `return false`: it complements the
             * per-hook telemetry failure that the calling hook manager records (via
             * the process-local telemetry accumulator, since install() returns
             * false) by making the ABI-level root cause unambiguous in logcat. On
             * host builds without liblog it still surfaces on stderr so the signal is
             * never lost.
             */
            [[maybe_unused]] void SignalUnsupportedAbi(const char *reason)
            {
                __android_log_print(ANDROID_LOG_WARN, "echidna",
                                    "hook_unsupported_abi: %s", reason);
#ifndef __ANDROID__
                std::fprintf(stderr, "echidna: hook_unsupported_abi: %s\n", reason);
#endif
            }
        } // namespace

#if defined(__aarch64__)
        namespace
        {
            constexpr uint32_t kAArch64LdrX16Literal = 0x58000050; // LDR X16, #8
            constexpr uint32_t kAArch64BrX16 = 0xD61F0200;         // BR X16
            constexpr size_t kAArch64InstructionSize = sizeof(uint32_t);
            constexpr size_t kAArch64HookSize = sizeof(uint32_t) * 2 + sizeof(uint64_t);

            constexpr uint32_t kAArch64ScratchRegister = 17; // X17/IP1

            uint32_t EncodeLiteralLoad(uint32_t rt, int32_t imm19)
            {
                return 0x58000000 | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5) | (rt & 0x1F);
            }

            uint32_t EncodeConditionalBranch(uint32_t original, int32_t imm19)
            {
                return (original & 0xFF00001F) | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5);
            }

            uint32_t EncodeCompareBranch(uint32_t original, int32_t imm19)
            {
                return (original & 0xFFC0001F) | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5);
            }

            uint32_t EncodeTestBranch(uint32_t original, int32_t imm14)
            {
                return (original & 0xFFF8001F) | (static_cast<uint32_t>(imm14 & 0x3FFF) << 5);
            }

            uint32_t EncodeBr(uint32_t rn)
            {
                return 0xD61F0000 | ((rn & 0x1F) << 5);
            }

            uint32_t EncodeBlr(uint32_t rn)
            {
                return 0xD63F0000 | ((rn & 0x1F) << 5);
            }

            int32_t SignExtend(int32_t value, int bits)
            {
                const int32_t shift = 32 - bits;
                return (value << shift) >> shift;
            }

            uintptr_t AlignUp(uintptr_t value, uintptr_t alignment)
            {
                return (value + alignment - 1) & ~(alignment - 1);
            }

            struct LiteralFixup
            {
                size_t instruction_index;
                size_t literal_index;
                uint32_t rt;
            };

            struct BranchFixup
            {
                size_t instruction_index;
                size_t target_instruction_index;
                uint32_t original;
            };

            struct CompareBranchFixup
            {
                size_t instruction_index;
                size_t target_instruction_index;
                uint32_t original;
            };

            struct TestBranchFixup
            {
                size_t instruction_index;
                size_t target_instruction_index;
                uint32_t original;
            };

            struct InternalBranchFixup
            {
                size_t literal_index;
                uintptr_t target_address;
            };

            struct RelocationResult
            {
                std::vector<uint32_t> instructions;
                std::vector<uint64_t> literals;
                std::vector<LiteralFixup> literal_fixups;
                std::vector<BranchFixup> branch_fixups;
                std::vector<CompareBranchFixup> compare_branch_fixups;
                std::vector<TestBranchFixup> test_branch_fixups;
                std::vector<std::pair<uintptr_t, size_t>> original_address_map;
                std::vector<InternalBranchFixup> internal_branch_fixups;
                struct PendingConditionalStub
                {
                    size_t fixup_index;
                    uint64_t target;
                };
                struct PendingCompareStub
                {
                    size_t fixup_index;
                    uint64_t target;
                };
                struct PendingTestStub
                {
                    size_t fixup_index;
                    uint64_t target;
                };
                std::vector<PendingConditionalStub> pending_conditional_stubs;
                std::vector<PendingCompareStub> pending_compare_stubs;
                std::vector<PendingTestStub> pending_test_stubs;
                uintptr_t original_start = 0;
                size_t original_size = 0;
            };

            size_t AppendLiteralLoad(RelocationResult &result, uint32_t rt, uint64_t value)
            {
                LiteralFixup fixup{
                    .instruction_index = result.instructions.size(),
                    .literal_index = result.literals.size(),
                    .rt = rt,
                };
                result.instructions.push_back(0);
                result.literal_fixups.push_back(fixup);
                result.literals.push_back(value);
                return fixup.literal_index;
            }

            void AppendBranchTo(RelocationResult &result, uint64_t target, bool link)
            {
                bool target_in_relocated_range =
                    target >= result.original_start &&
                    target < (result.original_start + result.original_size);
                uint64_t literal_value = target;
                if (target_in_relocated_range)
                {
                    literal_value = 0;
                }
                size_t literal_index =
                    AppendLiteralLoad(result, kAArch64ScratchRegister, literal_value);
                if (target_in_relocated_range)
                {
                    result.internal_branch_fixups.push_back(
                        {.literal_index = literal_index, .target_address = static_cast<uintptr_t>(target)});
                }
                result.instructions.push_back(link ? EncodeBlr(kAArch64ScratchRegister)
                                                   : EncodeBr(kAArch64ScratchRegister));
            }

            void AppendBranchStub(RelocationResult &result, uint64_t target, uint32_t original)
            {
                BranchFixup fixup{
                    .instruction_index = result.instructions.size(),
                    .target_instruction_index = 0,
                    .original = original,
                };
                result.instructions.push_back(0);
                result.branch_fixups.push_back(fixup);
                result.pending_conditional_stubs.push_back(
                    {.fixup_index = result.branch_fixups.size() - 1, .target = target});
            }

            void AppendCompareBranchStub(RelocationResult &result, uint64_t target, uint32_t original)
            {
                CompareBranchFixup fixup{
                    .instruction_index = result.instructions.size(),
                    .target_instruction_index = 0,
                    .original = original,
                };
                result.instructions.push_back(0);
                result.compare_branch_fixups.push_back(fixup);
                result.pending_compare_stubs.push_back(
                    {.fixup_index = result.compare_branch_fixups.size() - 1, .target = target});
            }

            void AppendTestBranchStub(RelocationResult &result, uint64_t target, uint32_t original)
            {
                TestBranchFixup fixup{
                    .instruction_index = result.instructions.size(),
                    .target_instruction_index = 0,
                    .original = original,
                };
                result.instructions.push_back(0);
                result.test_branch_fixups.push_back(fixup);
                result.pending_test_stubs.push_back(
                    {.fixup_index = result.test_branch_fixups.size() - 1, .target = target});
            }

            void EmitPendingBranchStubs(RelocationResult &result)
            {
                auto emit = [&](auto &pending, auto &fixups)
                {
                    for (const auto &entry : pending)
                    {
                        size_t stub_index = result.instructions.size();
                        AppendBranchTo(result, entry.target, false);
                        if (entry.fixup_index < fixups.size())
                        {
                            fixups[entry.fixup_index].target_instruction_index = stub_index;
                        }
                    }
                    pending.clear();
                };

                emit(result.pending_conditional_stubs, result.branch_fixups);
                emit(result.pending_compare_stubs, result.compare_branch_fixups);
                emit(result.pending_test_stubs, result.test_branch_fixups);
            }

            /** Relocate a single instruction into a form suitable for the trampoline. */
            bool RelocateInstruction(uint32_t instruction, uintptr_t pc, RelocationResult &result)
            {
                const uint32_t opcode = instruction;

                if ((opcode & 0x9F000000) == 0x90000000)
                { // ADRP
                    uint32_t rd = opcode & 0x1F;
                    int32_t immlo = (opcode >> 29) & 0x3;
                    int32_t immhi = (opcode >> 5) & 0x7FFFF;
                    int32_t imm = (immhi << 2) | immlo;
                    imm = SignExtend(imm, 21);
                    uint64_t base_pc = pc & ~0xFFFULL;
                    uint64_t target = base_pc + (static_cast<int64_t>(imm) << 12);
                    AppendLiteralLoad(result, rd, target);
                    return true;
                }

                if ((opcode & 0x9F000000) == 0x10000000)
                { // ADR
                    uint32_t rd = opcode & 0x1F;
                    int32_t immlo = (opcode >> 29) & 0x3;
                    int32_t immhi = (opcode >> 5) & 0x7FFFF;
                    int32_t imm = (immhi << 2) | immlo;
                    imm = SignExtend(imm, 21);
                    uint64_t target = pc + static_cast<int64_t>(imm);
                    AppendLiteralLoad(result, rd, target);
                    return true;
                }

                const auto branch_kind = aarch64::ClassifyUnconditionalBranch(opcode);
                if (branch_kind == aarch64::UnconditionalBranchKind::kBranch)
                {
                    int32_t imm26 = SignExtend(static_cast<int32_t>(opcode & 0x03FFFFFF), 26);
                    uint64_t target = pc + (static_cast<int64_t>(imm26) << 2);
                    AppendBranchTo(result, target, false);
                    return true;
                }

                if (branch_kind == aarch64::UnconditionalBranchKind::kBranchWithLink)
                {
                    int32_t imm26 = SignExtend(static_cast<int32_t>(opcode & 0x03FFFFFF), 26);
                    uint64_t target = pc + (static_cast<int64_t>(imm26) << 2);
                    AppendBranchTo(result, target, true);
                    return true;
                }

                if ((opcode & 0xFF000010) == 0x54000000)
                { // B.cond
                    int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
                    uint64_t target = pc + (static_cast<int64_t>(imm19) << 2);
                    AppendBranchStub(result, target, opcode);
                    return true;
                }

                if ((opcode & 0x7F000000) == 0x34000000 ||
                    (opcode & 0x7F000000) == 0x35000000)
                { // CBZ/CBNZ
                    int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
                    uint64_t target = pc + (static_cast<int64_t>(imm19) << 2);
                    AppendCompareBranchStub(result, target, opcode);
                    return true;
                }

                if ((opcode & 0x7F000000) == 0x36000000 ||
                    (opcode & 0x7F000000) == 0x37000000)
                { // TBZ/TBNZ
                    int32_t imm14 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x3FFF), 14);
                    uint64_t target = pc + (static_cast<int64_t>(imm14) << 2);
                    AppendTestBranchStub(result, target, opcode);
                    return true;
                }

                if ((opcode & 0x3B000000) == 0x18000000)
                { // LDR literal family
                    uint32_t rt = opcode & 0x1F;
                    uint32_t opc = (opcode >> 30) & 0x3;
                    int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
                    uint64_t literal_address = pc + (static_cast<int64_t>(imm19) << 2);
                    AppendLiteralLoad(result, kAArch64ScratchRegister, literal_address);
                    uint32_t base = kAArch64ScratchRegister << 5;
                    switch (opc)
                    {
                    case 0: // LDR Wt
                        result.instructions.push_back(0xB9400000 | base | rt);
                        return true;
                    case 1: // LDR Xt
                        result.instructions.push_back(0xF9400000 | base | rt);
                        return true;
                    case 2: // LDRSW Xt
                        result.instructions.push_back(0xB9800000 | base | rt);
                        return true;
                    case 3: // PRFM
                        result.instructions.push_back(0xF9800000 | base | rt);
                        return true;
                    default:
                        return false;
                    }
                }

                result.instructions.push_back(instruction);
                return true;
            }

            std::optional<size_t> FindInstructionIndex(const RelocationResult &result,
                                                       uintptr_t original_address)
            {
                for (const auto &entry : result.original_address_map)
                {
                    if (entry.first == original_address)
                    {
                        return entry.second;
                    }
                }
                return std::nullopt;
            }

            /** Finalize relocation by writing encoded instructions and literals into
             * the trampoline buffer and computing relative fixups. */
            bool FinalizeRelocation(RelocationResult &result, void *trampoline_base)
            {
                unsigned char *base = static_cast<unsigned char *>(trampoline_base);
                uintptr_t code_size_bytes = result.instructions.size() * kAArch64InstructionSize;
                uintptr_t code_aligned = AlignUp(code_size_bytes, alignof(uint64_t));
                uintptr_t literal_base_offset = code_aligned;
                for (size_t i = 0; i < result.instructions.size(); ++i)
                {
                    std::memcpy(base + i * kAArch64InstructionSize, &result.instructions[i],
                                sizeof(uint32_t));
                }

                for (const auto &fixup : result.internal_branch_fixups)
                {
                    auto target_index = FindInstructionIndex(result, fixup.target_address);
                    if (!target_index.has_value())
                    {
                        return false;
                    }
                    uintptr_t relocated_address = reinterpret_cast<uintptr_t>(base) +
                                                  target_index.value() * kAArch64InstructionSize;
                    if (fixup.literal_index >= result.literals.size())
                    {
                        return false;
                    }
                    result.literals[fixup.literal_index] = relocated_address;
                }

                // Write literal values
                for (size_t i = 0; i < result.literals.size(); ++i)
                {
                    std::memcpy(base + literal_base_offset + i * sizeof(uint64_t), &result.literals[i],
                                sizeof(uint64_t));
                }

                auto computeLiteralImmediate = [&](size_t instr_index, size_t literal_index,
                                                   uint32_t rt) -> std::optional<uint32_t>
                {
                    uintptr_t instr_address = reinterpret_cast<uintptr_t>(base) +
                                              instr_index * kAArch64InstructionSize;
                    uintptr_t literal_address = reinterpret_cast<uintptr_t>(base) +
                                                literal_base_offset + literal_index * sizeof(uint64_t);
                    const auto imm19 =
                        aarch64::EncodePcRelativeImmediate(instr_address, literal_address, 19);
                    if (!imm19.has_value())
                    {
                        return std::nullopt;
                    }
                    return EncodeLiteralLoad(rt, imm19.value());
                };

                for (const auto &fixup : result.literal_fixups)
                {
                    auto encoded = computeLiteralImmediate(fixup.instruction_index, fixup.literal_index,
                                                           fixup.rt);
                    if (!encoded.has_value())
                    {
                        return false;
                    }
                    std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize,
                                &encoded.value(), sizeof(uint32_t));
                }

                auto applyBranchFixup = [&](const BranchFixup &fixup) -> bool
                {
                    uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.instruction_index * kAArch64InstructionSize;
                    uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.target_instruction_index * kAArch64InstructionSize;
                    const auto imm19 =
                        aarch64::EncodePcRelativeImmediate(branch_address, target_address, 19);
                    if (!imm19.has_value())
                    {
                        return false;
                    }
                    uint32_t encoded = EncodeConditionalBranch(fixup.original, imm19.value());
                    std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                                sizeof(uint32_t));
                    return true;
                };

                auto applyCompareFixup = [&](const CompareBranchFixup &fixup) -> bool
                {
                    uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.instruction_index * kAArch64InstructionSize;
                    uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.target_instruction_index * kAArch64InstructionSize;
                    const auto imm19 =
                        aarch64::EncodePcRelativeImmediate(branch_address, target_address, 19);
                    if (!imm19.has_value())
                    {
                        return false;
                    }
                    uint32_t encoded =
                        EncodeCompareBranch(fixup.original, imm19.value());
                    std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                                sizeof(uint32_t));
                    return true;
                };

                auto applyTestFixup = [&](const TestBranchFixup &fixup) -> bool
                {
                    uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.instruction_index * kAArch64InstructionSize;
                    uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                               fixup.target_instruction_index * kAArch64InstructionSize;
                    const auto imm14 =
                        aarch64::EncodePcRelativeImmediate(branch_address, target_address, 14);
                    if (!imm14.has_value())
                    {
                        return false;
                    }
                    uint32_t encoded = EncodeTestBranch(fixup.original, imm14.value());
                    std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                                sizeof(uint32_t));
                    return true;
                };

                for (const auto &fixup : result.branch_fixups)
                {
                    if (!applyBranchFixup(fixup))
                    {
                        return false;
                    }
                }

                for (const auto &fixup : result.compare_branch_fixups)
                {
                    if (!applyCompareFixup(fixup))
                    {
                        return false;
                    }
                }

                for (const auto &fixup : result.test_branch_fixups)
                {
                    if (!applyTestFixup(fixup))
                    {
                        return false;
                    }
                }

                return true;
            }

            /** Compute the total size in bytes required for the relocated trampoline. */
            size_t CalculateTrampolineSize(const RelocationResult &result)
            {
                uintptr_t code_size_bytes = result.instructions.size() * kAArch64InstructionSize;
                uintptr_t code_aligned = AlignUp(code_size_bytes, alignof(uint64_t));
                uintptr_t total = code_aligned + result.literals.size() * sizeof(uint64_t);
                return static_cast<size_t>(total);
            }

        } // namespace
#endif // defined(__aarch64__)

#if defined(__x86_64__)
        namespace
        {
            // Absolute, register-safe jump: FF 25 00000000 (jmp qword [rip+0])
            // followed by the 8-byte absolute destination. Position-independent, so
            // it needs no relocation when copied.
            constexpr size_t kX64AbsoluteJumpSize = 14;
            constexpr size_t kX64MaxInstructionSize = 15;

            // Result of decoding one x86-64 instruction: enough to know its length
            // and to relocate it (RIP-relative displacement or rel32 branch).
            struct X64Decoded
            {
                size_t length = 0;
                bool rip_relative = false; // ModRM mod=00,rm=101 (no SIB): RIP-rel disp32
                size_t disp_offset = 0;    // byte offset of the RIP-relative disp32
                bool rel_branch = false;   // E8/E9/0F 8x: trailing rel32 branch
                size_t rel_offset = 0;     // byte offset of the rel32
            };

            // Conservative, allow-listed per-opcode attributes. Any opcode not
            // explicitly understood leaves `valid=false`, causing the decoder to
            // fail closed so the hook is declined rather than mis-relocated.
            struct X64OpcodeAttr
            {
                bool valid = false;
                bool modrm = false;
                uint8_t imm = 0;      // fixed immediate byte count
                bool imm_osz = false; // 4-byte immediate, or 2 when 0x66 present
                bool rel32 = false;   // rel32 branch (E8/E9/0F 8x)
                bool group3 = false;  // F6/F7: immediate only when ModRM.reg in {0,1}
            };

            X64OpcodeAttr OneByteAttr(uint8_t op)
            {
                X64OpcodeAttr a;
                // ADD/OR/ADC/SBB/AND/SUB/XOR/CMP rows. Within each 8-opcode row:
                // +0..+3 have ModRM; +4 = AL,imm8; +5 = eAX,imm(16/32).
                auto arith_row = [](uint8_t o)
                {
                    uint8_t hi = o & 0xF8;
                    return hi == 0x00 || hi == 0x08 || hi == 0x10 || hi == 0x18 ||
                           hi == 0x20 || hi == 0x28 || hi == 0x30 || hi == 0x38;
                };
                if (arith_row(op))
                {
                    uint8_t lo = op & 0x07;
                    if (lo <= 0x03)
                    {
                        a.valid = true;
                        a.modrm = true;
                    }
                    else if (lo == 0x04)
                    {
                        a.valid = true;
                        a.imm = 1;
                    }
                    else if (lo == 0x05)
                    {
                        a.valid = true;
                        a.imm_osz = true;
                    }
                    return a;
                }
                switch (op)
                {
                case 0x50:
                case 0x51:
                case 0x52:
                case 0x53: // push r64
                case 0x54:
                case 0x55:
                case 0x56:
                case 0x57:
                case 0x58:
                case 0x59:
                case 0x5A:
                case 0x5B: // pop r64
                case 0x5C:
                case 0x5D:
                case 0x5E:
                case 0x5F:
                case 0x90: // nop / pause
                case 0xC3: // ret
                case 0xC9: // leave
                    a.valid = true;
                    return a;
                case 0x63: // movsxd
                case 0x84:
                case 0x85: // test
                case 0x86:
                case 0x87: // xchg
                case 0x88:
                case 0x89:
                case 0x8A:
                case 0x8B: // mov
                case 0x8D: // lea (may be RIP-rel)
                case 0xD0:
                case 0xD1:
                case 0xD2:
                case 0xD3: // shift by 1/CL
                case 0xFE: // inc/dec r/m8
                case 0xFF: // group5 (inc/dec/call/jmp/push)
                    a.valid = true;
                    a.modrm = true;
                    return a;
                case 0x80: // group1 r/m8, imm8
                case 0x83: // group1 r/m, imm8
                case 0xC0:
                case 0xC1: // shift group2, imm8
                case 0xC6: // mov r/m8, imm8
                    a.valid = true;
                    a.modrm = true;
                    a.imm = 1;
                    return a;
                case 0x81: // group1 r/m, imm(16/32)
                case 0xC7: // mov r/m, imm(16/32)
                    a.valid = true;
                    a.modrm = true;
                    a.imm_osz = true;
                    return a;
                case 0xF6:
                case 0xF7: // group3 (TEST carries imm)
                    a.valid = true;
                    a.modrm = true;
                    a.group3 = true;
                    return a;
                case 0x68: // push imm(16/32)
                    a.valid = true;
                    a.imm_osz = true;
                    return a;
                case 0x6A: // push imm8
                    a.valid = true;
                    a.imm = 1;
                    return a;
                case 0xE8:
                case 0xE9: // call/jmp rel32
                    a.valid = true;
                    a.rel32 = true;
                    return a;
                default:
                    // Unknown, or rel8 branches (EB, 70-7F) we deliberately do not
                    // relocate: fail closed.
                    return a;
                }
            }

            X64OpcodeAttr TwoByteAttr(uint8_t op)
            {
                X64OpcodeAttr a;
                if (op >= 0x80 && op <= 0x8F) // jcc rel32
                {
                    a.valid = true;
                    a.rel32 = true;
                    return a;
                }
                if ((op >= 0x40 && op <= 0x4F) || // cmovcc
                    (op >= 0x90 && op <= 0x9F))   // setcc
                {
                    a.valid = true;
                    a.modrm = true;
                    return a;
                }
                switch (op)
                {
                case 0x05: // syscall
                    a.valid = true;
                    return a;
                case 0x10:
                case 0x11: // movups/movss/movsd
                case 0x1E: // endbr32/64 (with F3) / NOP r/m
                case 0x1F: // multi-byte NOP r/m
                case 0x28:
                case 0x29: // movaps/movapd
                case 0xAF: // imul r, r/m
                case 0xB6:
                case 0xB7: // movzx
                case 0xBE:
                case 0xBF: // movsx
                    a.valid = true;
                    a.modrm = true;
                    return a;
                default:
                    return a; // fail closed
                }
            }

            // Decode one instruction at p (bounded by max). Returns false (fail
            // closed) on any encoding this conservative decoder does not fully
            // understand.
            bool DecodeX64Instruction(const uint8_t *p, size_t max, X64Decoded &out)
            {
                size_t i = 0;
                bool operand_size = false; // 0x66 prefix seen

                // Legacy prefixes.
                for (;;)
                {
                    if (i >= max)
                    {
                        return false;
                    }
                    uint8_t b = p[i];
                    if (b == 0x66)
                    {
                        operand_size = true;
                        ++i;
                        continue;
                    }
                    if (b == 0x67 || b == 0xF0 || b == 0xF2 || b == 0xF3 ||
                        b == 0x2E || b == 0x36 || b == 0x3E || b == 0x26 ||
                        b == 0x64 || b == 0x65)
                    {
                        ++i;
                        continue;
                    }
                    break;
                }

                // VEX/EVEX/XOP: we do not relocate these — fail closed.
                if (i < max && (p[i] == 0xC4 || p[i] == 0xC5 || p[i] == 0x62))
                {
                    return false;
                }

                // REX prefix.
                if (i < max && (p[i] & 0xF0) == 0x40)
                {
                    ++i;
                }

                if (i >= max)
                {
                    return false;
                }
                uint8_t op = p[i++];
                X64OpcodeAttr attr;
                if (op == 0x0F)
                {
                    if (i >= max)
                    {
                        return false;
                    }
                    uint8_t op2 = p[i++];
                    if (op2 == 0x38 || op2 == 0x3A)
                    {
                        return false; // 3-byte opcodes: fail closed
                    }
                    attr = TwoByteAttr(op2);
                }
                else
                {
                    attr = OneByteAttr(op);
                }
                if (!attr.valid)
                {
                    return false;
                }

                if (attr.modrm)
                {
                    if (i >= max)
                    {
                        return false;
                    }
                    uint8_t modrm = p[i++];
                    uint8_t mod = (modrm >> 6) & 0x3;
                    uint8_t reg = (modrm >> 3) & 0x7;
                    uint8_t rm = modrm & 0x7;

                    // group3 (F6/F7): only TEST (reg 0/1) carries an immediate.
                    if (attr.group3 && (reg == 0 || reg == 1))
                    {
                        if (op == 0xF6)
                        {
                            attr.imm = 1;
                        }
                        else
                        {
                            attr.imm_osz = true;
                        }
                    }

                    if (mod != 0x3)
                    {
                        bool sib = (rm == 0x4);
                        uint8_t base = 0;
                        if (sib)
                        {
                            if (i >= max)
                            {
                                return false;
                            }
                            base = p[i] & 0x7;
                            ++i;
                        }
                        if (mod == 0x0)
                        {
                            if (!sib && rm == 0x5)
                            {
                                // RIP-relative disp32 (64-bit addressing).
                                out.rip_relative = true;
                                out.disp_offset = i;
                                i += 4;
                            }
                            else if (sib && base == 0x5)
                            {
                                i += 4; // absolute disp32, no base register
                            }
                        }
                        else if (mod == 0x1)
                        {
                            i += 1;
                        }
                        else // mod == 0x2
                        {
                            i += 4;
                        }
                    }
                }

                if (attr.rel32)
                {
                    out.rel_branch = true;
                    out.rel_offset = i;
                    i += 4;
                }
                else if (attr.imm_osz)
                {
                    i += operand_size ? 2 : 4;
                }
                else if (attr.imm)
                {
                    i += attr.imm;
                }

                if (i > max)
                {
                    return false;
                }
                out.length = i;
                return true;
            }

            // A single relocated instruction and its pending fixups, resolved once
            // the trampoline's final base address is known.
            struct X64RelocatedInstr
            {
                size_t offset; // offset within the trampoline code buffer
                size_t length;
                bool rip_relative = false;
                size_t rip_disp_offset = 0; // offset of disp32 within code buffer
                int64_t rip_target = 0;     // absolute address the disp referenced
                bool rel_branch = false;
                size_t rel_offset = 0;  // offset of rel32 within code buffer
                int64_t rel_target = 0; // absolute branch target
            };

            struct X64Relocation
            {
                std::vector<uint8_t> code;
                std::vector<X64RelocatedInstr> instrs;
                size_t covered = 0; // original bytes consumed (whole instructions)
            };

            int32_t ReadInt32(const uint8_t *p)
            {
                int32_t v;
                std::memcpy(&v, p, sizeof(v));
                return v;
            }

            // Relocate whole instructions covering at least `min_bytes` of the
            // target into result.code. Returns false (decline the hook) if any
            // instruction cannot be safely relocated.
            bool BuildX64Relocation(const uint8_t *target, size_t min_bytes,
                                    X64Relocation &result)
            {
                uintptr_t orig_base = reinterpret_cast<uintptr_t>(target);
                size_t offset = 0;
                while (result.covered < min_bytes)
                {
                    const uint8_t *p = target + offset;
                    X64Decoded decoded;
                    if (!DecodeX64Instruction(p, kX64MaxInstructionSize, decoded))
                    {
                        return false;
                    }

                    X64RelocatedInstr instr;
                    instr.offset = result.code.size();
                    instr.length = decoded.length;
                    uintptr_t instr_end = orig_base + offset + decoded.length;

                    if (decoded.rip_relative)
                    {
                        int32_t disp = ReadInt32(p + decoded.disp_offset);
                        instr.rip_relative = true;
                        instr.rip_disp_offset = instr.offset + decoded.disp_offset;
                        instr.rip_target =
                            static_cast<int64_t>(instr_end) + disp;
                    }
                    if (decoded.rel_branch)
                    {
                        int32_t rel = ReadInt32(p + decoded.rel_offset);
                        int64_t branch_target =
                            static_cast<int64_t>(instr_end) + rel;
                        // A branch back into the patched region would need internal
                        // redirection; decline rather than mis-relocate.
                        if (branch_target >= static_cast<int64_t>(orig_base) &&
                            branch_target <
                                static_cast<int64_t>(orig_base) +
                                    static_cast<int64_t>(min_bytes + kX64MaxInstructionSize))
                        {
                            return false;
                        }
                        instr.rel_branch = true;
                        instr.rel_offset = instr.offset + decoded.rel_offset;
                        instr.rel_target = branch_target;
                    }

                    result.code.insert(result.code.end(), p, p + decoded.length);
                    result.instrs.push_back(instr);
                    offset += decoded.length;
                    result.covered += decoded.length;
                }
                return true;
            }

            // Append the position-independent absolute jump to `dest`.
            void AppendX64AbsoluteJump(std::vector<uint8_t> &code, uint64_t dest)
            {
                code.push_back(0xFF);
                code.push_back(0x25);
                code.push_back(0x00);
                code.push_back(0x00);
                code.push_back(0x00);
                code.push_back(0x00);
                for (int i = 0; i < 8; ++i)
                {
                    code.push_back(static_cast<uint8_t>((dest >> (i * 8)) & 0xFF));
                }
            }

            // Resolve RIP-relative and rel32 fixups now that the code lives at
            // `base`. Returns false if any target no longer fits in a signed 32-bit
            // displacement.
            bool ApplyX64Fixups(uint8_t *base, const X64Relocation &result)
            {
                for (const auto &instr : result.instrs)
                {
                    int64_t next = reinterpret_cast<int64_t>(base) +
                                   static_cast<int64_t>(instr.offset + instr.length);
                    if (instr.rip_relative)
                    {
                        int64_t delta = instr.rip_target - next;
                        if (delta < INT32_MIN || delta > INT32_MAX)
                        {
                            return false;
                        }
                        int32_t d = static_cast<int32_t>(delta);
                        std::memcpy(base + instr.rip_disp_offset, &d, sizeof(d));
                    }
                    if (instr.rel_branch)
                    {
                        int64_t delta = instr.rel_target - next;
                        if (delta < INT32_MIN || delta > INT32_MAX)
                        {
                            return false;
                        }
                        int32_t d = static_cast<int32_t>(delta);
                        std::memcpy(base + instr.rel_offset, &d, sizeof(d));
                    }
                }
                return true;
            }
        } // namespace
#endif // defined(__x86_64__)

        InlineHook::InlineHook()
            : installed_(false), target_(nullptr), trampoline_(nullptr), trampoline_size_(0), patch_size_(0) {}

        InlineHook::~InlineHook()
        {
            std::scoped_lock lock(mutex_);
            if (installed_ && target_)
            {
                if (protect(target_, patch_size_, PROT_READ | PROT_WRITE | PROT_EXEC))
                {
                    std::memcpy(target_, original_bytes_, patch_size_);
                    __builtin___clear_cache(reinterpret_cast<char *>(target_),
                                            reinterpret_cast<char *>(target_) + patch_size_);
                    protect(target_, patch_size_, PROT_READ | PROT_EXEC);
                }
            }
            if (trampoline_)
            {
                munmap(trampoline_, trampoline_size_);
            }
        }

        bool InlineHook::install(void *target, void *replacement, void **original)
        {
            if (!target || !replacement || !original)
            {
                return false;
            }

            std::scoped_lock lock(mutex_);
            if (installed_)
            {
                return true;
            }

            target_ = target;

#if defined(__aarch64__)
            patch_size_ = kAArch64HookSize;
            RelocationResult relocation;
            relocation.original_start = reinterpret_cast<uintptr_t>(target_);
            relocation.original_size = patch_size_;
            unsigned char *source = static_cast<unsigned char *>(target_);
            uintptr_t pc = reinterpret_cast<uintptr_t>(source);
            for (size_t offset = 0; offset < patch_size_; offset += kAArch64InstructionSize)
            {
                uint32_t instruction;
                std::memcpy(&instruction, source + offset, sizeof(uint32_t));
                uintptr_t instruction_pc = pc + offset + kAArch64InstructionSize;
                size_t relocated_index = relocation.instructions.size();
                if (!RelocateInstruction(instruction, instruction_pc, relocation))
                {
                    return false;
                }
                relocation.original_address_map.emplace_back(pc + offset, relocated_index);
            }

            EmitPendingBranchStubs(relocation);

            // Append branch back to the original function after the patched bytes.
            AppendLiteralLoad(relocation, kAArch64ScratchRegister,
                              reinterpret_cast<uint64_t>(target_) + patch_size_);
            relocation.instructions.push_back(EncodeBr(kAArch64ScratchRegister));

            trampoline_size_ = CalculateTrampolineSize(relocation);
            trampoline_size_ += kAArch64HookSize; // Reserve space for patch literal.
            void *trampoline =
                mmap(nullptr, trampoline_size_, PROT_READ | PROT_WRITE | PROT_EXEC,
                     MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
            if (trampoline == MAP_FAILED)
            {
                trampoline_ = nullptr;
                return false;
            }
            trampoline_ = trampoline;

            std::memcpy(original_bytes_, target_, patch_size_);

            unsigned char *trampoline_bytes = static_cast<unsigned char *>(trampoline_);
            if (!FinalizeRelocation(relocation, trampoline_bytes))
            {
                munmap(trampoline_, trampoline_size_);
                trampoline_ = nullptr;
                return false;
            }

            struct alignas(8) BranchPatch
            {
                uint32_t ldr;
                uint32_t br;
                uint64_t address;
            };

            auto *trampoline_patch = reinterpret_cast<BranchPatch *>(
                trampoline_bytes + CalculateTrampolineSize(relocation));
            trampoline_patch->ldr = kAArch64LdrX16Literal;
            trampoline_patch->br = kAArch64BrX16;
            trampoline_patch->address = reinterpret_cast<uint64_t>(target_) + patch_size_;
            __builtin___clear_cache(reinterpret_cast<char *>(trampoline_),
                                    reinterpret_cast<char *>(trampoline_) + trampoline_size_);

            if (!protect(target_, patch_size_, PROT_READ | PROT_WRITE | PROT_EXEC))
            {
                munmap(trampoline_, trampoline_size_);
                trampoline_ = nullptr;
                return false;
            }

            BranchPatch patch{.ldr = kAArch64LdrX16Literal, .br = kAArch64BrX16, .address = reinterpret_cast<uint64_t>(replacement)};
            std::memcpy(target_, &patch, sizeof(patch));
            __builtin___clear_cache(reinterpret_cast<char *>(target_),
                                    reinterpret_cast<char *>(target_) + sizeof(patch));
            protect(target_, patch_size_, PROT_READ | PROT_EXEC);

            *original = trampoline_;
            installed_ = true;
            return true;
#elif defined(__x86_64__)
            patch_size_ = kX64AbsoluteJumpSize;

            X64Relocation relocation;
            if (!BuildX64Relocation(static_cast<const uint8_t *>(target_), patch_size_,
                                    relocation))
            {
                // The prologue contains an instruction this conservative relocator
                // cannot safely move: decline the hook. No bytes have been patched.
                return false;
            }

            // Trampoline = relocated prologue + absolute jump back to the first
            // instruction boundary after the patched region.
            AppendX64AbsoluteJump(relocation.code, reinterpret_cast<uint64_t>(target_) +
                                                       relocation.covered);

            trampoline_size_ = relocation.code.size();
            void *trampoline =
                mmap(nullptr, trampoline_size_, PROT_READ | PROT_WRITE | PROT_EXEC,
                     MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
            if (trampoline == MAP_FAILED)
            {
                trampoline_ = nullptr;
                return false;
            }
            trampoline_ = trampoline;

            unsigned char *trampoline_bytes = static_cast<unsigned char *>(trampoline_);
            std::memcpy(trampoline_bytes, relocation.code.data(), relocation.code.size());
            if (!ApplyX64Fixups(trampoline_bytes, relocation))
            {
                munmap(trampoline_, trampoline_size_);
                trampoline_ = nullptr;
                return false;
            }
            __builtin___clear_cache(reinterpret_cast<char *>(trampoline_),
                                    reinterpret_cast<char *>(trampoline_) + trampoline_size_);

            // Save original bytes, then overwrite the prologue with an absolute jump
            // to `replacement`.
            std::memcpy(original_bytes_, target_, patch_size_);
            if (!protect(target_, patch_size_, PROT_READ | PROT_WRITE | PROT_EXEC))
            {
                munmap(trampoline_, trampoline_size_);
                trampoline_ = nullptr;
                return false;
            }

            std::vector<uint8_t> patch;
            AppendX64AbsoluteJump(patch, reinterpret_cast<uint64_t>(replacement));
            std::memcpy(target_, patch.data(), patch.size());
            __builtin___clear_cache(reinterpret_cast<char *>(target_),
                                    reinterpret_cast<char *>(target_) + patch_size_);
            protect(target_, patch_size_, PROT_READ | PROT_EXEC);

            *original = trampoline_;
            installed_ = true;
            return true;
#else
            // No trampoline implementation for this ABI (e.g. armeabi-v7a). Degrade
            // gracefully: touch nothing, and emit the explicit hook_unsupported_abi
            // signal instead of a silent failure.
            (void)patch_size_;
            (void)trampoline_size_;
            (void)target_;
            (void)replacement;
            (void)original;
            SignalUnsupportedAbi(
                "inline hooking is implemented for arm64/x86_64 only; hooks disabled "
                "for this ABI");
            return false;
#endif
        }

        bool InlineHook::protect(void *address, size_t length, int prot)
        {
            if (!address || length == 0)
            {
                return false;
            }

            long page_size_long = sysconf(_SC_PAGESIZE);
            if (page_size_long <= 0)
            {
                return false;
            }
            size_t page_size = static_cast<size_t>(page_size_long);
            uintptr_t start = reinterpret_cast<uintptr_t>(address) & ~(page_size - 1);
            uintptr_t end = reinterpret_cast<uintptr_t>(address) + length;
            size_t total = ((end - start) + page_size - 1) & ~(page_size - 1);
            return mprotect(reinterpret_cast<void *>(start), total, prot) == 0;
        }

    } // namespace runtime
} // namespace echidna
