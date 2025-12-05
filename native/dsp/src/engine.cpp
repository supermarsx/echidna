#include "engine.h"

/**
 * @file engine.cpp
 * @brief Implementation of the DspEngine that composes and runs the effects
 * chain. Function-level documentation mirrors the public API.
 */

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <thread>

#include "runtime/simd.h"

namespace echidna::dsp {

/**
 * @brief Construct a DspEngine.
 *
 * Initializes the engine with the provided sample rate, channel count and
 * quality mode. The constructor also attempts to load DSP plugin libraries
 * from the ECHIDNA_PLUGIN_DIR environment variable or a default path.
 */
DspEngine::DspEngine(uint32_t sample_rate,
                     uint32_t channels,
                     ech_dsp_quality_mode_t quality)
    : sample_rate_(sample_rate),
      channels_(channels),
      quality_mode_(quality),
      input_queue_(8),
      output_queue_(8) {
  const char *plugin_dir = std::getenv("ECHIDNA_PLUGIN_DIR");
  if (!plugin_dir || plugin_dir[0] == '\0') {
    plugin_dir = "/data/local/tmp/echidna/plugins";
  }
  plugin_loader_.LoadFromDirectory(plugin_dir);
}

/**
 * @brief Destructor - ensures worker thread is stopped cleanly.
 */
DspEngine::~DspEngine() { StopWorker(); }

/**
 * @brief Apply a new preset definition and configure internal effects.
 *
 * This method updates internal effect parameters, stops/restarts the
 * hybrid worker if required and returns a status code.
 */
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

/**
 * @brief Public block processing API.
 *
 * This method is the main entry point for block processing. Behavior differs
 * based on the configured processing mode. In synchronous mode this will call
 * ProcessInternal directly. In hybrid mode the call will attempt to schedule
 * work for the background worker and return a previously processed block if
 * available. On invalid parameters an error status is returned.
 */
ech_dsp_status_t DspEngine::ProcessBlock(const float *input,
                                         float *output,
                                         size_t frames) {
  if (frames == 0 || channels_ == 0) {
    return ECH_DSP_STATUS_INVALID_ARGUMENT;
  }

  config::ProcessingMode mode;
  uint32_t block_timeout_ms;
  {
    std::lock_guard<std::mutex> lock(preset_mutex_);
    mode = processing_mode_;
    block_timeout_ms = preset_.block_ms;
  }

  if (mode == config::ProcessingMode::kSynchronous) {
    return ProcessInternal(input, output, frames);
  }

  auto block = std::make_shared<runtime::AudioBlock>(sample_rate_, channels_, frames);
  block->cancelled.store(false, std::memory_order_relaxed);
  std::memcpy(block->data.data(), input, sizeof(float) * frames * channels_);
  if (!input_queue_.push(block)) {
    return ProcessInternal(input, output, frames);
  }
  auto processed =
      output_queue_.pop_wait(std::chrono::milliseconds(block_timeout_ms));
  if (!processed) {
    block->cancelled.store(true, std::memory_order_release);
    // Ensure no stale hybrid output is returned on the next call.
    while (output_queue_.pop()) {
    }
    return ProcessInternal(input, output, frames);
  }
  if (processed->data.size() < frames * channels_) {
    return ECH_DSP_STATUS_ERROR;
  }
  std::memcpy(output, processed->data.data(), sizeof(float) * frames * channels_);
  return ECH_DSP_STATUS_OK;
}

/**
 * @brief The synchronous processing implementation used internally.
 *
 * This copies input into internal buffers, runs every effect in order and
 * invokes plugins, then mixes dry/wet buffers into the supplied output.
 */
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

  plugin_loader_.ProcessAll(ctx);

  mix_.process_buffers(dry_buffer_.data(), wet_buffer_.data(), output, frames);
  return ECH_DSP_STATUS_OK;
}

/**
 * @brief Ensure internal buffers have capacity for 'frames' frames.
 */
void DspEngine::EnsureBuffers(size_t frames) {
  const size_t samples = frames * channels_;
  if (dry_buffer_.size() < samples) {
    dry_buffer_.resize(samples);
  }
  if (wet_buffer_.size() < samples) {
    wet_buffer_.resize(samples);
  }
}

/**
 * @brief Apply preset configuration to all owned effects and plugins.
 */
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

  plugin_loader_.PrepareAll(sample_rate_, channels_);
  plugin_loader_.ResetAll();
}

/**
 * @brief Start the hybrid processing worker thread if not already running.
 */
void DspEngine::StartWorker() {
  if (worker_running_) {
    return;
  }
  worker_running_ = true;
  worker_thread_ = std::thread([this]() { WorkerLoop(); });
}

/**
 * @brief Stop the hybrid worker thread and drain queues.
 */
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

/**
 * @brief Worker thread loop which pulls blocks from the input queue,
 * processes them, and pushes them to the output queue.
 */
void DspEngine::WorkerLoop() {
  while (worker_running_) {
    auto block = input_queue_.pop_wait(std::chrono::milliseconds(5));
    if (!block) {
      continue;
    }
    if (block->cancelled.load(std::memory_order_acquire)) {
      continue;
    }
    std::vector<float> output(block->frames * block->channels);
    if (ProcessInternal(block->data.data(), output.data(), block->frames) !=
        ECH_DSP_STATUS_OK) {
      continue;
    }
    if (block->cancelled.load(std::memory_order_acquire)) {
      continue;
    }
    block->data = std::move(output);
    while (worker_running_) {
      if (block->cancelled.load(std::memory_order_acquire)) {
        break;
      }
      if (output_queue_.push(block)) {
        break;
      }
      std::this_thread::yield();
    }
  }
}

}  // namespace echidna::dsp
