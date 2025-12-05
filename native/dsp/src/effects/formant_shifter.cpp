#include "formant_shifter.h"

/**
 * @file formant_shifter.cpp
 * @brief Implementation of the formant shifter effect.
 */

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects
{

  /** Set new formant shifter parameters. */
  void FormantShifter::set_parameters(const FormantParameters &params)
  {
    params_ = params;
  }

  /** Prepare internal per-channel buffers. */
  void FormantShifter::prepare(uint32_t sample_rate, uint32_t channels)
  {
    EffectProcessor::prepare(sample_rate, channels);
    delay_state_.assign(channels, 0.0f);
    tilt_state_.assign(channels, 0.0f);
  }

  /** Reset internal state buffers to zero. */
  void FormantShifter::reset()
  {
    std::fill(delay_state_.begin(), delay_state_.end(), 0.0f);
    std::fill(tilt_state_.begin(), tilt_state_.end(), 0.0f);
  }

  /** Perform in-place formant shifting across the buffer. */
  void FormantShifter::process(ProcessContext &ctx)
  {
    if (!enabled_)
    {
      return;
    }
    const float cents = std::clamp(params_.cents, -600.0f, 600.0f);
    const float ratio = std::pow(2.0f, cents / 1200.0f);
    const float allpass_coeff = (ratio - 1.0f) / (ratio + 1.0f);
    const float intelligibility_mix = params_.intelligibility_assist ? 0.25f : 0.0f;

    for (uint32_t ch = 0; ch < channels_; ++ch)
    {
      float prev_x = delay_state_[ch];
      float prev_y = tilt_state_[ch];
      for (size_t frame = 0; frame < ctx.frames; ++frame)
      {
        const size_t idx = frame * channels_ + ch;
        const float x = ctx.buffer[idx];
        float y = -allpass_coeff * x + prev_x + allpass_coeff * prev_y;
        prev_x = x;
        prev_y = y;
        if (intelligibility_mix > 0.0f)
        {
          const float high = y - ctx.buffer[idx > channels_ ? idx - channels_ : idx];
          y = y * (1.0f - intelligibility_mix) + high * intelligibility_mix;
        }
        ctx.buffer[idx] = y;
      }
      delay_state_[ch] = prev_x;
      tilt_state_[ch] = prev_y;
    }
  }

} // namespace echidna::dsp::effects
