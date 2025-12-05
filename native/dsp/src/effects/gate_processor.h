#pragma once

/**
 * @file gate_processor.h
 * @brief Signal gate implementation which attenuates audio below a threshold.
 */

#include "effect_base.h"

namespace echidna::dsp::effects {

/** Gate parameter set: threshold, attack/release, hysteresis. */
struct GateParameters {
  float threshold_db{-45.0f};
  float attack_ms{5.0f};
  float release_ms{80.0f};
  float hysteresis_db{3.0f};
};

/** Gate effect processor implementing soft attack/release and hysteresis. */
class GateProcessor : public EffectProcessor {
 public:
  /** Set gate control parameters (copied). */
  void set_parameters(const GateParameters &params);

  /** Configure internal coefficient values for the sample rate/channels. */
  void prepare(uint32_t sample_rate, uint32_t channels) override;
  /** Reset envelope and gain state. */
  void reset() override;
  /** Process buffer and apply gating to the samples in-place. */
  void process(ProcessContext &ctx) override;

 private:
  GateParameters params_{};
  float envelope_{0.0f};
  float gain_{1.0f};
  float attack_coeff_{0.0f};
  float release_coeff_{0.0f};
};

}  // namespace echidna::dsp::effects
