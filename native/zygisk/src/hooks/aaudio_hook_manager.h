#pragma once

#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

class AAudioHookManager : public HookManager {
  public:
    explicit AAudioHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return "AAudioStream_dataCallback"; }

  private:
    static int Replacement(void *stream, void *user, void *audio_data, int32_t num_frames);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
};

}  // namespace hooks
}  // namespace echidna
