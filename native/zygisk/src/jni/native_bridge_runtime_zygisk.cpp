#include "jni/native_bridge_runtime.h"

#include "echidna/api.h"
#include "state/shared_state.h"

namespace echidna::jni
{

    bool InitialiseRuntime()
    {
        auto &state = state::SharedState::instance();
        state.setStatus(state::InternalStatus::kWaitingForAttach);
        return true;
    }

    bool IsRuntimeReady()
    {
        return state::SharedState::instance().audioProcessingAllowed();
    }

    void SetRuntimeBypass(bool bypass)
    {
        auto &state = state::SharedState::instance();
        state.setBypass(bypass);
        state.setStatus(bypass ? state::InternalStatus::kDisabled
                               : state::InternalStatus::kWaitingForAttach);
    }

    bool SetRuntimeProfile(const char *json, size_t length)
    {
        return echidna_set_profile(json, length) == ECHIDNA_RESULT_OK;
    }

    int RuntimeStatus()
    {
        return static_cast<int>(echidna_get_status());
    }

    echidna_result_t ProcessRuntimeBlock(const float *input,
                                         float *output,
                                         uint32_t frames,
                                         uint32_t sample_rate,
                                         uint32_t channels)
    {
        return echidna_process_block(input, output, frames, sample_rate, channels);
    }

} // namespace echidna::jni
