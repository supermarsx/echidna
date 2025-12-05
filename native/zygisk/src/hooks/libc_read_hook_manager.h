#pragma once

/**
 * @file libc_read_hook_manager.h
 * @brief Hook manager to intercept the libc read() function for detecting
 * audio reads from device files when other hook points are not available.
 */

#include <cstddef>
#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

/**
 * @brief Fallback hook for libc read() to observe audio device reads.
 */
class LibcReadHookManager : public HookManager {
  public:
    explicit LibcReadHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return "libc_read"; }

  private:
    static ssize_t Replacement(int fd, void *buffer, size_t bytes);
    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
};

}  // namespace hooks
}  // namespace echidna
