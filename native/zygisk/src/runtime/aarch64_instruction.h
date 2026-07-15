#pragma once

#include <cstdint>
#include <optional>

namespace echidna::runtime::aarch64
{

    enum class UnconditionalBranchKind : uint8_t
    {
        kNone,
        kBranch,
        kBranchWithLink,
    };

    constexpr UnconditionalBranchKind ClassifyUnconditionalBranch(uint32_t opcode)
    {
        switch (opcode & 0xfc000000u)
        {
        case 0x14000000u:
            return UnconditionalBranchKind::kBranch;
        case 0x94000000u:
            return UnconditionalBranchKind::kBranchWithLink;
        default:
            return UnconditionalBranchKind::kNone;
        }
    }

    constexpr std::optional<int32_t> EncodePcRelativeImmediate(uintptr_t instruction_address,
                                                               uintptr_t target_address,
                                                               unsigned immediate_bits)
    {
        if (immediate_bits == 0 || immediate_bits >= 31)
        {
            return std::nullopt;
        }
        const int64_t delta = static_cast<int64_t>(target_address) -
                              static_cast<int64_t>(instruction_address);
        if ((delta & 0x3) != 0)
        {
            return std::nullopt;
        }
        const int64_t scaled = delta >> 2;
        const int64_t minimum = -(int64_t{1} << (immediate_bits - 1));
        const int64_t maximum = (int64_t{1} << (immediate_bits - 1)) - 1;
        if (scaled < minimum || scaled > maximum)
        {
            return std::nullopt;
        }
        return static_cast<int32_t>(scaled);
    }

} // namespace echidna::runtime::aarch64
