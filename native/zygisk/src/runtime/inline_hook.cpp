#include "runtime/inline_hook.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace echidna {
namespace runtime {

namespace {
constexpr uint32_t kAArch64LdrX16Literal = 0x58000050;  // LDR X16, #8
constexpr uint32_t kAArch64BrX16 = 0xD61F0200;          // BR X16
constexpr size_t kAArch64HookSize = sizeof(uint32_t) * 2 + sizeof(uint64_t);
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
    trampoline_size_ = patch_size_ + kAArch64HookSize;
    void *trampoline = mmap(nullptr, trampoline_size_, PROT_READ | PROT_WRITE | PROT_EXEC,
                            MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (trampoline == MAP_FAILED) {
        trampoline_ = nullptr;
        return false;
    }
    trampoline_ = trampoline;

    std::memcpy(original_bytes_, target_, patch_size_);
    std::memcpy(trampoline_, original_bytes_, patch_size_);

    struct alignas(8) BranchPatch {
        uint32_t ldr;
        uint32_t br;
        uint64_t address;
    };

    auto *trampoline_patch = reinterpret_cast<BranchPatch *>(static_cast<unsigned char *>(trampoline_) + patch_size_);
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
