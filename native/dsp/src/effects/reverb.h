#pragma once

#include <array>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

struct ReverbParameters {
  float room_size{20.0f};
  float damping{30.0f};
  float pre_delay_ms{0.0f};
  float mix{10.0f};
};

class Reverb : public EffectProcessor {
 public:
  void set_parameters(const ReverbParameters &params);

  void prepare(uint32_t sample_rate, uint32_t channels) override;
  void reset() override;
  void process(ProcessContext &ctx) override;

 private:
  struct Comb {
    std::vector<float> buffer;
    size_t index{0};
    float feedback{0.7f};
  };

  struct AllPass {
    std::vector<float> buffer;
    size_t index{0};
    float feedback{0.5f};
  };

  void ensure_buffers();

  ReverbParameters params_{};
  std::vector<Comb> combs_;
  std::vector<AllPass> allpasses_;
  std::vector<float> predelay_buffer_;
  size_t predelay_index_{0};
};

}  // namespace echidna::dsp::effects
