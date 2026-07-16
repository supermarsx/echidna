#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>

#include "echidna_api.h"

namespace echidna::hooks
{
    enum class AAudioProcessOwner : uint32_t
    {
        kRead = 1,
        kCallback = 2,
    };

    enum class AAudioProcessResult : uint8_t
    {
        kProcessed,
        kProcessorError,
        kBypassed,
        kUnavailable,
        kNotOwner,
    };

    /**
     * Application AAudio input-data callback ABI. Identical to the platform
     * AAudioStream_dataCallback signature; the dispatcher forwards to it with a
     * transformed buffer that is never the platform-owned input pointer.
     */
    using AAudioAppDataCallback = int (*)(void *stream,
                                          void *user_data,
                                          void *audio_data,
                                          int32_t frames);

    struct AAudioDspApi
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

    class AAudioStreamRegistry
    {
    public:
        static constexpr size_t kMaxStreams = 64;

        bool open(void *stream,
                  const echidna_stream_config_t &config,
                  AAudioProcessOwner owner,
                  const AAudioDspApi &api);
        AAudioProcessResult process(void *stream,
                                    AAudioProcessOwner owner,
                                    void *buffer,
                                    uint32_t frames);

        /**
         * Runs a callback-owned stream's transform without ever writing the
         * platform-owned input pointer. The platform buffer is copied into
         * per-stream, pre-faulted scratch by the DSP, and the resulting scratch
         * pointer is handed to @p app_callback. The slot's in-flight reference is
         * held for the whole callback so the scratch cannot be reclaimed under
         * it. When the stream is not admitted, not owned by the callback route,
         * or processing fails, the application callback still runs against the
         * untouched platform input (fail open for application audio).
         *
         * @return The application callback's own return value.
         */
        int dispatchCallback(void *stream,
                             void *platform_input,
                             int32_t frames,
                             AAudioAppDataCallback app_callback,
                             void *user_data,
                             AAudioProcessResult *out_result);
        void close(void *stream);

        /** Publishes or revokes one process policy while every callback is gated. */
        bool publishProfile(uint64_t snapshot_generation,
                            bool admitted,
                            std::string_view preset_json,
                            const AAudioDspApi &api);

    private:
        static constexpr uint32_t kActiveMask = 0x80000000U;
        static constexpr uint32_t kInFlightMask = 0x7FFFFFFFU;
        static constexpr uint64_t kMaxPublication = 0x7FFFFFFFFFFFFFFFULL;

        struct alignas(64) Slot
        {
            std::atomic<uint32_t> usage{0};
            std::atomic<void *> stream{nullptr};
            uint32_t owner{0};
            echidna_stream_handle_t handle{0};
            echidna_stream_config_t config{};
            uint32_t format{0};
            AAudioDspApi::CreateFn create{nullptr};
            AAudioDspApi::ProcessFn process{nullptr};
            AAudioDspApi::UpdateFn update{nullptr};
            AAudioDspApi::DestroyFn destroy{nullptr};
            // Owned, pre-faulted output scratch for the callback route so the
            // platform-owned input buffer is never written. Allocated at open
            // for callback-owned streams and released only after every in-flight
            // callback has drained. Null for read-owned streams.
            std::unique_ptr<uint8_t[]> scratch{};
            size_t scratch_bytes{0};
            bool allocated{false};
        };

        class MaintenanceGuard
        {
        public:
            explicit MaintenanceGuard(AAudioStreamRegistry &registry);
            ~MaintenanceGuard();

        private:
            AAudioStreamRegistry &registry_;
        };

        static bool validConfig(const echidna_stream_config_t &config);
        bool acquireAdmission();
        void releaseAdmission();
        static bool acquireSlot(Slot &slot, void *stream);
        static void releaseSlot(Slot &slot);
        void stopAdmission();
        void lockMaintenance();
        void unlockMaintenance();

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
