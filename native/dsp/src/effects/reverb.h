#pragma once

/**
 * @file reverb.h
 * @brief Reverb effect implementation and parameter structure.
 */

#include <array>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

/**
 * @brief Parameters controlling the reverb effect
 */
struct ReverbParameters {
  float room_size{20.0f};
  float damping{30.0f};
  float pre_delay_ms{0.0f};
  float mix{10.0f};
};

/**
 * @brief Simple multi-comb/all-pass reverb processor.
 */
class Reverb : public EffectProcessor {
 public:
  /** Set new reverb parameters (copied). */
  void set_parameters(const ReverbParameters &params);

  /** Prepare internal buffers for the configured sample rate and channels. */
  void prepare(uint32_t sample_rate, uint32_t channels) override;
  /** Reset internal filter states and buffers. */
  void reset() override;
  /** Process `ctx.frames` frames in-place stored at ctx.buffer. */
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
