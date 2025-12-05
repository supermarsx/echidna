#pragma once

/**
 * @file engine.h
 * @brief High-level DSP engine API and processing pipeline for Echidna.
 *
 * This header declares the DspEngine class which composes the set of DSP
 * effects and plugins and provides synchronous or hybrid processing modes.
 * Each public method is documented and callers should check returned
 * ech_dsp_status_t values for success/failure.
 */

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "echidna/dsp/api.h"

#include "config/preset_loader.h"
#include "effects/auto_tune.h"
#include "effects/compressor.h"
#include "effects/formant_shifter.h"
#include "effects/gate_processor.h"
#include "effects/mix_bus.h"
#include "effects/parametric_eq.h"
#include "effects/pitch_shifter.h"
#include "effects/reverb.h"
#include "plugins/plugin_loader.h"
#include "runtime/block_queue.h"

namespace echidna::dsp {

/**
 * @brief Main DSP engine which executes the configured effects chain.
 *
 * DspEngine owns effect instances (gate, EQ, compressor, pitch shifters,
 * reverb, etc.), a plugin loader and manages either synchronous processing
 * (direct per-call processing) or a hybrid mode where a worker thread
 * processes audio blocks asynchronously.
 */
class DspEngine {
 public:
  /**
   * @brief Construct a DspEngine.
   *
   * @param sample_rate Sample rate in Hz used to configure effects.
   * @param channels Number of audio channels (e.g., 1=mono, 2=stereo).
   * @param quality DSP quality mode / performance tradeoff selection.
   */
  DspEngine(uint32_t sample_rate,
            uint32_t channels,
            ech_dsp_quality_mode_t quality);
  ~DspEngine();

  /**
   * @brief Apply or update the currently active preset.
   *
   * This takes ownership (copies) of the provided preset definition and
   * updates internal effect parameters. This call will stop any running
   * hybrid worker before applying preset changes and restart it if
   * appropriate for the preset's processing mode.
   *
   * @param preset Preset definition to apply.
   * @return ECH_DSP_STATUS_OK on success, error code on failure.
   */
  ech_dsp_status_t UpdatePreset(const config::PresetDefinition &preset);
  /**
   * @brief Process a single audio block.
   *
   * If the engine is configured for synchronous processing the block
   * will be processed immediately and the output buffer will be populated.
   * For hybrid mode the block may be scheduled to an internal worker and
   * the function will attempt to return latest available hybrid output.
   *
   * @param input Pointer to input samples (frames * channels floats).
   * @param output Pointer where processed samples will be written.
   * @param frames Number of frames in this block.
   * @return ECH_DSP_STATUS_OK on success or an appropriate error.
   */
  ech_dsp_status_t ProcessBlock(const float *input,
                                float *output,
                                size_t frames);

 private:
  /**
   * @brief Internal synchronous processing implementation used by both
   * synchronous and hybrid codepaths.
   *
   * This function assumes locks are taken by the caller and performs the
   * actual per-effect processing of the provided input into the output
   * buffer.
   */
  ech_dsp_status_t ProcessInternal(const float *input,
                                   float *output,
                                   size_t frames);
  /**
   * @brief Ensure the internal dry/wet buffers are sized for the provided
   * number of frames.
   */
  void EnsureBuffers(size_t frames);
  /**
   * @brief Apply the current preset configuration to all owned effects.
   *
   * This method expects the caller to hold the `preset_mutex_` so updates
   * are atomic relative to other callers.
   */
  void ApplyPresetLocked();
  /**
   * @brief Start the hybrid worker thread (if not already running).
   */
  void StartWorker();
  /**
   * @brief Stop the hybrid worker thread and drain internal queues.
   */
  void StopWorker();
  /**
   * @brief Worker thread main loop which consumes input blocks and produces
   * processed outputs to the output queue.
   */
  void WorkerLoop();

  uint32_t sample_rate_{0};
  uint32_t channels_{0};
  ech_dsp_quality_mode_t quality_mode_{ECH_DSP_QUALITY_LOW_LATENCY};

  config::PresetDefinition preset_;

  effects::GateProcessor gate_;
  effects::ParametricEQ eq_;
  effects::Compressor compressor_;
  effects::PitchShifter pitch_;
  effects::FormantShifter formant_;
  effects::AutoTune autotune_;
  effects::Reverb reverb_;
  effects::MixBus mix_;
  plugins::PluginLoader plugin_loader_;

  std::vector<float> dry_buffer_;
  std::vector<float> wet_buffer_;

  config::ProcessingMode processing_mode_{config::ProcessingMode::kSynchronous};
  std::mutex preset_mutex_;
  std::mutex process_mutex_;

  runtime::BlockQueue input_queue_{8};
  runtime::BlockQueue output_queue_{8};
  std::atomic<bool> worker_running_{false};
  std::thread worker_thread_;
};

}  // namespace echidna::dsp
