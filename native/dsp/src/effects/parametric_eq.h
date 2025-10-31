#pragma once

#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

struct EqBand {
  float frequency_hz{1000.0f};
  float gain_db{0.0f};
  float q{1.0f};
};

class ParametricEQ : public EffectProcessor {
 public:
  void set_bands(std::vector<EqBand> bands);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  struct Biquad {
    float a0{1.0f};
    float a1{0.0f};
    float a2{0.0f};
    float b1{0.0f};
    float b2{0.0f};
    float z1{0.0f};
    float z2{0.0f};
  };

  void update_coefficients();

  std::vector<EqBand> bands_{};
  std::vector<Biquad> filters_{};
};

}  // namespace echidna::dsp::effects
