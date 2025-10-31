#pragma once

#include <cstdint>
#include <string>
#include <sys/types.h>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

class AudioRecordHookManager : public HookManager {
  public:
    explicit AudioRecordHookManager(utils::PltResolver &resolver);

    bool install() override;
    const char *name() const override { return active_symbol_.c_str(); }

  private:
    static ssize_t Replacement(void *instance, void *buffer, size_t bytes, bool blocking);

    utils::PltResolver &resolver_;
    runtime::InlineHook hook_;
    std::string active_symbol_;
};

}  // namespace hooks
}  // namespace echidna
