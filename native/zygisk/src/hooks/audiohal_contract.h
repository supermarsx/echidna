#pragma once

#include <charconv>
#include <cstdint>
#include <optional>
#include <string_view>

#include "audio/pcm_buffer_processor.h"

namespace echidna::hooks
{

    struct AudioHalPcmContract
    {
        uint32_t sample_rate{0};
        uint32_t channels{0};
        audio::PcmFormat format{audio::PcmFormat::kSigned16};
    };

    inline std::optional<uint32_t> ParseUnsigned(std::string_view text,
                                                 uint32_t minimum,
                                                 uint32_t maximum)
    {
        if (text.empty())
        {
            return std::nullopt;
        }
        uint32_t value = 0;
        const auto result = std::from_chars(text.data(), text.data() + text.size(), value);
        if (result.ec != std::errc{} || result.ptr != text.data() + text.size() ||
            value < minimum || value > maximum)
        {
            return std::nullopt;
        }
        return value;
    }

    inline std::optional<AudioHalPcmContract> ParseAudioHalPcmContract(
        const char *sample_rate,
        const char *channels,
        const char *format)
    {
        if (!sample_rate || !channels || !format)
        {
            return std::nullopt;
        }
        const auto parsed_rate = ParseUnsigned(sample_rate, 8000, 192000);
        const auto parsed_channels = ParseUnsigned(channels, 1, 8);
        if (!parsed_rate || !parsed_channels)
        {
            return std::nullopt;
        }

        audio::PcmFormat parsed_format;
        const std::string_view format_name(format);
        if (format_name == "pcm16")
        {
            parsed_format = audio::PcmFormat::kSigned16;
        }
        else if (format_name == "pcm24_packed")
        {
            parsed_format = audio::PcmFormat::kSigned24Packed;
        }
        else if (format_name == "pcm32")
        {
            parsed_format = audio::PcmFormat::kSigned32;
        }
        else if (format_name == "float")
        {
            parsed_format = audio::PcmFormat::kFloat32;
        }
        else
        {
            return std::nullopt;
        }
        return AudioHalPcmContract{*parsed_rate, *parsed_channels, parsed_format};
    }

} // namespace echidna::hooks
