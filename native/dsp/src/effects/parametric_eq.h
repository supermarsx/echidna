#pragma once

/**
 * @file parametric_eq.h
 * @brief Parametric equalizer effect: multiple biquad bands with per-band
 * frequency/gain/Q controls.
 */

#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

/**
 * @brief Single EQ band parameter description.
 */
struct EqBand {
  float frequency_hz{1000.0f};
  float gain_db{0.0f};
  float q{1.0f};
};

/**
 * @brief Parametric equalizer that maintains per-band biquad filters
 * and processes audio in-place.
 */
class ParametricEQ : public EffectProcessor {
 public:
  /** Set EQ bands and recompute filter coefficients. */
  void set_bands(std::vector<EqBand> bands);

  /** Prepare internal filters for the current sample-rate and channels. */
  void prepare(uint32_t sample_rate, uint32_t channels) override;
  /** Reset filter internal state. */
  void reset() override;
  /** Process frames through each configured biquad band in sequence. */
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

  /** Update internal biquad coefficients from configured bands. */
  void update_coefficients();

  std::vector<EqBand> bands_{};
  std::vector<Biquad> filters_{};
};

}  // namespace echidna::dsp::effects
