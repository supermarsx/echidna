#include "dsp/stream_handle_registry.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iostream>
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

    struct FakeEngine
    {
        uint32_t channels{0};
    };
    std::atomic<bool> g_fake_block{false};
    std::atomic<bool> g_fake_entered{false};
    std::atomic<bool> g_fake_release{false};

    ech_dsp_status_t FakeCreate(uint32_t,
                                uint32_t channels,
                                ech_dsp_quality_mode_t,
                                size_t,
                                const char *,
                                size_t,
                                ech_dsp_engine_t **engine)
    {
        *engine = reinterpret_cast<ech_dsp_engine_t *>(new FakeEngine{channels});
        return ECH_DSP_STATUS_OK;
    }

    ech_dsp_status_t FakeProcess(ech_dsp_engine_t *engine,
                                 const float *input,
                                 float *output,
                                 size_t frames)
    {
        const auto *fake = reinterpret_cast<const FakeEngine *>(engine);
        g_fake_entered = true;
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
    TestExhaustionAndDestroyRace();
    TestNoCallbackAllocationsAndGenerationExhaustion();
    std::cout << "stream_handle_registry_test: PASS\n";
    return 0;
}
