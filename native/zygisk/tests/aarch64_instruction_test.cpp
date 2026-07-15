#include "runtime/aarch64_instruction.h"

#include <cstdio>

namespace
{
    int g_failures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++g_failures;
        }
    }
} // namespace

int main()
{
    using echidna::runtime::aarch64::ClassifyUnconditionalBranch;
    using echidna::runtime::aarch64::EncodePcRelativeImmediate;
    using echidna::runtime::aarch64::UnconditionalBranchKind;

    Check(ClassifyUnconditionalBranch(0x14000000u) == UnconditionalBranchKind::kBranch,
          "B +0 must classify as a non-linking branch");
    Check(ClassifyUnconditionalBranch(0x17ffffffu) == UnconditionalBranchKind::kBranch,
          "B with a negative immediate must remain a branch");
    Check(ClassifyUnconditionalBranch(0x94000000u) ==
              UnconditionalBranchKind::kBranchWithLink,
          "BL +0 must preserve link semantics");
    Check(ClassifyUnconditionalBranch(0x97ffffffu) ==
              UnconditionalBranchKind::kBranchWithLink,
          "BL with a negative immediate must preserve link semantics");
    Check(ClassifyUnconditionalBranch(0x54000000u) == UnconditionalBranchKind::kNone,
          "conditional branches must not be classified as B/BL");

    const auto forward = EncodePcRelativeImmediate(0x1000u, 0x1010u, 19);
    Check(forward.has_value() && forward.value() == 4,
          "AArch64 immediates are relative to the current instruction, not PC+4");
    const auto backward = EncodePcRelativeImmediate(0x1010u, 0x1000u, 19);
    Check(backward.has_value() && backward.value() == -4,
          "negative PC-relative immediates must scale correctly");
    Check(!EncodePcRelativeImmediate(0x1000u, 0x1002u, 19).has_value(),
          "unaligned targets must fail closed");
    Check(!EncodePcRelativeImmediate(0x1000u, 0x1000u + (1u << 20), 19).has_value(),
          "out-of-range imm19 targets must fail closed");

    if (g_failures != 0)
    {
        std::fprintf(stderr, "aarch64_instruction_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "aarch64_instruction_test: all checks passed\n");
    return 0;
}
