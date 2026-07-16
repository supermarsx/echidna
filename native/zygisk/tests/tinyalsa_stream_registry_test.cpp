#include "hooks/tinyalsa_stream_registry.h"
#include "utils/telemetry_accumulator.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <string_view>
#include <thread>

namespace
{
    std::atomic<uint64_t> gAllocations{0};
    std::atomic<bool> gTrackAllocations{false};
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

namespace
{
    using namespace echidna::hooks;

    struct HandleState
    {
        bool active{false};
        bool admitted{false};
        uint32_t channels{0};
        uint32_t format{0};
        uint32_t max_frames{0};
        uint64_t publication{0};
    };

    std::array<HandleState, 128> gHandles{};
    std::atomic<uint32_t> gNextHandle{1};
    std::atomic<uint32_t> gCreates{0};
    std::atomic<uint32_t> gProcesses{0};
    std::atomic<uint32_t> gDestroys{0};
    std::atomic<uint32_t> gFailUpdateHandle{0};
    std::atomic<bool> gBlockProcess{false};
    std::atomic<bool> gProcessEntered{false};
    std::atomic<bool> gReleaseProcess{false};
    echidna::utils::TelemetryAccumulator gTelemetry;
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

    void Reset()
    {
        std::fill(gHandles.begin(), gHandles.end(), HandleState{});
        gNextHandle = 1;
        gCreates = 0;
        gProcesses = 0;
        gDestroys = 0;
        gFailUpdateHandle = 0;
        gBlockProcess = false;
        gProcessEntered = false;
        gReleaseProcess = false;
        (void)gTelemetry.take(echidna::utils::TelemetryRoute::kTinyAlsa);
    }

    echidna_result_t Create(const echidna_stream_config_t *config,
                            echidna_stream_handle_t *handle)
    {
        if (!config || !handle)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        const uint32_t token = gNextHandle.fetch_add(1);
        if (token >= gHandles.size())
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        gHandles[token] = {true,
                           false,
                           config->channel_count,
                           config->format,
                           config->max_frames,
                           0};
        *handle = token;
        gCreates.fetch_add(1);
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t Process(echidna_stream_handle_t handle,
                             const void *input,
                             void *output,
                             uint32_t frames,
                             uint32_t format)
    {
        gProcesses.fetch_add(1);
        if (handle == 0 || handle >= gHandles.size() || !input || !output)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        const auto &state = gHandles[handle];
        if (!state.active || !state.admitted || state.format != format ||
            frames == 0 || frames > state.max_frames)
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
        if (format == ECHIDNA_PCM_FORMAT_SIGNED_16)
        {
            auto *pcm = static_cast<int16_t *>(output);
            for (size_t index = 0; index < samples; ++index)
            {
                pcm[index] = static_cast<int16_t>(pcm[index] * 2);
            }
        }
        else
        {
            auto *pcm = static_cast<float *>(output);
            for (size_t index = 0; index < samples; ++index)
            {
                pcm[index] *= 2.0F;
            }
        }
        gTelemetry.recordBlock(echidna::utils::CurrentTelemetryRoute(),
                               frames,
                               echidna::utils::TelemetryBlockOutcome::kMutated);
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t Update(echidna_stream_handle_t handle,
                            const char *preset,
                            size_t length,
                            uint64_t publication)
    {
        if (handle == 0 || handle >= gHandles.size() ||
            !gHandles[handle].active || publication <= gHandles[handle].publication)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        if (gFailUpdateHandle.load() == handle && preset != nullptr)
        {
            return ECHIDNA_RESULT_ERROR;
        }
        auto &state = gHandles[handle];
        state.publication = publication;
        state.admitted = preset && std::string_view(preset, length) == "gain";
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t Destroy(echidna_stream_handle_t handle)
    {
        if (handle == 0 || handle >= gHandles.size() || !gHandles[handle].active)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        gHandles[handle].active = false;
        gDestroys.fetch_add(1);
        return ECHIDNA_RESULT_OK;
    }

    TinyAlsaDspApi Api()
    {
        return {Create, Process, Update, Destroy};
    }

    TinyAlsaPcmContract Contract(uint32_t channels,
                                 uint32_t format,
                                 uint32_t max_frames)
    {
        TinyAlsaPcmContract contract;
        contract.stream.struct_size = sizeof(contract.stream);
        contract.stream.sample_rate = 48000;
        contract.stream.channel_count = channels;
        contract.stream.max_frames = max_frames;
        contract.stream.format = format;
        contract.bytes_per_frame =
            channels * (format == ECHIDNA_PCM_FORMAT_SIGNED_16 ? 2U : 4U);
        return contract;
    }

    void TestMixedStreamsShortReadsAndExactMutation()
    {
        Reset();
        TinyAlsaStreamRegistry registry;
        const auto api = Api();
        CHECK(registry.publishProfile(1, true, "gain", api), "profile admits pcm streams");
        void *pcm16 = reinterpret_cast<void *>(uintptr_t{0x1000});
        void *float32 = reinterpret_cast<void *>(uintptr_t{0x2000});
        CHECK(registry.open(pcm16,
                            Contract(2, ECHIDNA_PCM_FORMAT_SIGNED_16, 8),
                            api),
              "PCM16 stream opens");
        CHECK(registry.open(float32,
                            Contract(1, ECHIDNA_PCM_FORMAT_FLOAT_32, 4),
                            api),
              "float stream opens independently");

        std::array<int16_t, 8> samples{100, -200, 300, -400, 500, -600, 700, -800};
        {
            echidna::utils::ScopedTelemetryRoute route(
                echidna::utils::TelemetryRoute::kTinyAlsa);
            CHECK(registry.processFrames(pcm16, samples.data(), 2) ==
                      TinyAlsaProcessResult::kProcessed,
                  "short read processes returned frames");
            CHECK(echidna::utils::CurrentTelemetryRoute() ==
                      echidna::utils::TelemetryRoute::kTinyAlsa,
                  "DSP call is attributed to tinyalsa telemetry route");
        }
        CHECK((samples == std::array<int16_t, 8>{200, -400, 600, -800,
                                                 500, -600, 700, -800}),
              "only returned PCM16 frames mutate exactly once");
        const auto first_telemetry =
            gTelemetry.take(echidna::utils::TelemetryRoute::kTinyAlsa);
        CHECK(first_telemetry.blocks == 1 && first_telemetry.frames == 2 &&
                  first_telemetry.mutations == 1,
              "tinyalsa mutation telemetry reports the exact returned frames");
        std::array<float, 4> floats{0.1F, -0.2F, 0.3F, -0.4F};
        uint32_t byte_frames = 0;
        CHECK(registry.framesForBytes(float32,
                                      static_cast<uint32_t>(sizeof(floats)),
                                      &byte_frames) &&
                  byte_frames == 4,
              "byte reads expose an exact telemetry frame count");
        CHECK(registry.processBytes(float32, floats.data(), sizeof(floats)) ==
                      TinyAlsaProcessResult::kProcessed &&
                  floats[0] == 0.2F && floats[3] == -0.8F,
              "simultaneous float pcm uses its own handle and layout");
        CHECK(gProcesses == 2, "each successful buffer enters DSP exactly once");

        const uint32_t before = gProcesses.load();
        CHECK(registry.processBytes(pcm16, samples.data(), 3) ==
                      TinyAlsaProcessResult::kProcessorError &&
                  registry.processFrames(float32, floats.data(), 5) ==
                      TinyAlsaProcessResult::kProcessorError &&
                  gProcesses.load() == before,
              "misaligned bytes and capacity overflow never enter DSP");
        registry.close(pcm16);
        registry.close(float32);
    }

    void TestProfileRevokeFailureAndRecovery()
    {
        Reset();
        TinyAlsaStreamRegistry registry;
        const auto api = Api();
        CHECK(registry.publishProfile(1, true, "gain", api), "initial profile");
        void *first = reinterpret_cast<void *>(uintptr_t{0x3000});
        void *second = reinterpret_cast<void *>(uintptr_t{0x4000});
        CHECK(registry.open(first, Contract(1, ECHIDNA_PCM_FORMAT_SIGNED_16, 8), api) &&
                  registry.open(second, Contract(1, ECHIDNA_PCM_FORMAT_SIGNED_16, 8), api),
              "two profile-bound handles open");
        CHECK(registry.publishProfile(2, false, {}, api), "explicit revoke publishes");
        int16_t sample = 100;
        CHECK(registry.processFrames(first, &sample, 1) ==
                      TinyAlsaProcessResult::kBypassed &&
                  sample == 100,
              "revoked route bypasses unchanged");
        CHECK(registry.publishProfile(3, true, "gain", api), "profile restores");

        gFailUpdateHandle = 2;
        CHECK(!registry.publishProfile(4, true, "gain", api),
              "one failed update rejects the transaction");
        CHECK(!gHandles[1].admitted && !gHandles[2].admitted &&
                  registry.processFrames(first, &sample, 1) ==
                      TinyAlsaProcessResult::kBypassed,
              "failed transaction revokes every handle before admission");
        gFailUpdateHandle = 0;
        CHECK(registry.publishProfile(5, true, "gain", api),
              "later generation recovers atomically");
        registry.close(first);
        registry.close(second);
    }

    void TestCloseRacePointerReuseAndNoAllocation()
    {
        Reset();
        TinyAlsaStreamRegistry registry;
        const auto api = Api();
        CHECK(registry.publishProfile(1, true, "gain", api), "race profile");
        void *pcm = reinterpret_cast<void *>(uintptr_t{0x5000});
        CHECK(registry.open(pcm, Contract(1, ECHIDNA_PCM_FORMAT_FLOAT_32, 8), api),
              "race pcm opens");
        float sample = 0.25F;
        gBlockProcess = true;
        std::atomic<bool> close_done{false};
        std::thread processor([&]()
                              { (void)registry.processFrames(pcm, &sample, 1); });
        while (!gProcessEntered.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::thread closer([&]()
                           {
            registry.close(pcm);
            close_done.store(true, std::memory_order_release); });
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        CHECK(!close_done.load(), "close waits for in-flight processing");
        gReleaseProcess = true;
        processor.join();
        closer.join();
        CHECK(gDestroys == 1, "close destroys exactly one handle");

        CHECK(registry.open(pcm, Contract(1, ECHIDNA_PCM_FORMAT_FLOAT_32, 8), api),
              "same pcm address can reopen with a fresh handle");
        const uint32_t destroys_before = gDestroys.load();
        CHECK(registry.open(pcm, Contract(2, ECHIDNA_PCM_FORMAT_SIGNED_16, 4), api),
              "pointer reuse retires stale state before replacement");
        CHECK(gDestroys.load() == destroys_before + 1,
              "pointer reuse destroys the stale handle once");

        std::array<int16_t, 2> pcm16{100, -100};
        const uint64_t allocations_before = gAllocations.load();
        gTrackAllocations = true;
        const auto result = registry.processFrames(pcm, pcm16.data(), 1);
        gTrackAllocations = false;
        CHECK(result == TinyAlsaProcessResult::kProcessed &&
                  pcm16[0] == 200 && pcm16[1] == -200,
              "reused pointer processes only its new PCM contract");
        CHECK(gAllocations.load() == allocations_before,
              "RT lookup and processing allocate no memory");
        registry.close(pcm);
    }

    void TestInvalidOpenNeverAllocates()
    {
        Reset();
        TinyAlsaStreamRegistry registry;
        auto invalid = Contract(2, ECHIDNA_PCM_FORMAT_SIGNED_16, 16385);
        CHECK(!registry.open(reinterpret_cast<void *>(uintptr_t{0x6000}), invalid, Api()) &&
                  gCreates == 0,
              "oversized contract fails before handle allocation");
        invalid = Contract(2, ECHIDNA_PCM_FORMAT_SIGNED_16, 8);
        invalid.bytes_per_frame = 3;
        CHECK(!registry.open(reinterpret_cast<void *>(uintptr_t{0x6000}), invalid, Api()) &&
                  gCreates == 0,
              "layout mismatch fails before handle allocation");
    }
} // namespace

int main()
{
    TestMixedStreamsShortReadsAndExactMutation();
    TestProfileRevokeFailureAndRecovery();
    TestCloseRacePointerReuseAndNoAllocation();
    TestInvalidOpenNeverAllocates();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "tinyalsa_stream_registry_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "tinyalsa_stream_registry_test: all checks passed\n");
    return 0;
}
