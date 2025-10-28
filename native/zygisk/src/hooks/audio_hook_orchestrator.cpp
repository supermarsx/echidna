#include "hooks/audio_hook_orchestrator.h"

#include <string>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna {
namespace hooks {

AudioHookOrchestrator::AudioHookOrchestrator()
    : aaudio_manager_(resolver_),
      opensl_manager_(resolver_),
      audiorecord_manager_(resolver_) {}

bool AudioHookOrchestrator::installHooks() {
    auto &state = state::SharedState::instance();
    state.refreshFromSharedMemory();
    const std::string &process = utils::CachedProcessName();

    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        state.setStatus(state::InternalStatus::kDisabled);
        return false;
    }

    state.setStatus(state::InternalStatus::kWaitingForAttach);

    if (shouldAttemptAAudio() && aaudio_manager_.install()) {
        return true;
    }
    if (opensl_manager_.install()) {
        return true;
    }
    if (audiorecord_manager_.install()) {
        return true;
    }

    state.setStatus(state::InternalStatus::kError);
    return false;
}

bool AudioHookOrchestrator::shouldAttemptAAudio() const {
    return api_probe_.apiLevel() >= 26;
}

}  // namespace hooks
}  // namespace echidna
