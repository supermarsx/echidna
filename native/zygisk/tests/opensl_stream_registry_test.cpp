#include "hooks/opensl_stream_registry.h"

#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <new>

namespace
{
    std::atomic<uint64_t> gAllocations{0};
    std::atomic<bool> gTrackAllocations{false};
    int gFailures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++gFailures;
        }
    }

    struct FakeState
    {
        std::atomic<bool> active{false};
        std::atomic<bool> enabled{false};
        bool gain{false};
        uint32_t channels{0};
        uint32_t max_frames{0};
        uint32_t format{0};
    };

    std::array<FakeState, 256> gStates{};
    std::atomic<uint32_t> gNextHandle{1};
    std::atomic<uint32_t> gCreates{0};
    std::atomic<uint32_t> gUpdates{0};
    std::atomic<uint32_t> gDestroys{0};
    std::atomic<bool> gFailCreate{false};
    std::atomic<bool> gFailUpdate{false};

    void ResetFake()
    {
        for (auto &state : gStates)
        {
            state.active = false;
            state.enabled = false;
            state.gain = false;
            state.channels = 0;
            state.max_frames = 0;
            state.format = 0;
        }
        gNextHandle = 1;
        gCreates = 0;
        gUpdates = 0;
        gDestroys = 0;
        gFailCreate = false;
        gFailUpdate = false;
    }

    echidna_result_t FakeCreate(const echidna_stream_config_t *config,
                                echidna_stream_handle_t *handle)
    {
        if (!config || !handle || gFailCreate.load(std::memory_order_acquire))
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        const uint32_t token = gNextHandle.fetch_add(1, std::memory_order_relaxed);
        if (token >= gStates.size())
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        auto &state = gStates[token];
        state.channels = config->channel_count;
        state.max_frames = config->max_frames;
        state.format = config->format;
        state.active.store(true, std::memory_order_release);
        *handle = token;
        gCreates.fetch_add(1, std::memory_order_relaxed);
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t FakeProcess(echidna_stream_handle_t handle,
                                 const void *input,
                                 void *output,
                                 uint32_t frames,
                                 uint32_t format)
    {
        if (handle == 0 || handle >= gStates.size() || !input || !output)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        auto &state = gStates[handle];
        if (!state.active.load(std::memory_order_acquire) ||
            !state.enabled.load(std::memory_order_acquire) ||
            state.format != format || frames == 0 || frames > state.max_frames)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        const size_t samples = static_cast<size_t>(frames) * state.channels;
        if (format == ECHIDNA_PCM_FORMAT_SIGNED_16)
        {
            const auto *source = static_cast<const int16_t *>(input);
            auto *destination = static_cast<int16_t *>(output);
            for (size_t i = 0; i < samples; ++i)
            {
                destination[i] = state.gain
                                     ? static_cast<int16_t>(source[i] * 2)
                                     : source[i];
            }
        }
        else
        {
            const auto *source = static_cast<const float *>(input);
            auto *destination = static_cast<float *>(output);
            for (size_t i = 0; i < samples; ++i)
            {
                destination[i] = state.gain ? source[i] * 2.0f : source[i];
            }
        }
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t FakeUpdate(echidna_stream_handle_t handle,
                                const char *profile,
                                size_t length,
                                uint64_t)
    {
        gUpdates.fetch_add(1, std::memory_order_relaxed);
        if (handle == 0 || handle >= gStates.size() ||
            gFailUpdate.load(std::memory_order_acquire))
        {
            return ECHIDNA_RESULT_ERROR;
        }
        auto &state = gStates[handle];
        if (!profile && length == 0)
        {
            state.enabled.store(false, std::memory_order_release);
            return ECHIDNA_RESULT_OK;
        }
        const bool gain = length == 4 && profile[0] == 'g' && profile[1] == 'a' &&
                          profile[2] == 'i' && profile[3] == 'n';
        state.gain = gain;
        state.enabled.store(true, std::memory_order_release);
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t FakeDestroy(echidna_stream_handle_t handle)
    {
        if (handle == 0 || handle >= gStates.size())
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        gStates[handle].active.store(false, std::memory_order_release);
        gDestroys.fetch_add(1, std::memory_order_relaxed);
        return ECHIDNA_RESULT_OK;
    }

    echidna::hooks::OpenSlDspApi Api()
    {
        return {FakeCreate, FakeProcess, FakeUpdate, FakeDestroy};
    }

    echidna_stream_config_t Config(uint32_t channels, uint32_t format)
    {
        echidna_stream_config_t config{};
        config.struct_size = sizeof(config);
        config.sample_rate = 48000;
        config.channel_count = channels;
        config.max_frames = 256;
        config.format = format;
        return config;
    }

    void TestExhaustionPublicationAndNoCallbackAllocations()
    {
        ResetFake();
        echidna::hooks::OpenSlStreamRegistry registry;
        const auto api = Api();
        Check(registry.publishProfile(1, true, "gain", api),
              "initial profile must publish");
        for (size_t index = 0;
             index < echidna::hooks::OpenSlStreamRegistry::kMaxRecorders;
             ++index)
        {
            Check(registry.open(index + 1,
                                Config(1, ECHIDNA_PCM_FORMAT_SIGNED_16),
                                api),
                  "each bounded recorder slot must open");
        }
        Check(!registry.open(0xFFFF,
                             Config(2, ECHIDNA_PCM_FORMAT_FLOAT_32),
                             api),
              "true live recorder exhaustion must fail closed");
        Check(gCreates == echidna::hooks::OpenSlStreamRegistry::kMaxRecorders &&
                  gUpdates == echidna::hooks::OpenSlStreamRegistry::kMaxRecorders,
              "every active recorder must own and receive exactly one handle update");

        int16_t sample = 100;
        Check(registry.process(1, &sample, 1) ==
                      echidna::hooks::OpenSlProcessResult::kProcessed &&
                  sample == 200,
              "recorder handle must mutate the exact callback buffer");
        const uint64_t before = gAllocations.load(std::memory_order_relaxed);
        gTrackAllocations = true;
        bool processed = true;
        for (size_t iteration = 0; iteration < 1000; ++iteration)
        {
            sample = 100;
            processed = processed &&
                        registry.process(1, &sample, 1) ==
                            echidna::hooks::OpenSlProcessResult::kProcessed &&
                        sample == 200;
        }
        gTrackAllocations = false;
        Check(processed && gAllocations.load(std::memory_order_relaxed) == before,
              "OpenSL callback lookup/process must allocate no memory");

        Check(registry.publishProfile(2, true, "pass", api),
              "replacement profile must reach every active handle");
        Check(gUpdates ==
                  2 * echidna::hooks::OpenSlStreamRegistry::kMaxRecorders,
              "profile replacement must update all active handles transactionally");
        sample = 100;
        Check(registry.process(1, &sample, 1) ==
                      echidna::hooks::OpenSlProcessResult::kProcessed &&
                  sample == 100,
              "replacement profile must change actual output semantics");

        gFailUpdate = true;
        Check(!registry.publishProfile(3, true, "gain", api),
              "any handle update failure must reject publication");
        Check(registry.process(1, &sample, 1) ==
                  echidna::hooks::OpenSlProcessResult::kBypassed,
              "failed publication must leave global OpenSL admission closed");
        for (size_t index = 0;
             index < echidna::hooks::OpenSlStreamRegistry::kMaxRecorders;
             ++index)
        {
            registry.close(index + 1);
        }
        Check(gDestroys == echidna::hooks::OpenSlStreamRegistry::kMaxRecorders,
              "every recorder handle must be destroyed exactly once");
    }

    void TestCreateFailureAndStaleIdentity()
    {
        ResetFake();
        echidna::hooks::OpenSlStreamRegistry registry;
        const auto api = Api();
        Check(registry.publishProfile(1, true, "gain", api),
              "failure test profile must publish");
        gFailCreate = true;
        Check(!registry.open(0x1000,
                             Config(2, ECHIDNA_PCM_FORMAT_FLOAT_32),
                             api),
              "DSP creation failure must fail closed");
        float samples[2]{0.25f, -0.5f};
        Check(registry.process(0x1000, samples, 1) ==
                      echidna::hooks::OpenSlProcessResult::kUnavailable &&
                  samples[0] == 0.25f && samples[1] == -0.5f,
              "creation failure must never invoke a fallback or mutate PCM");
        registry.close(0x1000);
        Check(registry.process(0x1000, samples, 1) ==
                  echidna::hooks::OpenSlProcessResult::kUnavailable,
              "closed recorder identities must remain stale");
    }
} // namespace

void *operator new(std::size_t size)
{
    if (gTrackAllocations.load(std::memory_order_relaxed))
    {
        gAllocations.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *memory = std::malloc(size))
    {
        return memory;
    }
    throw std::bad_alloc();
}

void *operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void *memory) noexcept { std::free(memory); }
void operator delete[](void *memory) noexcept { std::free(memory); }
void operator delete(void *memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void *memory, std::size_t) noexcept { std::free(memory); }

int main()
{
    TestExhaustionPublicationAndNoCallbackAllocations();
    TestCreateFailureAndStaleIdentity();
    if (gFailures != 0)
    {
        std::fprintf(stderr,
                     "opensl_stream_registry_test: %d failure(s)\n",
                     gFailures);
        return 1;
    }
    std::fprintf(stderr, "opensl_stream_registry_test: all checks passed\n");
    return 0;
}
