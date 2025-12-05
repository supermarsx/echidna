#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @file echidna_api.h
 * @brief Public C API exposed by the Zygisk hook module.
 */

#define ECHIDNA_API_VERSION_MAJOR 1U
#define ECHIDNA_API_VERSION_MINOR 1U
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

/**
 * @brief Returns packed MAJOR.MINOR.PATCH version.
 */
uint32_t echidna_api_get_version(void);

/**
 * @brief Returns current hook status for the calling process.
 */
echidna_status_t echidna_get_status(void);

/**
 * @brief Applies a preset/profile JSON string to the in-process DSP engine.
 *
 * Accepts the preset JSON payload as UTF-8, using the schema in spec.md. The
 * profile name is extracted to track active profile for diagnostics.
 *
 * @param profile_json Pointer to JSON buffer.
 * @param length Length of JSON buffer in bytes.
 * @return echidna_result_t Status code.
 */
echidna_result_t echidna_set_profile(const char *profile_json, size_t length);

/**
 * @brief Processes an interleaved float audio block through the DSP engine.
 *
 * If output is null or aliases input, a temporary buffer is used. The DSP is
 * lazily initialised using the first provided sample rate/channel count.
 *
 * @param input Non-null interleaved float samples.
 * @param output Optional output buffer (same length as input).
 * @param frames Per-channel frame count.
 * @param sample_rate Sample rate in Hz.
 * @param channel_count Number of channels.
 * @return echidna_result_t Status code.
 */
echidna_result_t echidna_process_block(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t sample_rate,
                                       uint32_t channel_count);

#ifdef __cplusplus
}
#endif

