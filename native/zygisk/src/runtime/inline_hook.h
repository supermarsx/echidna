#pragma once

#include <cstddef>
#include <mutex>

namespace echidna {
namespace runtime {

class InlineHook {
  public:
    InlineHook();
    ~InlineHook();

    InlineHook(const InlineHook &) = delete;
    InlineHook &operator=(const InlineHook &) = delete;
    InlineHook(InlineHook &&) = delete;
    InlineHook &operator=(InlineHook &&) = delete;

    bool install(void *target, void *replacement, void **original);

  private:
    bool protect(void *address, size_t length, int prot);

    std::mutex mutex_;
    bool installed_;
    void *target_;
    void *trampoline_;
    size_t trampoline_size_;
    size_t patch_size_;
    alignas(8) unsigned char original_bytes_[32];
};

}  // namespace runtime
}  // namespace echidna
