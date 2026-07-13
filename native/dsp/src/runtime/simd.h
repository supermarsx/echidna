#pragma once

/**
 * @file simd.h
 * @brief Small SIMD utility helpers used across the DSP pipeline. Implementations
 * will use platform optimized intrinsics when available.
 */

#include <cstddef>

namespace echidna::dsp::runtime
{

    /**
     * @brief Multiply each sample by a scalar gain in-place.
     * @param data Pointer to sample buffer with at least `samples` elements.
     * @param samples Number of samples to process.
     * @param gain Scalar gain multiplier.
     */
    void apply_gain(float *data, size_t samples, float gain);
    /**
     * @brief Mix `src` into `dst` with an applied gain to the source samples.
     *
     * dst[i] += src[i] * gain for i in [0, samples)
     */
    void mix_in(float *dst, const float *src, size_t samples, float gain);

} // namespace echidna::dsp::runtime
