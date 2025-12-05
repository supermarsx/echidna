#pragma once

/**
 * @file preset_loader.h
 * @brief Utilities to parse and validate JSON presets into typed
 * PresetDefinition structs used by the DSP engine.
 */

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

namespace echidna::dsp::config
{

  /** Processing mode selection for processing pipeline. */
  enum class ProcessingMode
  {
    kSynchronous,
    kHybrid
  };

  /** Quality preference representing desired processing quality vs latency. */
  enum class QualityPreference
  {
    kLowLatency,
    kBalanced,
    kHighQuality
  };

  struct GateConfig
  {
    bool enabled{false};
    echidna::dsp::effects::GateParameters params;
  };

  struct EqConfig
  {
    bool enabled{false};
    std::vector<echidna::dsp::effects::EqBand> bands;
  };

  struct CompressorConfig
  {
    bool enabled{false};
    echidna::dsp::effects::CompressorParameters params;
  };

  struct PitchConfig
  {
    bool enabled{false};
    echidna::dsp::effects::PitchParameters params;
  };

  struct FormantConfig
  {
    bool enabled{false};
    echidna::dsp::effects::FormantParameters params;
  };

  struct AutoTuneConfig
  {
    bool enabled{false};
    echidna::dsp::effects::AutoTuneParameters params;
  };

  struct ReverbConfig
  {
    bool enabled{false};
    echidna::dsp::effects::ReverbParameters params;
  };

  struct MixConfig
  {
    echidna::dsp::effects::MixParameters params;
  };

  struct PresetDefinition
  {
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

  /**
   * @brief Result from attempting to load/parse a preset.
   *
   * `ok` will be true when parsing and validation succeeded and `preset`
   * will contain the parsed configuration. On failure `error` contains a
   * diagnostic string.
   */
  struct PresetLoadResult
  {
    bool ok{false};
    std::string error;
    PresetDefinition preset;
  };

  /**
   * @brief Parse a JSON string and produce a PresetDefinition.
   * @param json JSON text to parse.
   * @return PresetLoadResult describing success or parse / validation errors.
   */
  PresetLoadResult LoadPresetFromJson(std::string_view json);

} // namespace echidna::dsp::config
