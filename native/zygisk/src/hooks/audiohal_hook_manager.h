#pragma once

/**
 * @file audiohal_hook_manager.h
 * @brief Hook into platform Audio HAL read entrypoints.
 */

#include <cstddef>
#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

/**
 * @brief Fallback hook for audio HAL stream reads (audio_stream_in_read).
 */
class AudioHalHookManager : public HookManager {
  public:
    explicit AudioHalHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return "audiohal_stream_read"; }

  private:
    static ssize_t Replacement(void *stream, void *buffer, size_t bytes);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
};

}  // namespace hooks
}  // namespace echidna
