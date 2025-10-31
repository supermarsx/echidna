#pragma once

#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

struct FormantParameters {
  float cents{0.0f};
  bool intelligibility_assist{false};
};

class FormantShifter : public EffectProcessor {
 public:
  void set_parameters(const FormantParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  FormantParameters params_{};
  std::vector<float> delay_state_;
  std::vector<float> tilt_state_;
};

}  // namespace echidna::dsp::effects
