#pragma once

#include "effect_base.h"

namespace echidna::dsp::effects {

struct MixParameters {
  float dry_wet{50.0f};
  float output_gain_db{0.0f};
};

class MixBus : public EffectProcessor {
 public:
  void set_parameters(const MixParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void process(ProcessContext &ctx) override;
  void process_buffers(const float *dry,
                       const float *wet,
                       float *output,
                       size_t frames);

 private:
  MixParameters params_{};
  float dry_gain_{0.5f};
  float wet_gain_{0.5f};
  float output_gain_{1.0f};
};

}  // namespace echidna::dsp::effects
