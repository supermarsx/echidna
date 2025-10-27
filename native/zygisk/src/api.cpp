#include "echidna/api.h"

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
