#pragma once

/**
 * @file mix_bus.h
 * @brief Mixing/dry-wet utilities used at the end of the DSP chain.
 */

#include "effect_base.h"

namespace echidna::dsp::effects
{

  /**
   * @brief Parameters controlling the dry/wet mix and output gain.
   */
  struct MixParameters
  {
    float dry_wet{50.0f};
    float output_gain_db{0.0f};
  };

  /**
   * @brief MixBus combines the processed (wet) buffer with the dry signal
   * according to configured parameters and applies an overall output gain.
   */
  class MixBus : public EffectProcessor
  {
  public:
    /** Update mix parameters (copy). */
    void set_parameters(const MixParameters &params);

    /** Prepare internal values and recompute derived gains. */
    void prepare(uint32_t sample_rate, uint32_t channels) override;
    /** Apply output gain to ctx.buffer. */
    void process(ProcessContext &ctx) override;
    /**
     * @brief Mix dry and wet buffers into output for `frames` frames.
     */
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

} // namespace echidna::dsp::effects
