#pragma once

#include "effect_base.h"

namespace echidna::dsp::effects {

enum class CompressorMode { kManual, kAuto };

enum class KneeType { kHard, kSoft };

struct CompressorParameters {
  CompressorMode mode{CompressorMode::kManual};
  float threshold_db{-24.0f};
  float ratio{3.0f};
  float knee_db{0.0f};
  KneeType knee{KneeType::kHard};
  float attack_ms{5.0f};
  float release_ms{120.0f};
  float makeup_gain_db{0.0f};
};

class Compressor : public EffectProcessor {
 public:
  void set_parameters(const CompressorParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  float compute_gain_reduction(float input_db);

  CompressorParameters params_{};
  float envelope_{0.0f};
  float attack_coeff_{0.0f};
  float release_coeff_{0.0f};
  float makeup_gain_{1.0f};
};

}  // namespace echidna::dsp::effects
