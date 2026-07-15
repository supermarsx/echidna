#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

#define ECH_DSP_API_VERSION_MAJOR 1U
#define ECH_DSP_API_VERSION_MINOR 2U
#define ECH_DSP_API_VERSION_PATCH 0U

#define ECH_DSP_API_VERSION                                                 \
    ((ECH_DSP_API_VERSION_MAJOR << 16) | (ECH_DSP_API_VERSION_MINOR << 8) | \
     ECH_DSP_API_VERSION_PATCH)

    /**
     * @file api.h
     * @brief C API for the Echidna DSP engine (libech_dsp.so).
     */

    typedef enum ech_dsp_status
    {
        ECH_DSP_STATUS_OK = 0,
        ECH_DSP_STATUS_ERROR = -1,
        ECH_DSP_STATUS_NOT_INITIALISED = -2,
        ECH_DSP_STATUS_INVALID_ARGUMENT = -3
    } ech_dsp_status_t;

    typedef enum ech_dsp_quality_mode
    {
        ECH_DSP_QUALITY_LOW_LATENCY = 0,
        ECH_DSP_QUALITY_BALANCED = 1,
        ECH_DSP_QUALITY_HIGH = 2
    } ech_dsp_quality_mode_t;

    /** Opaque independently-owned DSP engine used by stream registries. */
    typedef struct ech_dsp_engine ech_dsp_engine_t;

    /** @brief Returns the packed DSP ABI version used by native bridge loaders. */
    uint32_t ech_dsp_api_get_version(void);

    /**
     * @brief Initialises the DSP engine.
     *
     * Keeps quality mode to gate expensive algorithms (e.g. high-quality pitch).
     */
    ech_dsp_status_t ech_dsp_initialize(uint32_t sample_rate,
                                        uint32_t channels,
                                        ech_dsp_quality_mode_t quality_mode);

    /**
     * @brief Applies a preset configuration.
     *
     * JSON must follow the schema in spec.md; parameters are validated before
     * applying. Safe ranges are enforced to guard latency/CPU budgets.
     */
    ech_dsp_status_t ech_dsp_update_config(const char *json_config,
                                           size_t json_length);

    /**
     * @brief Preallocates callback-path buffers for blocks up to max_frames.
     *
     * This is a control/lifecycle operation and must not be called from an audio
     * callback. Once prepared, processing never grows internal block buffers.
     */
    ech_dsp_status_t ech_dsp_prepare_realtime(size_t max_frames);

    /**
     * @brief Processes a single interleaved float audio block.
     *
     * Input and output buffers must not overlap. Frames denote per-channel frames
     * (not samples).
     */
    ech_dsp_status_t ech_dsp_process_block(const float *input,
                                           float *output,
                                           size_t frames);

    /**
     * @brief Builds one independent, callback-prepared DSP engine.
     *
     * This lifecycle operation may allocate. When config is null and length is
     * zero, a safe pass-through preset is applied. The returned engine never
     * shares format or preset state with the legacy singleton C ABI.
     */
    ech_dsp_status_t ech_dsp_engine_create(uint32_t sample_rate,
                                           uint32_t channels,
                                           ech_dsp_quality_mode_t quality_mode,
                                           size_t max_frames,
                                           const char *config,
                                           size_t config_length,
                                           ech_dsp_engine_t **engine);

    /** Processes one block without allocation or internal locking. */
    ech_dsp_status_t ech_dsp_engine_process(ech_dsp_engine_t *engine,
                                            const float *input,
                                            float *output,
                                            size_t frames);

    /** Destroys an engine after its owner has quiesced all callbacks. */
    void ech_dsp_engine_destroy(ech_dsp_engine_t *engine);

    /** @brief Destroys engine state and frees resources. */
    void ech_dsp_shutdown(void);

#ifdef __cplusplus
}
#endif

#ifdef __cplusplus

#include <memory>
#include <string>

namespace echidna::dsp
{

    class DspEngine;

    std::shared_ptr<DspEngine> acquire_engine();
    void release_engine();

} // namespace echidna::dsp

#endif
