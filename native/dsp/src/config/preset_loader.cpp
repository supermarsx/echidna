#include "preset_loader.h"

/**
 * @file preset_loader.cpp
 * @brief Implementation of the small JSON parser and validation logic that
 * turns preset JSON strings into PresetDefinition objects.
 */

#include <charconv>
#include <cctype>
#include <map>
#include <stdexcept>

namespace echidna::dsp::config {
namespace {

namespace effects = echidna::dsp::effects;

enum class JsonType { kNull, kBool, kNumber, kString, kObject, kArray };

struct JsonValue {
  JsonType type{JsonType::kNull};
  bool bool_value{false};
  double number_value{0.0};
  std::string string_value;
  std::map<std::string, JsonValue> object_value;
  std::vector<JsonValue> array_value;
};

class JsonParser {
 public:
  explicit JsonParser(std::string_view input) : input_(input) {}

  JsonValue parse() {
    skip_ws();
    JsonValue value = parse_value();
    skip_ws();
    if (!eof()) {
      throw std::runtime_error("Unexpected trailing characters in JSON");
    }
    return value;
  }

 private:
  bool eof() const { return pos_ >= input_.size(); }

  char peek() const { return eof() ? '\0' : input_[pos_]; }

  char get() { return eof() ? '\0' : input_[pos_++]; }

  void skip_ws() {
    while (!eof()) {
      char c = peek();
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        ++pos_;
      } else {
        break;
      }
    }
  }

  JsonValue parse_value() {
    char c = peek();
    if (c == '"') {
      return parse_string();
    }
    if (c == '{') {
      return parse_object();
    }
    if (c == '[') {
      return parse_array();
    }
    if (c == 't' || c == 'f') {
      return parse_bool();
    }
    if (c == 'n') {
      return parse_null();
    }
    return parse_number();
  }

  JsonValue parse_string() {
    JsonValue value;
    value.type = JsonType::kString;
    if (get() != '"') {
      throw std::runtime_error("Expected string");
    }
    while (!eof()) {
      char c = get();
      if (c == '"') {
        break;
      }
      if (c == '\\') {
        char esc = get();
        switch (esc) {
          case '"':
          case '\\':
          case '/':
            value.string_value.push_back(esc);
            break;
          case 'b':
            value.string_value.push_back('\b');
            break;
          case 'f':
            value.string_value.push_back('\f');
            break;
          case 'n':
            value.string_value.push_back('\n');
            break;
          case 'r':
            value.string_value.push_back('\r');
            break;
          case 't':
            value.string_value.push_back('\t');
            break;
          default:
            throw std::runtime_error("Unsupported escape sequence");
        }
      } else {
        value.string_value.push_back(c);
      }
    }
    return value;
  }

  JsonValue parse_number() {
    JsonValue value;
    value.type = JsonType::kNumber;
    size_t start = pos_;
    if (peek() == '-') {
      get();
    }
    while (std::isdigit(peek())) {
      get();
    }
    if (peek() == '.') {
      get();
      while (std::isdigit(peek())) {
        get();
      }
    }
    if (peek() == 'e' || peek() == 'E') {
      get();
      if (peek() == '+' || peek() == '-') {
        get();
      }
      while (std::isdigit(peek())) {
        get();
      }
    }
    auto token = input_.substr(start, pos_ - start);
    auto result = std::from_chars(token.data(), token.data() + token.size(), value.number_value);
    if (result.ec != std::errc()) {
      throw std::runtime_error("Invalid numeric value");
    }
    return value;
  }

  JsonValue parse_bool() {
    JsonValue value;
    value.type = JsonType::kBool;
    if (input_.substr(pos_, 4) == "true") {
      pos_ += 4;
      value.bool_value = true;
    } else if (input_.substr(pos_, 5) == "false") {
      pos_ += 5;
      value.bool_value = false;
    } else {
      throw std::runtime_error("Invalid boolean token");
    }
    return value;
  }

  JsonValue parse_null() {
    if (input_.substr(pos_, 4) != "null") {
      throw std::runtime_error("Invalid null token");
    }
    pos_ += 4;
    return JsonValue{};
  }

  JsonValue parse_array() {
    JsonValue value;
    value.type = JsonType::kArray;
    if (get() != '[') {
      throw std::runtime_error("Expected array");
    }
    skip_ws();
    if (peek() == ']') {
      get();
      return value;
    }
    while (true) {
      value.array_value.push_back(parse_value());
      skip_ws();
      char c = get();
      if (c == ']') {
        break;
      }
      if (c != ',') {
        throw std::runtime_error("Expected comma in array");
      }
      skip_ws();
    }
    return value;
  }

  JsonValue parse_object() {
    JsonValue value;
    value.type = JsonType::kObject;
    if (get() != '{') {
      throw std::runtime_error("Expected object");
    }
    skip_ws();
    if (peek() == '}') {
      get();
      return value;
    }
    while (true) {
      skip_ws();
      JsonValue key = parse_string();
      skip_ws();
      if (get() != ':') {
        throw std::runtime_error("Expected colon in object");
      }
      skip_ws();
      JsonValue val = parse_value();
      value.object_value.emplace(std::move(key.string_value), std::move(val));
      skip_ws();
      char c = get();
      if (c == '}') {
        break;
      }
      if (c != ',') {
        throw std::runtime_error("Expected comma in object");
      }
      skip_ws();
    }
    return value;
  }

  std::string_view input_;
  size_t pos_{0};
};

const JsonValue *FindMember(const JsonValue &value, const std::string &key) {
  if (value.type != JsonType::kObject) {
    return nullptr;
  }
  auto it = value.object_value.find(key);
  if (it == value.object_value.end()) {
    return nullptr;
  }
  return &it->second;
}

std::optional<double> GetNumber(const JsonValue &value, const std::string &key) {
  if (const JsonValue *member = FindMember(value, key)) {
    if (member->type == JsonType::kNumber) {
      return member->number_value;
    }
  }
  return std::nullopt;
}

std::optional<bool> GetBool(const JsonValue &value, const std::string &key) {
  if (const JsonValue *member = FindMember(value, key)) {
    if (member->type == JsonType::kBool) {
      return member->bool_value;
    }
  }
  return std::nullopt;
}

std::optional<std::string> GetString(const JsonValue &value, const std::string &key) {
  if (const JsonValue *member = FindMember(value, key)) {
    if (member->type == JsonType::kString) {
      return member->string_value;
    }
  }
  return std::nullopt;
}

bool EnsureRange(const std::string &field,
                 double value,
                 double min,
                 double max,
                 PresetLoadResult *result) {
  if (value < min || value > max) {
    result->ok = false;
    result->error = field + " outside safe range";
    return false;
  }
  return true;
}

std::optional<effects::PitchQuality> ParsePitchQuality(const std::string &value) {
  if (value == "LL") {
    return effects::PitchQuality::kLowLatency;
  }
  if (value == "HQ") {
    return effects::PitchQuality::kHighQuality;
  }
  return std::nullopt;
}

effects::AutoTuneParameters ParseAutoTuneParams(const JsonValue &module,
                                                PresetLoadResult *result) {
  effects::AutoTuneParameters params;
  if (auto key = GetString(module, "key")) {
    static const std::map<std::string, effects::MusicalKey> kKeyMap{{"C", effects::MusicalKey::kC},
                                                                   {"C#", effects::MusicalKey::kCSharp},
                                                                   {"Db", effects::MusicalKey::kCSharp},
                                                                   {"D", effects::MusicalKey::kD},
                                                                   {"D#", effects::MusicalKey::kDSharp},
                                                                   {"Eb", effects::MusicalKey::kDSharp},
                                                                   {"E", effects::MusicalKey::kE},
                                                                   {"F", effects::MusicalKey::kF},
                                                                   {"F#", effects::MusicalKey::kFSharp},
                                                                   {"Gb", effects::MusicalKey::kFSharp},
                                                                   {"G", effects::MusicalKey::kG},
                                                                   {"G#", effects::MusicalKey::kGSharp},
                                                                   {"Ab", effects::MusicalKey::kGSharp},
                                                                   {"A", effects::MusicalKey::kA},
                                                                   {"A#", effects::MusicalKey::kASharp},
                                                                   {"Bb", effects::MusicalKey::kASharp},
                                                                   {"B", effects::MusicalKey::kB}};
    auto it = kKeyMap.find(*key);
    if (it != kKeyMap.end()) {
      params.key = it->second;
    }
  }
  if (auto scale = GetString(module, "scale")) {
    static const std::map<std::string, effects::ScaleType> kScaleMap{{"Major", effects::ScaleType::kMajor},
                                                                    {"Minor", effects::ScaleType::kMinor},
                                                                    {"Chromatic", effects::ScaleType::kChromatic},
                                                                    {"Dorian", effects::ScaleType::kDorian},
                                                                    {"Phrygian", effects::ScaleType::kPhrygian},
                                                                    {"Lydian", effects::ScaleType::kLydian},
                                                                    {"Mixolydian", effects::ScaleType::kMixolydian},
                                                                    {"Aeolian", effects::ScaleType::kAeolian},
                                                                    {"Locrian", effects::ScaleType::kLocrian}};
    auto it = kScaleMap.find(*scale);
    if (it != kScaleMap.end()) {
      params.scale = it->second;
    }
  }
  if (auto retune = GetNumber(module, "retuneMs")) {
    if (EnsureRange("AutoTune.retuneMs", *retune, 1.0, 200.0, result)) {
      params.retune_speed_ms = static_cast<float>(*retune);
    }
  }
  if (auto humanize = GetNumber(module, "humanize")) {
    if (EnsureRange("AutoTune.humanize", *humanize, 0.0, 100.0, result)) {
      params.humanize = static_cast<float>(*humanize);
    }
  }
  if (auto flex = GetNumber(module, "flexTune")) {
    if (EnsureRange("AutoTune.flexTune", *flex, 0.0, 100.0, result)) {
      params.flex_tune = static_cast<float>(*flex);
    }
  }
  if (auto snap = GetNumber(module, "snapStrength")) {
    if (EnsureRange("AutoTune.snapStrength", *snap, 0.0, 100.0, result)) {
      params.snap_strength = static_cast<float>(*snap);
    }
  }
  if (auto preserve = GetBool(module, "formantPreserve")) {
    params.formant_preserve = *preserve;
  }
  return params;
}

}  // namespace

/**
 * @brief Parse user supplied JSON and return a validated PresetDefinition.
 */
PresetLoadResult LoadPresetFromJson(std::string_view json) {
  PresetLoadResult result;
  try {
    JsonParser parser(json);
    JsonValue root = parser.parse();
    if (root.type != JsonType::kObject) {
      result.ok = false;
      result.error = "Preset root must be an object";
      return result;
    }

    if (auto name = GetString(root, "name")) {
      result.preset.name = *name;
    }

    if (const JsonValue *engine = FindMember(root, "engine")) {
      if (auto latency = GetString(*engine, "latencyMode")) {
        if (*latency == "LL") {
          result.preset.processing_mode = ProcessingMode::kSynchronous;
          result.preset.quality = QualityPreference::kLowLatency;
        } else if (*latency == "Balanced") {
          result.preset.processing_mode = ProcessingMode::kSynchronous;
          result.preset.quality = QualityPreference::kBalanced;
        } else if (*latency == "HQ") {
          result.preset.processing_mode = ProcessingMode::kHybrid;
          result.preset.quality = QualityPreference::kHighQuality;
        }
      }
      if (auto block = GetNumber(*engine, "blockMs")) {
        if (EnsureRange("engine.blockMs", *block, 5.0, 60.0, &result)) {
          result.preset.block_ms = static_cast<uint32_t>(*block);
        }
      }
    }

    if (const JsonValue *modules = FindMember(root, "modules")) {
      if (modules->type != JsonType::kArray) {
        result.ok = false;
        result.error = "modules must be an array";
        return result;
      }
      for (const JsonValue &module : modules->array_value) {
        if (module.type != JsonType::kObject) {
          continue;
        }
        auto id = GetString(module, "id");
        if (!id) {
          continue;
        }
        const bool enabled = GetBool(module, "enabled").value_or(true);
        if (*id == "gate") {
          result.preset.gate.enabled = enabled;
          if (auto threshold = GetNumber(module, "threshold")) {
            if (EnsureRange("gate.threshold", *threshold, -80.0, -20.0, &result)) {
              result.preset.gate.params.threshold_db = static_cast<float>(*threshold);
            }
          }
          if (auto attack = GetNumber(module, "attackMs")) {
            if (EnsureRange("gate.attackMs", *attack, 1.0, 50.0, &result)) {
              result.preset.gate.params.attack_ms = static_cast<float>(*attack);
            }
          }
          if (auto release = GetNumber(module, "releaseMs")) {
            if (EnsureRange("gate.releaseMs", *release, 20.0, 500.0, &result)) {
              result.preset.gate.params.release_ms = static_cast<float>(*release);
            }
          }
          if (auto hysteresis = GetNumber(module, "hysteresis")) {
            if (EnsureRange("gate.hysteresis", *hysteresis, 0.0, 12.0, &result)) {
              result.preset.gate.params.hysteresis_db = static_cast<float>(*hysteresis);
            }
          }
        } else if (*id == "eq") {
          result.preset.eq.enabled = enabled;
          const JsonValue *bands = FindMember(module, "bands");
          if (bands && bands->type == JsonType::kArray) {
            result.preset.eq.bands.clear();
            for (const JsonValue &band : bands->array_value) {
              if (band.type != JsonType::kObject) {
                continue;
              }
              auto freq = GetNumber(band, "f");
              auto gain = GetNumber(band, "g");
              auto q = GetNumber(band, "q");
              if (!freq || !gain || !q) {
                continue;
              }
              if (!EnsureRange("eq.band.frequency", *freq, 20.0, 12000.0, &result)) {
                return result;
              }
              if (!EnsureRange("eq.band.gain", *gain, -12.0, 12.0, &result)) {
                return result;
              }
              if (!EnsureRange("eq.band.q", *q, 0.3, 10.0, &result)) {
                return result;
              }
              effects::EqBand eq_band;
              eq_band.frequency_hz = static_cast<float>(*freq);
              eq_band.gain_db = static_cast<float>(*gain);
              eq_band.q = static_cast<float>(*q);
              result.preset.eq.bands.push_back(eq_band);
            }
          }
        } else if (*id == "comp") {
          result.preset.compressor.enabled = enabled;
          auto &params = result.preset.compressor.params;
          if (auto mode = GetString(module, "mode")) {
            if (*mode == "auto" || *mode == "Auto") {
              params.mode = effects::CompressorMode::kAuto;
            } else {
              params.mode = effects::CompressorMode::kManual;
            }
          }
          if (auto threshold = GetNumber(module, "threshold")) {
            if (EnsureRange("comp.threshold", *threshold, -60.0, -5.0, &result)) {
              params.threshold_db = static_cast<float>(*threshold);
            }
          }
          if (auto ratio = GetNumber(module, "ratio")) {
            if (EnsureRange("comp.ratio", *ratio, 1.2, 6.0, &result)) {
              params.ratio = static_cast<float>(*ratio);
            }
          }
          if (auto knee = GetNumber(module, "knee")) {
            if (EnsureRange("comp.knee", *knee, 0.0, 12.0, &result)) {
              params.knee_db = static_cast<float>(*knee);
              params.knee = *knee > 0.0 ? effects::KneeType::kSoft
                                        : effects::KneeType::kHard;
            }
          }
          if (auto attack = GetNumber(module, "attackMs")) {
            if (EnsureRange("comp.attackMs", *attack, 1.0, 50.0, &result)) {
              params.attack_ms = static_cast<float>(*attack);
            }
          }
          if (auto release = GetNumber(module, "releaseMs")) {
            if (EnsureRange("comp.releaseMs", *release, 20.0, 500.0, &result)) {
              params.release_ms = static_cast<float>(*release);
            }
          }
          if (auto makeup = GetNumber(module, "makeup")) {
            if (EnsureRange("comp.makeup", *makeup, 0.0, 12.0, &result)) {
              params.makeup_gain_db = static_cast<float>(*makeup);
            }
          }
        } else if (*id == "pitch") {
          result.preset.pitch.enabled = enabled;
          auto &params = result.preset.pitch.params;
          if (auto semitones = GetNumber(module, "semitones")) {
            if (EnsureRange("pitch.semitones", *semitones, -12.0, 12.0, &result)) {
              params.semitones = static_cast<float>(*semitones);
            }
          }
          if (auto cents = GetNumber(module, "cents")) {
            if (EnsureRange("pitch.cents", *cents, -100.0, 100.0, &result)) {
              params.cents = static_cast<float>(*cents);
            }
          }
          if (auto quality = GetString(module, "quality")) {
            if (auto parsed = ParsePitchQuality(*quality)) {
              params.quality = *parsed;
            }
          }
          if (auto preserve = GetBool(module, "preserveFormants")) {
            params.preserve_formants = *preserve;
          }
        } else if (*id == "formant") {
          result.preset.formant.enabled = enabled;
          if (auto cents = GetNumber(module, "cents")) {
            if (EnsureRange("formant.cents", *cents, -600.0, 600.0, &result)) {
              result.preset.formant.params.cents = static_cast<float>(*cents);
            }
          }
          if (auto intelligibility = GetBool(module, "intelligibility")) {
            result.preset.formant.params.intelligibility_assist = *intelligibility;
          }
        } else if (*id == "autotune") {
          result.preset.autotune.enabled = enabled;
          result.preset.autotune.params = ParseAutoTuneParams(module, &result);
        } else if (*id == "reverb") {
          result.preset.reverb.enabled = enabled;
          auto &params = result.preset.reverb.params;
          if (auto room = GetNumber(module, "room")) {
            if (EnsureRange("reverb.room", *room, 0.0, 100.0, &result)) {
              params.room_size = static_cast<float>(*room);
            }
          }
          if (auto damp = GetNumber(module, "damp")) {
            if (EnsureRange("reverb.damp", *damp, 0.0, 100.0, &result)) {
              params.damping = static_cast<float>(*damp);
            }
          }
          if (auto predelay = GetNumber(module, "predelayMs")) {
            if (EnsureRange("reverb.predelayMs", *predelay, 0.0, 40.0, &result)) {
              params.pre_delay_ms = static_cast<float>(*predelay);
            }
          }
          if (auto mix = GetNumber(module, "mix")) {
            if (EnsureRange("reverb.mix", *mix, 0.0, 50.0, &result)) {
              params.mix = static_cast<float>(*mix);
            }
          }
        } else if (*id == "mix") {
          auto &params = result.preset.mix.params;
          if (auto wet = GetNumber(module, "wet")) {
            if (EnsureRange("mix.wet", *wet, 0.0, 100.0, &result)) {
              params.dry_wet = static_cast<float>(*wet);
            }
          }
          if (auto gain = GetNumber(module, "outGain")) {
            if (EnsureRange("mix.outGain", *gain, -12.0, 12.0, &result)) {
              params.output_gain_db = static_cast<float>(*gain);
            }
          }
        }
      }
    }

    result.ok = true;
    return result;
  } catch (const std::exception &ex) {
    result.ok = false;
    result.error = ex.what();
    return result;
  }
}

}  // namespace echidna::dsp::config
