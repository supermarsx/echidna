#include "hooks/audio_hook_orchestrator.h"

/**
 * @file audio_hook_orchestrator.cpp
 * @brief Orchestrator implementation deciding which hook managers to attempt
 * and recording telemetry about installation attempts.
 */

#include <string>
#include <time.h>

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

    auto monotonic_now = []() {
        timespec ts{};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<uint64_t>(ts.tv_nsec);
    };
    auto &telemetry = state.telemetry();

    if (shouldAttemptAAudio()) {
        const bool success = aaudio_manager_.install();
        telemetry.registerHookResult("AAudio", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = opensl_manager_.install();
        telemetry.registerHookResult("OpenSL", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = audioflinger_manager_.install();
        telemetry.registerHookResult("AudioFlinger", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = audiorecord_manager_.install();
        telemetry.registerHookResult("AudioRecord", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = libc_read_manager_.install();
        telemetry.registerHookResult("libc_read", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = tinyalsa_manager_.install();
        telemetry.registerHookResult("tinyalsa", success, monotonic_now());
        if (success) {
            return true;
        }
    }
    {
        const bool success = audiohal_manager_.install();
        telemetry.registerHookResult("audiohal", success, monotonic_now());
        if (success) {
            return true;
        }
    }

    state.setStatus(state::InternalStatus::kError);
    return false;
}

bool AudioHookOrchestrator::shouldAttemptAAudio() const {
    return api_probe_.apiLevel() >= 26;
}

}  // namespace hooks
}  // namespace echidna
