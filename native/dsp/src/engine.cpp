#include "engine.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

#include "runtime/simd.h"

namespace echidna::dsp {

DspEngine::DspEngine(uint32_t sample_rate,
                     uint32_t channels,
                     ech_dsp_quality_mode_t quality)
    : sample_rate_(sample_rate),
      channels_(channels),
      quality_mode_(quality),
      input_queue_(8),
      output_queue_(8) {}

DspEngine::~DspEngine() { StopWorker(); }

ech_dsp_status_t DspEngine::UpdatePreset(const config::PresetDefinition &preset) {
  std::lock_guard<std::mutex> lock(preset_mutex_);
  preset_ = preset;

  if (preset.processing_mode == config::ProcessingMode::kHybrid &&
      quality_mode_ != ECH_DSP_QUALITY_LOW_LATENCY) {
    processing_mode_ = config::ProcessingMode::kHybrid;
  } else {
    processing_mode_ = config::ProcessingMode::kSynchronous;
  }

  StopWorker();

  gate_.set_enabled(preset_.gate.enabled);
  gate_.set_parameters(preset_.gate.params);

  eq_.set_enabled(preset_.eq.enabled);
  eq_.set_bands(preset_.eq.bands);

  compressor_.set_enabled(preset_.compressor.enabled);
  compressor_.set_parameters(preset_.compressor.params);

  pitch_.set_enabled(preset_.pitch.enabled);
  auto pitch_params = preset_.pitch.params;
  bool allow_high_quality =
      quality_mode_ == ECH_DSP_QUALITY_HIGH ||
      (quality_mode_ == ECH_DSP_QUALITY_BALANCED &&
       preset_.quality != config::QualityPreference::kLowLatency);
  if (!allow_high_quality) {
    pitch_params.quality = effects::PitchQuality::kLowLatency;
  }
  pitch_.set_parameters(pitch_params);

  formant_.set_enabled(preset_.formant.enabled);
  formant_.set_parameters(preset_.formant.params);

  autotune_.set_enabled(preset_.autotune.enabled);
  autotune_.set_parameters(preset_.autotune.params);

  reverb_.set_enabled(preset_.reverb.enabled);
  reverb_.set_parameters(preset_.reverb.params);

  mix_.set_parameters(preset_.mix.params);

  ApplyPresetLocked();

  if (processing_mode_ == config::ProcessingMode::kHybrid) {
    StartWorker();
  }

  return ECH_DSP_STATUS_OK;
}

ech_dsp_status_t DspEngine::ProcessBlock(const float *input,
                                         float *output,
                                         size_t frames) {
  if (frames == 0 || channels_ == 0) {
    return ECH_DSP_STATUS_INVALID_ARGUMENT;
  }

  config::ProcessingMode mode;
  {
    std::lock_guard<std::mutex> lock(preset_mutex_);
    mode = processing_mode_;
  }

  if (mode == config::ProcessingMode::kSynchronous) {
    return ProcessInternal(input, output, frames);
  }

  auto block = std::make_shared<runtime::AudioBlock>(sample_rate_, channels_, frames);
  std::memcpy(block->data.data(), input, sizeof(float) * frames * channels_);
  if (!input_queue_.push(block)) {
    return ProcessInternal(input, output, frames);
  }
  auto processed =
      output_queue_.pop_wait(std::chrono::milliseconds(preset_.block_ms));
  if (!processed) {
    return ProcessInternal(input, output, frames);
  }
  if (processed->data.size() < frames * channels_) {
    return ECH_DSP_STATUS_ERROR;
  }
  std::memcpy(output, processed->data.data(), sizeof(float) * frames * channels_);
  return ECH_DSP_STATUS_OK;
}

ech_dsp_status_t DspEngine::ProcessInternal(const float *input,
                                            float *output,
                                            size_t frames) {
  std::lock_guard<std::mutex> lock(process_mutex_);
  EnsureBuffers(frames);
  const size_t samples = frames * channels_;
  std::memcpy(dry_buffer_.data(), input, sizeof(float) * samples);
  std::memcpy(wet_buffer_.data(), input, sizeof(float) * samples);

  effects::ProcessContext ctx{wet_buffer_.data(), frames, channels_, sample_rate_};
  gate_.process(ctx);
  eq_.process(ctx);
  compressor_.process(ctx);
  pitch_.process(ctx);
  formant_.process(ctx);
  autotune_.process(ctx);
  reverb_.process(ctx);

  mix_.process_buffers(dry_buffer_.data(), wet_buffer_.data(), output, frames);
  return ECH_DSP_STATUS_OK;
}

void DspEngine::EnsureBuffers(size_t frames) {
  const size_t samples = frames * channels_;
  if (dry_buffer_.size() < samples) {
    dry_buffer_.resize(samples);
  }
  if (wet_buffer_.size() < samples) {
    wet_buffer_.resize(samples);
  }
}

void DspEngine::ApplyPresetLocked() {
  gate_.prepare(sample_rate_, channels_);
  gate_.reset();

  eq_.prepare(sample_rate_, channels_);
  eq_.reset();

  compressor_.prepare(sample_rate_, channels_);
  compressor_.reset();

  pitch_.prepare(sample_rate_, channels_);
  pitch_.reset();

  formant_.prepare(sample_rate_, channels_);
  formant_.reset();

  autotune_.prepare(sample_rate_, channels_);
  autotune_.reset();

  reverb_.prepare(sample_rate_, channels_);
  reverb_.reset();

  mix_.prepare(sample_rate_, channels_);
}

void DspEngine::StartWorker() {
  if (worker_running_) {
    return;
  }
  worker_running_ = true;
  worker_thread_ = std::thread([this]() { WorkerLoop(); });
}

void DspEngine::StopWorker() {
  if (!worker_running_) {
    return;
  }
  worker_running_ = false;
  if (worker_thread_.joinable()) {
    worker_thread_.join();
  }
  while (output_queue_.pop()) {
  }
  while (input_queue_.pop()) {
  }
}

void DspEngine::WorkerLoop() {
  while (worker_running_) {
    auto block = input_queue_.pop_wait(std::chrono::milliseconds(5));
    if (!block) {
      continue;
    }
    std::vector<float> output(block->frames * block->channels);
    if (ProcessInternal(block->data.data(), output.data(), block->frames) !=
        ECH_DSP_STATUS_OK) {
      continue;
    }
    block->data = std::move(output);
    while (!output_queue_.push(block) && worker_running_) {
      std::this_thread::yield();
    }
  }
}

}  // namespace echidna::dsp
