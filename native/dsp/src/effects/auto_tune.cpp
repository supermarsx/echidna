#include "auto_tune.h"

/**
 * @file auto_tune.cpp
 * @brief Implementation of AutoTune detector and correction algorithms.
 */

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

namespace echidna::dsp::effects
{
  namespace
  {
    const std::array<int, 12> kChromatic{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    const std::array<int, 7> kMajor{0, 2, 4, 5, 7, 9, 11};
    const std::array<int, 7> kMinor{0, 2, 3, 5, 7, 8, 10};
    const std::array<int, 7> kDorian{0, 2, 3, 5, 7, 9, 10};
    const std::array<int, 7> kPhrygian{0, 1, 3, 5, 7, 8, 10};
    const std::array<int, 7> kLydian{0, 2, 4, 6, 7, 9, 11};
    const std::array<int, 7> kMixolydian{0, 2, 4, 5, 7, 9, 10};
    const std::array<int, 7> kAeolian{0, 2, 3, 5, 7, 8, 10};
    const std::array<int, 7> kLocrian{0, 1, 3, 5, 6, 8, 10};

    float midi_to_hz(float midi)
    {
      return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
    }

    float hz_to_midi(float hz)
    {
      if (hz <= 0.0f)
      {
        return 0.0f;
      }
      return 69.0f + 12.0f * std::log2(hz / 440.0f);
    }

    float clamp_frequency(float hz)
    {
      return std::clamp(hz, 40.0f, 2000.0f);
    }

  } // namespace

  /** Set AutoTune configuration parameters. */
  void AutoTune::set_parameters(const AutoTuneParameters &params)
  {
    params_ = params;
  }

  /** Prepare state and tracking buffers for processing. */
  void AutoTune::prepare(uint32_t sample_rate, uint32_t channels)
  {
    EffectProcessor::prepare(sample_rate, channels);
    last_pitch_.assign(channels, 1.0f);
    scratch_.resize(0);
  }

  /** Reset last pitch tracking state to defaults. */
  void AutoTune::reset() { std::fill(last_pitch_.begin(), last_pitch_.end(), 1.0f); }

  /** Estimate the fundamental frequency from the provided mono samples. */
  float AutoTune::detect_pitch(const float *samples, size_t frames, uint32_t channel)
  {
    (void)channel;
    const size_t min_period = static_cast<size_t>(sample_rate_ / 1000.0f);
    const size_t max_period = static_cast<size_t>(sample_rate_ / 60.0f);
    if (max_period >= frames || min_period == 0)
    {
      return 0.0f;
    }
    float best_corr = 0.0f;
    size_t best_period = 0;
    for (size_t period = min_period; period <= max_period; ++period)
    {
      float corr = 0.0f;
      for (size_t i = 0; i + period < frames; ++i)
      {
        corr += samples[i] * samples[i + period];
      }
      if (corr > best_corr)
      {
        best_corr = corr;
        best_period = period;
      }
    }
    if (best_period == 0)
    {
      return 0.0f;
    }
    return static_cast<float>(sample_rate_) / static_cast<float>(best_period);
  }

  /** Compute the nearest target pitch (Hz) from input frequency using
   * configured key and scale. */
  float AutoTune::target_pitch(float input_hz) const
  {
    const float midi = hz_to_midi(input_hz);
    const int key = static_cast<int>(params_.key);
    float best_midi = midi;
    float best_diff = std::numeric_limits<float>::max();

    auto evaluate_pattern = [&](const auto &pattern)
    {
      for (int interval : pattern)
      {
        float candidate = static_cast<float>(key + interval);
        float octave = std::round((midi - candidate) / 12.0f);
        candidate += 12.0f * octave;
        const float diff = std::abs(candidate - midi);
        if (diff < best_diff)
        {
          best_diff = diff;
          best_midi = candidate;
        }
      }
    };

    switch (params_.scale)
    {
    case ScaleType::kMajor:
      evaluate_pattern(kMajor);
      break;
    case ScaleType::kMinor:
      evaluate_pattern(kMinor);
      break;
    case ScaleType::kChromatic:
      evaluate_pattern(kChromatic);
      break;
    case ScaleType::kDorian:
      evaluate_pattern(kDorian);
      break;
    case ScaleType::kPhrygian:
      evaluate_pattern(kPhrygian);
      break;
    case ScaleType::kLydian:
      evaluate_pattern(kLydian);
      break;
    case ScaleType::kMixolydian:
      evaluate_pattern(kMixolydian);
      break;
    case ScaleType::kAeolian:
      evaluate_pattern(kAeolian);
      break;
    case ScaleType::kLocrian:
      evaluate_pattern(kLocrian);
      break;
    }

    return clamp_frequency(midi_to_hz(best_midi));
  }

  /** Run pitch detection + correction across ctx.frames for each channel. */
  void AutoTune::process(ProcessContext &ctx)
  {
    if (!enabled_)
    {
      return;
    }
    scratch_.resize(ctx.frames);
    const float snap = std::clamp(params_.snap_strength, 0.0f, 100.0f) / 100.0f;
    const float flex = std::clamp(params_.flex_tune, 0.0f, 100.0f) / 100.0f;
    const float humanize = std::clamp(params_.humanize, 0.0f, 100.0f) / 100.0f;
    const float retune_ms = std::clamp(params_.retune_speed_ms, 1.0f, 200.0f);
    const float coeff = std::exp(-1.0f /
                                 ((retune_ms / 1000.0f) * static_cast<float>(sample_rate_) + 1.0f));

    for (uint32_t ch = 0; ch < channels_; ++ch)
    {
      for (size_t frame = 0; frame < ctx.frames; ++frame)
      {
        scratch_[frame] = ctx.buffer[frame * channels_ + ch];
      }
      float detected = detect_pitch(scratch_.data(), ctx.frames, ch);
      if (detected <= 0.0f)
      {
        continue;
      }
      const float target = target_pitch(detected);
      float ratio = target / detected;
      ratio = 1.0f + (ratio - 1.0f) * snap;
      ratio = ratio * (1.0f - flex) + 1.0f * flex;
      float smoothed = last_pitch_[ch];
      smoothed += (ratio - smoothed) * (1.0f - coeff);
      smoothed = smoothed * (1.0f - humanize) + 1.0f * humanize;
      last_pitch_[ch] = smoothed;

      // Simple resampling per channel.
      const float inv_ratio = 1.0f / smoothed;
      float phase = 0.0f;
      for (size_t frame = 0; frame < ctx.frames; ++frame)
      {
        const size_t i0 = std::min(static_cast<size_t>(phase), ctx.frames - 1);
        const size_t i1 = std::min(i0 + 1, ctx.frames - 1);
        const float frac = phase - static_cast<float>(i0);
        float value = scratch_[i0] + (scratch_[i1] - scratch_[i0]) * frac;
        if (params_.formant_preserve)
        {
          // Blend towards dry signal to preserve characteristics.
          value = value * 0.85f + scratch_[frame] * 0.15f;
        }
        ctx.buffer[frame * channels_ + ch] = value;
        phase += inv_ratio;
        if (phase >= static_cast<float>(ctx.frames - 1))
        {
          phase -= static_cast<float>(ctx.frames - 1);
        }
      }
    }
  }

} // namespace echidna::dsp::effects
