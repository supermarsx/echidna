#pragma once

/**
 * @file formant_shifter.h
 * @brief Formant shifting effect used to alter perceived vowel/formant
 * characteristics of the voice.
 */

#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects {

/**
 * @brief Parameters for formant shifter; cents and optional intelligibility
 * assist blending.
 */
struct FormantParameters {
  float cents{0.0f};
  bool intelligibility_assist{false};
};

/** Formant shifter implementation. */
class FormantShifter : public EffectProcessor {
 public:
  /** Update formant parameters. */
  void set_parameters(const FormantParameters &params);

  /** Prepare per-channel delay and tilt buffers. */
  void prepare(uint32_t sample_rate, uint32_t channels) override;
  /** Reset internal states to silence. */
  void reset() override;
  /** Run the per-sample formant-shifting algorithm in-place. */
  void process(ProcessContext &ctx) override;

 private:
  FormantParameters params_{};
  std::vector<float> delay_state_;
  std::vector<float> tilt_state_;
};

}  // namespace echidna::dsp::effects
