#pragma once

/**
 * @file audio_hook_orchestrator.h
 * @brief High-level coordinator which decides which hook managers to attempt
 * installing based on API availability and runtime process checks.
 */

#include <memory>

#include "hooks/aaudio_hook_manager.h"
#include "hooks/audiorecord_hook_manager.h"
#include "hooks/audioflinger_hook_manager.h"
#include "hooks/libc_read_hook_manager.h"
#include "hooks/opensl_hook_manager.h"
#include "hooks/tinyalsa_hook_manager.h"
#include "hooks/audiohal_hook_manager.h"
#include "utils/api_level_probe.h"
#include "utils/plt_resolver.h"

namespace echidna
{
  namespace hooks
  {

    class AudioHookOrchestrator
    {
    public:
      /** Initialize the orchestrator and any platform probes needed. */
      AudioHookOrchestrator();

      /** Attempt to install all available and permitted hooks. Returns true if
       * at least one hook was successfully installed. */
      bool installHooks();

    private:
      /** Internal predicate which checks whether AAudio should be attempted
       * based on the api level and process environment. */
      bool shouldAttemptAAudio() const;

      utils::PltResolver resolver_;
      utils::ApiLevelProbe api_probe_;
      AAudioHookManager aaudio_manager_;
      OpenSLHookManager opensl_manager_;
      AudioRecordHookManager audiorecord_manager_;
      AudioFlingerHookManager audioflinger_manager_;
      LibcReadHookManager libc_read_manager_;
      TinyAlsaHookManager tinyalsa_manager_;
      AudioHalHookManager audiohal_manager_;
    };

  } // namespace hooks
} // namespace echidna
