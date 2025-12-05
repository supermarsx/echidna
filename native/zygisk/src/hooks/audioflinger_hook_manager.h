#pragma once

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
    static bool Replacement(void *thiz);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
};

}  // namespace hooks
}  // namespace echidna
