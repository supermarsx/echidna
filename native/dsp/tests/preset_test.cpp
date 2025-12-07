#include "config/preset_loader.h"

#include <cassert>
#include <string>

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
    return 0;
}
