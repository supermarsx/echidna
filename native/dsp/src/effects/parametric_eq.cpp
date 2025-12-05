#include "parametric_eq.h"

/**
 * @file parametric_eq.cpp
 * @brief ParametricEQ implementation and filter coefficient math.
 */

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects
{

  /** Set new band definitions and update coefficients. */
  void ParametricEQ::set_bands(std::vector<EqBand> bands)
  {
    bands_ = std::move(bands);
    update_coefficients();
  }

  /** Prepare filters for sample-rate and channel count. */
  void ParametricEQ::prepare(uint32_t sample_rate, uint32_t channels)
  {
    EffectProcessor::prepare(sample_rate, channels);
    filters_.resize(bands_.size() * channels);
    update_coefficients();
  }

  /** Reset internal filter delays to zero. */
  void ParametricEQ::reset()
  {
    for (auto &f : filters_)
    {
      f.z1 = 0.0f;
      f.z2 = 0.0f;
    }
  }

  /** Run processing across all configured bands for each channel. */
  void ParametricEQ::process(ProcessContext &ctx)
  {
    if (!enabled_)
    {
      return;
    }
    for (size_t band = 0; band < bands_.size(); ++band)
    {
      for (uint32_t ch = 0; ch < channels_; ++ch)
      {
        Biquad &f = filters_[band * channels_ + ch];
        float z1 = f.z1;
        float z2 = f.z2;
        for (size_t frame = 0; frame < ctx.frames; ++frame)
        {
          const size_t idx = frame * channels_ + ch;
          const float x = ctx.buffer[idx];
          float y = f.a0 * x + z1;
          z1 = f.a1 * x - f.b1 * y + z2;
          z2 = f.a2 * x - f.b2 * y;
          ctx.buffer[idx] = y;
        }
        f.z1 = z1;
        f.z2 = z2;
      }
    }
  }

  /** Recompute biquad coefficients for the active band list. */
  void ParametricEQ::update_coefficients()
  {
    if (sample_rate_ == 0 || channels_ == 0)
    {
      return;
    }
    if (filters_.size() != bands_.size() * channels_)
    {
      filters_.assign(bands_.size() * channels_, {});
    }
    const float sr = static_cast<float>(sample_rate_);
    for (size_t band = 0; band < bands_.size(); ++band)
    {
      const EqBand &b = bands_[band];
      const float freq = std::clamp(b.frequency_hz, 20.0f, 12000.0f);
      const float gain_db = std::clamp(b.gain_db, -12.0f, 12.0f);
      const float q = std::clamp(b.q, 0.3f, 10.0f);
      const float a = std::pow(10.0f, gain_db / 40.0f);
      const float w0 = 2.0f * static_cast<float>(M_PI) * freq / sr;
      const float alpha = std::sin(w0) / (2.0f * q);
      const float cosw0 = std::cos(w0);

      const float b0 = 1.0f + alpha * a;
      const float b1 = -2.0f * cosw0;
      const float b2 = 1.0f - alpha * a;
      const float a0 = 1.0f + alpha / a;
      const float a1 = -2.0f * cosw0;
      const float a2 = 1.0f - alpha / a;

      const float inv_a0 = 1.0f / a0;

      for (uint32_t ch = 0; ch < channels_; ++ch)
      {
        Biquad &f = filters_[band * channels_ + ch];
        f.a0 = b0 * inv_a0;
        f.a1 = b1 * inv_a0;
        f.a2 = b2 * inv_a0;
        f.b1 = a1 * inv_a0;
        f.b2 = a2 * inv_a0;
        f.z1 = 0.0f;
        f.z2 = 0.0f;
      }
    }
  }

} // namespace echidna::dsp::effects
