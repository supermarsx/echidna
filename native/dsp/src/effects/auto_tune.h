#pragma once

#include <array>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

enum class MusicalKey {
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

enum class ScaleType {
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

struct AutoTuneParameters {
  MusicalKey key{MusicalKey::kC};
  ScaleType scale{ScaleType::kChromatic};
  float retune_speed_ms{50.0f};
  float humanize{50.0f};
  float flex_tune{0.0f};
  bool formant_preserve{false};
  float snap_strength{100.0f};
};

class AutoTune : public EffectProcessor {
 public:
  void set_parameters(const AutoTuneParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  float detect_pitch(const float *samples, size_t frames, uint32_t channel);
  float target_pitch(float input_hz) const;

  AutoTuneParameters params_{};
  std::vector<float> scratch_;
  std::vector<float> last_pitch_;
};

}  // namespace echidna::dsp::effects
