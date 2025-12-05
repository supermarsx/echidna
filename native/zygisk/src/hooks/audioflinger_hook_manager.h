#pragma once

/**
 * @file audioflinger_hook_manager.h
 * @brief Hook manager targeting AudioFlinger's record thread and related
 * read/process entrypoints.
 */

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

/**
 * @brief Fallback hook on AudioFlinger record thread loop for device-level capture.
 */
class AudioFlingerHookManager : public HookManager {
  public:
    explicit AudioFlingerHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return "AudioFlinger_RecordThread"; }

  private:
    struct CaptureContext;

    static bool Replacement(void *thiz);
    static ssize_t ReplacementRead(void *thiz, void *buffer, size_t bytes);
    static ssize_t ReplacementProcess(void *thiz, void *buffer, size_t bytes);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
    runtime::InlineHook hook_read_;
    runtime::InlineHook hook_process_;
};

}  // namespace hooks
}  // namespace echidna
