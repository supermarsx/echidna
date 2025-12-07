#include "config/preset_loader.h"

#include <cassert>
#include <string>
#include <vector>

int main() {
    const std::string preset = R"({
        "name": "TestPreset",
        "engine": {"latencyMode": "LL", "blockMs": 15},
        "modules": [
            {"id": "gate", "enabled": true, "threshold": -40.0, "attackMs": 5.0, "releaseMs": 80.0, "hysteresis": 3.0},
            {"id": "mix", "wet": 50.0, "outGain": 0.0}
        ]
    })";

    auto result = echidna::dsp::config::LoadPresetFromJson(preset);
    assert(result.ok && "Preset should parse");
    assert(result.preset.gate.enabled);
    assert(result.preset.mix.params.dry_wet == 50.0f);
    assert(result.preset.block_ms == 15u);

    // Invalid engine missing modules should be rejected.
    const std::string invalid_missing_modules = R"({
        "name": "Bad",
        "engine": {"latencyMode": "LL", "blockMs": 10}
    })";
    auto invalid_result = echidna::dsp::config::LoadPresetFromJson(invalid_missing_modules);
    assert(!invalid_result.ok);

    // Out-of-range EQ gain should be rejected.
    const std::string invalid_eq_gain = R"({
        "name": "BadEq",
        "engine": {"latencyMode": "LL", "blockMs": 15},
        "modules": [
            {"id": "eq", "enabled": true, "bands": [
                {"f": 200.0, "g": 20.0, "q": 1.0}
            ]},
            {"id": "mix", "wet": 50.0, "outGain": 0.0}
        ]
    })";
    auto invalid_eq_result = echidna::dsp::config::LoadPresetFromJson(invalid_eq_gain);
    assert(!invalid_eq_result.ok);

    // Verify module count guard (over limit) is rejected.
    std::string too_many_modules = R"({"name":"Flood","engine":{"latencyMode":"LL","blockMs":10},"modules":[)";
    for (int i = 0; i < 70; ++i) {
        too_many_modules += R"({"id":"mix","wet":50,"outGain":0.0})";
        if (i != 69) {
            too_many_modules += ",";
        }
    }
    too_many_modules += "]}";
    auto flood_result = echidna::dsp::config::LoadPresetFromJson(too_many_modules);
    assert(!flood_result.ok);
    return 0;
}
