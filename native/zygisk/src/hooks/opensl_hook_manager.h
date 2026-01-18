#pragma once

/**
 * @file opensl_hook_manager.h
 * @brief Hook manager for OpenSL ES buffer queue callbacks used in some
 * audio capture paths.
 */

#include <cstdint>
#include <string>

#ifdef __ANDROID__
#include <SLES/OpenSLES.h>
#else
using SLresult = int;
constexpr SLresult SL_RESULT_SUCCESS = 0;
#endif

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna
{
  namespace hooks
  {

    class OpenSLHookManager : public HookManager
    {
    public:
      explicit OpenSLHookManager(utils::PltResolver &resolver);

      bool install() override;
      const char *name() const override { return active_symbol_.c_str(); }
      const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

    private:
      static SLresult Replacement(void *caller, void *context, void *buffer, uint32_t size);

      utils::PltResolver &resolver_;
      runtime::InlineHook hook_;
      std::string active_symbol_;
      HookInstallInfo last_info_;
    };

  } // namespace hooks
} // namespace echidna
