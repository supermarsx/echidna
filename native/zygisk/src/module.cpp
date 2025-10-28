#include "hooks/audio_hook_orchestrator.h"

#include <memory>

#include "state/shared_state.h"

namespace {

std::unique_ptr<echidna::hooks::AudioHookOrchestrator> g_audio_orchestrator;

}  // namespace

extern "C" void echidna_module_attach() {
    auto &state = echidna::state::SharedState::instance();
    state.refreshFromSharedMemory();
    state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);

    if (!g_audio_orchestrator) {
        g_audio_orchestrator = std::make_unique<echidna::hooks::AudioHookOrchestrator>();
    }

    if (!g_audio_orchestrator->installHooks()) {
        if (state.status() != static_cast<int>(echidna::state::InternalStatus::kDisabled)) {
            state.setStatus(echidna::state::InternalStatus::kError);
        }
    }
}
