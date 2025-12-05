#pragma once

/**
 * @file effect_base.h
 * @brief Base classes and helper types for effect implementations.
 */

#include <cstddef>
#include <cstdint>

namespace echidna::dsp::effects {

/**
 * @brief Context passed to effect processors during `process()` calls.
 */
struct ProcessContext {
  float *buffer{nullptr};
  size_t frames{0};
  uint32_t channels{0};
  uint32_t sample_rate{0};
};

/**
 * @brief Abstract base class for an effect processor instance.
 *
 * Implementations should override prepare, reset and process. The base
 * prepare implementation stores the sample rate and channel count.
 */
class EffectProcessor {
 public:
  virtual ~EffectProcessor() = default;

  /**
   * @brief Prepare the effect for the specified sample rate and channels.
   *
   * Default implementation stores the values in protected members.
   */
  virtual void prepare(uint32_t sample_rate, uint32_t channels) {
    sample_rate_ = sample_rate;
    channels_ = channels;
  }

  /**
   * @brief Reset effect internal state to initial conditions.
   */
  virtual void reset() {}

  /**
   * @brief Process `ctx.frames` frames in-place through ctx.buffer.
   */
  virtual void process(ProcessContext &ctx) = 0;

  /**
   * @brief Enable or disable the effect.
   */
  void set_enabled(bool enabled) { enabled_ = enabled; }
  /**
   * @brief Return whether the effect is currently enabled.
   */
  bool enabled() const { return enabled_; }

 protected:
  uint32_t sample_rate_{0};
  uint32_t channels_{0};
  bool enabled_{false};
};

}  // namespace echidna::dsp::effects
