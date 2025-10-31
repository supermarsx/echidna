#include "mix_bus.h"

#include <algorithm>
#include <cmath>

#include "../runtime/simd.h"

namespace echidna::dsp::effects {

void MixBus::set_parameters(const MixParameters &params) {
  params_ = params;
  const float wet_ratio = std::clamp(params_.dry_wet, 0.0f, 100.0f) / 100.0f;
  wet_gain_ = wet_ratio;
  dry_gain_ = 1.0f - wet_ratio;
  output_gain_ = std::pow(10.0f, std::clamp(params_.output_gain_db, -12.0f, 12.0f) / 20.0f);
}

void MixBus::prepare(uint32_t sample_rate, uint32_t channels) {
  EffectProcessor::prepare(sample_rate, channels);
  set_parameters(params_);
}

void MixBus::process(ProcessContext &ctx) {
  runtime::apply_gain(ctx.buffer, ctx.frames * ctx.channels, output_gain_);
}

void MixBus::process_buffers(const float *dry,
                             const float *wet,
                             float *output,
                             size_t frames) {
  const size_t samples = frames * channels_;
  for (size_t i = 0; i < samples; ++i) {
    output[i] = dry[i] * dry_gain_ + wet[i] * wet_gain_;
  }
  runtime::apply_gain(output, samples, output_gain_);
}

}  // namespace echidna::dsp::effects
