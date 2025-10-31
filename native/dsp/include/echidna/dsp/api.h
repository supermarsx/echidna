#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum ech_dsp_status {
    ECH_DSP_STATUS_OK = 0,
    ECH_DSP_STATUS_ERROR = -1,
    ECH_DSP_STATUS_NOT_INITIALISED = -2,
    ECH_DSP_STATUS_INVALID_ARGUMENT = -3
} ech_dsp_status_t;

typedef enum ech_dsp_quality_mode {
    ECH_DSP_QUALITY_LOW_LATENCY = 0,
    ECH_DSP_QUALITY_BALANCED = 1,
    ECH_DSP_QUALITY_HIGH = 2
} ech_dsp_quality_mode_t;

/**
 * Initialises the DSP engine with the provided sample rate and channels. The
 * engine keeps the quality mode to gate expensive algorithms (e.g. RubberBand).
 */
ech_dsp_status_t ech_dsp_initialize(uint32_t sample_rate,
                                     uint32_t channels,
                                     ech_dsp_quality_mode_t quality_mode);

/**
 * Updates the current preset configuration. The JSON string must match the
 * schema described in spec.md and safe ranges are validated before applying.
 */
ech_dsp_status_t ech_dsp_update_config(const char *json_config,
                                        size_t json_length);

/**
 * Processes a single interleaved float audio block. Input and output buffers
 * must not overlap. Frames denote per-channel frames (not samples).
 */
ech_dsp_status_t ech_dsp_process_block(const float *input,
                                        float *output,
                                        size_t frames);

/**
 * Destroys engine state and frees resources.
 */
void ech_dsp_shutdown(void);

#ifdef __cplusplus
}
#endif

#ifdef __cplusplus

#include <memory>
#include <string>

namespace echidna::dsp {

class DspEngine;

std::shared_ptr<DspEngine> acquire_engine();
void release_engine();

}  // namespace echidna::dsp

#endif
