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
    std::atomic<uint32_t> gProcessCalls{0};
    std::atomic<bool> gFailCreate{false};
    std::atomic<bool> gFailUpdate{false};
    std::atomic<bool> gBlockProcess{false};
    std::atomic<bool> gProcessEntered{false};
    std::atomic<bool> gReleaseProcess{false};

    // Application-callback gates and recording state for the dispatchCallback
    // route. The recording callback never allocates so it can run on the RT
    // allocation-tracking path.
    constexpr int kCallbackReturnSentinel = 4242;
    std::atomic<void *> gCallbackBuffer{nullptr};
    std::atomic<void *> gCallbackUserData{nullptr};
    std::atomic<int32_t> gCallbackFrames{0};
    std::atomic<uint32_t> gCallbackCalls{0};
    std::atomic<bool> gBlockCallback{false};
    std::atomic<bool> gCallbackEntered{false};
    std::atomic<bool> gReleaseCallback{false};

    void ResetCallbackRecord()
    {
        gCallbackBuffer.store(nullptr, std::memory_order_release);
        gCallbackUserData.store(nullptr, std::memory_order_release);
        gCallbackFrames.store(0, std::memory_order_release);
        gCallbackCalls.store(0, std::memory_order_release);
    }

    int RecordingCallback(void *, void *user_data, void *audio_data, int32_t frames)
    {
        gCallbackBuffer.store(audio_data, std::memory_order_release);
        gCallbackUserData.store(user_data, std::memory_order_release);
        gCallbackFrames.store(frames, std::memory_order_release);
        gCallbackCalls.fetch_add(1, std::memory_order_relaxed);
        return kCallbackReturnSentinel;
    }

    int BlockingCallback(void *, void *user_data, void *audio_data, int32_t frames)
    {
        gCallbackBuffer.store(audio_data, std::memory_order_release);
        gCallbackUserData.store(user_data, std::memory_order_release);
        gCallbackFrames.store(frames, std::memory_order_release);
        gCallbackCalls.fetch_add(1, std::memory_order_relaxed);
        gCallbackEntered.store(true, std::memory_order_release);
        while (gBlockCallback.load(std::memory_order_acquire) &&
               !gReleaseCallback.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        return kCallbackReturnSentinel;
    }

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
        gProcessCalls = 0;
        gFailCreate = false;
        gFailUpdate = false;
        gBlockProcess = false;
        gProcessEntered = false;
        gReleaseProcess = false;
        gBlockCallback = false;
        gCallbackEntered = false;
        gReleaseCallback = false;
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
        gProcessCalls.fetch_add(1, std::memory_order_relaxed);
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
        gFailUpdate = false;
        CHECK(registry.publishProfile(13, true, "gain", api),
              "later valid publication recovers after transient update failure");
        sample = 0.25f;
        CHECK(registry.process(stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               &sample,
                               1) == echidna::hooks::AAudioProcessResult::kProcessed &&
                  sample == 0.5f,
              "recovered publication reopens processing");
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
        gFailCreate = false;
        CHECK(create_failure.publishProfile(2, true, "gain", api),
              "later profile publication retries a zero-handle stream");
        CHECK(create_failure.process(failed_stream,
                                     echidna::hooks::AAudioProcessOwner::kRead,
                                     &untouched,
                                     1) == echidna::hooks::AAudioProcessResult::kProcessed &&
                  untouched == 246,
              "transient create failure recovers without reopening the stream");
        create_failure.close(failed_stream);

        ResetFake();
        echidna::hooks::AAudioStreamRegistry initial_update_failure;
        CHECK(initial_update_failure.publishProfile(1, true, "gain", api),
              "update-failure policy");
        gFailUpdate = true;
        void *update_failed_stream = reinterpret_cast<void *>(uintptr_t{0x3200});
        CHECK(!initial_update_failure.open(
                  update_failed_stream,
                  Config(48000, 1, ECHIDNA_PCM_FORMAT_SIGNED_16),
                  echidna::hooks::AAudioProcessOwner::kRead,
                  api),
              "initial handle profile failure publishes no broken handle");
        gFailUpdate = false;
        CHECK(initial_update_failure.publishProfile(2, true, "gain", api),
              "later generation recreates a handle after initial update failure");
        untouched = 123;
        CHECK(initial_update_failure.process(
                  update_failed_stream,
                  echidna::hooks::AAudioProcessOwner::kRead,
                  &untouched,
                  1) == echidna::hooks::AAudioProcessResult::kProcessed &&
                  untouched == 246,
              "recreated handle processes the original stream identity");
        initial_update_failure.close(update_failed_stream);
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

    void TestActualCapacityBoundsProcessingAndAllocation()
    {
        ResetFake();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        CHECK(registry.publishProfile(1, true, "gain", api),
              "capacity policy publishes");

        void *read_stream = reinterpret_cast<void *>(uintptr_t{0x5100});
        CHECK(registry.open(read_stream,
                            Config(48000,
                                   2,
                                   ECHIDNA_PCM_FORMAT_SIGNED_16,
                                   4),
                            echidna::hooks::AAudioProcessOwner::kRead,
                            api),
              "actual four-frame read capacity allocates one handle");
        CHECK(gHandles[1].max_frames == 4,
              "handle memory sizing receives actual capacity frames");
        std::array<int16_t, 8> read_pcm{100, 200, 300, 400, 500, 600, 700, 800};
        CHECK(registry.process(read_stream,
                               echidna::hooks::AAudioProcessOwner::kRead,
                               read_pcm.data(),
                               2) == echidna::hooks::AAudioProcessResult::kProcessed,
              "partial read below capacity is processed");
        CHECK((read_pcm ==
               std::array<int16_t, 8>{200, 400, 600, 800, 500, 600, 700, 800}),
              "partial read mutates only returned frames");
        registry.close(read_stream);

        void *callback_stream = reinterpret_cast<void *>(uintptr_t{0x5200});
        CHECK(registry.open(callback_stream,
                            Config(48000,
                                   1,
                                   ECHIDNA_PCM_FORMAT_FLOAT_32,
                                   4),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "callback stream uses its exact capacity");
        const uint32_t calls_before = gProcessCalls.load(std::memory_order_acquire);
        const uint64_t allocations_before =
            gAllocationCount.load(std::memory_order_acquire);
        std::array<float, 5> callback_pcm{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        const auto original = callback_pcm;
        gTrackAllocations.store(true, std::memory_order_release);
        const auto overflow_result = registry.process(
            callback_stream,
            echidna::hooks::AAudioProcessOwner::kCallback,
            callback_pcm.data(),
            5);
        gTrackAllocations.store(false, std::memory_order_release);
        CHECK(overflow_result == echidna::hooks::AAudioProcessResult::kProcessorError,
              "callback larger than recorded capacity fails closed");
        CHECK(gProcessCalls.load(std::memory_order_acquire) == calls_before &&
                  callback_pcm == original,
              "callback overflow never enters DSP or mutates audio");
        CHECK(gAllocationCount.load(std::memory_order_acquire) == allocations_before,
              "callback overflow handling performs no allocation");
        registry.close(callback_stream);

        ResetFake();
        echidna::hooks::AAudioStreamRegistry invalid;
        CHECK(!invalid.open(reinterpret_cast<void *>(uintptr_t{0x5300}),
                            Config(48000,
                                   2,
                                   ECHIDNA_PCM_FORMAT_SIGNED_16,
                                   16385),
                            echidna::hooks::AAudioProcessOwner::kRead,
                            api) &&
                  gCreates.load(std::memory_order_acquire) == 0,
              "oversized capacity fails before allocating a handle");
    }

    void TestCallbackDispatchOwnershipTransparencyAndFailOpen()
    {
        ResetFake();
        ResetCallbackRecord();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        CHECK(registry.publishProfile(1, true, "gain", api),
              "callback dispatch gain policy");
        void *stream = reinterpret_cast<void *>(uintptr_t{0x6000});
        CHECK(registry.open(stream,
                            Config(48000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32, 8),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "callback stream opens with scratch");

        std::array<float, 8> platform{
            0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.7f, -0.8f};
        const auto original = platform;
        echidna::hooks::AAudioProcessResult result =
            echidna::hooks::AAudioProcessResult::kUnavailable;
        void *const user_data = reinterpret_cast<void *>(uintptr_t{0xABCD});
        const int returned = registry.dispatchCallback(
            stream, platform.data(), 4, &RecordingCallback, user_data, &result);

        CHECK(result == echidna::hooks::AAudioProcessResult::kProcessed,
              "callback dispatch processes a callback-owned stream");
        CHECK(returned == kCallbackReturnSentinel,
              "dispatch preserves the application callback return value");
        CHECK(std::memcmp(platform.data(), original.data(), sizeof(platform)) == 0,
              "platform input buffer is never written by the callback route");
        void *handed = gCallbackBuffer.load(std::memory_order_acquire);
        CHECK(handed != nullptr && handed != platform.data(),
              "application callback receives scratch, not the platform input");
        CHECK(gCallbackUserData.load(std::memory_order_acquire) == user_data,
              "dispatch forwards the application user_data");
        CHECK(gCallbackFrames.load(std::memory_order_acquire) == 4,
              "dispatch forwards the frame count");
        const auto *scratch = static_cast<const float *>(handed);
        bool scratch_ok = true;
        for (size_t i = 0; i < platform.size(); ++i)
        {
            scratch_ok = scratch_ok && scratch[i] == original[i] * 2.0f;
        }
        CHECK(scratch_ok, "scratch holds the gain-transformed samples handed to the app");

        // Fail open: after an admission revoke the untouched platform input is
        // handed straight to the application and the return value is preserved.
        CHECK(registry.publishProfile(2, false, {}, api),
              "callback dispatch admission revoke");
        ResetCallbackRecord();
        platform = original;
        result = echidna::hooks::AAudioProcessResult::kProcessed;
        const int bypass_return = registry.dispatchCallback(
            stream, platform.data(), 4, &RecordingCallback, nullptr, &result);
        CHECK(result == echidna::hooks::AAudioProcessResult::kBypassed,
              "revoked admission bypasses the callback route");
        CHECK(bypass_return == kCallbackReturnSentinel,
              "fail-open dispatch still returns the application value");
        CHECK(std::memcmp(platform.data(), original.data(), sizeof(platform)) == 0,
              "fail-open leaves the platform input unchanged");
        CHECK(gCallbackBuffer.load(std::memory_order_acquire) == platform.data(),
              "fail-open hands the untouched platform input to the application");
        registry.close(stream);
    }

    void TestCallbackDispatchHoldsScratchAcrossAppCallback()
    {
        ResetFake();
        ResetCallbackRecord();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        CHECK(registry.publishProfile(1, true, "gain", api), "lifetime policy");
        void *stream = reinterpret_cast<void *>(uintptr_t{0x6100});
        CHECK(registry.open(stream,
                            Config(48000, 1, ECHIDNA_PCM_FORMAT_FLOAT_32, 8),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "lifetime stream opens");
        gBlockCallback = true;
        std::array<float, 4> platform{0.1f, 0.2f, 0.3f, 0.4f};
        std::atomic<bool> close_done{false};
        std::atomic<int> dispatch_return{0};
        std::thread dispatcher(
            [&]()
            {
                echidna::hooks::AAudioProcessResult result =
                    echidna::hooks::AAudioProcessResult::kUnavailable;
                dispatch_return.store(
                    registry.dispatchCallback(
                        stream, platform.data(), 4, &BlockingCallback, nullptr, &result),
                    std::memory_order_release);
            });
        while (!gCallbackEntered.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::thread closer(
            [&]()
            {
                registry.close(stream);
                close_done.store(true, std::memory_order_release);
            });
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        CHECK(!close_done.load(std::memory_order_acquire),
              "close waits for the in-flight application callback (scratch stays valid)");
        void *handed = gCallbackBuffer.load(std::memory_order_acquire);
        CHECK(handed != nullptr && handed != platform.data(),
              "blocking callback is reading the scratch, not the platform input");
        gReleaseCallback = true;
        dispatcher.join();
        closer.join();
        CHECK(close_done.load(std::memory_order_acquire) && gDestroys == 1,
              "close completes once the callback returns and destroys once");
        CHECK(dispatch_return.load(std::memory_order_acquire) == kCallbackReturnSentinel,
              "blocking dispatch returns the application value");
    }

    void TestCallbackDispatchNoRealtimeAllocation()
    {
        ResetFake();
        ResetCallbackRecord();
        const auto api = Api();
        echidna::hooks::AAudioStreamRegistry registry;
        CHECK(registry.publishProfile(1, true, "gain", api), "callback alloc policy");
        void *stream = reinterpret_cast<void *>(uintptr_t{0x6200});
        // Open BEFORE tracking so the one-time scratch allocation is not counted.
        CHECK(registry.open(stream,
                            Config(48000, 2, ECHIDNA_PCM_FORMAT_FLOAT_32, 8),
                            echidna::hooks::AAudioProcessOwner::kCallback,
                            api),
              "callback alloc stream opens");
        std::array<float, 8> platform{
            0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.7f, -0.8f};
        echidna::hooks::AAudioProcessResult result =
            echidna::hooks::AAudioProcessResult::kUnavailable;
        (void)registry.dispatchCallback(
            stream, platform.data(), 4, &RecordingCallback, nullptr, &result);
        CHECK(result == echidna::hooks::AAudioProcessResult::kProcessed,
              "warmup dispatch processes before tracking");

        const uint64_t before = gAllocationCount.load(std::memory_order_relaxed);
        gTrackAllocations.store(true, std::memory_order_release);
        int returned = 0;
        for (size_t i = 0; i < 1000; ++i)
        {
            returned = registry.dispatchCallback(
                stream, platform.data(), 4, &RecordingCallback, nullptr, &result);
        }
        gTrackAllocations.store(false, std::memory_order_release);
        CHECK(returned == kCallbackReturnSentinel &&
                  result == echidna::hooks::AAudioProcessResult::kProcessed,
              "tracked dispatch keeps processing");
        CHECK(gAllocationCount.load(std::memory_order_relaxed) == before,
              "callback dispatch RT path allocates no memory");
        registry.close(stream);
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
    TestActualCapacityBoundsProcessingAndAllocation();
    TestCallbackDispatchOwnershipTransparencyAndFailOpen();
    TestCallbackDispatchHoldsScratchAcrossAppCallback();
    TestCallbackDispatchNoRealtimeAllocation();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "aaudio_stream_registry_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "aaudio_stream_registry_test: all checks passed\n");
    return 0;
}
