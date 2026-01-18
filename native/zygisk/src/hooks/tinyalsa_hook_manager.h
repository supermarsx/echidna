#pragma once

/**
 * @file tinyalsa_hook_manager.h
 * @brief Hook manager for tinyalsa PCM read functions.
 */

#include <cstddef>
#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna
{
  namespace hooks
  {

    /**
     * @brief Fallback hook for tinyalsa pcm_read/pcm_readi on devices using tinyalsa.
     */
    class TinyAlsaHookManager : public HookManager
    {
    public:
      explicit TinyAlsaHookManager(utils::PltResolver &resolver);

      bool install() override;
      const char *name() const override { return "tinyalsa_pcm_read"; }
      const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

    private:
      static int ReplacementRead(void *pcm, void *data, unsigned int count);
      static int ReplacementReadi(void *pcm, void *data, unsigned int frames);

      utils::PltResolver &resolver_;
      runtime::InlineHook hook_read_;
      runtime::InlineHook hook_readi_;
      HookInstallInfo last_info_;
    };

  } // namespace hooks
} // namespace echidna
