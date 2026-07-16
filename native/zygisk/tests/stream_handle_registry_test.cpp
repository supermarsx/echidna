#include "dsp/stream_handle_registry.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <new>
#include <thread>
#include <type_traits>
#include <vector>

static_assert(std::is_same_v<decltype(&echidna_stream_create),
                             echidna_result_t (*)(const echidna_stream_config_t *,
                                                  echidna_stream_handle_t *)>);
static_assert(std::is_same_v<decltype(&echidna_stream_process),
                             echidna_result_t (*)(echidna_stream_handle_t,
                                                  const void *,
                                                  void *,
                                                  uint32_t,
                                                  uint32_t)>);
static_assert(std::is_same_v<decltype(&echidna_stream_update),
                             echidna_result_t (*)(echidna_stream_handle_t,
                                                  const char *,
                                                  size_t,
                                                  uint64_t)>);
static_assert(std::is_same_v<decltype(&echidna_stream_destroy),
                             echidna_result_t (*)(echidna_stream_handle_t)>);

namespace
{
    std::atomic<size_t> g_allocations{0};
    std::atomic<bool> g_count_allocations{false};

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::cerr << "FAIL: " << message << '\n';
            std::exit(1);
        }
    }

    constexpr const char *kPassThroughPreset = R"({
        "name":"pass","engine":{"latencyMode":"Balanced","blockMs":20},
        "modules":[{"id":"gate","enabled":false},{"id":"eq","enabled":false,"bands":[]},
        {"id":"comp","enabled":false},{"id":"pitch","enabled":false},
        {"id":"formant","enabled":false},{"id":"autotune","enabled":false},
        {"id":"reverb","enabled":false},{"id":"mix","wet":100.0,"outGain":0.0}]})";

    constexpr const char *kGainPreset = R"({
        "name":"gain","engine":{"latencyMode":"Balanced","blockMs":20},
        "modules":[{"id":"gate","enabled":false},{"id":"eq","enabled":false,"bands":[]},
        {"id":"comp","enabled":false},{"id":"pitch","enabled":false},
        {"id":"formant","enabled":false},{"id":"autotune","enabled":false},
        {"id":"reverb","enabled":false},{"id":"mix","wet":100.0,"outGain":6.0}]})";

    // A single 0 dB peaking EQ band: mathematically transparent but it forces the
    // engine down the real float processing path (not the disabled short-circuit),
    // so the registry re-encodes PCM16 through the int16->float->int16 round trip.
    // Used to exercise the *quantization tolerance* of a near-neutral transform.
    constexpr const char *kEqZeroPreset = R"({
        "name":"eq0","engine":{"latencyMode":"Balanced","blockMs":20},
        "modules":[{"id":"gate","enabled":false},
        {"id":"eq","enabled":true,"bands":[{"f":1000.0,"g":0.0,"q":1.0}]},
        {"id":"comp","enabled":false},{"id":"pitch","enabled":false},
        {"id":"formant","enabled":false},{"id":"autotune","enabled":false},
        {"id":"reverb","enabled":false},{"id":"mix","wet":100.0,"outGain":0.0}]})";

    // A +6 dB peaking EQ band at 1 kHz. The underlying biquad carries state (z1/z2)
    // across process() calls, so this preset is used to prove that a bypass call
    // between two real blocks does not advance or reset that unrelated filter state.
    constexpr const char *kEqStatefulPreset = R"({
        "name":"eq6","engine":{"latencyMode":"Balanced","blockMs":20},
        "modules":[{"id":"gate","enabled":false},
        {"id":"eq","enabled":true,"bands":[{"f":1000.0,"g":6.0,"q":1.0}]},
        {"id":"comp","enabled":false},{"id":"pitch","enabled":false},
        {"id":"formant","enabled":false},{"id":"autotune","enabled":false},
        {"id":"reverb","enabled":false},{"id":"mix","wet":100.0,"outGain":0.0}]})";

    echidna_stream_config_t Config(uint32_t rate,
                                   uint32_t channels,
                                   uint32_t format,
                                   uint32_t max_frames = 256)
    {
        echidna_stream_config_t config{};
        config.struct_size = sizeof(config);
        config.sample_rate = rate;
        config.channel_count = channels;
        config.max_frames = max_frames;
        config.format = format;
        return config;
    }

    echidna::dsp_runtime::StreamDspBackend RealBackend()
    {
        return {ech_dsp_engine_create, ech_dsp_engine_process, ech_dsp_engine_destroy};
    }

    void TestMixedIndependentStreams()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const std::array<echidna_stream_config_t, 6> configs = {
            Config(44100, 1, ECHIDNA_PCM_FORMAT_SIGNED_16),
            Config(48000, 2, ECHIDNA_PCM_FORMAT_SIGNED_16),
            Config(96000, 1, ECHIDNA_PCM_FORMAT_SIGNED_16),
            Config(44100, 2, ECHIDNA_PCM_FORMAT_FLOAT_32),
            Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
            Config(96000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32)};
        std::array<echidna_stream_handle_t, configs.size()> handles{};
        for (size_t i = 0; i < configs.size(); ++i)
        {
            Check(registry.create(configs[i], backend, &handles[i]) == ECHIDNA_RESULT_OK,
                  "mixed stream create");
        }

        std::array<std::thread, configs.size()> workers;
        std::atomic<bool> failed{false};
        for (size_t i = 0; i < configs.size(); ++i)
        {
            workers[i] = std::thread([&, i]
                                     {
                const auto &config = configs[i];
                const size_t samples = 128 * config.channel_count;
                for (size_t iteration = 0; iteration < 100; ++iteration)
                {
                    bool mutated = true;
                    if (config.format == ECHIDNA_PCM_FORMAT_SIGNED_16)
                    {
                        std::vector<int16_t> input(samples);
                        std::vector<int16_t> output(samples);
                        for (size_t sample = 0; sample < samples; ++sample)
                        {
                            input[sample] = static_cast<int16_t>((sample * 97 + i * 31) % 20000 - 10000);
                        }
                        if (registry.process(handles[i], input.data(), output.data(), 128,
                                             config.format, false, &mutated) != ECHIDNA_RESULT_OK ||
                            output != input || mutated)
                        {
                            failed = true;
                            return;
                        }
                    }
                    else
                    {
                        std::vector<float> input(samples);
                        std::vector<float> output(samples);
                        for (size_t sample = 0; sample < samples; ++sample)
                        {
                            input[sample] = static_cast<float>((sample + i) % 17) / 20.0f;
                        }
                        if (registry.process(handles[i], input.data(), output.data(), 128,
                                             config.format, false, &mutated) != ECHIDNA_RESULT_OK ||
                            std::memcmp(output.data(), input.data(), samples * sizeof(float)) != 0 ||
                            mutated)
                        {
                            failed = true;
                            return;
                        }
                    }
                } });
        }
        for (auto &worker : workers)
        {
            worker.join();
        }
        Check(!failed, "mixed formats/rates must not cross-talk");
        for (auto handle : handles)
        {
            Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "mixed stream destroy");
        }
    }

    void TestProfileGenerationAndLegacyIsolation()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32);
        echidna_stream_handle_t first = 0;
        echidna_stream_handle_t second = 0;
        Check(registry.create(config, backend, &first) == ECHIDNA_RESULT_OK, "first create");
        Check(registry.create(config, backend, &second) == ECHIDNA_RESULT_OK, "second create");
        Check(registry.update(first, kGainPreset, std::strlen(kGainPreset), 1, backend) ==
                  ECHIDNA_RESULT_OK,
              "new profile generation");
        Check(registry.update(first, kPassThroughPreset, std::strlen(kPassThroughPreset), 1,
                              backend) == ECHIDNA_RESULT_INVALID_ARGUMENT,
              "same generation rejected");

        std::array<float, 8> input{0.0f, 0.1f, -0.2f, 0.3f, 0.0f, -0.4f, 0.5f, 0.0f};
        std::array<float, 8> first_output{};
        std::array<float, 8> second_output{};
        bool first_mutated = false;
        bool second_mutated = true;
        Check(registry.process(first, input.data(), first_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false, &first_mutated) ==
                  ECHIDNA_RESULT_OK,
              "gain process");
        Check(registry.process(second, input.data(), second_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false, &second_mutated) ==
                  ECHIDNA_RESULT_OK,
              "isolated process");
        Check(first_mutated && !second_mutated, "only updated stream mutates");
        size_t exact_changes = 0;
        for (size_t i = 0; i < input.size(); ++i)
        {
            exact_changes += first_output[i] != input[i] ? 1 : 0;
        }
        Check(exact_changes == 5, "exact non-zero mutation count");
        Check(std::memcmp(second_output.data(), input.data(), sizeof(input)) == 0,
              "other stream remains bit-exact");

        Check(ech_dsp_initialize(32000, 2, ECH_DSP_QUALITY_LOW_LATENCY) == ECH_DSP_STATUS_OK,
              "legacy initialize");
        Check(ech_dsp_update_config(kPassThroughPreset, std::strlen(kPassThroughPreset)) ==
                  ECH_DSP_STATUS_OK,
              "legacy preset");
        Check(ech_dsp_prepare_realtime(64) == ECH_DSP_STATUS_OK, "legacy prepare");
        std::array<float, 16> legacy_input{};
        std::array<float, 16> legacy_output{};
        legacy_input[1] = 0.25f;
        Check(ech_dsp_process_block(legacy_input.data(), legacy_output.data(), 8) ==
                  ECH_DSP_STATUS_OK,
              "legacy process");
        ech_dsp_shutdown();
        first_mutated = false;
        Check(registry.process(first, input.data(), first_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false, &first_mutated) ==
                      ECHIDNA_RESULT_OK &&
                  first_mutated,
              "legacy singleton cannot reinitialize stream handle");

        Check(registry.update(first, nullptr, 0, 2, backend) == ECHIDNA_RESULT_OK,
              "policy revoke");
        first_output.fill(99.0f);
        bool stream_bypassed = false;
        Check(registry.process(first, input.data(), first_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false, &first_mutated,
                               &stream_bypassed) ==
                      ECHIDNA_RESULT_OK &&
                  !first_mutated &&
                  stream_bypassed &&
                  std::memcmp(first_output.data(), input.data(), sizeof(input)) == 0,
              "revoked stream immediately bypasses");
        Check(registry.update(first, kPassThroughPreset, std::strlen(kPassThroughPreset), 3,
                              backend) == ECHIDNA_RESULT_OK,
              "reactivate newer generation");
        Check(registry.process(first, input.data(), first_output.data(), 257,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) ==
                  ECHIDNA_RESULT_INVALID_ARGUMENT,
              "frames above configured maximum rejected");
        Check(registry.process(first, input.data(), first_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_SIGNED_16, false) ==
                  ECHIDNA_RESULT_INVALID_ARGUMENT,
              "format mismatch rejected");
        Check(registry.destroy(first) == ECHIDNA_RESULT_OK, "destroy first");
        Check(registry.process(first, input.data(), first_output.data(), 8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) ==
                  ECHIDNA_RESULT_NOT_INITIALISED,
              "stale token rejected");
        Check(registry.destroy(second) == ECHIDNA_RESULT_OK, "destroy second");
    }

    void TestExactInPlaceAndOutOfPlaceMutationTruth()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();

        const auto verify_float = [&]
        {
            const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 32);
            echidna_stream_handle_t separate = 0;
            echidna_stream_handle_t in_place = 0;
            Check(registry.create(config, backend, &separate) == ECHIDNA_RESULT_OK &&
                      registry.create(config, backend, &in_place) == ECHIDNA_RESULT_OK,
                  "float mutation handles create");
            Check(registry.update(separate,
                                  kGainPreset,
                                  std::strlen(kGainPreset),
                                  1,
                                  backend) == ECHIDNA_RESULT_OK &&
                      registry.update(in_place,
                                      kGainPreset,
                                      std::strlen(kGainPreset),
                                      1,
                                      backend) == ECHIDNA_RESULT_OK,
                  "float gain profiles publish");

            const std::array<float, 8> input{
                0.0f, 0.125f, -0.25f, 0.375f, -0.5f, 0.625f, -0.75f, 0.0f};
            std::array<float, 8> separate_output{};
            auto aliased = input;
            bool separate_mutated = false;
            bool aliased_mutated = false;
            Check(registry.process(separate,
                                   input.data(),
                                   separate_output.data(),
                                   input.size(),
                                   ECHIDNA_PCM_FORMAT_FLOAT_32,
                                   false,
                                   &separate_mutated) == ECHIDNA_RESULT_OK &&
                      registry.process(in_place,
                                       aliased.data(),
                                       aliased.data(),
                                       aliased.size(),
                                       ECHIDNA_PCM_FORMAT_FLOAT_32,
                                       false,
                                       &aliased_mutated) == ECHIDNA_RESULT_OK,
                  "float gain processes in-place and out-of-place");
            Check(separate_mutated && aliased_mutated &&
                      std::memcmp(separate_output.data(),
                                  aliased.data(),
                                  sizeof(aliased)) == 0,
                  "float mutation truth and output are alias-independent");

            Check(registry.update(separate,
                                  kPassThroughPreset,
                                  std::strlen(kPassThroughPreset),
                                  2,
                                  backend) == ECHIDNA_RESULT_OK &&
                      registry.update(in_place,
                                      kPassThroughPreset,
                                      std::strlen(kPassThroughPreset),
                                      2,
                                      backend) == ECHIDNA_RESULT_OK,
                  "float neutral profiles publish");
            separate_output.fill(99.0f);
            aliased = input;
            separate_mutated = true;
            aliased_mutated = true;
            Check(registry.process(separate,
                                   input.data(),
                                   separate_output.data(),
                                   input.size(),
                                   ECHIDNA_PCM_FORMAT_FLOAT_32,
                                   false,
                                   &separate_mutated) == ECHIDNA_RESULT_OK &&
                      registry.process(in_place,
                                       aliased.data(),
                                       aliased.data(),
                                       aliased.size(),
                                       ECHIDNA_PCM_FORMAT_FLOAT_32,
                                       false,
                                       &aliased_mutated) == ECHIDNA_RESULT_OK,
                  "float neutral processes in-place and out-of-place");
            Check(!separate_mutated && !aliased_mutated &&
                      std::memcmp(separate_output.data(), input.data(), sizeof(input)) == 0 &&
                      std::memcmp(aliased.data(), input.data(), sizeof(input)) == 0,
                  "float unchanged truth remains bit-exact for both ownership modes");
            Check(registry.destroy(separate) == ECHIDNA_RESULT_OK &&
                      registry.destroy(in_place) == ECHIDNA_RESULT_OK,
                  "float mutation handles destroy");
        };

        const auto verify_pcm16 = [&]
        {
            const auto config = Config(44100, 1, ECHIDNA_PCM_FORMAT_SIGNED_16, 32);
            echidna_stream_handle_t separate = 0;
            echidna_stream_handle_t in_place = 0;
            Check(registry.create(config, backend, &separate) == ECHIDNA_RESULT_OK &&
                      registry.create(config, backend, &in_place) == ECHIDNA_RESULT_OK,
                  "PCM16 mutation handles create");
            Check(registry.update(separate,
                                  kGainPreset,
                                  std::strlen(kGainPreset),
                                  1,
                                  backend) == ECHIDNA_RESULT_OK &&
                      registry.update(in_place,
                                      kGainPreset,
                                      std::strlen(kGainPreset),
                                      1,
                                      backend) == ECHIDNA_RESULT_OK,
                  "PCM16 gain profiles publish");

            const std::array<int16_t, 8> input{
                0, 1000, -2000, 3000, -4000, 12000, -16000, 0};
            std::array<int16_t, 8> separate_output{};
            auto aliased = input;
            bool separate_mutated = false;
            bool aliased_mutated = false;
            Check(registry.process(separate,
                                   input.data(),
                                   separate_output.data(),
                                   input.size(),
                                   ECHIDNA_PCM_FORMAT_SIGNED_16,
                                   false,
                                   &separate_mutated) == ECHIDNA_RESULT_OK &&
                      registry.process(in_place,
                                       aliased.data(),
                                       aliased.data(),
                                       aliased.size(),
                                       ECHIDNA_PCM_FORMAT_SIGNED_16,
                                       false,
                                       &aliased_mutated) == ECHIDNA_RESULT_OK,
                  "PCM16 gain processes in-place and out-of-place");
            Check(separate_mutated && aliased_mutated && separate_output == aliased,
                  "PCM16 mutation truth and output are alias-independent");

            Check(registry.update(separate,
                                  kPassThroughPreset,
                                  std::strlen(kPassThroughPreset),
                                  2,
                                  backend) == ECHIDNA_RESULT_OK &&
                      registry.update(in_place,
                                      kPassThroughPreset,
                                      std::strlen(kPassThroughPreset),
                                      2,
                                      backend) == ECHIDNA_RESULT_OK,
                  "PCM16 neutral profiles publish");
            separate_output.fill(123);
            aliased = input;
            separate_mutated = true;
            aliased_mutated = true;
            Check(registry.process(separate,
                                   input.data(),
                                   separate_output.data(),
                                   input.size(),
                                   ECHIDNA_PCM_FORMAT_SIGNED_16,
                                   false,
                                   &separate_mutated) == ECHIDNA_RESULT_OK &&
                      registry.process(in_place,
                                       aliased.data(),
                                       aliased.data(),
                                       aliased.size(),
                                       ECHIDNA_PCM_FORMAT_SIGNED_16,
                                       false,
                                       &aliased_mutated) == ECHIDNA_RESULT_OK,
                  "PCM16 neutral processes in-place and out-of-place");
            Check(!separate_mutated && !aliased_mutated && separate_output == input &&
                      aliased == input,
                  "PCM16 unchanged truth remains exact for both ownership modes");
            Check(registry.destroy(separate) == ECHIDNA_RESULT_OK &&
                      registry.destroy(in_place) == ECHIDNA_RESULT_OK,
                  "PCM16 mutation handles destroy");
        };

        verify_float();
        verify_pcm16();
    }

    struct FakeEngine
    {
        uint32_t channels{0};
        uint32_t processed_blocks{0};
    };
    std::atomic<bool> g_fake_block{false};
    std::atomic<bool> g_fake_entered{false};
    std::atomic<bool> g_fake_release{false};
    std::atomic<uint32_t> g_fake_quality{ECH_DSP_QUALITY_HIGH};

    ech_dsp_status_t FakeCreate(uint32_t,
                                uint32_t channels,
                                ech_dsp_quality_mode_t quality,
                                size_t,
                                const char *,
                                size_t,
                                ech_dsp_engine_t **engine)
    {
        g_fake_quality.store(static_cast<uint32_t>(quality),
                             std::memory_order_release);
        *engine = reinterpret_cast<ech_dsp_engine_t *>(new FakeEngine{channels});
        return ECH_DSP_STATUS_OK;
    }

    ech_dsp_status_t FakeProcess(ech_dsp_engine_t *engine,
                                 const float *input,
                                 float *output,
                                 size_t frames)
    {
        auto *fake = reinterpret_cast<FakeEngine *>(engine);
        ++fake->processed_blocks;
        g_fake_entered = fake->processed_blocks != 0;
        while (g_fake_block.load(std::memory_order_acquire) &&
               !g_fake_release.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::memcpy(output, input, frames * fake->channels * sizeof(float));
        return ECH_DSP_STATUS_OK;
    }

    void FakeDestroy(ech_dsp_engine_t *engine)
    {
        delete reinterpret_cast<FakeEngine *>(engine);
    }

    echidna::dsp_runtime::StreamDspBackend FakeBackend()
    {
        return {FakeCreate, FakeProcess, FakeDestroy};
    }

    // Engine that emits a non-finite output sample. Proves the registry's
    // std::isfinite guard on the *output* fails safe (preserves the original
    // input) rather than propagating NaN/inf into the caller's buffer.
    ech_dsp_status_t FakeProcessNan(ech_dsp_engine_t *engine,
                                    const float *input,
                                    float *output,
                                    size_t frames)
    {
        auto *fake = reinterpret_cast<FakeEngine *>(engine);
        const size_t samples = frames * fake->channels;
        for (size_t i = 0; i < samples; ++i)
        {
            output[i] = input[i];
        }
        if (samples != 0)
        {
            output[samples / 2] = std::numeric_limits<float>::quiet_NaN();
        }
        return ECH_DSP_STATUS_OK;
    }

    ech_dsp_status_t FakeProcessInf(ech_dsp_engine_t *engine,
                                    const float *input,
                                    float *output,
                                    size_t frames)
    {
        auto *fake = reinterpret_cast<FakeEngine *>(engine);
        const size_t samples = frames * fake->channels;
        for (size_t i = 0; i < samples; ++i)
        {
            output[i] = input[i];
        }
        if (samples != 0)
        {
            output[0] = -std::numeric_limits<float>::infinity();
            output[samples - 1] = std::numeric_limits<float>::infinity();
        }
        return ECH_DSP_STATUS_OK;
    }

    // Writes a plausible, fully-mutated block and *then* reports failure. Proves
    // the registry discards a partially/mutated engine result and restores the
    // original input rather than leaking the scribbled block to the caller.
    ech_dsp_status_t FakeProcessMutateThenFail(ech_dsp_engine_t *engine,
                                               const float *input,
                                               float *output,
                                               size_t frames)
    {
        auto *fake = reinterpret_cast<FakeEngine *>(engine);
        const size_t samples = frames * fake->channels;
        for (size_t i = 0; i < samples; ++i)
        {
            output[i] = input[i] + 0.5f;
        }
        return ECH_DSP_STATUS_ERROR;
    }

    echidna::dsp_runtime::StreamDspBackend FakeBackendNan()
    {
        return {FakeCreate, FakeProcessNan, FakeDestroy};
    }

    echidna::dsp_runtime::StreamDspBackend FakeBackendInf()
    {
        return {FakeCreate, FakeProcessInf, FakeDestroy};
    }

    echidna::dsp_runtime::StreamDspBackend FakeBackendMutateThenFail()
    {
        return {FakeCreate, FakeProcessMutateThenFail, FakeDestroy};
    }

    void TestExhaustionAndDestroyRace()
    {
        const auto backend = FakeBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 16);
        echidna::dsp_runtime::StreamHandleRegistry registry;
        std::array<echidna_stream_handle_t,
                   echidna::dsp_runtime::StreamHandleRegistry::kMaxStreams>
            handles{};
        for (auto &handle : handles)
        {
            Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
                  "live slot create");
        }
        Check(g_fake_quality.load(std::memory_order_acquire) ==
                  ECH_DSP_QUALITY_LOW_LATENCY,
              "capture handles always request explicit low-latency quality");
        echidna_stream_handle_t overflow = 0;
        Check(registry.create(config, backend, &overflow) == ECHIDNA_RESULT_NOT_AVAILABLE &&
                  overflow == 0,
              "true live exhaustion");
        const auto stale = handles[7];
        Check(registry.destroy(stale) == ECHIDNA_RESULT_OK, "free one live slot");
        Check(registry.create(config, backend, &handles[7]) == ECHIDNA_RESULT_OK &&
                  handles[7] != stale,
              "slot reuse advances generation");
        std::array<float, 4> samples{};
        Check(registry.process(stale, samples.data(), samples.data(), 4,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) ==
                  ECHIDNA_RESULT_NOT_INITIALISED,
              "reused slot rejects stale generation");
        for (auto handle : handles)
        {
            Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "destroy live slot");
        }

        echidna::dsp_runtime::StreamHandleRegistry race_registry;
        echidna_stream_handle_t race_handle = 0;
        Check(race_registry.create(config, backend, &race_handle) == ECHIDNA_RESULT_OK,
              "race create");
        g_fake_block = true;
        g_fake_entered = false;
        g_fake_release = false;
        std::atomic<echidna_result_t> process_result{ECHIDNA_RESULT_ERROR};
        std::atomic<bool> destroy_done{false};
        std::thread processor([&]
                              { process_result = race_registry.process(
                                    race_handle, samples.data(), samples.data(), 4,
                                    ECHIDNA_PCM_FORMAT_FLOAT_32, false); });
        while (!g_fake_entered.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        const auto start = std::chrono::steady_clock::now();
        Check(race_registry.process(race_handle, samples.data(), samples.data(), 4,
                                    ECHIDNA_PCM_FORMAT_FLOAT_32, false) ==
                  ECHIDNA_RESULT_NOT_AVAILABLE,
              "second caller rejected without blocking");
        Check(std::chrono::steady_clock::now() - start < std::chrono::milliseconds(100),
              "Process never waits on single-caller guard");
        std::thread destroyer([&]
                              {
                                  Check(race_registry.destroy(race_handle) == ECHIDNA_RESULT_OK,
                                        "race destroy");
                                  destroy_done = true; });
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        Check(!destroy_done, "destroy waits for in-flight process");
        g_fake_release = true;
        processor.join();
        destroyer.join();
        Check(process_result == ECHIDNA_RESULT_OK && destroy_done,
              "destroy/process quiescence");
        g_fake_block = false;
    }

    void TestNoCallbackAllocationsAndGenerationExhaustion()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32);
        echidna_stream_handle_t handle = 0;
        Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
              "allocation test create");
        std::array<float, 256> samples{};
        Check(registry.process(handle, samples.data(), samples.data(), 128,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
              "allocation warmup");
        g_allocations = 0;
        g_count_allocations = true;
        for (size_t i = 0; i < 100; ++i)
        {
            Check(registry.process(handle, samples.data(), samples.data(), 128,
                                   ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
                  "allocation process");
        }
        g_count_allocations = false;
        Check(g_allocations == 0, "callback path allocates no memory");
        Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "allocation test destroy");

        echidna::dsp_runtime::StreamHandleRegistry exhausted;
        Check(exhausted.setGenerationForTesting(
                  0, echidna::dsp_runtime::StreamHandleRegistry::kMaxHandleGeneration - 1),
              "seed final generation");
        Check(exhausted.create(config, backend, &handle) == ECHIDNA_RESULT_OK &&
                  (handle & 63U) == 0,
              "create final generation");
        Check(exhausted.destroy(handle) == ECHIDNA_RESULT_OK, "destroy final generation");
        echidna_stream_handle_t next = 0;
        Check(exhausted.create(config, backend, &next) == ECHIDNA_RESULT_OK &&
                  (next & 63U) != 0,
              "exhausted slot is never recycled");
        Check(exhausted.destroy(next) == ECHIDNA_RESULT_OK, "destroy post-exhaustion slot");
    }

    // ---- §10 DSP correctness / quality --------------------------------------

    // Non-finite INPUT on the float path must be rejected and the ORIGINAL input
    // preserved byte-for-byte (fail-safe), never processed, never silenced. The
    // guard lives only on the float path (PCM16 is integer and cannot be NaN/inf).
    void TestNonFiniteInputRejectedFloat()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 32);
        echidna_stream_handle_t handle = 0;
        Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
              "non-finite input create");
        // A gain preset that WOULD mutate finite audio, to prove the block was not
        // processed at all when a poison sample is present.
        Check(registry.update(handle, kGainPreset, std::strlen(kGainPreset), 1, backend) ==
                  ECHIDNA_RESULT_OK,
              "non-finite input gain publish");

        const float nan_value = std::numeric_limits<float>::quiet_NaN();
        const float posinf = std::numeric_limits<float>::infinity();
        const float neginf = -std::numeric_limits<float>::infinity();
        const float poisons[] = {nan_value, posinf, neginf};
        for (float poison : poisons)
        {
            for (size_t position : {size_t{0}, size_t{3}, size_t{7}})
            {
                std::array<float, 8> input{
                    0.0f, 0.125f, -0.25f, 0.375f, -0.5f, 0.625f, -0.75f, 0.1f};
                input[position] = poison;
                // Separate output buffer, pre-seeded with a sentinel: fail-safe
                // must overwrite it with the ORIGINAL input, not leave the sentinel
                // and not write silence.
                std::array<float, 8> output;
                output.fill(424242.0f);
                bool mutated = true;
                bool bypassed = true;
                Check(registry.process(handle,
                                       input.data(),
                                       output.data(),
                                       input.size(),
                                       ECHIDNA_PCM_FORMAT_FLOAT_32,
                                       false,
                                       &mutated,
                                       &bypassed) == ECHIDNA_RESULT_INVALID_ARGUMENT,
                      "non-finite input yields INVALID_ARGUMENT");
                Check(!mutated && !bypassed,
                      "non-finite input leaves mutated/bypassed false");
                Check(std::memcmp(output.data(), input.data(), sizeof(input)) == 0,
                      "non-finite input preserves original bit-for-bit (separate)");

                // Aliased (in == out): original must survive untouched.
                auto aliased = input;
                bool aliased_mutated = true;
                Check(registry.process(handle,
                                       aliased.data(),
                                       aliased.data(),
                                       aliased.size(),
                                       ECHIDNA_PCM_FORMAT_FLOAT_32,
                                       false,
                                       &aliased_mutated) == ECHIDNA_RESULT_INVALID_ARGUMENT &&
                          !aliased_mutated &&
                          std::memcmp(aliased.data(), input.data(), sizeof(input)) == 0,
                      "non-finite input preserves original bit-for-bit (aliased)");
            }
        }
        Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "non-finite input destroy");
    }

    // A non-finite ENGINE OUTPUT must be caught by the registry's std::isfinite
    // guard and the ORIGINAL input restored, for both PCM formats, and even when
    // the poison sample sits mid-block after valid samples were already written
    // to the caller buffer (no partial-mutation leak).
    void TestNonFiniteOutputPreservesOriginal()
    {
        const auto config_f = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 64);
        const auto config_i = Config(44100, 1, ECHIDNA_PCM_FORMAT_SIGNED_16, 64);

        for (const auto backend : {FakeBackendNan(), FakeBackendInf()})
        {
            echidna::dsp_runtime::StreamHandleRegistry registry;
            echidna_stream_handle_t fh = 0;
            echidna_stream_handle_t ih = 0;
            Check(registry.create(config_f, backend, &fh) == ECHIDNA_RESULT_OK &&
                      registry.create(config_i, backend, &ih) == ECHIDNA_RESULT_OK,
                  "non-finite output create");

            std::array<float, 16> finput{};
            for (size_t i = 0; i < finput.size(); ++i)
            {
                finput[i] = static_cast<float>(i) / 32.0f - 0.25f;
            }
            std::array<float, 16> foutput;
            foutput.fill(-1234.0f);
            bool fmutated = true;
            bool fbypassed = true;
            Check(registry.process(fh,
                                   finput.data(),
                                   foutput.data(),
                                   finput.size(),
                                   ECHIDNA_PCM_FORMAT_FLOAT_32,
                                   false,
                                   &fmutated,
                                   &fbypassed) == ECHIDNA_RESULT_ERROR,
                  "non-finite float output yields ERROR");
            Check(!fmutated && !fbypassed &&
                      std::memcmp(foutput.data(), finput.data(), sizeof(finput)) == 0,
                  "non-finite float output restores original, no partial leak");

            std::array<int16_t, 16> iinput{};
            for (size_t i = 0; i < iinput.size(); ++i)
            {
                iinput[i] = static_cast<int16_t>(i * 1500 - 8000);
            }
            std::array<int16_t, 16> ioutput;
            ioutput.fill(31337);
            bool imutated = true;
            Check(registry.process(ih,
                                   iinput.data(),
                                   ioutput.data(),
                                   iinput.size(),
                                   ECHIDNA_PCM_FORMAT_SIGNED_16,
                                   false,
                                   &imutated) == ECHIDNA_RESULT_ERROR &&
                      !imutated && ioutput == iinput,
                  "non-finite pcm16 output restores original, no partial leak");

            Check(registry.destroy(fh) == ECHIDNA_RESULT_OK &&
                      registry.destroy(ih) == ECHIDNA_RESULT_OK,
                  "non-finite output destroy");
        }
    }

    // A processing failure (engine returns a non-OK status after scribbling its
    // scratch output) must preserve the ORIGINAL input, never a mutated block.
    void TestProcessingFailurePreservesOriginal()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = FakeBackendMutateThenFail();
        const auto config = Config(48000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32, 32);
        echidna_stream_handle_t handle = 0;
        Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
              "failure-preserve create");

        std::array<float, 16> input{};
        for (size_t i = 0; i < input.size(); ++i)
        {
            input[i] = static_cast<float>(i) * 0.03125f - 0.2f;
        }
        std::array<float, 16> output;
        output.fill(777.0f);
        bool mutated = true;
        bool bypassed = true;
        Check(registry.process(handle,
                               input.data(),
                               output.data(),
                               8,
                               ECHIDNA_PCM_FORMAT_FLOAT_32,
                               false,
                               &mutated,
                               &bypassed) == ECHIDNA_RESULT_ERROR,
              "engine failure yields ERROR");
        Check(!mutated && !bypassed &&
                  std::memcmp(output.data(), input.data(), sizeof(input)) == 0,
              "engine failure restores original input");
        Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "failure-preserve destroy");
    }

    // Neutral (pass) preset must be float bit-exact, including for subnormal /
    // denormal inputs, and must never produce a non-finite or amplified sample.
    void TestNeutralTransparencyAndDenormals()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 64);
        echidna_stream_handle_t handle = 0;
        Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
              "denormal create");
        Check(registry.update(handle, kPassThroughPreset, std::strlen(kPassThroughPreset), 1,
                              backend) == ECHIDNA_RESULT_OK,
              "denormal neutral publish");

        const float denorm_min = std::numeric_limits<float>::denorm_min();
        const float smallest_normal = std::numeric_limits<float>::min();
        std::array<float, 16> input{denorm_min,
                                    -denorm_min,
                                    smallest_normal,
                                    -smallest_normal,
                                    1e-40f,
                                    -1e-40f,
                                    denorm_min * 5.0f,
                                    0.0f,
                                    -0.0f,
                                    0.5f,
                                    -0.5f,
                                    1.0f,
                                    -1.0f,
                                    smallest_normal / 2.0f,
                                    denorm_min * 100.0f,
                                    0.25f};
        std::array<float, 16> output;
        output.fill(999.0f);
        bool mutated = true;
        Check(registry.process(handle,
                               input.data(),
                               output.data(),
                               input.size(),
                               ECHIDNA_PCM_FORMAT_FLOAT_32,
                               false,
                               &mutated) == ECHIDNA_RESULT_OK,
              "denormal neutral process");
        for (size_t i = 0; i < input.size(); ++i)
        {
            Check(std::isfinite(output[i]), "denormal neutral output must be finite");
            // Defined behavior: a neutral chain either preserves the subnormal
            // exactly or a CPU flush-to-zero mode collapses it toward zero. Never
            // amplified, never non-finite, never NaN.
            Check(std::fabs(output[i]) <= std::fabs(input[i]) ||
                      output[i] == input[i],
                  "denormal neutral must not amplify a subnormal");
        }
        // For ordinary (non-subnormal) inputs the neutral preset is strictly
        // bit-exact via the registry's bit-cast short-circuit.
        std::array<float, 8> normal{0.0f, 0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.75f};
        std::array<float, 8> normal_out;
        normal_out.fill(5.0f);
        bool normal_mutated = true;
        Check(registry.process(handle,
                               normal.data(),
                               normal_out.data(),
                               normal.size(),
                               ECHIDNA_PCM_FORMAT_FLOAT_32,
                               false,
                               &normal_mutated) == ECHIDNA_RESULT_OK &&
                  !normal_mutated &&
                  std::memcmp(normal_out.data(), normal.data(), sizeof(normal)) == 0,
              "neutral preset is float bit-exact for normal inputs");
        Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "denormal destroy");
    }

    // PCM16 through a near-neutral transform (0 dB EQ) must stay within a small,
    // defined quantization tolerance of the input; a truly neutral (disabled)
    // preset is bit-exact via the bit-cast short-circuit.
    void TestPcm16NeutralQuantizationTolerance()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_SIGNED_16, 512);
        echidna_stream_handle_t handle = 0;
        Check(registry.create(config, backend, &handle) == ECHIDNA_RESULT_OK,
              "pcm16 tolerance create");

        // True-neutral first: bit-exact.
        Check(registry.update(handle, kPassThroughPreset, std::strlen(kPassThroughPreset), 1,
                              backend) == ECHIDNA_RESULT_OK,
              "pcm16 neutral publish");
        std::array<int16_t, 8> nin{0, 1000, -2000, 16000, -16000, 32767, -32768, 12345};
        std::array<int16_t, 8> nout;
        nout.fill(4242);
        bool nmutated = true;
        Check(registry.process(handle, nin.data(), nout.data(), nin.size(),
                               ECHIDNA_PCM_FORMAT_SIGNED_16, false, &nmutated) ==
                      ECHIDNA_RESULT_OK &&
                  !nmutated && nout == nin,
              "pcm16 true-neutral is bit-exact");

        // Near-neutral 0 dB EQ: forces the real float path + int16 re-encode. A
        // 300 Hz sine at -6 dBFS; assert every sample is within 2 int16 LSB.
        Check(registry.update(handle, kEqZeroPreset, std::strlen(kEqZeroPreset), 2, backend) ==
                  ECHIDNA_RESULT_OK,
              "pcm16 eq0 publish");
        constexpr size_t frames = 480;
        std::vector<int16_t> in(frames);
        for (size_t i = 0; i < frames; ++i)
        {
            const double s = 0.5 * std::sin(2.0 * 3.14159265358979323846 * 300.0 *
                                            static_cast<double>(i) / 48000.0);
            in[i] = static_cast<int16_t>(std::lround(s * 32767.0));
        }
        std::vector<int16_t> out(frames, 0);
        Check(registry.process(handle, in.data(), out.data(),
                               static_cast<uint32_t>(frames),
                               ECHIDNA_PCM_FORMAT_SIGNED_16, false) == ECHIDNA_RESULT_OK,
              "pcm16 eq0 process");
        int max_abs_diff = 0;
        for (size_t i = 0; i < frames; ++i)
        {
            const int diff = std::abs(static_cast<int>(out[i]) - static_cast<int>(in[i]));
            max_abs_diff = std::max(max_abs_diff, diff);
        }
        Check(max_abs_diff <= 2,
              "pcm16 near-neutral stays within 2 LSB quantization tolerance");
        Check(registry.destroy(handle) == ECHIDNA_RESULT_OK, "pcm16 tolerance destroy");
    }

    // A bypass call between two real processing blocks must be transparent AND
    // must not advance or reset the engine's unrelated (stateful) effect state.
    // Proven by comparing a stream that takes a bypass call mid-sequence against
    // a reference stream that does not: their post-sequence outputs must match.
    void TestBypassTransparencyAndStatePreservation()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 256);
        echidna_stream_handle_t probe = 0;
        echidna_stream_handle_t reference = 0;
        Check(registry.create(config, backend, &probe) == ECHIDNA_RESULT_OK &&
                  registry.create(config, backend, &reference) == ECHIDNA_RESULT_OK,
              "bypass-state create");
        Check(registry.update(probe, kEqStatefulPreset, std::strlen(kEqStatefulPreset), 1,
                              backend) == ECHIDNA_RESULT_OK &&
                  registry.update(reference, kEqStatefulPreset, std::strlen(kEqStatefulPreset),
                                  1, backend) == ECHIDNA_RESULT_OK,
              "bypass-state stateful publish");

        constexpr size_t frames = 128;
        const auto make_block = [](float phase)
        {
            std::array<float, frames> b{};
            for (size_t i = 0; i < frames; ++i)
            {
                b[i] = 0.4f * std::sin(2.0f * 3.14159265f * 440.0f *
                                       (static_cast<float>(i) + phase) / 48000.0f);
            }
            return b;
        };
        const auto block1 = make_block(0.0f);
        const auto block2 = make_block(static_cast<float>(frames));

        std::array<float, frames> probe_out{};
        std::array<float, frames> ref_out{};
        std::array<float, frames> bypass_out{};

        // Reference: block1 (advance state), then block2.
        Check(registry.process(reference, block1.data(), ref_out.data(), frames,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
              "reference block1");
        Check(registry.process(reference, block2.data(), ref_out.data(), frames,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
              "reference block2");

        // Probe: block1 (advance state), a globally-bypassed block, then block2.
        Check(registry.process(probe, block1.data(), probe_out.data(), frames,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
              "probe block1");
        bool bypass_mutated = true;
        bool bypassed = false;
        auto bypass_in = make_block(97.0f);
        Check(registry.process(probe, bypass_in.data(), bypass_out.data(), frames,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, true, &bypass_mutated,
                               &bypassed) == ECHIDNA_RESULT_OK,
              "probe bypass block");
        Check(!bypass_mutated && bypassed &&
                  std::memcmp(bypass_out.data(), bypass_in.data(), sizeof(bypass_in)) == 0,
              "bypass is transparent (bit-exact passthrough, bypassed=true)");
        Check(registry.process(probe, block2.data(), probe_out.data(), frames,
                               ECHIDNA_PCM_FORMAT_FLOAT_32, false) == ECHIDNA_RESULT_OK,
              "probe block2");

        Check(std::memcmp(probe_out.data(), ref_out.data(), sizeof(ref_out)) == 0,
              "bypass call must not advance or reset unrelated filter state");
        Check(registry.destroy(probe) == ECHIDNA_RESULT_OK &&
                  registry.destroy(reference) == ECHIDNA_RESULT_OK,
              "bypass-state destroy");
    }

    // Boundary / canary coverage: single frame, max frames, full-scale DC,
    // alternating full-scale, impulse, and out-of-range ("clipped") float input,
    // each surrounded by guard samples that must remain untouched. Plus randomized
    // block-boundary sizes to catch off-by-one handling of odd frame counts.
    void TestBoundaryAndCanaries()
    {
        echidna::dsp_runtime::StreamHandleRegistry registry;
        const auto backend = RealBackend();
        constexpr uint32_t kMaxFrames = 256;

        // ---- float boundary/canary ----
        {
            const auto config = Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, kMaxFrames);
            echidna_stream_handle_t neutral = 0;
            echidna_stream_handle_t gain = 0;
            Check(registry.create(config, backend, &neutral) == ECHIDNA_RESULT_OK &&
                      registry.create(config, backend, &gain) == ECHIDNA_RESULT_OK,
                  "float boundary create");
            Check(registry.update(neutral, kPassThroughPreset,
                                  std::strlen(kPassThroughPreset), 1, backend) ==
                          ECHIDNA_RESULT_OK &&
                      registry.update(gain, kGainPreset, std::strlen(kGainPreset), 1,
                                      backend) == ECHIDNA_RESULT_OK,
                  "float boundary publish");

            constexpr float kInGuard = 314159.0f;
            constexpr float kOutGuard = -271828.0f;
            const auto run = [&](echidna_stream_handle_t handle,
                                 const std::vector<float> &payload,
                                 const char *what)
            {
                const size_t n = payload.size();
                std::vector<float> in(n + 2, kInGuard);
                std::vector<float> out(n + 2, kOutGuard);
                std::copy(payload.begin(), payload.end(), in.begin() + 1);
                const echidna_result_t r =
                    registry.process(handle, in.data() + 1, out.data() + 1,
                                     static_cast<uint32_t>(n),
                                     ECHIDNA_PCM_FORMAT_FLOAT_32, false);
                Check(r == ECHIDNA_RESULT_OK, what);
                Check(in.front() == kInGuard && in.back() == kInGuard,
                      "float input canaries intact");
                Check(out.front() == kOutGuard && out.back() == kOutGuard,
                      "float output canaries intact");
                for (size_t i = 0; i < n; ++i)
                {
                    Check(std::isfinite(out[i + 1]), "float boundary output finite");
                }
            };

            run(neutral, std::vector<float>(1, 0.5f), "float single frame");
            run(gain, std::vector<float>(1, 0.5f), "float single frame gain");
            run(neutral, std::vector<float>(kMaxFrames, 1.0f), "float max frames DC +1");
            run(neutral, std::vector<float>(kMaxFrames, -1.0f), "float max frames DC -1");

            std::vector<float> alternating(kMaxFrames);
            for (size_t i = 0; i < alternating.size(); ++i)
            {
                alternating[i] = (i % 2 == 0) ? 1.0f : -1.0f;
            }
            run(neutral, alternating, "float alternating full-scale");
            run(gain, alternating, "float alternating full-scale gain");

            std::vector<float> impulse(kMaxFrames, 0.0f);
            impulse[kMaxFrames / 2] = 1.0f;
            run(neutral, impulse, "float impulse");

            // Out-of-range ("clipped") but finite input: must pass the isfinite
            // guard, stay finite, and never corrupt neighbours.
            std::vector<float> clipped(kMaxFrames);
            for (size_t i = 0; i < clipped.size(); ++i)
            {
                clipped[i] = (i % 2 == 0) ? 3.5f : -4.25f;
            }
            run(neutral, clipped, "float clipped neutral");
            run(gain, clipped, "float clipped gain");

            // Randomized block-boundary sizes with deterministic LCG input.
            uint32_t rng = 0xC0FFEEu;
            const auto next = [&]
            {
                rng = rng * 1664525u + 1013904223u;
                return rng;
            };
            for (int iter = 0; iter < 64; ++iter)
            {
                const uint32_t n = 1 + (next() % kMaxFrames);
                std::vector<float> payload(n);
                for (size_t i = 0; i < n; ++i)
                {
                    payload[i] = static_cast<float>(next() >> 8) / 8388608.0f - 1.0f;
                }
                run(gain, payload, "float randomized block size");
            }
            Check(registry.destroy(neutral) == ECHIDNA_RESULT_OK &&
                      registry.destroy(gain) == ECHIDNA_RESULT_OK,
                  "float boundary destroy");
        }

        // ---- pcm16 boundary/canary ----
        {
            const auto config = Config(44100, 1, ECHIDNA_PCM_FORMAT_SIGNED_16, kMaxFrames);
            echidna_stream_handle_t neutral = 0;
            Check(registry.create(config, backend, &neutral) == ECHIDNA_RESULT_OK,
                  "pcm16 boundary create");
            Check(registry.update(neutral, kPassThroughPreset,
                                  std::strlen(kPassThroughPreset), 1, backend) ==
                      ECHIDNA_RESULT_OK,
                  "pcm16 boundary publish");

            constexpr int16_t kInGuard = 0x5A5A;
            constexpr int16_t kOutGuard = 0x3C3C;
            const auto run = [&](const std::vector<int16_t> &payload, const char *what)
            {
                const size_t n = payload.size();
                std::vector<int16_t> in(n + 2, kInGuard);
                std::vector<int16_t> out(n + 2, kOutGuard);
                std::copy(payload.begin(), payload.end(), in.begin() + 1);
                Check(registry.process(neutral, in.data() + 1, out.data() + 1,
                                       static_cast<uint32_t>(n),
                                       ECHIDNA_PCM_FORMAT_SIGNED_16, false) ==
                          ECHIDNA_RESULT_OK,
                      what);
                Check(in.front() == kInGuard && in.back() == kInGuard,
                      "pcm16 input canaries intact");
                Check(out.front() == kOutGuard && out.back() == kOutGuard,
                      "pcm16 output canaries intact");
                // Neutral preset is bit-exact for pcm16.
                for (size_t i = 0; i < n; ++i)
                {
                    Check(out[i + 1] == payload[i], "pcm16 neutral boundary bit-exact");
                }
            };

            run(std::vector<int16_t>(1, 12345), "pcm16 single frame");
            run(std::vector<int16_t>(kMaxFrames, 32767), "pcm16 max frames full-scale +");
            run(std::vector<int16_t>(kMaxFrames, -32768), "pcm16 max frames full-scale -");
            std::vector<int16_t> alternating(kMaxFrames);
            for (size_t i = 0; i < alternating.size(); ++i)
            {
                alternating[i] = (i % 2 == 0) ? int16_t{32767} : int16_t{-32768};
            }
            run(alternating, "pcm16 alternating full-scale");
            std::vector<int16_t> impulse(kMaxFrames, 0);
            impulse[kMaxFrames / 2] = 32767;
            run(impulse, "pcm16 impulse");
            Check(registry.destroy(neutral) == ECHIDNA_RESULT_OK, "pcm16 boundary destroy");
        }
    }
} // namespace

void *operator new(std::size_t size)
{
    if (g_count_allocations.load(std::memory_order_relaxed))
    {
        g_allocations.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *memory = std::malloc(size))
    {
        return memory;
    }
    throw std::bad_alloc();
}

void *operator new[](std::size_t size)
{
    return ::operator new(size);
}

void operator delete(void *memory) noexcept { std::free(memory); }
void operator delete[](void *memory) noexcept { std::free(memory); }
void operator delete(void *memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void *memory, std::size_t) noexcept { std::free(memory); }

int main()
{
    TestMixedIndependentStreams();
    TestProfileGenerationAndLegacyIsolation();
    TestExactInPlaceAndOutOfPlaceMutationTruth();
    TestExhaustionAndDestroyRace();
    TestNoCallbackAllocationsAndGenerationExhaustion();
    TestNonFiniteInputRejectedFloat();
    TestNonFiniteOutputPreservesOriginal();
    TestProcessingFailurePreservesOriginal();
    TestNeutralTransparencyAndDenormals();
    TestPcm16NeutralQuantizationTolerance();
    TestBypassTransparencyAndStatePreservation();
    TestBoundaryAndCanaries();
    std::cout << "stream_handle_registry_test: PASS\n";
    return 0;
}
