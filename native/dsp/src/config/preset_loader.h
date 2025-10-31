#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "../effects/auto_tune.h"
#include "../effects/compressor.h"
#include "../effects/formant_shifter.h"
#include "../effects/gate_processor.h"
#include "../effects/mix_bus.h"
#include "../effects/parametric_eq.h"
#include "../effects/pitch_shifter.h"
#include "../effects/reverb.h"

namespace echidna::dsp::config {

enum class ProcessingMode { kSynchronous, kHybrid };

enum class QualityPreference { kLowLatency, kBalanced, kHighQuality };

struct GateConfig {
  bool enabled{false};
  echidna::dsp::effects::GateParameters params;
};

struct EqConfig {
  bool enabled{false};
  std::vector<echidna::dsp::effects::EqBand> bands;
};

struct CompressorConfig {
  bool enabled{false};
  echidna::dsp::effects::CompressorParameters params;
};

struct PitchConfig {
  bool enabled{false};
  echidna::dsp::effects::PitchParameters params;
};

struct FormantConfig {
  bool enabled{false};
  echidna::dsp::effects::FormantParameters params;
};

struct AutoTuneConfig {
  bool enabled{false};
  echidna::dsp::effects::AutoTuneParameters params;
};

struct ReverbConfig {
  bool enabled{false};
  echidna::dsp::effects::ReverbParameters params;
};

struct MixConfig {
  echidna::dsp::effects::MixParameters params;
};

struct PresetDefinition {
  std::string name;
  ProcessingMode processing_mode{ProcessingMode::kSynchronous};
  QualityPreference quality{QualityPreference::kLowLatency};
  uint32_t block_ms{15};
  GateConfig gate;
  EqConfig eq;
  CompressorConfig compressor;
  PitchConfig pitch;
  FormantConfig formant;
  AutoTuneConfig autotune;
  ReverbConfig reverb;
  MixConfig mix;
};

struct PresetLoadResult {
  bool ok{false};
  std::string error;
  PresetDefinition preset;
};

PresetLoadResult LoadPresetFromJson(std::string_view json);

}  // namespace echidna::dsp::config
