#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "capability_protocol.h"
#include "effect_abi.h"
#include "config/preset_loader.h"
#include "engine.h"
#include "telemetry_protocol.h"

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
        uint32_t mutations{0};
    };

    /** One independent legacy effect instance for one AudioFlinger session. */
    class EffectContext
    {
    public:
        EffectContext(int32_t session_id,
                      int32_t io_id,
                      CapabilityVerifierOptions verifier_options = {},
                      TelemetryProofKeyOptions proof_key_options = {});
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

        /** Host-test seam. Production AELI activation uses ApplyCapability(). */
        int32_t SetPolicyPreset(
            bool policy_allowed,
            const echidna::dsp::config::PresetDefinition *preset);
        int32_t ApplyCapability(std::string_view value);
        void RevokeAuthorization() noexcept;

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
            std::atomic<uint32_t> mutations{0};
        };

        struct PreparedEngine
        {
            std::unique_ptr<echidna::dsp::DspEngine> engine;
            std::vector<float> input;
            std::vector<float> output;
        };

        static bool ValidateConfig(const effect_config_t &config,
                                   uint32_t *channels) noexcept;
        static size_t SampleCount(size_t frames, uint32_t channels) noexcept;
        static void Increment(std::atomic<uint32_t> &counter,
                              uint32_t amount = 1) noexcept;

        static int32_t BuildPreparedEngine(
            uint32_t sample_rate,
            uint32_t channels,
            const echidna::dsp::config::PresetDefinition *preset,
            PreparedEngine *prepared);
        void CommitPreparedEngine(PreparedEngine prepared,
                                  bool preset_ready) noexcept;
        int32_t RebuildEngineLocked(uint32_t sample_rate, uint32_t channels);
        int32_t PrepareVerifiedPresetLocked(
            const echidna::dsp::config::PresetDefinition &preset);
        int32_t Identity(audio_buffer_t *input,
                         audio_buffer_t *output,
                         size_t samples) noexcept;
        void ConvertInput(const audio_buffer_t &input,
                          size_t samples) noexcept;
        bool WriteProcessed(const audio_buffer_t &input,
                            audio_buffer_t &output,
                            size_t samples) noexcept;
        int32_t GetTelemetryParameter(uint32_t command_size,
                                      const void *command_data,
                                      uint32_t *reply_size,
                                      void *reply_data);
        TelemetryWireSnapshot TelemetryWireState();
        int32_t EncodeTelemetryProofForNonce(
            const TelemetryProofNonce &requested_nonce,
            std::array<uint8_t, kTelemetryProofValueBytes> *encoded);
        uint16_t TelemetryFlags(uint64_t now,
                                uint64_t generation,
                                uint64_t expires) const noexcept;
        bool AuthorizationActive() noexcept;
        bool NonceSeen(const CapabilityNonce &nonce) const noexcept;
        void RememberNonce(const CapabilityNonce &nonce) noexcept;

        int32_t session_id_{0};
        int32_t io_id_{0};
        Lifecycle lifecycle_{Lifecycle::kCreated};
        effect_config_t config_{};
        uint32_t channels_{0};
        std::unique_ptr<echidna::dsp::DspEngine> engine_;
        std::unique_ptr<echidna::dsp::config::PresetDefinition> verified_preset_;
        std::vector<float> input_float_;
        std::vector<float> output_float_;
        std::atomic<bool> configured_{false};
        std::atomic<bool> enabled_{false};
        std::atomic<bool> policy_allowed_{false};
        std::atomic<bool> profile_ready_{false};
        std::atomic<uint32_t> authorization_deadline_ms_{0};
        std::atomic<int32_t> permanent_bypass_status_{0};
        CapabilityVerifier capability_verifier_;
        TelemetryProofSigner telemetry_proof_signer_;
        std::mutex capability_mutex_;
        bool engine_preset_ready_{false};
        uint64_t capability_generation_{0};
        uint64_t capability_issued_ms_{0};
        uint64_t capability_expires_ms_{0};
        uint32_t capability_target_uid_{0};
        std::string capability_process_;
        CapabilityHash capability_preset_hash_{};
        CapabilityNonce active_capability_nonce_{};
        bool has_active_capability_nonce_{false};
        std::array<CapabilityNonce, 16> accepted_nonces_{};
        size_t accepted_nonce_count_{0};
        size_t next_nonce_slot_{0};
        RealtimeCounters counters_{};
        std::atomic<uint32_t> telemetry_sequence_{0};
        std::atomic<uint32_t> telemetry_proof_sequence_{0};
    };

    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "effect telemetry must stay lock-free on the callback path");

} // namespace echidna::effects::legacy
