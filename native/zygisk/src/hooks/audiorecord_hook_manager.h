#pragma once

/**
 * @file audiorecord_hook_manager.h
 * @brief Hook manager for intercepting AudioRecord native read paths and
 * forwarding samples for processing/telemetry.
 */

#include <cstdint>
#include <string>
#include <sys/types.h>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna
{
  namespace hooks
  {

    class AudioRecordHookManager : public HookManager
    {
    public:
      explicit AudioRecordHookManager(utils::PltResolver &resolver);

      /**
       * @brief Installs AudioRecord::read hooks (native bridge).
       */
      bool install() override;
      const char *name() const override { return active_symbol_.c_str(); }
      const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

    private:
      /** Replacement shim wrapping the original read behaviour. */
      static ssize_t Replacement(void *instance, void *buffer, size_t bytes, bool blocking);

      utils::PltResolver &resolver_;
      runtime::InlineHook hook_;
      std::string active_symbol_;
      HookInstallInfo last_info_;
    };

  } // namespace hooks
} // namespace echidna
