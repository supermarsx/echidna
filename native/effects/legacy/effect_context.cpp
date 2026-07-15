#include "effect_context.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>

namespace echidna::effects::legacy
{
    namespace
    {
        constexpr uint16_t kRequiredConfigMask =
            EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS |
            EFFECT_CONFIG_FORMAT | EFFECT_CONFIG_ACC_MODE;

        int32_t WriteStatusReply(uint32_t *reply_size,
                                 void *reply_data,
                                 int32_t status) noexcept
        {
            if (reply_size == nullptr || reply_data == nullptr ||
                *reply_size < sizeof(status))
            {
                return -EINVAL;
            }
            std::memcpy(reply_data, &status, sizeof(status));
            *reply_size = sizeof(status);
            return 0;
        }

        int16_t SaturatingPcm16(int32_t sample) noexcept
        {
            return static_cast<int16_t>(
                std::clamp(sample,
                           static_cast<int32_t>(std::numeric_limits<int16_t>::min()),
                           static_cast<int32_t>(std::numeric_limits<int16_t>::max())));
        }

    } // namespace

    EffectContext::EffectContext(int32_t session_id,
                                 int32_t io_id,
                                 CapabilityVerifierOptions verifier_options)
        : session_id_(session_id),
          io_id_(io_id),
          capability_verifier_(std::move(verifier_options))
    {
    }

    EffectContext::~EffectContext() = default;

    void EffectContext::Increment(std::atomic<uint32_t> &counter,
                                  uint32_t amount) noexcept
    {
        counter.fetch_add(amount, std::memory_order_relaxed);
    }

    size_t EffectContext::SampleCount(size_t frames, uint32_t channels) noexcept
    {
        if (frames == 0 || channels == 0 ||
            frames > std::numeric_limits<size_t>::max() / channels)
        {
            return 0;
        }
        return frames * channels;
    }

    bool EffectContext::ValidateConfig(const effect_config_t &config,
                                       uint32_t *channels) noexcept
    {
        if (channels == nullptr)
        {
            return false;
        }
        const auto &input = config.inputCfg;
        const auto &output = config.outputCfg;
        if ((input.mask & kRequiredConfigMask) != kRequiredConfigMask ||
            (output.mask & kRequiredConfigMask) != kRequiredConfigMask ||
            (input.mask & ~EFFECT_CONFIG_ALL) != 0 ||
            (output.mask & ~EFFECT_CONFIG_ALL) != 0)
        {
            return false;
        }
        if (input.samplingRate < kMinSampleRate ||
            input.samplingRate > kMaxSampleRate ||
            input.samplingRate != output.samplingRate)
        {
            return false;
        }
        if (input.channels != output.channels)
        {
            return false;
        }
        if (input.channels == AUDIO_CHANNEL_IN_MONO)
        {
            *channels = 1;
        }
        else if (input.channels == AUDIO_CHANNEL_IN_STEREO)
        {
            *channels = 2;
        }
        else
        {
            return false;
        }
        if (input.format != output.format ||
            (input.format != AUDIO_FORMAT_PCM_16_BIT &&
             input.format != AUDIO_FORMAT_PCM_FLOAT))
        {
            return false;
        }
        if (input.accessMode != EFFECT_BUFFER_ACCESS_READ ||
            (output.accessMode != EFFECT_BUFFER_ACCESS_WRITE &&
             output.accessMode != EFFECT_BUFFER_ACCESS_ACCUMULATE))
        {
            return false;
        }
        const auto has_provider = [](const buffer_provider_t &provider)
        {
            return provider.getBuffer != nullptr ||
                   provider.releaseBuffer != nullptr || provider.cookie != nullptr;
        };
        return !has_provider(input.bufferProvider) &&
               !has_provider(output.bufferProvider);
    }

    int32_t EffectContext::Initialize()
    {
        if (enabled_.load(std::memory_order_acquire))
        {
            return -EBUSY;
        }
        RevokeAuthorization();
        configured_.store(false, std::memory_order_release);
        engine_.reset();
        input_float_.clear();
        output_float_.clear();
        config_ = {};
        channels_ = 0;
        preset_ = {};
        {
            std::scoped_lock lock(capability_mutex_);
            capability_generation_ = 0;
            capability_issued_ms_ = 0;
            capability_expires_ms_ = 0;
            capability_target_uid_ = 0;
            capability_process_.clear();
            capability_preset_hash_.fill(0);
            accepted_nonce_count_ = 0;
            next_nonce_slot_ = 0;
        }
        lifecycle_ = Lifecycle::kInitialized;
        return 0;
    }

    int32_t EffectContext::RebuildEngine(uint32_t sample_rate, uint32_t channels)
    {
        try
        {
            echidna::dsp::DspEngineOptions options;
            options.load_plugins = false;
            options.lock_free_realtime_process = true;
            auto candidate = std::make_unique<echidna::dsp::DspEngine>(
                sample_rate, channels, ECH_DSP_QUALITY_LOW_LATENCY, options);
            if (profile_ready_.load(std::memory_order_acquire) &&
                candidate->UpdatePreset(preset_) != ECH_DSP_STATUS_OK)
            {
                return -EINVAL;
            }
            if (candidate->PrepareRealtime(kMaxFramesPerProcess) != ECH_DSP_STATUS_OK)
            {
                return -ENOMEM;
            }
            const size_t max_samples = kMaxFramesPerProcess * channels;
            std::vector<float> input(max_samples, 0.0f);
            std::vector<float> output(max_samples, 0.0f);
            engine_ = std::move(candidate);
            input_float_ = std::move(input);
            output_float_ = std::move(output);
            return 0;
        }
        catch (const std::bad_alloc &)
        {
            return -ENOMEM;
        }
        catch (...)
        {
            return -EINVAL;
        }
    }

    int32_t EffectContext::SetConfig(const effect_config_t &config)
    {
        if (enabled_.load(std::memory_order_acquire))
        {
            return -EBUSY;
        }
        if (lifecycle_ != Lifecycle::kInitialized &&
            lifecycle_ != Lifecycle::kConfigured)
        {
            return -ENODATA;
        }
        uint32_t channels = 0;
        if (!ValidateConfig(config, &channels))
        {
            return -EINVAL;
        }
        const int32_t status = RebuildEngine(config.inputCfg.samplingRate, channels);
        if (status != 0)
        {
            return status;
        }
        config_ = config;
        channels_ = channels;
        configured_.store(true, std::memory_order_release);
        lifecycle_ = Lifecycle::kConfigured;
        return 0;
    }

    int32_t EffectContext::Enable()
    {
        if (lifecycle_ != Lifecycle::kConfigured ||
            !configured_.load(std::memory_order_acquire))
        {
            return -ENODATA;
        }
        if (!AuthorizationActive())
        {
            return -EPERM;
        }
        enabled_.store(true, std::memory_order_release);
        lifecycle_ = Lifecycle::kEnabled;
        return 0;
    }

    int32_t EffectContext::Disable()
    {
        if (lifecycle_ != Lifecycle::kEnabled)
        {
            return -EINVAL;
        }
        enabled_.store(false, std::memory_order_release);
        RevokeAuthorization();
        lifecycle_ = Lifecycle::kConfigured;
        return 0;
    }

    int32_t EffectContext::Reset()
    {
        if (!configured_.load(std::memory_order_acquire))
        {
            return -ENODATA;
        }
        const bool was_enabled = enabled_.load(std::memory_order_acquire);
        enabled_.store(false, std::memory_order_release);
        const int32_t status = RebuildEngine(config_.inputCfg.samplingRate, channels_);
        RevokeAuthorization();
        if (status != 0)
        {
            lifecycle_ = Lifecycle::kConfigured;
            return status;
        }
        enabled_.store(was_enabled, std::memory_order_release);
        lifecycle_ = was_enabled ? Lifecycle::kEnabled : Lifecycle::kConfigured;
        return 0;
    }

    int32_t EffectContext::GetConfig(effect_config_t *config) const
    {
        if (config == nullptr || !configured_.load(std::memory_order_acquire))
        {
            return -EINVAL;
        }
        *config = config_;
        return 0;
    }

    int32_t EffectContext::SetPolicyPreset(
        bool policy_allowed,
        const echidna::dsp::config::PresetDefinition *preset)
    {
        if (!configured_.load(std::memory_order_acquire))
        {
            return -ENODATA;
        }
        if (enabled_.load(std::memory_order_acquire))
        {
            return -EBUSY;
        }
        policy_allowed_.store(false, std::memory_order_release);
        profile_ready_.store(false, std::memory_order_release);
        if (preset == nullptr)
        {
            preset_ = {};
            return 0;
        }
        try
        {
            auto candidate = *preset;
            if (engine_ == nullptr ||
                engine_->UpdatePreset(candidate) != ECH_DSP_STATUS_OK ||
                engine_->PrepareRealtime(kMaxFramesPerProcess) != ECH_DSP_STATUS_OK)
            {
                return -EINVAL;
            }
            preset_ = std::move(candidate);
            profile_ready_.store(true, std::memory_order_release);
            const uint64_t now = capability_verifier_.nowBoottimeMs();
            authorization_deadline_ms_.store(
                static_cast<uint32_t>(now + kCapabilityMaxLifetimeMs),
                std::memory_order_release);
            policy_allowed_.store(policy_allowed, std::memory_order_release);
            return 0;
        }
        catch (const std::bad_alloc &)
        {
            return -ENOMEM;
        }
        catch (...)
        {
            return -EINVAL;
        }
    }

    bool EffectContext::NonceSeen(const CapabilityNonce &nonce) const noexcept
    {
        for (size_t index = 0; index < accepted_nonce_count_; ++index)
        {
            if (accepted_nonces_[index] == nonce)
            {
                return true;
            }
        }
        return false;
    }

    void EffectContext::RememberNonce(const CapabilityNonce &nonce) noexcept
    {
        accepted_nonces_[next_nonce_slot_] = nonce;
        next_nonce_slot_ = (next_nonce_slot_ + 1) % accepted_nonces_.size();
        accepted_nonce_count_ = std::min(accepted_nonce_count_ + 1,
                                         accepted_nonces_.size());
    }

    void EffectContext::RevokeAuthorization() noexcept
    {
        policy_allowed_.store(false, std::memory_order_release);
        profile_ready_.store(false, std::memory_order_release);
        authorization_deadline_ms_.store(0, std::memory_order_release);
    }

    int32_t EffectContext::ApplyCapability(std::string_view value)
    {
        const int32_t permanent_status =
            permanent_bypass_status_.load(std::memory_order_acquire);
        if (permanent_status != 0)
        {
            RevokeAuthorization();
            return permanent_status;
        }

        CapabilityClaims claims;
        const CapabilityStatus verification =
            capability_verifier_.verify(value, session_id_, &claims);
        if (verification != CapabilityStatus::kOk)
        {
            if (verification == CapabilityStatus::kKeyUnavailable ||
                verification == CapabilityStatus::kSignatureInvalid)
            {
                int32_t expected = 0;
                permanent_bypass_status_.compare_exchange_strong(
                    expected,
                    static_cast<int32_t>(verification),
                    std::memory_order_acq_rel,
                    std::memory_order_acquire);
            }
            RevokeAuthorization();
            return static_cast<int32_t>(verification);
        }

        std::scoped_lock lock(capability_mutex_);
        if (NonceSeen(claims.nonce))
        {
            return kCapabilityReplayStatus;
        }
        if (claims.generation < capability_generation_)
        {
            return kCapabilityRollbackStatus;
        }
        const bool same_generation = claims.generation == capability_generation_;
        if (same_generation)
        {
            const bool same_identity =
                claims.target_uid == capability_target_uid_ &&
                claims.process == capability_process_ &&
                claims.preset_hash == capability_preset_hash_;
            if (!same_identity || claims.expires_boottime_ms <= capability_expires_ms_ ||
                claims.issued_boottime_ms < capability_issued_ms_)
            {
                return kCapabilityConflictStatus;
            }
            capability_issued_ms_ = claims.issued_boottime_ms;
            capability_expires_ms_ = claims.expires_boottime_ms;
            RememberNonce(claims.nonce);
            authorization_deadline_ms_.store(
                static_cast<uint32_t>(claims.expires_boottime_ms),
                std::memory_order_release);
            profile_ready_.store(true, std::memory_order_release);
            policy_allowed_.store(true, std::memory_order_release);
            return 0;
        }

        if (enabled_.load(std::memory_order_acquire))
        {
            return kCapabilityBusyStatus;
        }
        const auto loaded =
            echidna::dsp::config::LoadPresetFromJson(claims.preset_json);
        if (!loaded.ok)
        {
            RevokeAuthorization();
            return -EINVAL;
        }
        const int32_t preset_status = SetPolicyPreset(true, &loaded.preset);
        if (preset_status != 0)
        {
            RevokeAuthorization();
            return preset_status;
        }
        capability_generation_ = claims.generation;
        capability_issued_ms_ = claims.issued_boottime_ms;
        capability_expires_ms_ = claims.expires_boottime_ms;
        capability_target_uid_ = claims.target_uid;
        capability_process_ = std::move(claims.process);
        capability_preset_hash_ = claims.preset_hash;
        RememberNonce(claims.nonce);
        authorization_deadline_ms_.store(
            static_cast<uint32_t>(claims.expires_boottime_ms),
            std::memory_order_release);
        return 0;
    }

    bool EffectContext::AuthorizationActive() noexcept
    {
        if (!policy_allowed_.load(std::memory_order_acquire) ||
            !profile_ready_.load(std::memory_order_acquire))
        {
            return false;
        }
        const uint32_t deadline =
            authorization_deadline_ms_.load(std::memory_order_acquire);
        const uint32_t now =
            static_cast<uint32_t>(capability_verifier_.nowBoottimeMs());
        if (!CapabilityVerifier::IsActiveDeadline(now, deadline))
        {
            RevokeAuthorization();
            return false;
        }
        return true;
    }

    int32_t EffectContext::Identity(audio_buffer_t *input,
                                    audio_buffer_t *output,
                                    size_t samples) noexcept
    {
        if (input == nullptr || output == nullptr || input->raw == nullptr ||
            output->raw == nullptr)
        {
            return -EINVAL;
        }
        if (config_.inputCfg.format == AUDIO_FORMAT_PCM_16_BIT)
        {
            if (config_.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_WRITE)
            {
                std::memmove(output->s16, input->s16, samples * sizeof(int16_t));
            }
            else
            {
                for (size_t index = 0; index < samples; ++index)
                {
                    output->s16[index] = SaturatingPcm16(
                        static_cast<int32_t>(output->s16[index]) + input->s16[index]);
                }
            }
            return 0;
        }

        for (size_t index = 0; index < samples; ++index)
        {
            float value = input->f32[index];
            if (!std::isfinite(value))
            {
                value = 0.0f;
                Increment(counters_.sanitized_samples);
            }
            else if (value < -1.0f || value > 1.0f)
            {
                value = std::clamp(value, -1.0f, 1.0f);
                Increment(counters_.sanitized_samples);
            }
            if (config_.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE)
            {
                float accumulated = output->f32[index];
                if (!std::isfinite(accumulated))
                {
                    accumulated = 0.0f;
                    Increment(counters_.sanitized_samples);
                }
                value = std::clamp(accumulated + value, -1.0f, 1.0f);
            }
            output->f32[index] = value;
        }
        return 0;
    }

    void EffectContext::ConvertInput(const audio_buffer_t &input,
                                     size_t samples) noexcept
    {
        if (config_.inputCfg.format == AUDIO_FORMAT_PCM_16_BIT)
        {
            constexpr float kPcm16Scale = 1.0f / 32768.0f;
            for (size_t index = 0; index < samples; ++index)
            {
                input_float_[index] = static_cast<float>(input.s16[index]) * kPcm16Scale;
            }
            return;
        }
        for (size_t index = 0; index < samples; ++index)
        {
            float value = input.f32[index];
            if (!std::isfinite(value))
            {
                value = 0.0f;
                Increment(counters_.sanitized_samples);
            }
            else if (value < -1.0f || value > 1.0f)
            {
                value = std::clamp(value, -1.0f, 1.0f);
                Increment(counters_.sanitized_samples);
            }
            input_float_[index] = value;
        }
    }

    void EffectContext::WriteProcessed(audio_buffer_t &output,
                                       size_t samples) noexcept
    {
        for (size_t index = 0; index < samples; ++index)
        {
            float value = output_float_[index];
            if (!std::isfinite(value))
            {
                value = input_float_[index];
                Increment(counters_.sanitized_samples);
            }
            if (value < -1.0f || value > 1.0f)
            {
                value = std::clamp(value, -1.0f, 1.0f);
                Increment(counters_.sanitized_samples);
            }
            if (config_.outputCfg.format == AUDIO_FORMAT_PCM_FLOAT)
            {
                if (config_.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE)
                {
                    float existing = output.f32[index];
                    if (!std::isfinite(existing))
                    {
                        existing = 0.0f;
                        Increment(counters_.sanitized_samples);
                    }
                    value = std::clamp(existing + value, -1.0f, 1.0f);
                }
                output.f32[index] = value;
                continue;
            }

            const int32_t converted = static_cast<int32_t>(std::lround(value * 32767.0f));
            if (config_.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE)
            {
                output.s16[index] = SaturatingPcm16(
                    static_cast<int32_t>(output.s16[index]) + converted);
            }
            else
            {
                output.s16[index] = SaturatingPcm16(converted);
            }
        }
    }

    int32_t EffectContext::Process(audio_buffer_t *input,
                                   audio_buffer_t *output) noexcept
    {
        Increment(counters_.process_calls);
        if (!configured_.load(std::memory_order_acquire) || input == nullptr ||
            output == nullptr || input->raw == nullptr || output->raw == nullptr ||
            input->frameCount == 0 || input->frameCount != output->frameCount ||
            input->frameCount > kMaxFramesPerProcess)
        {
            Increment(counters_.invalid_calls);
            return -EINVAL;
        }
        const size_t samples = SampleCount(input->frameCount, channels_);
        if (samples == 0 || samples > input_float_.size() ||
            samples > output_float_.size())
        {
            Increment(counters_.invalid_calls);
            return -EINVAL;
        }
        Increment(counters_.processed_frames,
                  static_cast<uint32_t>(input->frameCount));

        if (!enabled_.load(std::memory_order_acquire))
        {
            Increment(counters_.bypass_calls);
            const int32_t status = Identity(input, output, samples);
            return status == 0 ? -ENODATA : status;
        }
        if (!AuthorizationActive())
        {
            Increment(counters_.bypass_calls);
            return Identity(input, output, samples);
        }

        ConvertInput(*input, samples);
        if (engine_ == nullptr ||
            engine_->ProcessBlock(input_float_.data(),
                                  output_float_.data(),
                                  input->frameCount) != ECH_DSP_STATUS_OK)
        {
            Increment(counters_.dsp_failures);
            Increment(counters_.bypass_calls);
            return Identity(input, output, samples);
        }
        WriteProcessed(*output, samples);
        Increment(counters_.processed_calls);
        return 0;
    }

    int32_t EffectContext::Command(uint32_t command_code,
                                   uint32_t command_size,
                                   void *command_data,
                                   uint32_t *reply_size,
                                   void *reply_data)
    {
        switch (command_code)
        {
        case EFFECT_CMD_INIT:
            if (command_size != 0)
            {
                return -EINVAL;
            }
            return WriteStatusReply(reply_size, reply_data, Initialize());
        case EFFECT_CMD_SET_CONFIG:
        {
            if (command_size != sizeof(effect_config_t) || command_data == nullptr)
            {
                return -EINVAL;
            }
            effect_config_t config{};
            std::memcpy(&config, command_data, sizeof(config));
            return WriteStatusReply(reply_size, reply_data, SetConfig(config));
        }
        case EFFECT_CMD_RESET:
            if (command_size != 0)
            {
                return -EINVAL;
            }
            if (reply_size != nullptr)
            {
                *reply_size = 0;
            }
            return Reset();
        case EFFECT_CMD_ENABLE:
            if (command_size != 0)
            {
                return -EINVAL;
            }
            return WriteStatusReply(reply_size, reply_data, Enable());
        case EFFECT_CMD_DISABLE:
            if (command_size != 0)
            {
                return -EINVAL;
            }
            return WriteStatusReply(reply_size, reply_data, Disable());
        case EFFECT_CMD_SET_PARAM:
        {
            if (command_data == nullptr || command_size < sizeof(effect_param_t) ||
                reinterpret_cast<uintptr_t>(command_data) % alignof(effect_param_t) != 0)
            {
                return -EINVAL;
            }
            const auto &parameter = *static_cast<const effect_param_t *>(command_data);
            if (IsRevokeParameter(parameter, command_size))
            {
                RevokeAuthorization();
                return WriteStatusReply(reply_size, reply_data, 0);
            }
            if (!IsAuthorizeParameter(parameter, command_size))
            {
                return WriteStatusReply(reply_size, reply_data, -EINVAL);
            }
            return WriteStatusReply(
                reply_size,
                reply_data,
                ApplyCapability(EffectParameterValue(parameter, command_size)));
        }
        case EFFECT_CMD_GET_CONFIG:
            if (command_size != 0 || reply_size == nullptr || reply_data == nullptr ||
                *reply_size < sizeof(effect_config_t))
            {
                return -EINVAL;
            }
            if (GetConfig(static_cast<effect_config_t *>(reply_data)) != 0)
            {
                return -ENODATA;
            }
            *reply_size = sizeof(effect_config_t);
            return 0;
        default:
            return -ENOSYS;
        }
    }

    TelemetrySnapshot EffectContext::telemetry() const noexcept
    {
        return {
            counters_.process_calls.load(std::memory_order_relaxed),
            counters_.processed_calls.load(std::memory_order_relaxed),
            counters_.bypass_calls.load(std::memory_order_relaxed),
            counters_.invalid_calls.load(std::memory_order_relaxed),
            counters_.dsp_failures.load(std::memory_order_relaxed),
            counters_.sanitized_samples.load(std::memory_order_relaxed),
            counters_.processed_frames.load(std::memory_order_relaxed),
        };
    }

    bool EffectContext::plugin_directory_scanned() const
    {
        return engine_ != nullptr && engine_->plugin_directory_scanned();
    }

} // namespace echidna::effects::legacy
