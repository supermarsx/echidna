#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ECHIDNA_API_VERSION_MAJOR 1U
#define ECHIDNA_API_VERSION_MINOR 0U
#define ECHIDNA_API_VERSION_PATCH 0U

#define ECHIDNA_API_VERSION \
  ((ECHIDNA_API_VERSION_MAJOR << 16) | (ECHIDNA_API_VERSION_MINOR << 8) | \
   (ECHIDNA_API_VERSION_PATCH))

typedef enum echidna_result {
  ECHIDNA_RESULT_OK = 0,
  ECHIDNA_RESULT_ERROR = -1,
  ECHIDNA_RESULT_INVALID_ARGUMENT = -2,
  ECHIDNA_RESULT_NOT_INITIALISED = -3,
  ECHIDNA_RESULT_PERMISSION_DENIED = -4,
  ECHIDNA_RESULT_NOT_SUPPORTED = -5,
  ECHIDNA_RESULT_SIGNATURE_INVALID = -6,
  ECHIDNA_RESULT_NOT_AVAILABLE = -7
} echidna_result_t;

typedef enum echidna_status {
  ECHIDNA_STATUS_DISABLED = 0,
  ECHIDNA_STATUS_WAITING_FOR_ATTACH = 1,
  ECHIDNA_STATUS_HOOKED = 2,
  ECHIDNA_STATUS_ERROR = 3
} echidna_status_t;

uint32_t echidna_api_get_version(void);

echidna_status_t echidna_get_status(void);

echidna_result_t echidna_set_profile(const char *profile);

echidna_result_t echidna_process_block(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t sample_rate,
                                       uint32_t channel_count);

#ifdef __cplusplus
}
#endif

