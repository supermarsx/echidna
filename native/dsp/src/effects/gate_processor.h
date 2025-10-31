#pragma once

#include "effect_base.h"

namespace echidna::dsp::effects {

struct GateParameters {
  float threshold_db{-45.0f};
  float attack_ms{5.0f};
  float release_ms{80.0f};
  float hysteresis_db{3.0f};
};

class GateProcessor : public EffectProcessor {
 public:
  void set_parameters(const GateParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  GateParameters params_{};
  float envelope_{0.0f};
  float gain_{1.0f};
  float attack_coeff_{0.0f};
  float release_coeff_{0.0f};
};

}  // namespace echidna::dsp::effects
