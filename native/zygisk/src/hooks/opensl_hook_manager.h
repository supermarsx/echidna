#pragma once

#include <cstdint>
#include <string>

#include <SLES/OpenSLES.h>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

class OpenSLHookManager : public HookManager {
  public:
    explicit OpenSLHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return active_symbol_.c_str(); }

  private:
    static SLresult Replacement(void *caller, void *context, void *buffer, uint32_t size);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
    std::string active_symbol_;
};

}  // namespace hooks
}  // namespace echidna
