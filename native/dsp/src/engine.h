#pragma once

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
#include "runtime/block_queue.h"

namespace echidna::dsp {

class DspEngine {
 public:
  DspEngine(uint32_t sample_rate,
            uint32_t channels,
            ech_dsp_quality_mode_t quality);
  ~DspEngine();

  ech_dsp_status_t UpdatePreset(const config::PresetDefinition &preset);
  ech_dsp_status_t ProcessBlock(const float *input,
                                float *output,
                                size_t frames);

 private:
  ech_dsp_status_t ProcessInternal(const float *input,
                                   float *output,
                                   size_t frames);
  void EnsureBuffers(size_t frames);
  void ApplyPresetLocked();
  void StartWorker();
  void StopWorker();
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
