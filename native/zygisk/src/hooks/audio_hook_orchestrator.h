#pragma once

#include <memory>

#include "hooks/aaudio_hook_manager.h"
#include "hooks/audiorecord_hook_manager.h"
#include "hooks/audioflinger_hook_manager.h"
#include "hooks/libc_read_hook_manager.h"
#include "hooks/opensl_hook_manager.h"
#include "utils/api_level_probe.h"
#include "utils/plt_resolver.h"

namespace echidna {
namespace hooks {

class AudioHookOrchestrator {
  public:
    AudioHookOrchestrator();

    bool installHooks();

  private:
    bool shouldAttemptAAudio() const;

    utils::PltResolver resolver_;
    utils::ApiLevelProbe api_probe_;
    AAudioHookManager aaudio_manager_;
    OpenSLHookManager opensl_manager_;
    AudioRecordHookManager audiorecord_manager_;
    AudioFlingerHookManager audioflinger_manager_;
    LibcReadHookManager libc_read_manager_;
};

}  // namespace hooks
}  // namespace echidna
