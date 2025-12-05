#pragma once

/**
 * @file compressor.h
 * @brief Dynamic range compressor effect (manual / auto modes, knee shapes).
 */

#include "effect_base.h"

namespace echidna::dsp::effects {

/** Compressor operating mode. */
enum class CompressorMode { kManual, kAuto };

/** Knee characteristic for the compressor. */
enum class KneeType { kHard, kSoft };

/** Compressor tuning parameters. */
struct CompressorParameters {
  CompressorMode mode{CompressorMode::kManual};
  float threshold_db{-24.0f};
  float ratio{3.0f};
  float knee_db{0.0f};
  KneeType knee{KneeType::kHard};
  float attack_ms{5.0f};
  float release_ms{120.0f};
  float makeup_gain_db{0.0f};
};

/** Per-sample compressor implementation applying smooth attack/release. */
class Compressor : public EffectProcessor {
 public:
  /** Set compressor parameters (copied). */
  void set_parameters(const CompressorParameters &params);

  /** Prepare internal state/coefficient calculations for sample rate. */
  void prepare(uint32_t sample_rate, uint32_t channels) override;
  /** Reset envelope state. */
  void reset() override;
  /** Perform compression on the given buffer in-place. */
  void process(ProcessContext &ctx) override;

 private:
  /** Compute the required gain reduction in decibels for a given input dB. */
  float compute_gain_reduction(float input_db);

  CompressorParameters params_{};
  float envelope_{0.0f};
  float attack_coeff_{0.0f};
  float release_coeff_{0.0f};
  float makeup_gain_{1.0f};
};

}  // namespace echidna::dsp::effects
