#pragma once

/**
 * @file pitch_shifter.h
 * @brief Pitch shifting support including different backends (granular,
 * phase vocoder, optional SoundTouch) and parameter structures.
 */

#include <cstdint>
#include <memory>
#include <vector>

#include "effect_base.h"

namespace echidna::dsp::effects
{

  /** Quality / algorithm selection for pitch shifting. */
  enum class PitchQuality
  {
    kLowLatency,
    kHighQuality
  };

  /** Parameter block controlling pitch shift behaviour. */
  struct PitchParameters
  {
    float semitones{0.0f};
    float cents{0.0f};
    PitchQuality quality{PitchQuality::kLowLatency};
    bool preserve_formants{false};
  };

  /**
   * @brief Abstract backend for performing pitch shift processing. Concrete
   * implementations provide configure(), reset() and process().
   */
  class PitchBackend
  {
  public:
    virtual ~PitchBackend() = default;
    virtual void configure(uint32_t sample_rate,
                           uint32_t channels,
                           float ratio,
                           bool preserve_formants) = 0;
    virtual void reset() = 0;
    virtual void process(const float *input,
                         float *output,
                         size_t frames) = 0;
  };

  /**
   * @brief Top-level PitchShifter effect which selects and owns a concrete
   * PitchBackend instance per configuration.
   */
  class PitchShifter : public EffectProcessor
  {
  public:
    /** Construct an unconfigured PitchShifter. */
    PitchShifter();
    ~PitchShifter() override;

    /** Update parameters (copy) and rebuild internal backend if required. */
    void set_parameters(const PitchParameters &params);

    /** Prepare effect; triggers backend configuration. */
    void prepare(uint32_t sample_rate, uint32_t channels) override;
    /** Reset backend state. */
    void reset() override;
    /** Execute processing with the active backend. */
    void process(ProcessContext &ctx) override;

  private:
    void rebuild_backend();
    float ratio() const;

    PitchParameters params_{};
    std::unique_ptr<PitchBackend> backend_;
    std::vector<float> scratch_;
  };

} // namespace echidna::dsp::effects
