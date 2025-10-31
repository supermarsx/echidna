#include "reverb.h"

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects {
namespace {
constexpr std::array<float, 4> kCombTimes{0.0297f, 0.0371f, 0.0411f, 0.0437f};
constexpr std::array<float, 3> kAllPassTimes{0.005f, 0.0017f, 0.0006f};
}

void Reverb::set_parameters(const ReverbParameters &params) { params_ = params; }

void Reverb::prepare(uint32_t sample_rate, uint32_t channels) {
  EffectProcessor::prepare(sample_rate, channels);
  ensure_buffers();
}

void Reverb::reset() {
  for (auto &comb : combs_) {
    std::fill(comb.buffer.begin(), comb.buffer.end(), 0.0f);
    comb.index = 0;
  }
  for (auto &ap : allpasses_) {
    std::fill(ap.buffer.begin(), ap.buffer.end(), 0.0f);
    ap.index = 0;
  }
  std::fill(predelay_buffer_.begin(), predelay_buffer_.end(), 0.0f);
  predelay_index_ = 0;
}

void Reverb::ensure_buffers() {
  const float room = std::clamp(params_.room_size, 0.0f, 100.0f) / 100.0f;
  const float damp = std::clamp(params_.damping, 0.0f, 100.0f) / 100.0f;
  const float base_feedback = 0.6f + room * 0.35f;
  const float damping = 0.2f + damp * 0.6f;

  combs_.resize(channels_ * kCombTimes.size());
  allpasses_.resize(channels_ * kAllPassTimes.size());

  for (uint32_t ch = 0; ch < channels_; ++ch) {
    for (size_t i = 0; i < kCombTimes.size(); ++i) {
      Comb &comb = combs_[ch * kCombTimes.size() + i];
      const size_t length =
          std::max<size_t>(1, static_cast<size_t>(kCombTimes[i] * sample_rate_));
      comb.buffer.assign(length, 0.0f);
      comb.index = 0;
      comb.feedback = base_feedback - damping * 0.1f * static_cast<float>(i);
    }
    for (size_t i = 0; i < kAllPassTimes.size(); ++i) {
      AllPass &ap = allpasses_[ch * kAllPassTimes.size() + i];
      const size_t length =
          std::max<size_t>(1, static_cast<size_t>(kAllPassTimes[i] * sample_rate_));
      ap.buffer.assign(length, 0.0f);
      ap.index = 0;
      ap.feedback = 0.5f - 0.1f * static_cast<float>(i);
    }
  }

  const size_t predelay_samples =
      std::max<size_t>(1, static_cast<size_t>(params_.pre_delay_ms * sample_rate_ / 1000.0f));
  predelay_buffer_.assign(predelay_samples * channels_, 0.0f);
  predelay_index_ = 0;
}

void Reverb::process(ProcessContext &ctx) {
  if (!enabled_) {
    return;
  }
  const float wet = std::clamp(params_.mix, 0.0f, 50.0f) / 100.0f;
  const size_t comb_per_channel = kCombTimes.size();
  const size_t ap_per_channel = kAllPassTimes.size();

  for (size_t frame = 0; frame < ctx.frames; ++frame) {
    const size_t predelay_base = predelay_index_ * channels_;
    for (uint32_t ch = 0; ch < channels_; ++ch) {
      const size_t idx = frame * channels_ + ch;
      const float input = ctx.buffer[idx];
      float delayed = predelay_buffer_[predelay_base + ch];
      predelay_buffer_[predelay_base + ch] = input;

      float acc = 0.0f;
      for (size_t i = 0; i < comb_per_channel; ++i) {
        Comb &comb = combs_[ch * comb_per_channel + i];
        float y = comb.buffer[comb.index];
        comb.buffer[comb.index] = delayed + y * comb.feedback;
        comb.index = (comb.index + 1) % comb.buffer.size();
        acc += y;
      }
      float out = acc / static_cast<float>(comb_per_channel);
      for (size_t i = 0; i < ap_per_channel; ++i) {
        AllPass &ap = allpasses_[ch * ap_per_channel + i];
        float buf = ap.buffer[ap.index];
        float y = -out + buf;
        ap.buffer[ap.index] = out + buf * ap.feedback;
        ap.index = (ap.index + 1) % ap.buffer.size();
        out = y;
      }
      ctx.buffer[idx] = input * (1.0f - wet) + out * wet;
    }
    predelay_index_ = (predelay_index_ + 1) % (predelay_buffer_.size() / channels_);
  }
}

}  // namespace echidna::dsp::effects
