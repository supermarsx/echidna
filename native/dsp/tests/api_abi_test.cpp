#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <memory>
#include <type_traits>

#include "echidna/dsp/api.h"
#include "engine.h"

namespace
{
    int Check(bool condition, const char *expression, int line)
    {
        if (!condition)
        {
            std::cerr << __FILE__ << ':' << line
                      << ": check failed: " << expression << '\n';
            return 1;
        }
        return 0;
    }
} // namespace

#define CHECK_TRUE(condition)                                                   \
    do                                                                          \
    {                                                                           \
        if (Check((condition), #condition, __LINE__) != 0)                       \
        {                                                                       \
            return 1;                                                           \
        }                                                                       \
    } while (false)

static_assert(std::is_same_v<decltype(&ech_dsp_api_get_version),
                             uint32_t (*)(void)>);
static_assert(std::is_same_v<decltype(&ech_dsp_initialize),
                             ech_dsp_status_t (*)(uint32_t,
                                                  uint32_t,
                                                  ech_dsp_quality_mode_t)>);
static_assert(std::is_same_v<decltype(&ech_dsp_update_config),
                             ech_dsp_status_t (*)(const char *, size_t)>);
static_assert(std::is_same_v<decltype(&ech_dsp_prepare_realtime),
                             ech_dsp_status_t (*)(size_t)>);
static_assert(std::is_same_v<decltype(&ech_dsp_process_block),
                             ech_dsp_status_t (*)(const float *, float *, size_t)>);
static_assert(std::is_same_v<decltype(&ech_dsp_shutdown), void (*)(void)>);
static_assert(std::is_constructible_v<echidna::dsp::DspEngine,
                                      uint32_t,
                                      uint32_t,
                                      ech_dsp_quality_mode_t>);

int main()
{
    using echidna::dsp::DspEngine;
    using echidna::dsp::DspEngineOptions;

    DspEngine standalone(48000, 1, ECH_DSP_QUALITY_LOW_LATENCY);
    CHECK_TRUE(standalone.plugin_directory_scanned());

    DspEngineOptions embedded_options;
    embedded_options.load_plugins = false;
    embedded_options.lock_free_realtime_process = true;
    DspEngine embedded(48000,
                       1,
                       ECH_DSP_QUALITY_LOW_LATENCY,
                       embedded_options);
    CHECK_TRUE(!embedded.plugin_directory_scanned());

    ech_dsp_shutdown();
    CHECK_TRUE(ech_dsp_api_get_version() == ECH_DSP_API_VERSION);
    CHECK_TRUE(ech_dsp_prepare_realtime(64) ==
               ECH_DSP_STATUS_NOT_INITIALISED);
    CHECK_TRUE(ech_dsp_initialize(48000, 1, ECH_DSP_QUALITY_LOW_LATENCY) ==
               ECH_DSP_STATUS_OK);
    CHECK_TRUE(echidna::dsp::acquire_engine() != nullptr);
    CHECK_TRUE(ech_dsp_prepare_realtime(64) == ECH_DSP_STATUS_OK);

    std::array<float, 64> input{};
    std::array<float, 64> output{};
    input.fill(0.125f);
    CHECK_TRUE(ech_dsp_process_block(input.data(), output.data(), input.size()) ==
               ECH_DSP_STATUS_OK);
    for (float sample : output)
    {
        CHECK_TRUE(std::isfinite(sample));
    }
    echidna::dsp::release_engine();
    ech_dsp_shutdown();
    CHECK_TRUE(echidna::dsp::acquire_engine() == nullptr);
    return 0;
}
