#include "simd.h"

#include <algorithm>

#if defined(ECHIDNA_DSP_HAS_NEON)
#include <arm_neon.h>
#elif defined(ECHIDNA_DSP_HAS_AVX) && defined(__AVX__)
#include <immintrin.h>
#endif

namespace echidna::dsp::runtime {

void apply_gain(float *data, size_t samples, float gain) {
#if defined(ECHIDNA_DSP_HAS_NEON)
  const float32x4_t gain_vec = vdupq_n_f32(gain);
  size_t i = 0;
  for (; i + 4 <= samples; i += 4) {
    float32x4_t x = vld1q_f32(&data[i]);
    x = vmulq_f32(x, gain_vec);
    vst1q_f32(&data[i], x);
  }
  for (; i < samples; ++i) {
    data[i] *= gain;
  }
#elif defined(ECHIDNA_DSP_HAS_AVX) && defined(__AVX__)
  const __m256 gain_vec = _mm256_set1_ps(gain);
  size_t i = 0;
  for (; i + 8 <= samples; i += 8) {
    __m256 x = _mm256_loadu_ps(&data[i]);
    x = _mm256_mul_ps(x, gain_vec);
    _mm256_storeu_ps(&data[i], x);
  }
  for (; i < samples; ++i) {
    data[i] *= gain;
  }
#else
  for (size_t i = 0; i < samples; ++i) {
    data[i] *= gain;
  }
#endif
}

void mix_in(float *dst, const float *src, size_t samples, float gain) {
#if defined(ECHIDNA_DSP_HAS_NEON)
  const float32x4_t gain_vec = vdupq_n_f32(gain);
  size_t i = 0;
  for (; i + 4 <= samples; i += 4) {
    float32x4_t d = vld1q_f32(&dst[i]);
    float32x4_t s = vld1q_f32(&src[i]);
    s = vmulq_f32(s, gain_vec);
    d = vaddq_f32(d, s);
    vst1q_f32(&dst[i], d);
  }
  for (; i < samples; ++i) {
    dst[i] += src[i] * gain;
  }
#elif defined(ECHIDNA_DSP_HAS_AVX) && defined(__AVX__)
  const __m256 gain_vec = _mm256_set1_ps(gain);
  size_t i = 0;
  for (; i + 8 <= samples; i += 8) {
    __m256 d = _mm256_loadu_ps(&dst[i]);
    __m256 s = _mm256_loadu_ps(&src[i]);
    s = _mm256_mul_ps(s, gain_vec);
    d = _mm256_add_ps(d, s);
    _mm256_storeu_ps(&dst[i], d);
  }
  for (; i < samples; ++i) {
    dst[i] += src[i] * gain;
  }
#else
  for (size_t i = 0; i < samples; ++i) {
    dst[i] += src[i] * gain;
  }
#endif
}

}  // namespace echidna::dsp::runtime
