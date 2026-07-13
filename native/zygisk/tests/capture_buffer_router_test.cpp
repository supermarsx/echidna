#include "hooks/capture_buffer_router.h"

#include "echidna/dsp/api.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace
{
    constexpr double kPi = 3.14159265358979323846;
    constexpr uint32_t kSampleRate = 48000;

    int g_failures = 0;

    void check(bool condition, const char *expr, const char *file, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [%s:%d] %s\n", expr, file, line, message);
            ++g_failures;
        }
    }

#define CHECK(cond, msg) check((cond), #cond, __FILE__, __LINE__, (msg))

    const char *kCutPreset = R"({
        "name": "RouterCut",
        "engine": {"latencyMode": "Balanced", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": false},
            {"id": "eq", "enabled": false, "bands": []},
            {"id": "comp", "enabled": false},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": -9.0}
        ]
    })";

    echidna_result_t DspProcessBlock(const float *input,
                                     float *output,
                                     uint32_t frames,
                                     uint32_t,
                                     uint32_t)
    {
        const auto status = ech_dsp_process_block(input, output, frames);
        return status == ECH_DSP_STATUS_OK ? ECHIDNA_RESULT_OK : ECHIDNA_RESULT_ERROR;
    }

    std::vector<int16_t> MakeInt16Tone(double freq, size_t frames, double amp)
    {
        std::vector<int16_t> pcm(frames);
        for (size_t i = 0; i < frames; ++i)
        {
            const double sample =
                amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / kSampleRate);
            pcm[i] = static_cast<int16_t>(std::lround(std::clamp(sample, -1.0, 1.0) * 32767.0));
        }
        return pcm;
    }

    std::vector<float> MakeFloatTone(double freq, size_t frames, double amp)
    {
        std::vector<float> pcm(frames);
        for (size_t i = 0; i < frames; ++i)
        {
            pcm[i] = static_cast<float>(
                amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / kSampleRate));
        }
        return pcm;
    }

    template <typename T>
    double Rms(const std::vector<T> &samples)
    {
        double sum = 0.0;
        for (const auto sample : samples)
        {
            const double value = static_cast<double>(sample);
            sum += value * value;
        }
        return std::sqrt(sum / static_cast<double>(samples.size()));
    }

    void ApplyCutPreset()
    {
        CHECK(ech_dsp_initialize(kSampleRate, 1, ECH_DSP_QUALITY_BALANCED) ==
                  ECH_DSP_STATUS_OK,
              "DSP must initialize");
        CHECK(ech_dsp_update_config(kCutPreset, std::strlen(kCutPreset)) == ECH_DSP_STATUS_OK,
              "cut preset must apply");
    }

    void TestInt16RouterProcessesBuffer()
    {
        ApplyCutPreset();
        const auto original = MakeInt16Tone(1000.0, 4096, 0.2);
        auto pcm = original;
        const bool routed = echidna::hooks::RouteInt16CaptureBufferInPlace(
            pcm.data(),
            pcm.size() * sizeof(int16_t),
            kSampleRate,
            1,
            DspProcessBlock);
        CHECK(routed, "int16 router must process a valid capture buffer");
        const double ratio = Rms(pcm) / Rms(original);
        CHECK(ratio > 0.30 && ratio < 0.42, "cut preset must attenuate int16 PCM");
        CHECK(pcm != original, "int16 output must change");
        ech_dsp_shutdown();
    }

    void TestFloatRouterProcessesBuffer()
    {
        ApplyCutPreset();
        const auto original = MakeFloatTone(1000.0, 4096, 0.2);
        auto pcm = original;
        const bool routed = echidna::hooks::RouteFloatCaptureBufferInPlace(
            pcm.data(),
            static_cast<uint32_t>(pcm.size()),
            kSampleRate,
            1,
            DspProcessBlock);
        CHECK(routed, "float router must process a valid capture buffer");
        const double ratio = Rms(pcm) / Rms(original);
        CHECK(ratio > 0.30 && ratio < 0.42, "cut preset must attenuate float PCM");
        CHECK(pcm != original, "float output must change");
        ech_dsp_shutdown();
    }

    void TestRouterRejectsInvalidBuffers()
    {
        ApplyCutPreset();
        auto pcm = MakeInt16Tone(1000.0, 17, 0.2);
        const auto original = pcm;
        const bool routed = echidna::hooks::RouteInt16CaptureBufferInPlace(
            pcm.data(),
            (pcm.size() * sizeof(int16_t)) - 1,
            kSampleRate,
            1,
            DspProcessBlock);
        CHECK(!routed, "router must reject byte counts that split int16 samples");
        CHECK(pcm == original, "invalid buffers must remain untouched");
        ech_dsp_shutdown();
    }

} // namespace

int main()
{
    TestInt16RouterProcessesBuffer();
    TestFloatRouterProcessesBuffer();
    TestRouterRejectsInvalidBuffers();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "capture_buffer_router_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "capture_buffer_router_test: all checks passed\n");
    return 0;
}
