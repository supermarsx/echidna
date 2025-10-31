#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

enum class PitchQuality { kLowLatency, kHighQuality };

struct PitchParameters {
  float semitones{0.0f};
  float cents{0.0f};
  PitchQuality quality{PitchQuality::kLowLatency};
  bool preserve_formants{false};
};

class PitchBackend {
 public:
  virtual ~PitchBackend() = default;
  virtual void configure(uint32_t sample_rate,
                         uint32_t channels,
                         float ratio,
                         bool preserve_formants) = 0;
  virtual void reset() = 0;
  virtual void process(const float *input,
                       float *output,
                       size_t frames) = 0;
};

class PitchShifter : public EffectProcessor {
 public:
  PitchShifter();
  ~PitchShifter() override;

  void set_parameters(const PitchParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  void rebuild_backend();
  float ratio() const;

  PitchParameters params_{};
  std::unique_ptr<PitchBackend> backend_;
  std::vector<float> scratch_;
};

}  // namespace echidna::dsp::effects
