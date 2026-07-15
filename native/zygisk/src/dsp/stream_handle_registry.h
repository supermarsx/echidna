#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "echidna/dsp/api.h"
#include "echidna_api.h"

namespace echidna::dsp_runtime
{

    struct StreamDspBackend
    {
        using CreateFn = ech_dsp_status_t (*)(uint32_t,
                                              uint32_t,
                                              ech_dsp_quality_mode_t,
                                              size_t,
                                              const char *,
                                              size_t,
                                              ech_dsp_engine_t **);
        using ProcessFn = ech_dsp_status_t (*)(ech_dsp_engine_t *,
                                               const float *,
                                               float *,
                                               size_t);
        using DestroyFn = void (*)(ech_dsp_engine_t *);

        CreateFn create{nullptr};
        ProcessFn process{nullptr};
        DestroyFn destroy{nullptr};

        bool complete() const { return create && process && destroy; }
    };

    class StreamHandleRegistry
    {
    public:
        static constexpr size_t kMaxStreams = 64;
        static constexpr uint32_t kMaxHandleGeneration = 0x03FFFFFFU;

        echidna_result_t create(const echidna_stream_config_t &config,
                                const StreamDspBackend &backend,
                                echidna_stream_handle_t *handle);
        echidna_result_t process(echidna_stream_handle_t handle,
                                 const void *input,
                                 void *output,
                                 uint32_t frames,
                                 uint32_t format,
                                 bool globally_bypassed,
                                 bool *mutated = nullptr,
                                 bool *bypassed = nullptr);
        echidna_result_t update(echidna_stream_handle_t handle,
                                const char *profile_json,
                                size_t length,
                                uint64_t profile_generation,
                                const StreamDspBackend &backend);
        echidna_result_t destroy(echidna_stream_handle_t handle);

#if defined(ECHIDNA_STREAM_REGISTRY_TESTING)
        bool setGenerationForTesting(size_t slot, uint32_t generation);
#endif

    private:
        static constexpr uint32_t kActiveMask = 0x80000000U;
        static constexpr uint32_t kExhaustedMask = 0x40000000U;
        static constexpr uint32_t kInFlightMask = 0x3FFFFFFFU;
        static constexpr uint32_t kCallbackProcessing = 1U;
        static constexpr uint32_t kCallbackMaintenance = 2U;
        static constexpr unsigned kSlotBits = 6;

        struct EngineState
        {
            ech_dsp_engine_t *engine{nullptr};
            StreamDspBackend::ProcessFn process{nullptr};
            StreamDspBackend::DestroyFn destroy{nullptr};
            uint32_t sample_rate{0};
            uint32_t channels{0};
            uint32_t max_frames{0};
            uint32_t format{0};
            std::vector<float> input_scratch;
            std::vector<float> output_scratch;

            ~EngineState();
        };

        struct alignas(64) Slot
        {
            std::atomic<uint32_t> usage{0};
            std::atomic<uint32_t> generation{0};
            // A single CAS-owned word makes callback entry and state
            // replacement mutually exclusive without a callback-side lock.
            std::atomic<uint32_t> callback_gate{0};
            std::atomic<uint32_t> policy_enabled{1};
            EngineState *state{nullptr};
            uint64_t profile_generation{0};
            bool allocated{false};
        };

        class MaintenanceGuard
        {
        public:
            explicit MaintenanceGuard(StreamHandleRegistry &registry);
            ~MaintenanceGuard();

        private:
            StreamHandleRegistry &registry_;
        };

        static bool validConfig(const echidna_stream_config_t &config);
        static bool decodeHandle(echidna_stream_handle_t handle,
                                 size_t *slot,
                                 uint32_t *generation);
        static echidna_stream_handle_t makeHandle(size_t slot, uint32_t generation);
        static EngineState *buildState(const echidna_stream_config_t &config,
                                       const char *profile_json,
                                       size_t length,
                                       const StreamDspBackend &backend,
                                       echidna_result_t *result);
        bool acquire(Slot &slot, uint32_t generation);
        static void release(Slot &slot);
        static void copyBypass(const EngineState &state,
                               const void *input,
                               void *output,
                               uint32_t frames);
        void lockMaintenance();
        void unlockMaintenance();

        std::atomic<uint32_t> maintenance_gate_{0};
        std::array<Slot, kMaxStreams> slots_{};
    };

} // namespace echidna::dsp_runtime
