#pragma once

#include <cstddef>
#include <cstdint>

namespace echidna::dsp::effects {

struct ProcessContext {
  float *buffer{nullptr};
  size_t frames{0};
  uint32_t channels{0};
  uint32_t sample_rate{0};
};

class EffectProcessor {
 public:
  virtual ~EffectProcessor() = default;

  virtual void prepare(uint32_t sample_rate, uint32_t channels) {
    sample_rate_ = sample_rate;
    channels_ = channels;
  }

  virtual void reset() {}

  virtual void process(ProcessContext &ctx) = 0;

  void set_enabled(bool enabled) { enabled_ = enabled; }
  bool enabled() const { return enabled_; }

 protected:
  uint32_t sample_rate_{0};
  uint32_t channels_{0};
  bool enabled_{false};
};

}  // namespace echidna::dsp::effects
