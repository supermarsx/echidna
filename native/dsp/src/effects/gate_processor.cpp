#include "gate_processor.h"

/**
 * @file gate_processor.cpp
 * @brief Gate processor math and runtime implementation.
 */

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects
{
  namespace
  {
    constexpr float kMinDb = -120.0f;

    float db_to_amplitude(float db)
    {
      return std::pow(10.0f, db / 20.0f);
    }

    float ms_to_coeff(float ms, uint32_t sample_rate)
    {
      const float samples = (ms / 1000.0f) * static_cast<float>(sample_rate);
      if (samples <= 1.0f)
      {
        return 0.0f;
      }
      return std::exp(-1.0f / samples);
    }
  } // namespace

  /** Set gate parameters (threshold/attack/release/hysteresis). */
  void GateProcessor::set_parameters(const GateParameters &params)
  {
    params_ = params;
  }

  /** Compute attack/release coefficients for the configured sample rate. */
  void GateProcessor::prepare(uint32_t sample_rate, uint32_t channels)
  {
    EffectProcessor::prepare(sample_rate, channels);
    attack_coeff_ = ms_to_coeff(params_.attack_ms, sample_rate);
    release_coeff_ = ms_to_coeff(params_.release_ms, sample_rate);
  }

  /** Reset internal envelope and gain. */
  void GateProcessor::reset()
  {
    envelope_ = 0.0f;
    gain_ = 1.0f;
  }

  /** Apply gating algorithm to the provided buffer in-place. */
  void GateProcessor::process(ProcessContext &ctx)
  {
    if (!enabled_)
    {
      return;
    }

    const float threshold_amp = db_to_amplitude(params_.threshold_db);
    const float open_amp =
        db_to_amplitude(params_.threshold_db + params_.hysteresis_db);
    const float close_amp =
        db_to_amplitude(params_.threshold_db - params_.hysteresis_db);

    float *buffer = ctx.buffer;
    const size_t samples = ctx.frames * ctx.channels;
    for (size_t i = 0; i < samples; ++i)
    {
      const float level = std::abs(buffer[i]);
      envelope_ = std::max(level, envelope_);
      if (envelope_ > open_amp)
      {
        const float delta = 1.0f - gain_;
        gain_ += (1.0f - attack_coeff_) * delta;
      }
      else if (envelope_ < close_amp)
      {
        gain_ *= release_coeff_;
      }
      if (envelope_ < threshold_amp)
      {
        envelope_ = threshold_amp;
      }
      buffer[i] *= gain_;
      envelope_ *= release_coeff_;
    }
  }

} // namespace echidna::dsp::effects
