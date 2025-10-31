#include "compressor.h"

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects {
namespace {
constexpr float kEpsilon = 1e-8f;

float linear_to_db(float value) {
  return 20.0f * std::log10(std::max(value, kEpsilon));
}

float db_to_linear(float db) { return std::pow(10.0f, db / 20.0f); }

float ms_to_coeff(float ms, uint32_t sr) {
  const float samples = (ms / 1000.0f) * static_cast<float>(sr);
  if (samples <= 1.0f) {
    return 0.0f;
  }
  return std::exp(-1.0f / samples);
}
}  // namespace

void Compressor::set_parameters(const CompressorParameters &params) {
  params_ = params;
}

void Compressor::prepare(uint32_t sample_rate, uint32_t channels) {
  EffectProcessor::prepare(sample_rate, channels);
  attack_coeff_ = ms_to_coeff(params_.attack_ms, sample_rate);
  release_coeff_ = ms_to_coeff(params_.release_ms, sample_rate);
  if (params_.mode == CompressorMode::kAuto) {
    // Estimate makeup gain so average level near threshold remains stable.
    const float auto_makeup = -params_.threshold_db / 4.0f;
    makeup_gain_ = db_to_linear(auto_makeup);
  } else {
    makeup_gain_ = db_to_linear(params_.makeup_gain_db);
  }
  envelope_ = 1.0f;
}

void Compressor::reset() { envelope_ = 1.0f; }

float Compressor::compute_gain_reduction(float input_db) {
  const float threshold = std::clamp(params_.threshold_db, -60.0f, -5.0f);
  const float ratio = std::clamp(params_.ratio, 1.2f, 6.0f);
  const float knee_width = std::clamp(params_.knee_db, 0.0f, 12.0f);
  const float delta = input_db - threshold;
  if (params_.knee == KneeType::kHard || knee_width <= 0.0f) {
    if (delta <= 0.0f) {
      return 0.0f;
    }
    return threshold + delta / ratio - input_db;
  }
  const float half_knee = knee_width * 0.5f;
  if (delta <= -half_knee) {
    return 0.0f;
  }
  if (delta >= half_knee) {
    return threshold + delta / ratio - input_db;
  }
  const float proportion = (delta + half_knee) / knee_width;
  const float soft_db = proportion * proportion * half_knee;
  const float compressed = input_db - soft_db + soft_db / ratio;
  return compressed - input_db;
}

void Compressor::process(ProcessContext &ctx) {
  if (!enabled_) {
    return;
  }

  float *buffer = ctx.buffer;
  const size_t total = ctx.frames * ctx.channels;
  for (size_t i = 0; i < total; ++i) {
    const float sample = buffer[i];
    const float level_db = linear_to_db(std::abs(sample));
    float gain_db = compute_gain_reduction(level_db);
    float target = db_to_linear(gain_db) * makeup_gain_;
    if (target < envelope_) {
      envelope_ += (target - envelope_) * (1.0f - attack_coeff_);
    } else {
      envelope_ += (target - envelope_) * (1.0f - release_coeff_);
    }
    buffer[i] = sample * envelope_;
  }
}

}  // namespace echidna::dsp::effects
