#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

    /**
     * @file echidna_api.h
     * @brief Public C API exposed by the Zygisk hook module.
     */

#define ECHIDNA_API_VERSION_MAJOR 1U
#define ECHIDNA_API_VERSION_MINOR 3U
#define ECHIDNA_API_VERSION_PATCH 0U

#define ECHIDNA_API_VERSION                                                 \
    ((ECHIDNA_API_VERSION_MAJOR << 16) | (ECHIDNA_API_VERSION_MINOR << 8) | \
     (ECHIDNA_API_VERSION_PATCH))

    typedef enum echidna_result
    {
        ECHIDNA_RESULT_OK = 0,
        ECHIDNA_RESULT_ERROR = -1,
        ECHIDNA_RESULT_INVALID_ARGUMENT = -2,
        ECHIDNA_RESULT_NOT_INITIALISED = -3,
        ECHIDNA_RESULT_PERMISSION_DENIED = -4,
        ECHIDNA_RESULT_NOT_SUPPORTED = -5,
        ECHIDNA_RESULT_SIGNATURE_INVALID = -6,
        ECHIDNA_RESULT_NOT_AVAILABLE = -7
    } echidna_result_t;

    typedef enum echidna_status
    {
        ECHIDNA_STATUS_DISABLED = 0,
        ECHIDNA_STATUS_WAITING_FOR_ATTACH = 1,
        ECHIDNA_STATUS_HOOKED = 2,
        ECHIDNA_STATUS_ERROR = 3
    } echidna_status_t;

    /** Portable generation token for one independently-owned DSP stream. */
    typedef uint32_t echidna_stream_handle_t;

    typedef enum echidna_pcm_format
    {
        ECHIDNA_PCM_FORMAT_SIGNED_16 = 1,
        ECHIDNA_PCM_FORMAT_FLOAT_32 = 2
    } echidna_pcm_format_t;

    typedef struct echidna_stream_config
    {
        /** Must be sizeof(echidna_stream_config_t). */
        uint32_t struct_size;
        uint32_t sample_rate;
        uint32_t channel_count;
        uint32_t max_frames;
        /** One of echidna_pcm_format_t. */
        uint32_t format;
        uint32_t reserved[3];
    } echidna_stream_config_t;

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
     * @brief Loads, configures, and preallocates DSP state for one PCM stream.
     *
     * This may allocate and perform dynamic linking. Call it from stream-open or
     * hook-install lifecycle code, never from an audio callback.
     */
    echidna_result_t echidna_prepare_stream(uint32_t sample_rate,
                                            uint32_t channel_count);

    /**
     * @brief Processes an interleaved float audio block through the DSP engine.
     *
     * The stream must first be prepared with echidna_prepare_stream(). If output
     * is null or aliases input, lifecycle-preallocated scratch is used.
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

    /** Creates one fixed-registry DSP stream. This is never callback-safe. */
    echidna_result_t echidna_stream_create(const echidna_stream_config_t *config,
                                           echidna_stream_handle_t *handle);

    /**
     * Processes PCM without allocation or locking. Input and output may alias.
     * The supplied format must exactly match the stream's immutable format.
     */
    echidna_result_t echidna_stream_process(echidna_stream_handle_t handle,
                                            const void *input,
                                            void *output,
                                            uint32_t frames,
                                            uint32_t format);

    /**
     * Publishes a replacement engine for a strictly newer profile generation.
     * A null profile with zero length revokes processing and causes immediate
     * pass-through until a newer non-empty generation is published.
     */
    echidna_result_t echidna_stream_update(echidna_stream_handle_t handle,
                                           const char *profile_json,
                                           size_t length,
                                           uint64_t profile_generation);

    /** Revokes, quiesces, and destroys a stream. Stale tokens remain invalid. */
    echidna_result_t echidna_stream_destroy(echidna_stream_handle_t handle);

#ifdef __cplusplus
}
#endif
