#pragma once

/**
 * @file auto_tune.h
 * @brief Auto-tune effect which detects pitch and snaps notes to a musical
 * scale with smoothing, humanize and formant-preserving options.
 */

#include <array>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects
{

  /** Musical key enumeration for AutoTune. */
  enum class MusicalKey
  {
    kC = 0,
    kCSharp,
    kD,
    kDSharp,
    kE,
    kF,
    kFSharp,
    kG,
    kGSharp,
    kA,
    kASharp,
    kB
  };

  /** Musical scale selection used to pick target pitches. */
  enum class ScaleType
  {
    kMajor,
    kMinor,
    kChromatic,
    kDorian,
    kPhrygian,
    kLydian,
    kMixolydian,
    kAeolian,
    kLocrian
  };

  /** Parameters controlling auto-tune behaviour and heuristics. */
  struct AutoTuneParameters
  {
    MusicalKey key{MusicalKey::kC};
    ScaleType scale{ScaleType::kChromatic};
    float retune_speed_ms{50.0f};
    float humanize{50.0f};
    float flex_tune{0.0f};
    bool formant_preserve{false};
    float snap_strength{100.0f};
  };

  /** AutoTune effect implementation. */
  class AutoTune : public EffectProcessor
  {
  public:
    /** Configure auto-tune parameters. */
    void set_parameters(const AutoTuneParameters &params);

    /** Prepare internal buffers/state for the sample rate and channels. */
    void prepare(uint32_t sample_rate, uint32_t channels) override;
    /** Reset filter and tracking state. */
    void reset() override;
    /** Perform pitch detection + correction across `ctx.frames`. */
    void process(ProcessContext &ctx) override;

  private:
    /** Detect the dominant pitch (Hz) from samples for a given channel. */
    float detect_pitch(const float *samples, size_t frames, uint32_t channel);
    /** Map an input frequency to a target pitch based on selected key/scale. */
    float target_pitch(float input_hz) const;

    AutoTuneParameters params_{};
    std::vector<float> scratch_;
    std::vector<float> last_pitch_;
  };

} // namespace echidna::dsp::effects
