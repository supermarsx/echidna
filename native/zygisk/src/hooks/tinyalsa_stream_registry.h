#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "echidna_api.h"
#include "hooks/tinyalsa_contract.h"

namespace echidna::hooks
{
    enum class TinyAlsaProcessResult : uint8_t
    {
        kProcessed,
        kProcessorError,
        kBypassed,
        kUnavailable,
    };

    struct TinyAlsaDspApi
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

    /** Fixed-capacity, per-pcm ownership for tinyalsa DSP stream handles. */
    class TinyAlsaStreamRegistry
    {
    public:
        static constexpr size_t kMaxStreams = 64;

        bool open(void *pcm,
                  const TinyAlsaPcmContract &contract,
                  const TinyAlsaDspApi &api);
        TinyAlsaProcessResult processBytes(void *pcm,
                                           void *buffer,
                                           uint32_t bytes);
        bool framesForBytes(void *pcm,
                            uint32_t bytes,
                            uint32_t *frames);
        TinyAlsaProcessResult processFrames(void *pcm,
                                            void *buffer,
                                            uint32_t frames);
        void close(void *pcm);
        bool publishProfile(uint64_t snapshot_generation,
                            bool admitted,
                            std::string_view preset_json,
                            const TinyAlsaDspApi &api);

    private:
        static constexpr uint32_t kActiveMask = 0x80000000U;
        static constexpr uint32_t kInFlightMask = 0x7FFFFFFFU;
        static constexpr uint64_t kMaxPublication = 0x7FFFFFFFFFFFFFFFULL;

        struct alignas(64) Slot
        {
            std::atomic<uint32_t> usage{0};
            std::atomic<void *> pcm{nullptr};
            echidna_stream_handle_t handle{0};
            TinyAlsaPcmContract contract{};
            TinyAlsaDspApi::CreateFn create{nullptr};
            TinyAlsaDspApi::ProcessFn process{nullptr};
            TinyAlsaDspApi::UpdateFn update{nullptr};
            TinyAlsaDspApi::DestroyFn destroy{nullptr};
            bool allocated{false};
        };

        class MaintenanceGuard
        {
        public:
            explicit MaintenanceGuard(TinyAlsaStreamRegistry &registry);
            ~MaintenanceGuard();

        private:
            TinyAlsaStreamRegistry &registry_;
        };

        static bool validContract(const TinyAlsaPcmContract &contract);
        bool acquireAdmission();
        void releaseAdmission();
        static bool acquireSlot(Slot &slot, void *pcm);
        static void releaseSlot(Slot &slot);
        static void retireSlot(Slot &slot);
        void stopAdmission();
        void lockMaintenance();
        void unlockMaintenance();
        TinyAlsaProcessResult process(void *pcm,
                                      void *buffer,
                                      uint32_t amount,
                                      bool amount_is_bytes);

        std::atomic<uint32_t> maintenance_gate_{0};
        std::atomic<uint32_t> admission_usage_{0};
        std::array<Slot, kMaxStreams> slots_{};
        uint64_t snapshot_generation_{0};
        uint64_t publication_{0};
        bool has_snapshot_{false};
        bool admitted_{false};
        std::string preset_json_;
    };
} // namespace echidna::hooks
