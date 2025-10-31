#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum echidna_status {
    ECHIDNA_STATUS_DISABLED = 0,
    ECHIDNA_STATUS_WAITING_FOR_ATTACH = 1,
    ECHIDNA_STATUS_HOOKED = 2,
    ECHIDNA_STATUS_ERROR = 3
} echidna_status_t;

/**
 * Returns the current status of the Echidna audio capture hooks.
 */
echidna_status_t echidna_get_status(void);

/**
 * Sets the active routing profile. The profile can be used by companion
 * applications to coordinate behaviour across processes.
 */
void echidna_set_profile(const char *profile);

#ifdef __cplusplus
}
#endif
