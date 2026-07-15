#pragma once

#include <cstdint>
#include <optional>

#include "audio/pcm_buffer_processor.h"

namespace echidna::hooks
{
    constexpr uint32_t kOpenSlDataFormatPcm = 0x00000002u;
    constexpr uint32_t kOpenSlAndroidDataFormatPcmEx = 0x00000004u;
    constexpr uint32_t kOpenSlByteOrderLittleEndian = 0x00000002u;
    constexpr uint32_t kOpenSlPcmRepresentationSignedInt = 0x00000001u;
    constexpr uint32_t kOpenSlPcmRepresentationUnsignedInt = 0x00000002u;
    constexpr uint32_t kOpenSlPcmRepresentationFloat = 0x00000003u;
    constexpr uint32_t kOpenSlSupportedChannelMask = 0x0003FFFFu;

    struct OpenSlPcmDescriptor
    {
        uint32_t format_type{0};
        uint32_t channels{0};
        uint32_t sample_rate_millihz{0};
        uint32_t bits_per_sample{0};
        uint32_t container_bits{0};
        uint32_t channel_mask{0};
        uint32_t byte_order{0};
        uint32_t representation{0};
    };

    struct OpenSlPcmContract
    {
        uint32_t sample_rate{0};
        uint32_t channels{0};
        audio::PcmFormat format{audio::PcmFormat::kSigned16};
    };

    inline std::optional<OpenSlPcmContract> ParseOpenSlPcmContract(
        const OpenSlPcmDescriptor &descriptor)
    {
        uint32_t channel_bits = descriptor.channel_mask;
        uint32_t channel_count = 0;
        while (channel_bits != 0)
        {
            channel_count += channel_bits & 1U;
            channel_bits >>= 1U;
        }
        if ((descriptor.format_type != kOpenSlDataFormatPcm &&
             descriptor.format_type != kOpenSlAndroidDataFormatPcmEx) ||
            descriptor.channels == 0 || descriptor.channels > 8 ||
            descriptor.channel_mask == 0 ||
            (descriptor.channel_mask & ~kOpenSlSupportedChannelMask) != 0 ||
            channel_count != descriptor.channels ||
            descriptor.sample_rate_millihz % 1000u != 0 ||
            descriptor.byte_order != kOpenSlByteOrderLittleEndian ||
            descriptor.bits_per_sample != descriptor.container_bits)
        {
            return std::nullopt;
        }
        const uint32_t sample_rate = descriptor.sample_rate_millihz / 1000u;
        if (sample_rate < 8000 || sample_rate > 384000)
        {
            return std::nullopt;
        }

        uint32_t representation = descriptor.representation;
        if (descriptor.format_type == kOpenSlDataFormatPcm)
        {
            representation = descriptor.bits_per_sample == 8
                                 ? kOpenSlPcmRepresentationUnsignedInt
                                 : kOpenSlPcmRepresentationSignedInt;
        }

        audio::PcmFormat format{};
        if (representation == kOpenSlPcmRepresentationUnsignedInt &&
            descriptor.bits_per_sample == 8)
        {
            format = audio::PcmFormat::kUnsigned8;
        }
        else if (representation == kOpenSlPcmRepresentationSignedInt &&
                 descriptor.bits_per_sample == 16)
        {
            format = audio::PcmFormat::kSigned16;
        }
        else if (representation == kOpenSlPcmRepresentationSignedInt &&
                 descriptor.bits_per_sample == 24)
        {
            format = audio::PcmFormat::kSigned24Packed;
        }
        else if (representation == kOpenSlPcmRepresentationSignedInt &&
                 descriptor.bits_per_sample == 32)
        {
            format = audio::PcmFormat::kSigned32;
        }
        else if (representation == kOpenSlPcmRepresentationFloat &&
                 descriptor.bits_per_sample == 32)
        {
            format = audio::PcmFormat::kFloat32;
        }
        else
        {
            return std::nullopt;
        }
        return OpenSlPcmContract{sample_rate, descriptor.channels, format};
    }
} // namespace echidna::hooks
