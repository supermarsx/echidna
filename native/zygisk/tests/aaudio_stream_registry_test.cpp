#include "hooks/aaudio_stream_registry.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string_view>
#include <thread>

namespace
{
    std::atomic<uint64_t> gAllocationCount{0};
    std::atomic<bool> gTrackAllocations{false};

    int gFailures = 0;
    void Check(bool condition, const char *expression, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [line %d] %s\n", expression, line, message);
            ++gFailures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    struct FakeHandleState
    {
        std::atomic<uint32_t> active{0};
        std::atomic<uint32_t> enabled{0};
        std::atomic<uint32_t> gain{0};
        uint32_t format{0};
        uint32_t channels{0};
        uint32_t max_frames{0};
    };

    std::array<FakeHandleState, 256> gHandles{};
    std::atomic<uint32_t> gNextHandle{1};
    std::atomic<uint32_t> gCreates{0};
    std::atomic<uint32_t> gDestroys{0};
    std::atomic<bool> gFailCreate{false};
    std::atomic<bool> gFailUpdate{false};
    std::atomic<bool> gBlockProcess{false};
    std::atomic<bool> gProcessEntered{false};
    std::atomic<bool> gReleaseProcess{false};

    void ResetFake()
    {
        for (auto &state : gHandles)
        {
            state.active = 0;
            state.enabled = 0;
            state.gain = 0;
            state.format = 0;
            state.channels = 0;
            state.max_frames = 0;
        }
        gNextHandle = 1;
        gCreates = 0;
        gDestroys = 0;
        gFailCreate = false;
        gFailUpdate = false;
        gBlockProcess = false;
        gProcessEntered = false;
        gReleaseProcess = false;
    }

    echidna_result_t FakeCreate(const echidna_stream_config_t *config,
                                echidna_stream_handle_t *handle)
    {
        if (!config || !handle || gFailCreate.load(std::memory_order_acquire))
        {
            return ECHIDNA_RESULT_ERROR;
        }
        const uint32_t token = gNextHandle.fetch_add(1, std::memory_order_relaxed);
        if (token >= gHandles.size())
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        auto &state = gHandles[token];
        state.format = config->format;
        state.channels = config->channel_count;
        state.max_frames = config->max_frames;
        state.active.store(1, std::memory_order_release);
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
        if (handle == 0 || handle >= gHandles.size() || !input || !output)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        auto &state = gHandles[handle];
        if (state.active.load(std::memory_order_acquire) == 0 ||
            state.enabled.load(std::memory_order_acquire) == 0 ||
            state.format != format || frames == 0 || frames > state.max_frames)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        gProcessEntered.store(true, std::memory_order_release);
        while (gBlockProcess.load(std::memory_order_acquire) &&
               !gReleaseProcess.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        const size_t samples = static_cast<size_t>(frames) * state.channels;
        const bool gain = state.gain.load(std::memory_order_acquire) != 0;
        if (format == ECHIDNA_PCM_FORMAT_SIGNED_16)
        {
            const auto *source = static_cast<const int16_t *>(input);
            auto *destination = static_cast<int16_t *>(output);
            for (size_t i = 0; i < samples; ++i)
            {
                destination[i] = gain ? static_cast<int16_t>(source[i] * 2) : source[i];
            }
        }
        else
        {
            const auto *source = static_cast<const float *>(input);
            auto *destination = static_cast<float *>(output);
            for (size_t i = 0; i < samples; ++i)
            {
                destination[i] = gain ? source[i] * 2.0f : source[i];
            }
        }
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t FakeUpdate(echidna_stream_handle_t handle,
                                const char *profile,
                                size_t length,
                                uint64_t)
    {
        if (handle == 0 || handle >= gHandles.size() ||
            gFailUpdate.load(std::memory_order_acquire))
        {
            return ECHIDNA_RESULT_ERROR;
        }
        auto &state = gHandles[handle];
        if (state.active.load(std::memory_order_acquire) == 0)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        if (!profile && length == 0)
        {
            state.enabled.store(0, std::memory_order_release);
            return ECHIDNA_RESULT_OK;
        }
        const std::string_view preset(profile, length);
        state.gain.store(preset == "gain" ? 1U : 0U, std::memory_order_relaxed);
        state.enabled.store(1, std::memory_order_release);
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t FakeDestroy(echidna_stream_handle_t handle)
    {
        if (handle == 0 || handle >= gHandles.size())
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        gHandles[handle].active.store(0, std::memory_order_release);
        gDestroys.fetch_add(1, std::memory_order_relaxed);
        return ECHIDNA_RESULT_OK;
    }

    echidna::hooks::AAudioDspApi Api()
    {
        return {FakeCreate, FakeProcess, FakeUpdate, FakeDestroy};
    }

    echidna_stream_config_t Config(uint32_t rate, uint32_t channels, uint32_t format)
    {
        echidna_stream_config_t config{};
        config.struct_size = sizeof(config);
        config.sample_rate = rate;
        config.channel_count = channels;
        config.max_frames = 256;
        config.format = format;
        return config;
    }

    void TestMixedStreamsAndDeterministicOwnership()
    {
        ResetFake();
        echidna::hooks::AAudioStreamRegistry registry;
        const auto api = Api();
        CHECK(registry.publishProfile(1, true, "gain", api), "initial gain publication");
        void *callback_stream = reinterpret_cast<void *>(uintptr_t{0x1000});
        void *read_stream = reinterpret_cast<void *>(uintptr_t{0x2000});
        CHECK(registry.open(callback_stream,
                            Config(48000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "callback stream opens with a handle");
        CHECK(registry.open(read_stream,
                            Config(44100, 1, ECHIDNA_PCM_FORMAT_SIGNED_16),
                            echidna::hooks::AAudioProcessOwner::kRead,
                            api),
              "read stream opens with an independent handle");

        std::array<float, 8> floats{0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.7f, -0.8f};
        const auto original_floats = floats;
        CHECK(registry.process(callback_stream,
                               echidna::hooks::AAudioProcessOwner::kCallback,
                               floats.data(),
                               4) == echidna::hooks::AAudioProcessResult::kProcessed,
              "callback owner processes");
        for (size_t i = 0; i < floats.size(); ++i)
        {
            CHECK(floats[i] == original_floats[i] * 2.0f, "float mutation is exact");
        }
        const auto once_processed = floats;
        CHECK(registry.process(callback_stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               floats.data(),
                               4) == echidna::hooks::AAudioProcessResult::kNotOwner,
              "read path cannot process callback-owned stream");
        CHECK(std::memcmp(floats.data(), once_processed.data(), sizeof(floats)) == 0,
              "wrong owner cannot double-process a buffer");

        std::array<int16_t, 4> pcm{100, -200, 300, -400};
        CHECK(registry.process(read_stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               pcm.data(),
                               4) == echidna::hooks::AAudioProcessResult::kProcessed,
              "read owner processes");
        CHECK((pcm == std::array<int16_t, 4>{200, -400, 600, -800}),
              "PCM16 mutation is exact and independent");
        registry.close(callback_stream);
        registry.close(read_stream);
        CHECK(gDestroys == 2, "each stream destroys exactly one handle");
    }

    void TestProfileUpdateRevokeAndFailureBypass()
    {
        ResetFake();
        echidna::hooks::AAudioStreamRegistry registry;
        const auto api = Api();
        void *stream = reinterpret_cast<void *>(uintptr_t{0x3000});
        CHECK(registry.publishProfile(10, true, "gain", api), "gain publication");
        CHECK(registry.open(stream,
                            Config(96000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
                            echidna::hooks::AAudioProcessOwner::kRead,
                            api),
              "profiled stream open");
        float sample = 0.25f;
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kProcessed &&
                  sample == 0.5f,
              "gain profile applies");
        CHECK(registry.publishProfile(11, false, {}, api), "policy revoke publishes");
        sample = 0.25f;
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kBypassed &&
                  sample == 0.25f,
              "revoke immediately bypasses unchanged");
        CHECK(registry.publishProfile(11, true, "pass", api),
              "same snapshot generation may restore after disconnect revoke");
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kProcessed &&
                  sample == 0.25f,
              "replacement profile is active");

        gFailUpdate = true;
        CHECK(!registry.publishProfile(12, true, "gain", api),
              "failed replacement is rejected");
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kBypassed,
              "failed update leaves global AAudio admission closed");
        registry.close(stream);

        echidna::hooks::AAudioStreamRegistry create_failure;
        CHECK(create_failure.publishProfile(1, true, "gain", api), "failure registry policy");
        gFailCreate = true;
        void *failed_stream = reinterpret_cast<void *>(uintptr_t{0x3100});
        CHECK(!create_failure.open(failed_stream,
                                   Config(48000, 1, ECHIDNA_PCM_FORMAT_SIGNED_16),
                                   echidna::hooks::AAudioProcessOwner::kRead,
                                   api),
              "create error fails closed");
        int16_t untouched = 123;
        CHECK(create_failure.process(failed_stream,
                                     echidna::hooks::AAudioProcessOwner::kRead,
                                     &untouched,
                                     1) == echidna::hooks::AAudioProcessResult::kUnavailable &&
                  untouched == 123,
              "create failure never falls back or mutates");
        create_failure.close(failed_stream);
    }

    void TestCloseAndPublicationQuiesceProcessing()
    {
        ResetFake();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        void *stream = reinterpret_cast<void *>(uintptr_t{0x4000});
        CHECK(registry.publishProfile(1, true, "gain", api), "race policy");
        CHECK(registry.open(stream,
                            Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "race stream open");
        gBlockProcess = true;
        float sample = 0.25f;
        std::atomic<bool> close_done{false};
        std::thread processor([&]()
                              { (void)registry.process(stream,
                                                       echidna::hooks::AAudioProcessOwner::kCallback,
                                                       &sample,
                                                       1); });
        while (!gProcessEntered.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::thread closer([&]()
                           {
            registry.close(stream);
            close_done.store(true, std::memory_order_release); });
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        CHECK(!close_done.load(std::memory_order_acquire),
              "close waits for the in-flight callback before destroy");
        gReleaseProcess = true;
        processor.join();
        closer.join();
        CHECK(close_done && gDestroys == 1, "close quiesces and destroys once");
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kCallback,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kUnavailable,
              "closed stream identity is stale");

        ResetFake();
        echidna::hooks::AAudioStreamRegistry publication_registry;
        CHECK(publication_registry.publishProfile(1, true, "gain", api), "publish race policy");
        CHECK(publication_registry.open(stream,
                                        Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
                                        echidna::hooks::AAudioProcessOwner::kRead,
                                        api),
              "publish race open");
        gBlockProcess = true;
        gProcessEntered = false;
        gReleaseProcess = false;
        std::atomic<bool> publish_done{false};
        std::thread active([&]()
                           { (void)publication_registry.process(stream,
                                                                echidna::hooks::AAudioProcessOwner::kRead,
                                                                &sample,
                                                                1); });
        while (!gProcessEntered.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::thread publisher([&]()
                              {
            (void)publication_registry.publishProfile(2, true, "pass", api);
            publish_done.store(true, std::memory_order_release); });
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        CHECK(!publish_done, "profile publication waits off RT for active processing");
        gReleaseProcess = true;
        active.join();
        publisher.join();
        CHECK(publish_done, "publication completes after callback quiescence");
        publication_registry.close(stream);
    }

    void TestExhaustionAndNoRealtimeAllocations()
    {
        ResetFake();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        CHECK(registry.publishProfile(1, true, "pass", api), "exhaustion policy");
        for (size_t i = 0; i < echidna::hooks::AAudioStreamRegistry::kMaxStreams; ++i)
        {
            CHECK(registry.open(reinterpret_cast<void *>(uintptr_t{i + 1}),
                                Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
                                echidna::hooks::AAudioProcessOwner::kRead,
                                api),
                  "every bounded stream slot opens");
        }
        CHECK(!registry.open(reinterpret_cast<void *>(uintptr_t{0xFFFF}),
                             Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32),
                             echidna::hooks::AAudioProcessOwner::kRead,
                             api),
              "true live exhaustion fails closed");

        float sample = 0.25f;
        CHECK(registry.process(reinterpret_cast<void *>(uintptr_t{1}),
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kProcessed,
              "allocation test warmup");
        const uint64_t before = gAllocationCount.load(std::memory_order_relaxed);
        gTrackAllocations = true;
        bool all_processed = true;
        for (size_t i = 0; i < 1000; ++i)
        {
            all_processed = all_processed &&
                            registry.process(reinterpret_cast<void *>(uintptr_t{1}),
                                             echidna::hooks::AAudioProcessOwner::kRead,
                                             &sample,
                                             1) ==
                                echidna::hooks::AAudioProcessResult::kProcessed;
        }
        gTrackAllocations = false;
        CHECK(all_processed, "RT path remains available under churn");
        CHECK(gAllocationCount.load(std::memory_order_relaxed) == before,
              "AAudio lookup/process path allocates no memory");
        for (size_t i = 0; i < echidna::hooks::AAudioStreamRegistry::kMaxStreams; ++i)
        {
            registry.close(reinterpret_cast<void *>(uintptr_t{i + 1}));
        }
    }
} // namespace

void *operator new(std::size_t size)
{
    if (gTrackAllocations.load(std::memory_order_relaxed))
    {
        gAllocationCount.fetch_add(1, std::memory_order_relaxed);
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
    TestMixedStreamsAndDeterministicOwnership();
    TestProfileUpdateRevokeAndFailureBypass();
    TestCloseAndPublicationQuiesceProcessing();
    TestExhaustionAndNoRealtimeAllocations();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "aaudio_stream_registry_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "aaudio_stream_registry_test: all checks passed\n");
    return 0;
}
