#include "echidna/dsp/api.h"

#include <cassert>
#include <cstring>
#include <string>
#include <vector>

namespace
{

    const char *kPassThroughPreset = R"({
        "name": "Passthrough",
        "engine": {"latencyMode": "Balanced", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": false},
            {"id": "eq", "enabled": false, "bands": []},
            {"id": "comp", "enabled": false},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": 0.0}
        ]
    })";

    const char *kInvalidPreset = R"({"name":"bad","modules":[]})";

} // namespace

int main()
{
    const uint32_t sample_rate = 48000;
    const uint32_t channels = 2;
    const size_t frames = 256;
    const size_t samples = frames * channels;

    // Initialise engine and apply a minimal passthrough preset.
    auto init_status = ech_dsp_initialize(sample_rate, channels, ECH_DSP_QUALITY_BALANCED);
    assert(init_status == ECH_DSP_STATUS_OK);

    auto preset_status =
        ech_dsp_update_config(kPassThroughPreset, std::strlen(kPassThroughPreset));
    assert(preset_status == ECH_DSP_STATUS_OK);

    std::vector<float> input(samples, 0.0f);
    input[0] = 0.25f; // simple impulse for sanity
    std::vector<float> output(samples, 0.0f);

    auto process_status =
        ech_dsp_process_block(input.data(), output.data(), frames);
    assert(process_status == ECH_DSP_STATUS_OK);
    assert(output.size() == samples);
    assert(output[0] != 0.0f); // impulse should survive through mix

    // Invalid preset should be rejected.
    auto invalid_status = ech_dsp_update_config(kInvalidPreset, std::strlen(kInvalidPreset));
    assert(invalid_status == ECH_DSP_STATUS_INVALID_ARGUMENT);

    ech_dsp_shutdown();
    return 0;
}
