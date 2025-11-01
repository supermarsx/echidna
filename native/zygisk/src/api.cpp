#include "echidna/api.h"

#include <cstring>

#include "state/shared_state.h"

using echidna::state::SharedState;

echidna_status_t echidna_get_status(void) {
    return static_cast<echidna_status_t>(SharedState::instance().status());
}

void echidna_set_profile(const char *profile) {
    if (!profile) {
        return;
    }
    SharedState::instance().setProfile(profile);
}

echidna_status_t echidna_process_block(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t sample_rate,
                                       uint32_t channel_count) {
    auto &state = SharedState::instance();
    if (!input || frames == 0 || channel_count == 0 || sample_rate == 0) {
        state.setStatus(echidna::state::InternalStatus::kError);
        return static_cast<echidna_status_t>(state.status());
    }
    if (output && output != input) {
        std::memcpy(output, input, static_cast<size_t>(frames) * channel_count * sizeof(float));
    }
    state.setStatus(echidna::state::InternalStatus::kHooked);
    return static_cast<echidna_status_t>(state.status());
}
