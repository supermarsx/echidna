#include "hooks/capture_buffer_router.h"

#include "echidna/dsp/api.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#ifndef _WIN32
#include <sys/mman.h>
#include <unistd.h>
#endif

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

    echidna_result_t HalfGain(const float *input,
                              float *output,
                              uint32_t frames,
                              uint32_t,
                              uint32_t channels)
    {
        const size_t samples = static_cast<size_t>(frames) * channels;
        for (size_t i = 0; i < samples; ++i)
        {
            output[i] = input[i] * 0.5f;
        }
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t PartialThenFail(const float *,
                                     float *output,
                                     uint32_t frames,
                                     uint32_t,
                                     uint32_t channels)
    {
        const size_t samples = static_cast<size_t>(frames) * channels;
        std::fill(output, output + samples, 0.75f);
        return ECHIDNA_RESULT_ERROR;
    }

    echidna_result_t ProduceNan(const float *,
                                float *output,
                                uint32_t frames,
                                uint32_t,
                                uint32_t channels)
    {
        const size_t samples = static_cast<size_t>(frames) * channels;
        std::fill(output, output + samples, 0.0f);
        output[samples / 2] = std::numeric_limits<float>::quiet_NaN();
        return ECHIDNA_RESULT_OK;
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

    void TestAndroidEncodingContract()
    {
        echidna::audio::PcmFormat format{};
        CHECK(echidna::audio::PcmFormatFromAndroidEncoding(1, &format) &&
                  format == echidna::audio::PcmFormat::kSigned16,
              "default encoding must resolve to PCM16");
        CHECK(echidna::audio::PcmFormatFromAndroidEncoding(21, &format) &&
                  format == echidna::audio::PcmFormat::kSigned24Packed,
              "public encoding 21 must resolve to packed PCM24");
        CHECK(echidna::audio::PcmFormatFromAndroidEncoding(22, &format) &&
                  format == echidna::audio::PcmFormat::kSigned32,
              "public encoding 22 must resolve to PCM32");
        CHECK(!echidna::audio::PcmFormatFromAndroidEncoding(20, &format),
              "unknown encoding 20 must fail closed");
    }

    template <typename Container>
    bool ProcessWithScratch(Container &buffer,
                            size_t offset_bytes,
                            size_t byte_count,
                            echidna::audio::PcmFormat format,
                            echidna::audio::ProcessBlockFn process)
    {
        std::vector<float> input(128);
        std::vector<float> output(128);
        auto *bytes = reinterpret_cast<uint8_t *>(buffer.data());
        return echidna::audio::ProcessPcmBufferInPlace(bytes + offset_bytes,
                                                       byte_count,
                                                       format,
                                                       kSampleRate,
                                                       1,
                                                       input.data(),
                                                       output.data(),
                                                       input.size(),
                                                       process) ==
               echidna::audio::BufferProcessResult::kProcessed;
    }

    void TestFormatMatrixAndSentinelBounds()
    {
        {
            std::vector<uint8_t> pcm{0xA5, 0, 64, 128, 192, 255, 0x5A};
            const auto before = pcm;
            CHECK(ProcessWithScratch(pcm,
                                     1,
                                     5,
                                     echidna::audio::PcmFormat::kUnsigned8,
                                     HalfGain),
                  "unsigned PCM8 must process");
            CHECK(pcm.front() == before.front() && pcm.back() == before.back(),
                  "PCM8 processing must preserve prefix/suffix sentinels");
            CHECK(pcm[1] > before[1] && pcm[5] < before[5],
                  "PCM8 conversion must use unsigned midpoint 128");
        }
        {
            std::vector<int16_t> pcm{1111, -32768, -16384, 0, 16384, 32767, 2222};
            const auto before = pcm;
            CHECK(ProcessWithScratch(pcm,
                                     sizeof(int16_t),
                                     5 * sizeof(int16_t),
                                     echidna::audio::PcmFormat::kSigned16,
                                     HalfGain),
                  "PCM16 must process");
            CHECK(pcm.front() == before.front() && pcm.back() == before.back(),
                  "PCM16 processing must preserve sentinels");
            CHECK(std::abs(static_cast<int>(pcm[2])) < std::abs(static_cast<int>(before[2])),
                  "PCM16 output must be attenuated");
        }
        {
            std::vector<uint8_t> pcm{
                0xA5,
                0x00, 0x00, 0x80,
                0x00, 0x00, 0xC0,
                0x00, 0x00, 0x00,
                0x00, 0x00, 0x40,
                0xFF, 0xFF, 0x7F,
                0x5A};
            const auto before = pcm;
            CHECK(ProcessWithScratch(pcm,
                                     1,
                                     15,
                                     echidna::audio::PcmFormat::kSigned24Packed,
                                     HalfGain),
                  "packed PCM24 must process");
            CHECK(pcm.front() == before.front() && pcm.back() == before.back(),
                  "packed PCM24 processing must preserve byte sentinels");
            CHECK(pcm != before, "packed PCM24 output must change");
        }
        {
            std::vector<int32_t> pcm{1111,
                                     std::numeric_limits<int32_t>::min(),
                                     -1073741824,
                                     0,
                                     1073741824,
                                     std::numeric_limits<int32_t>::max(),
                                     2222};
            const auto before = pcm;
            CHECK(ProcessWithScratch(pcm,
                                     sizeof(int32_t),
                                     5 * sizeof(int32_t),
                                     echidna::audio::PcmFormat::kSigned32,
                                     HalfGain),
                  "PCM32 must process");
            CHECK(pcm.front() == before.front() && pcm.back() == before.back(),
                  "PCM32 processing must preserve sentinels");
            CHECK(std::abs(static_cast<int64_t>(pcm[2])) <
                      std::abs(static_cast<int64_t>(before[2])),
                  "PCM32 output must be attenuated");
        }
        {
            std::vector<float> pcm{9.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 8.0f};
            const auto before = pcm;
            CHECK(ProcessWithScratch(pcm,
                                     sizeof(float),
                                     5 * sizeof(float),
                                     echidna::audio::PcmFormat::kFloat32,
                                     HalfGain),
                  "float PCM must process");
            CHECK(pcm.front() == before.front() && pcm.back() == before.back(),
                  "float processing must preserve sentinels");
            CHECK(std::fabs(pcm[2] + 0.25f) < 1e-6f,
                  "float output must be attenuated exactly");
        }
    }

    void TestFailuresNeverCommitPartialOutput()
    {
        std::vector<int16_t> pcm{101, -2000, -1000, 0, 1000, 2000, 202};
        const auto original = pcm;
        CHECK(!ProcessWithScratch(pcm,
                                  sizeof(int16_t),
                                  5 * sizeof(int16_t),
                                  echidna::audio::PcmFormat::kSigned16,
                                  PartialThenFail),
              "DSP failure must be reported");
        CHECK(pcm == original, "DSP failure must leave the whole region unchanged");

        CHECK(!ProcessWithScratch(pcm,
                                  sizeof(int16_t),
                                  5 * sizeof(int16_t),
                                  echidna::audio::PcmFormat::kSigned16,
                                  ProduceNan),
              "non-finite DSP output must be rejected");
        CHECK(pcm == original, "non-finite DSP output must not partially commit");

        std::vector<float> small_input(2);
        std::vector<float> small_output(2);
        const auto result = echidna::audio::ProcessPcmBufferInPlace(
            pcm.data() + 1,
            5 * sizeof(int16_t),
            echidna::audio::PcmFormat::kSigned16,
            kSampleRate,
            1,
            small_input.data(),
            small_output.data(),
            small_input.size(),
            HalfGain);
        CHECK(result == echidna::audio::BufferProcessResult::kScratchTooSmall,
              "undersized scratch must fail explicitly");
        CHECK(pcm == original, "scratch failure must preserve input");
    }

    void TestFrameAlignmentFailsClosed()
    {
        std::vector<int16_t> pcm{100, 200, 300};
        const auto original = pcm;
        std::vector<float> input(8);
        std::vector<float> output(8);
        const auto result = echidna::audio::ProcessPcmBufferInPlace(
            pcm.data(),
            pcm.size() * sizeof(int16_t),
            echidna::audio::PcmFormat::kSigned16,
            kSampleRate,
            2,
            input.data(),
            output.data(),
            input.size(),
            HalfGain);
        CHECK(result == echidna::audio::BufferProcessResult::kInvalidArgument,
              "sample counts not divisible by channels must fail closed");
        CHECK(pcm == original, "frame alignment failure must preserve input");
    }

#ifndef _WIN32
    void TestGuardPageBoundary()
    {
        const long page_size = sysconf(_SC_PAGESIZE);
        CHECK(page_size > 0, "page size must be available");
        if (page_size <= 0)
        {
            return;
        }
        void *mapping = mmap(nullptr,
                             static_cast<size_t>(page_size) * 2,
                             PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS,
                             -1,
                             0);
        CHECK(mapping != MAP_FAILED, "guard mapping must allocate");
        if (mapping == MAP_FAILED)
        {
            return;
        }
        auto *guard = static_cast<uint8_t *>(mapping) + page_size;
        CHECK(mprotect(guard, static_cast<size_t>(page_size), PROT_NONE) == 0,
              "second page must become inaccessible");
        auto *pcm = reinterpret_cast<int16_t *>(guard - (4 * sizeof(int16_t)));
        pcm[0] = -2000;
        pcm[1] = -1000;
        pcm[2] = 1000;
        pcm[3] = 2000;
        CHECK(echidna::hooks::RouteInt16CaptureBufferInPlace(
                  pcm, 4 * sizeof(int16_t), kSampleRate, 1, HalfGain),
              "router must process a buffer ending exactly at a guard page");
        CHECK(pcm[3] > 0 && pcm[3] < 2000,
              "guard-page-adjacent last sample must be transformed");
        munmap(mapping, static_cast<size_t>(page_size) * 2);
    }
#endif

} // namespace

int main()
{
    TestInt16RouterProcessesBuffer();
    TestFloatRouterProcessesBuffer();
    TestRouterRejectsInvalidBuffers();
    TestAndroidEncodingContract();
    TestFormatMatrixAndSentinelBounds();
    TestFailuresNeverCommitPartialOutput();
    TestFrameAlignmentFailsClosed();
#ifndef _WIN32
    TestGuardPageBoundary();
#endif

    if (g_failures != 0)
    {
        std::fprintf(stderr, "capture_buffer_router_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "capture_buffer_router_test: all checks passed\n");
    return 0;
}
