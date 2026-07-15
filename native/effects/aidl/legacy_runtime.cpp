#include "legacy_runtime.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <limits>
#include <optional>
#include <string_view>

#include <Utils.h>
#include <aidl/android/hardware/audio/effect/Flags.h>
#include <aidl/android/media/audio/common/PcmType.h>
#include <system/audio_effects/effect_uuid.h>

#include "capability_protocol.h"
#include "telemetry_protocol.h"

namespace echidna::effects::aidl
{
    namespace
    {
        using AidlDescriptor =
            ::aidl::android::hardware::audio::effect::Descriptor;
        using Flags = ::aidl::android::hardware::audio::effect::Flags;
        using Parameter = ::aidl::android::hardware::audio::effect::Parameter;
        using AudioUuid = ::aidl::android::media::audio::common::AudioUuid;
        constexpr size_t kWordBytes = sizeof(uint32_t);

        size_t PaddedSize(size_t size) noexcept
        {
            if (size > std::numeric_limits<size_t>::max() - (kWordBytes - 1))
            {
                return 0;
            }
            return (size + (kWordBytes - 1)) & ~(kWordBytes - 1);
        }

        class AlignedBytes
        {
        public:
            explicit AlignedBytes(size_t bytes)
                : words_((bytes + kWordBytes - 1) / kWordBytes, 0),
                  bytes_(bytes)
            {
            }

            uint8_t *data() noexcept
            {
                return reinterpret_cast<uint8_t *>(words_.data());
            }

            const uint8_t *data() const noexcept
            {
                return reinterpret_cast<const uint8_t *>(words_.data());
            }

            size_t size() const noexcept { return bytes_; }

        private:
            std::vector<uint32_t> words_;
            size_t bytes_;
        };

        bool CopyPacket(std::span<const uint8_t> packet,
                        AlignedBytes *aligned,
                        effect_param_t **parameter) noexcept
        {
            if (aligned == nullptr || parameter == nullptr ||
                packet.size() < sizeof(effect_param_t) ||
                packet.size() > ::echidna::effects::legacy::kEffectParamMaxBytes)
            {
                return false;
            }

            effect_param_t header{};
            std::memcpy(&header, packet.data(), sizeof(header));
            const size_t padded = PaddedSize(header.psize);
            if (padded == 0 || padded > packet.size() - sizeof(effect_param_t) ||
                header.vsize > packet.size() - sizeof(effect_param_t) - padded)
            {
                return false;
            }

            *aligned = AlignedBytes(packet.size());
            std::memcpy(aligned->data(), packet.data(), packet.size());
            *parameter = reinterpret_cast<effect_param_t *>(aligned->data());
            return true;
        }

        int32_t CommandStatus(
            ::echidna::effects::legacy::EffectContext &context,
            uint32_t command,
            uint32_t size,
            void *data)
        {
            int32_t result = -EINVAL;
            uint32_t result_size = sizeof(result);
            const int32_t status = context.Command(
                command, size, data, &result_size, &result);
            return status == 0 && result_size == sizeof(result) ? result : status;
        }

        bool SameDspConfig(const effect_config_t &left,
                           const effect_config_t &right) noexcept
        {
            return left.inputCfg.samplingRate == right.inputCfg.samplingRate &&
                   left.inputCfg.channels == right.inputCfg.channels &&
                   left.inputCfg.format == right.inputCfg.format &&
                   left.outputCfg.samplingRate == right.outputCfg.samplingRate &&
                   left.outputCfg.channels == right.outputCfg.channels &&
                   left.outputCfg.format == right.outputCfg.format;
        }

    } // namespace

    const AudioUuid &TypeUuid()
    {
        static const AudioUuid uuid =
            ::aidl::android::hardware::audio::effect::stringToUuid(kTypeUuid);
        return uuid;
    }

    const AudioUuid &ImplementationUuid()
    {
        static const AudioUuid uuid =
            ::aidl::android::hardware::audio::effect::stringToUuid(
                kImplementationUuid);
        return uuid;
    }

    AidlDescriptor Descriptor()
    {
        return {
            .common = {
                .id = {
                    .type = TypeUuid(),
                    .uuid = ImplementationUuid(),
                    .proxy = std::nullopt,
                },
                .flags = {
                    .type = Flags::Type::PRE_PROC,
                    .insert = Flags::Insert::FIRST,
                },
                .cpuLoad = 100,
                .memoryUsage = 512,
                .name = kEffectName,
                .implementor = kImplementor,
            },
        };
    }

    LegacyRuntime::LegacyRuntime(const Parameter::Common &common)
        : context_(common.session, common.ioHandle)
    {
        effect_config_t config{};
        uint32_t channels = 0;
        if (!BuildConfig(common, &config, &channels) ||
            context_.Initialize() != 0 || context_.SetConfig(config) != 0)
        {
            return;
        }
        channels_ = channels;
        ready_ = true;
    }

    bool LegacyRuntime::BuildConfig(const Parameter::Common &common,
                                    effect_config_t *config,
                                    uint32_t *channels) noexcept
    {
        if (config == nullptr || channels == nullptr ||
            common.input.base.format.pcm !=
                ::aidl::android::media::audio::common::PcmType::FLOAT_32_BIT ||
            common.output.base.format.pcm !=
                ::aidl::android::media::audio::common::PcmType::FLOAT_32_BIT ||
            common.input.base.sampleRate != common.output.base.sampleRate ||
            common.input.frameCount <= 0 ||
            common.output.frameCount <= 0 ||
            common.input.base.sampleRate <
                static_cast<int32_t>(::echidna::effects::legacy::kMinSampleRate) ||
            common.input.base.sampleRate >
                static_cast<int32_t>(::echidna::effects::legacy::kMaxSampleRate))
        {
            return false;
        }

        const size_t input_channels =
            ::aidl::android::hardware::audio::common::getChannelCount(
                common.input.base.channelMask);
        const size_t output_channels =
            ::aidl::android::hardware::audio::common::getChannelCount(
                common.output.base.channelMask);
        if (input_channels != output_channels ||
            (input_channels != 1 && input_channels != 2))
        {
            return false;
        }

        const uint32_t channel_mask = input_channels == 1
                                          ? AUDIO_CHANNEL_IN_MONO
                                          : AUDIO_CHANNEL_IN_STEREO;
        buffer_config_t input{};
        input.samplingRate = static_cast<uint32_t>(common.input.base.sampleRate);
        input.channels = channel_mask;
        input.format = AUDIO_FORMAT_PCM_FLOAT;
        input.accessMode = EFFECT_BUFFER_ACCESS_READ;
        input.mask = EFFECT_CONFIG_ALL;

        buffer_config_t output = input;
        output.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
        *config = {.inputCfg = input, .outputCfg = output};
        *channels = static_cast<uint32_t>(input_channels);
        return true;
    }

    int32_t LegacyRuntime::Reconfigure(const Parameter::Common &common)
    {
        effect_config_t config{};
        uint32_t channels = 0;
        if (!BuildConfig(common, &config, &channels))
        {
            return -EINVAL;
        }

        effect_config_t current{};
        if (context_.GetConfig(&current) == 0 && SameDspConfig(current, config))
        {
            // Stable AIDL V2 resizes the input and output FMQs independently.
            // Their frame counts are not part of the legacy DSP format, so a
            // reopen-only change must not revoke an otherwise valid grant.
            channels_ = channels;
            return 0;
        }

        if (legacy_enabled_)
        {
            const int32_t disable_status = context_.Disable();
            if (disable_status != 0)
            {
                return disable_status;
            }
            legacy_enabled_ = false;
        }
        const int32_t status = context_.SetConfig(config);
        if (status == 0)
        {
            channels_ = channels;
            ready_ = true;
        }
        return status;
    }

    int32_t LegacyRuntime::Start()
    {
        if (!ready_)
        {
            return -ENODATA;
        }
        start_requested_ = true;
        const int32_t status = context_.Enable();
        if (status == 0)
        {
            legacy_enabled_ = true;
            return 0;
        }
        // Stable AIDL VTS and ordinary clients may start a vendor effect
        // before setting its private parameter. Enter the AIDL PROCESSING
        // state but keep the legacy runtime disabled, which produces identity.
        // A later valid capability activates it without another START command.
        return status == -EPERM ? 0 : status;
    }

    int32_t LegacyRuntime::Stop()
    {
        if (!ready_)
        {
            return -ENODATA;
        }
        start_requested_ = false;
        if (!legacy_enabled_)
        {
            context_.RevokeAuthorization();
            return 0;
        }
        const int32_t status = context_.Disable();
        if (status == 0)
        {
            legacy_enabled_ = false;
        }
        return status;
    }

    int32_t LegacyRuntime::Reset()
    {
        return ready_ ? context_.Reset() : -ENODATA;
    }

    int32_t LegacyRuntime::SetValueV1(std::span<const uint8_t> value)
    {
        if (!ready_)
        {
            return -ENODATA;
        }
        if (value.empty())
        {
            context_.RevokeAuthorization();
            return 0;
        }
        if (value.size() > ::echidna::effects::legacy::kEffectParamMaxBytes)
        {
            return -E2BIG;
        }
        return ActivateIfRequested(context_.ApplyCapability(std::string_view(
            reinterpret_cast<const char *>(value.data()), value.size())));
    }

    int32_t LegacyRuntime::GetValueV1(std::span<const uint8_t> key,
                                      std::vector<uint8_t> *value)
    {
        if (!ready_ || value == nullptr)
        {
            return -EINVAL;
        }
        size_t value_bytes = 0;
        if (key.size() ==
                ::echidna::effects::legacy::kTelemetrySnapshotParameter.size() &&
            std::equal(key.begin(), key.end(),
                       ::echidna::effects::legacy::kTelemetrySnapshotParameter.begin()))
        {
            value_bytes =
                ::echidna::effects::legacy::kTelemetrySnapshotValueBytes;
        }
        else if (key.size() ==
                     ::echidna::effects::legacy::kTelemetryProofQueryBytes &&
                 std::equal(
                     key.begin(),
                     key.begin() +
                         ::echidna::effects::legacy::kTelemetryProofParameter.size(),
                     ::echidna::effects::legacy::kTelemetryProofParameter.begin()))
        {
            value_bytes = ::echidna::effects::legacy::kTelemetryProofValueBytes;
        }
        else
        {
            return -EINVAL;
        }

        const size_t request_bytes = sizeof(effect_param_t) + key.size();
        AlignedBytes request(request_bytes);
        auto *request_parameter =
            reinterpret_cast<effect_param_t *>(request.data());
        request_parameter->psize = static_cast<uint32_t>(key.size());
        request_parameter->vsize = static_cast<uint32_t>(value_bytes);
        std::memcpy(request_parameter->data, key.data(), key.size());

        const size_t reply_capacity = request_bytes + value_bytes;
        AlignedBytes response(reply_capacity);
        uint32_t reply_size = static_cast<uint32_t>(reply_capacity);
        const int32_t status = context_.Command(
            EFFECT_CMD_GET_PARAM,
            static_cast<uint32_t>(request.size()),
            request.data(),
            &reply_size,
            response.data());
        if (status != 0 || reply_size > response.size() ||
            reply_size < sizeof(effect_param_t) + key.size())
        {
            return status == 0 ? -EINVAL : status;
        }

        const auto *reply_parameter =
            reinterpret_cast<const effect_param_t *>(response.data());
        if (reply_parameter->status != 0)
        {
            return reply_parameter->status;
        }
        if (reply_parameter->psize != key.size() ||
            reply_parameter->vsize != value_bytes ||
            reply_size != sizeof(effect_param_t) + key.size() + value_bytes)
        {
            return -EINVAL;
        }
        value->assign(
            reinterpret_cast<const uint8_t *>(reply_parameter->data) + key.size(),
            reinterpret_cast<const uint8_t *>(reply_parameter->data) +
                key.size() + value_bytes);
        return 0;
    }

    int32_t LegacyRuntime::SetPacketV2(std::span<const uint8_t> packet)
    {
        AlignedBytes aligned(0);
        effect_param_t *parameter = nullptr;
        if (!ready_ || !CopyPacket(packet, &aligned, &parameter))
        {
            return -EINVAL;
        }
        return ActivateIfRequested(CommandStatus(
            context_,
            EFFECT_CMD_SET_PARAM,
            static_cast<uint32_t>(aligned.size()),
            parameter));
    }

    int32_t LegacyRuntime::ActivateIfRequested(int32_t mutation_status)
    {
        if (mutation_status != 0 || !start_requested_ || legacy_enabled_)
        {
            return mutation_status;
        }
        const int32_t enable_status = context_.Enable();
        if (enable_status == 0)
        {
            legacy_enabled_ = true;
            return 0;
        }
        // A successful revoke while AIDL is processing intentionally leaves
        // the runtime in identity mode rather than turning revoke into an
        // error. Only an unexpected lifecycle failure escapes.
        return enable_status == -EPERM ? 0 : enable_status;
    }

    int32_t LegacyRuntime::GetPacketV2(std::span<const uint8_t> packet,
                                       std::vector<uint8_t> *reply)
    {
        AlignedBytes request(0);
        effect_param_t *parameter = nullptr;
        if (!ready_ || reply == nullptr ||
            !CopyPacket(packet, &request, &parameter))
        {
            return -EINVAL;
        }

        const size_t capacity = std::max(
            packet.size(),
            ::echidna::effects::legacy::kTelemetryProofReplyBytes);
        AlignedBytes response(capacity);
        uint32_t reply_size = static_cast<uint32_t>(capacity);
        const int32_t status = context_.Command(
            EFFECT_CMD_GET_PARAM,
            static_cast<uint32_t>(request.size()),
            parameter,
            &reply_size,
            response.data());
        if (status != 0 || reply_size > response.size())
        {
            return status == 0 ? -EINVAL : status;
        }
        reply->assign(response.data(), response.data() + reply_size);
        return 0;
    }

    bool LegacyRuntime::Process(float *input,
                                float *output,
                                int32_t samples) noexcept
    {
        if (!ready_ || input == nullptr || output == nullptr || samples <= 0 ||
            channels_ == 0 || samples % static_cast<int32_t>(channels_) != 0)
        {
            return false;
        }

        const size_t frames = static_cast<size_t>(samples) / channels_;
        std::memmove(output, input, static_cast<size_t>(samples) * sizeof(float));
        size_t offset_frames = 0;
        while (offset_frames < frames)
        {
            const size_t block_frames = std::min(
                frames - offset_frames,
                ::echidna::effects::legacy::kMaxFramesPerProcess);
            const size_t offset_samples = offset_frames * channels_;
            audio_buffer_t input_buffer{
                .frameCount = block_frames,
                .f32 = input + offset_samples,
            };
            audio_buffer_t output_buffer{
                .frameCount = block_frames,
                .f32 = output + offset_samples,
            };
            // The legacy runtime writes identity for policy expiry/DSP failure.
            // A non-zero status therefore does not invalidate the FMQ block.
            (void)context_.Process(&input_buffer, &output_buffer);
            offset_frames += block_frames;
        }
        return true;
    }

} // namespace echidna::effects::aidl
