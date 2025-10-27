#include "hooks/audio_hook_orchestrator.h"

#include "state/shared_state.h"

extern "C" void echidna_module_attach() {
    auto &state = echidna::state::SharedState::instance();
    state.refreshFromSharedMemory();
    state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);

    echidna::hooks::AudioHookOrchestrator orchestrator;
    if (!orchestrator.installHooks()) {
        if (state.status() != static_cast<int>(echidna::state::InternalStatus::kDisabled)) {
            state.setStatus(echidna::state::InternalStatus::kError);
        }
    }
}
