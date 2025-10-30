#include "runtime/inline_hook.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <optional>
#include <vector>
#include <utility>

#include <sys/mman.h>
#include <unistd.h>

namespace echidna {
namespace runtime {

namespace {
constexpr uint32_t kAArch64LdrX16Literal = 0x58000050;  // LDR X16, #8
constexpr uint32_t kAArch64BrX16 = 0xD61F0200;          // BR X16
constexpr size_t kAArch64InstructionSize = sizeof(uint32_t);
constexpr size_t kAArch64HookSize = sizeof(uint32_t) * 2 + sizeof(uint64_t);

constexpr uint32_t kAArch64ScratchRegister = 17;  // X17/IP1

uint32_t EncodeLiteralLoad(uint32_t rt, int32_t imm19) {
    return 0x58000000 | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5) | (rt & 0x1F);
}

uint32_t EncodeConditionalBranch(uint32_t original, int32_t imm19) {
    return (original & 0xFF00001F) | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5);
}

uint32_t EncodeCompareBranch(uint32_t original, int32_t imm19) {
    return (original & 0xFFC0001F) | (static_cast<uint32_t>(imm19 & 0x7FFFF) << 5);
}

uint32_t EncodeTestBranch(uint32_t original, int32_t imm14) {
    return (original & 0xFFF8001F) | (static_cast<uint32_t>(imm14 & 0x3FFF) << 5);
}

uint32_t EncodeBr(uint32_t rn) {
    return 0xD61F0000 | ((rn & 0x1F) << 5);
}

uint32_t EncodeBlr(uint32_t rn) {
    return 0xD63F0000 | ((rn & 0x1F) << 5);
}

int32_t SignExtend(int32_t value, int bits) {
    const int32_t shift = 32 - bits;
    return (value << shift) >> shift;
}

uintptr_t AlignUp(uintptr_t value, uintptr_t alignment) {
    return (value + alignment - 1) & ~(alignment - 1);
}

struct LiteralFixup {
    size_t instruction_index;
    size_t literal_index;
    uint32_t rt;
};

struct BranchFixup {
    size_t instruction_index;
    size_t target_instruction_index;
    uint32_t original;
};

struct CompareBranchFixup {
    size_t instruction_index;
    size_t target_instruction_index;
    uint32_t original;
};

struct TestBranchFixup {
    size_t instruction_index;
    size_t target_instruction_index;
    uint32_t original;
};

struct InternalBranchFixup {
    size_t literal_index;
    uintptr_t target_address;
};

struct RelocationResult {
    std::vector<uint32_t> instructions;
    std::vector<uint64_t> literals;
    std::vector<LiteralFixup> literal_fixups;
    std::vector<BranchFixup> branch_fixups;
    std::vector<CompareBranchFixup> compare_branch_fixups;
    std::vector<TestBranchFixup> test_branch_fixups;
    std::vector<std::pair<uintptr_t, size_t>> original_address_map;
    std::vector<InternalBranchFixup> internal_branch_fixups;
    uintptr_t original_start = 0;
    size_t original_size = 0;
};

size_t AppendLiteralLoad(RelocationResult &result, uint32_t rt, uint64_t value) {
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

void AppendBranchTo(RelocationResult &result, uint64_t target, bool link) {
    bool target_in_relocated_range =
        target >= result.original_start &&
        target < (result.original_start + result.original_size);
    uint64_t literal_value = target;
    if (target_in_relocated_range) {
        literal_value = 0;
    }
    size_t literal_index =
        AppendLiteralLoad(result, kAArch64ScratchRegister, literal_value);
    if (target_in_relocated_range) {
        result.internal_branch_fixups.push_back(
            {.literal_index = literal_index, .target_address = static_cast<uintptr_t>(target)});
    }
    result.instructions.push_back(link ? EncodeBlr(kAArch64ScratchRegister)
                                       : EncodeBr(kAArch64ScratchRegister));
}

void AppendBranchStub(RelocationResult &result, uint64_t target, uint32_t original) {
    BranchFixup fixup{
        .instruction_index = result.instructions.size(),
        .target_instruction_index = result.instructions.size() + 1,
        .original = original,
    };
    result.instructions.push_back(0);
    result.branch_fixups.push_back(fixup);
    AppendBranchTo(result, target, false);
}

void AppendCompareBranchStub(RelocationResult &result, uint64_t target, uint32_t original) {
    CompareBranchFixup fixup{
        .instruction_index = result.instructions.size(),
        .target_instruction_index = result.instructions.size() + 1,
        .original = original,
    };
    result.instructions.push_back(0);
    result.compare_branch_fixups.push_back(fixup);
    AppendBranchTo(result, target, false);
}

void AppendTestBranchStub(RelocationResult &result, uint64_t target, uint32_t original) {
    TestBranchFixup fixup{
        .instruction_index = result.instructions.size(),
        .target_instruction_index = result.instructions.size() + 1,
        .original = original,
    };
    result.instructions.push_back(0);
    result.test_branch_fixups.push_back(fixup);
    AppendBranchTo(result, target, false);
}

bool RelocateInstruction(uint32_t instruction, uintptr_t pc, RelocationResult &result) {
    const uint32_t opcode = instruction;

    if ((opcode & 0x9F000000) == 0x90000000) {  // ADRP
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

    if ((opcode & 0x9F000000) == 0x10000000) {  // ADR
        uint32_t rd = opcode & 0x1F;
        int32_t immlo = (opcode >> 29) & 0x3;
        int32_t immhi = (opcode >> 5) & 0x7FFFF;
        int32_t imm = (immhi << 2) | immlo;
        imm = SignExtend(imm, 21);
        uint64_t target = pc + static_cast<int64_t>(imm);
        AppendLiteralLoad(result, rd, target);
        return true;
    }

    if ((opcode & 0x7C000000) == 0x14000000) {  // B
        int32_t imm26 = SignExtend(static_cast<int32_t>(opcode & 0x03FFFFFF), 26);
        uint64_t target = pc + (static_cast<int64_t>(imm26) << 2);
        AppendBranchTo(result, target, false);
        return true;
    }

    if ((opcode & 0x7C000000) == 0x94000000) {  // BL
        int32_t imm26 = SignExtend(static_cast<int32_t>(opcode & 0x03FFFFFF), 26);
        uint64_t target = pc + (static_cast<int64_t>(imm26) << 2);
        AppendBranchTo(result, target, true);
        return true;
    }

    if ((opcode & 0xFF000010) == 0x54000000) {  // B.cond
        int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
        uint64_t target = pc + (static_cast<int64_t>(imm19) << 2);
        AppendBranchStub(result, target, opcode);
        return true;
    }

    if ((opcode & 0x7F000000) == 0x34000000 ||
        (opcode & 0x7F000000) == 0x35000000) {  // CBZ/CBNZ
        int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
        uint64_t target = pc + (static_cast<int64_t>(imm19) << 2);
        AppendCompareBranchStub(result, target, opcode);
        return true;
    }

    if ((opcode & 0x7F000000) == 0x36000000 ||
        (opcode & 0x7F000000) == 0x37000000) {  // TBZ/TBNZ
        int32_t imm14 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x3FFF), 14);
        uint64_t target = pc + (static_cast<int64_t>(imm14) << 2);
        AppendTestBranchStub(result, target, opcode);
        return true;
    }

    if ((opcode & 0x3B000000) == 0x18000000) {  // LDR literal family
        uint32_t rt = opcode & 0x1F;
        uint32_t opc = (opcode >> 30) & 0x3;
        int32_t imm19 = SignExtend(static_cast<int32_t>((opcode >> 5) & 0x7FFFF), 19);
        uint64_t literal_address = pc + (static_cast<int64_t>(imm19) << 2);
        AppendLiteralLoad(result, kAArch64ScratchRegister, literal_address);
        uint32_t base = kAArch64ScratchRegister << 5;
        switch (opc) {
            case 0:  // LDR Wt
                result.instructions.push_back(0xB9400000 | base | rt);
                return true;
            case 1:  // LDR Xt
                result.instructions.push_back(0xF9400000 | base | rt);
                return true;
            case 2:  // LDRSW Xt
                result.instructions.push_back(0xB9800000 | base | rt);
                return true;
            case 3:  // PRFM
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
                                          uintptr_t original_address) {
    for (const auto &entry : result.original_address_map) {
        if (entry.first == original_address) {
            return entry.second;
        }
    }
    return std::nullopt;
}

bool FinalizeRelocation(RelocationResult &result, void *trampoline_base) {
    unsigned char *base = static_cast<unsigned char *>(trampoline_base);
    uintptr_t code_size_bytes = result.instructions.size() * kAArch64InstructionSize;
    uintptr_t code_aligned = AlignUp(code_size_bytes, alignof(uint64_t));
    uintptr_t literal_base_offset = code_aligned;
    for (size_t i = 0; i < result.instructions.size(); ++i) {
        std::memcpy(base + i * kAArch64InstructionSize, &result.instructions[i],
                    sizeof(uint32_t));
    }

    for (const auto &fixup : result.internal_branch_fixups) {
        auto target_index = FindInstructionIndex(result, fixup.target_address);
        if (!target_index.has_value()) {
            return false;
        }
        uintptr_t relocated_address = reinterpret_cast<uintptr_t>(base) +
                                      target_index.value() * kAArch64InstructionSize;
        if (fixup.literal_index >= result.literals.size()) {
            return false;
        }
        result.literals[fixup.literal_index] = relocated_address;
    }

    // Write literal values
    for (size_t i = 0; i < result.literals.size(); ++i) {
        std::memcpy(base + literal_base_offset + i * sizeof(uint64_t), &result.literals[i],
                    sizeof(uint64_t));
    }

    auto computeLiteralImmediate = [&](size_t instr_index, size_t literal_index,
                                       uint32_t rt) -> std::optional<uint32_t> {
        uintptr_t instr_address = reinterpret_cast<uintptr_t>(base) +
                                  instr_index * kAArch64InstructionSize;
        uintptr_t literal_address = reinterpret_cast<uintptr_t>(base) +
                                    literal_base_offset + literal_index * sizeof(uint64_t);
        int64_t delta = static_cast<int64_t>(literal_address) -
                        static_cast<int64_t>(instr_address + 4);
        if ((delta & 0x3) != 0) {
            return std::nullopt;
        }
        int64_t imm19 = delta >> 2;
        if (imm19 < -(1 << 18) || imm19 > ((1 << 18) - 1)) {
            return std::nullopt;
        }
        return EncodeLiteralLoad(rt, static_cast<int32_t>(imm19));
    };

    for (const auto &fixup : result.literal_fixups) {
        auto encoded = computeLiteralImmediate(fixup.instruction_index, fixup.literal_index,
                                               fixup.rt);
        if (!encoded.has_value()) {
            return false;
        }
        std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize,
                    &encoded.value(), sizeof(uint32_t));
    }

    auto applyBranchFixup = [&](const BranchFixup &fixup) -> bool {
        uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.instruction_index * kAArch64InstructionSize;
        uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.target_instruction_index * kAArch64InstructionSize;
        int64_t delta = static_cast<int64_t>(target_address) -
                        static_cast<int64_t>(branch_address + 4);
        if ((delta & 0x3) != 0) {
            return false;
        }
        int64_t imm19 = delta >> 2;
        if (imm19 < -(1 << 18) || imm19 > ((1 << 18) - 1)) {
            return false;
        }
        uint32_t encoded = EncodeConditionalBranch(fixup.original, static_cast<int32_t>(imm19));
        std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                    sizeof(uint32_t));
        return true;
    };

    auto applyCompareFixup = [&](const CompareBranchFixup &fixup) -> bool {
        uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.instruction_index * kAArch64InstructionSize;
        uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.target_instruction_index * kAArch64InstructionSize;
        int64_t delta = static_cast<int64_t>(target_address) -
                        static_cast<int64_t>(branch_address + 4);
        if ((delta & 0x3) != 0) {
            return false;
        }
        int64_t imm19 = delta >> 2;
        if (imm19 < -(1 << 18) || imm19 > ((1 << 18) - 1)) {
            return false;
        }
        uint32_t encoded =
            EncodeCompareBranch(fixup.original, static_cast<int32_t>(imm19));
        std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                    sizeof(uint32_t));
        return true;
    };

    auto applyTestFixup = [&](const TestBranchFixup &fixup) -> bool {
        uintptr_t branch_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.instruction_index * kAArch64InstructionSize;
        uintptr_t target_address = reinterpret_cast<uintptr_t>(base) +
                                   fixup.target_instruction_index * kAArch64InstructionSize;
        int64_t delta = static_cast<int64_t>(target_address) -
                        static_cast<int64_t>(branch_address + 4);
        if ((delta & 0x3) != 0) {
            return false;
        }
        int64_t imm14 = delta >> 2;
        if (imm14 < -(1 << 13) || imm14 > ((1 << 13) - 1)) {
            return false;
        }
        uint32_t encoded = EncodeTestBranch(fixup.original, static_cast<int32_t>(imm14));
        std::memcpy(base + fixup.instruction_index * kAArch64InstructionSize, &encoded,
                    sizeof(uint32_t));
        return true;
    };

    for (const auto &fixup : result.branch_fixups) {
        if (!applyBranchFixup(fixup)) {
            return false;
        }
    }

    for (const auto &fixup : result.compare_branch_fixups) {
        if (!applyCompareFixup(fixup)) {
            return false;
        }
    }

    for (const auto &fixup : result.test_branch_fixups) {
        if (!applyTestFixup(fixup)) {
            return false;
        }
    }

    return true;
}

size_t CalculateTrampolineSize(const RelocationResult &result) {
    uintptr_t code_size_bytes = result.instructions.size() * kAArch64InstructionSize;
    uintptr_t code_aligned = AlignUp(code_size_bytes, alignof(uint64_t));
    uintptr_t total = code_aligned + result.literals.size() * sizeof(uint64_t);
    return static_cast<size_t>(total);
}

#endif
}  // namespace

InlineHook::InlineHook()
    : installed_(false), target_(nullptr), trampoline_(nullptr), trampoline_size_(0), patch_size_(0) {}

InlineHook::~InlineHook() {
    std::scoped_lock lock(mutex_);
    if (installed_ && target_) {
        if (protect(target_, patch_size_, PROT_READ | PROT_WRITE | PROT_EXEC)) {
            std::memcpy(target_, original_bytes_, patch_size_);
            __builtin___clear_cache(reinterpret_cast<char *>(target_),
                                    reinterpret_cast<char *>(target_) + patch_size_);
            protect(target_, patch_size_, PROT_READ | PROT_EXEC);
        }
    }
    if (trampoline_) {
        munmap(trampoline_, trampoline_size_);
    }
}

bool InlineHook::install(void *target, void *replacement, void **original) {
    if (!target || !replacement || !original) {
        return false;
    }

    std::scoped_lock lock(mutex_);
    if (installed_) {
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
    for (size_t offset = 0; offset < patch_size_; offset += kAArch64InstructionSize) {
        uint32_t instruction;
        std::memcpy(&instruction, source + offset, sizeof(uint32_t));
        uintptr_t instruction_pc = pc + offset + kAArch64InstructionSize;
        size_t relocated_index = relocation.instructions.size();
        if (!RelocateInstruction(instruction, instruction_pc, relocation)) {
            return false;
        }
        relocation.original_address_map.emplace_back(pc + offset, relocated_index);
    }

    // Append branch back to the original function after the patched bytes.
    AppendLiteralLoad(relocation, kAArch64ScratchRegister,
                      reinterpret_cast<uint64_t>(target_) + patch_size_);
    relocation.instructions.push_back(EncodeBr(kAArch64ScratchRegister));

    trampoline_size_ = CalculateTrampolineSize(relocation);
    trampoline_size_ += kAArch64HookSize;  // Reserve space for patch literal.
    void *trampoline =
        mmap(nullptr, trampoline_size_, PROT_READ | PROT_WRITE | PROT_EXEC,
                            MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (trampoline == MAP_FAILED) {
        trampoline_ = nullptr;
        return false;
    }
    trampoline_ = trampoline;

    std::memcpy(original_bytes_, target_, patch_size_);

    unsigned char *trampoline_bytes = static_cast<unsigned char *>(trampoline_);
    if (!FinalizeRelocation(relocation, trampoline_bytes)) {
        munmap(trampoline_, trampoline_size_);
        trampoline_ = nullptr;
        return false;
    }

    struct alignas(8) BranchPatch {
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

    if (!protect(target_, patch_size_, PROT_READ | PROT_WRITE | PROT_EXEC)) {
        munmap(trampoline_, trampoline_size_);
        trampoline_ = nullptr;
        return false;
    }

    BranchPatch patch{.ldr = kAArch64LdrX16Literal, .br = kAArch64BrX16,
                      .address = reinterpret_cast<uint64_t>(replacement)};
    std::memcpy(target_, &patch, sizeof(patch));
    __builtin___clear_cache(reinterpret_cast<char *>(target_),
                            reinterpret_cast<char *>(target_) + sizeof(patch));
    protect(target_, patch_size_, PROT_READ | PROT_EXEC);

    *original = trampoline_;
    installed_ = true;
    return true;
#else
    (void)patch_size_;
    (void)trampoline_size_;
    (void)target_;
    return false;
#endif
}

bool InlineHook::protect(void *address, size_t length, int prot) {
    if (!address || length == 0) {
        return false;
    }

    long page_size_long = sysconf(_SC_PAGESIZE);
    if (page_size_long <= 0) {
        return false;
    }
    size_t page_size = static_cast<size_t>(page_size_long);
    uintptr_t start = reinterpret_cast<uintptr_t>(address) & ~(page_size - 1);
    uintptr_t end = reinterpret_cast<uintptr_t>(address) + length;
    size_t total = ((end - start) + page_size - 1) & ~(page_size - 1);
    return mprotect(reinterpret_cast<void *>(start), total, prot) == 0;
}

}  // namespace runtime
}  // namespace echidna
