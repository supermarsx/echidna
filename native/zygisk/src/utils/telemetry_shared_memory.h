#pragma once

/**
 * @file telemetry_shared_memory.h
 * @brief Shared-memory based telemetry for runtime diagnostics and hook
 * installation stats. Used to export timing metrics and hook events to
 * external monitoring tools.
 */

#include <stddef.h>
#include <stdint.h>

#include <mutex>
#include <string>
#include <vector>

namespace echidna
{
    namespace utils
    {

        constexpr const char *kTelemetrySharedMemoryName = "/echidna_telemetry";
        constexpr uint32_t kTelemetryMagic = 0xEDC1DA10u;
        constexpr uint32_t kTelemetryVersion = 2u;
        constexpr size_t kTelemetryMaxSamples = 96;
        constexpr size_t kTelemetryMaxHooks = 8;

        enum TelemetryFlags : uint32_t
        {
            kTelemetryFlagCallback = 1u << 0,
            kTelemetryFlagDsp = 1u << 1,
            kTelemetryFlagBypassed = 1u << 2,
            kTelemetryFlagError = 1u << 3,
        };

        enum TelemetryWarningFlags : uint32_t
        {
            kTelemetryWarningHighLatency = 1u << 0,
            kTelemetryWarningHighCpu = 1u << 1,
            kTelemetryWarningXrun = 1u << 2,
        };

        /**
         * @brief Per-callback timing and xrun record.
         */
        struct TelemetrySampleRecord
        {
            uint64_t timestamp_ns;
            uint32_t duration_us;
            uint32_t cpu_time_us;
            uint32_t flags;
            uint32_t xruns;
        };

        /**
         * @brief Hook installation attempt tracking.
         */
        struct TelemetryHookRecord
        {
            char name[32];
            char library[32];
            char symbol[48];
            char reason[48];
            uint32_t attempts;
            uint32_t successes;
            uint32_t failures;
            uint32_t reserved;
            uint64_t last_attempt_ns;
            uint64_t last_success_ns;
        };

        /**
         * @brief Snapshot of telemetry values suitable for serialization and
         * external diagnostics.
         */
        struct TelemetrySnapshot
        {
            uint64_t total_callbacks{0};
            uint64_t total_callback_ns{0};
            uint64_t total_cpu_ns{0};
            float rolling_latency_ms{0.0f};
            float rolling_cpu_percent{0.0f};
            float input_rms{-120.0f};
            float output_rms{-120.0f};
            float input_peak{-120.0f};
            float output_peak{-120.0f};
            float detected_pitch_hz{0.0f};
            float target_pitch_hz{0.0f};
            float formant_shift_cents{0.0f};
            float formant_width{0.0f};
            uint32_t xruns{0};
            uint32_t warning_flags{0};
            std::vector<TelemetrySampleRecord> samples;
            std::vector<TelemetryHookRecord> hooks;
        };

        class TelemetrySharedMemory
        {
        public:
            /** Construct and map the telemetry shared memory region. */
            TelemetrySharedMemory();
            /** Unmap and close the shared memory region. */
            ~TelemetrySharedMemory();

            /**
             * @brief Records a single callback timing sample.
             */
            void recordCallback(uint64_t timestamp_ns,
                                uint32_t duration_us,
                                uint32_t cpu_time_us,
                                uint32_t flags,
                                uint32_t xruns);

            /**
             * @brief Updates rolling audio level metrics.
             */
            void updateAudioLevels(float input_rms,
                                   float output_rms,
                                   float input_peak,
                                   float output_peak,
                                   float detected_pitch_hz,
                                   float target_pitch_hz,
                                   float formant_shift_cents,
                                   float formant_width,
                                   uint32_t xruns);

            /**
             * @brief Records hook install attempt/success.
             */
            void registerHookResult(const std::string &hook_name,
                                    bool success,
                                    uint64_t timestamp_ns,
                                    const std::string &library,
                                    const std::string &symbol,
                                    const std::string &reason);

            /**
             * @brief Sets warning flags (latency/CPU/xrun).
             */
            void setWarningFlags(uint32_t flags);

            /**
             * @brief Produces a snapshot copy for consumers.
             */
            TelemetrySnapshot snapshot() const;

        private:
            struct SharedLayout;

            void ensureInitialized();

            SharedLayout *layout_;
            size_t layout_size_;
            int fd_;
            mutable std::mutex mutex_;
        };

    } // namespace utils
} // namespace echidna
