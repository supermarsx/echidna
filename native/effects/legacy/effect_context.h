#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "effect_abi.h"
#include "config/preset_loader.h"
#include "engine.h"

namespace echidna::effects::legacy
{
    constexpr size_t kMaxFramesPerProcess = 4096;
    constexpr uint32_t kMinSampleRate = 8000;
    constexpr uint32_t kMaxSampleRate = 192000;

    struct TelemetrySnapshot
    {
        uint32_t process_calls{0};
        uint32_t processed_calls{0};
        uint32_t bypass_calls{0};
        uint32_t invalid_calls{0};
        uint32_t dsp_failures{0};
        uint32_t sanitized_samples{0};
        uint32_t processed_frames{0};
    };

    /** One independent legacy effect instance for one AudioFlinger session. */
    class EffectContext
    {
    public:
        EffectContext(int32_t session_id, int32_t io_id);
        ~EffectContext();

        EffectContext(const EffectContext &) = delete;
        EffectContext &operator=(const EffectContext &) = delete;

        int32_t Initialize();
        int32_t SetConfig(const effect_config_t &config);
        int32_t Enable();
        int32_t Disable();
        int32_t Reset();
        int32_t GetConfig(effect_config_t *config) const;

        int32_t Command(uint32_t command_code,
                        uint32_t command_size,
                        void *command_data,
                        uint32_t *reply_size,
                        void *reply_data);
        int32_t Process(audio_buffer_t *input, audio_buffer_t *output) noexcept;

        /**
         * Non-realtime Phase-1 seam for a future authenticated session policy.
         * The exported effect never calls this itself, so new instances remain
         * fail-open/bypassed until a later control boundary explicitly supplies
         * both an allowed policy and a validated preset.
         */
        int32_t SetPolicyPreset(
            bool policy_allowed,
            const echidna::dsp::config::PresetDefinition *preset);

        TelemetrySnapshot telemetry() const noexcept;
        bool plugin_directory_scanned() const;
        int32_t session_id() const noexcept { return session_id_; }
        int32_t io_id() const noexcept { return io_id_; }

    private:
        enum class Lifecycle : uint8_t
        {
            kCreated,
            kInitialized,
            kConfigured,
            kEnabled,
        };

        struct RealtimeCounters
        {
            std::atomic<uint32_t> process_calls{0};
            std::atomic<uint32_t> processed_calls{0};
            std::atomic<uint32_t> bypass_calls{0};
            std::atomic<uint32_t> invalid_calls{0};
            std::atomic<uint32_t> dsp_failures{0};
            std::atomic<uint32_t> sanitized_samples{0};
            std::atomic<uint32_t> processed_frames{0};
        };

        static bool ValidateConfig(const effect_config_t &config,
                                   uint32_t *channels) noexcept;
        static size_t SampleCount(size_t frames, uint32_t channels) noexcept;
        static void Increment(std::atomic<uint32_t> &counter,
                              uint32_t amount = 1) noexcept;

        int32_t RebuildEngine(uint32_t sample_rate, uint32_t channels);
        int32_t Identity(audio_buffer_t *input,
                         audio_buffer_t *output,
                         size_t samples) noexcept;
        void ConvertInput(const audio_buffer_t &input,
                          size_t samples) noexcept;
        void WriteProcessed(audio_buffer_t &output,
                            size_t samples) noexcept;

        int32_t session_id_{0};
        int32_t io_id_{0};
        Lifecycle lifecycle_{Lifecycle::kCreated};
        effect_config_t config_{};
        uint32_t channels_{0};
        std::unique_ptr<echidna::dsp::DspEngine> engine_;
        echidna::dsp::config::PresetDefinition preset_{};
        std::vector<float> input_float_;
        std::vector<float> output_float_;
        std::atomic<bool> configured_{false};
        std::atomic<bool> enabled_{false};
        std::atomic<bool> policy_allowed_{false};
        std::atomic<bool> profile_ready_{false};
        RealtimeCounters counters_{};
    };

    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "effect telemetry must stay lock-free on the callback path");

} // namespace echidna::effects::legacy
