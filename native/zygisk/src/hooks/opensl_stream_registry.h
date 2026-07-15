#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "echidna_api.h"

namespace echidna::hooks
{
    enum class OpenSlProcessResult : uint8_t
    {
        kProcessed,
        kProcessorError,
        kBypassed,
        kUnavailable,
    };

    struct OpenSlDspApi
    {
        using CreateFn = echidna_result_t (*)(const echidna_stream_config_t *,
                                              echidna_stream_handle_t *);
        using ProcessFn = echidna_result_t (*)(echidna_stream_handle_t,
                                               const void *,
                                               void *,
                                               uint32_t,
                                               uint32_t);
        using UpdateFn = echidna_result_t (*)(echidna_stream_handle_t,
                                              const char *,
                                              size_t,
                                              uint64_t);
        using DestroyFn = echidna_result_t (*)(echidna_stream_handle_t);

        CreateFn create{nullptr};
        ProcessFn process{nullptr};
        UpdateFn update{nullptr};
        DestroyFn destroy{nullptr};

        [[nodiscard]] bool complete() const
        {
            return create && process && update && destroy;
        }
    };

    /** Fixed, generation-safe ownership for recorder-scoped OpenSL DSP state. */
    class OpenSlStreamRegistry
    {
    public:
        static constexpr size_t kMaxRecorders = 64;

        bool open(uintptr_t recorder,
                  const echidna_stream_config_t &config,
                  const OpenSlDspApi &api);
        OpenSlProcessResult process(uintptr_t recorder,
                                    void *buffer,
                                    uint32_t frames);
        void close(uintptr_t recorder);

        /** Publishes or revokes one process policy while callbacks are gated. */
        bool publishProfile(uint64_t snapshot_generation,
                            bool admitted,
                            std::string_view preset_json,
                            const OpenSlDspApi &api);

    private:
        static constexpr uint32_t kActiveMask = 0x80000000U;
        static constexpr uint32_t kInFlightMask = 0x7FFFFFFFU;
        static constexpr uint64_t kMaxPublication = 0x7FFFFFFFFFFFFFFFULL;

        struct alignas(64) Slot
        {
            std::atomic<uint32_t> usage{0};
            std::atomic<uintptr_t> recorder{0};
            echidna_stream_handle_t handle{0};
            uint32_t format{0};
            OpenSlDspApi::ProcessFn process{nullptr};
            OpenSlDspApi::DestroyFn destroy{nullptr};
            bool allocated{false};
        };

        class MaintenanceGuard
        {
        public:
            explicit MaintenanceGuard(OpenSlStreamRegistry &registry);
            ~MaintenanceGuard();

        private:
            OpenSlStreamRegistry &registry_;
        };

        static bool validConfig(const echidna_stream_config_t &config);
        bool acquireAdmission();
        void releaseAdmission();
        static bool acquireSlot(Slot &slot, uintptr_t recorder);
        static void releaseSlot(Slot &slot);
        void stopAdmission();
        void lockMaintenance();
        void unlockMaintenance();

        std::atomic<uint32_t> maintenance_gate_{0};
        std::atomic<uint32_t> admission_usage_{0};
        std::array<Slot, kMaxRecorders> slots_{};
        uint64_t snapshot_generation_{0};
        uint64_t publication_{0};
        bool has_snapshot_{false};
        bool admitted_{false};
        std::string preset_json_;
    };
} // namespace echidna::hooks
