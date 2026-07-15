#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>

#include "audio/pcm_buffer_processor.h"

namespace echidna::hooks
{
    constexpr uint32_t kTinyAlsaPcmIn = 0x10000000u;

    struct TinyAlsaConfigPrefix
    {
        uint32_t channels{0};
        uint32_t rate{0};
        uint32_t period_size{0};
        uint32_t period_count{0};
        int32_t format{-1};
    };

    struct TinyAlsaPcmContract
    {
        uint32_t sample_rate{0};
        uint32_t channels{0};
        audio::PcmFormat format{audio::PcmFormat::kSigned16};
        size_t bytes_per_sample{0};
    };

    inline std::optional<TinyAlsaPcmContract> ParseTinyAlsaContract(
        uint32_t flags,
        const TinyAlsaConfigPrefix *config)
    {
        if (!config || (flags & kTinyAlsaPcmIn) == 0 ||
            config->channels == 0 || config->channels > 8 ||
            config->rate < 8000 || config->rate > 384000)
        {
            return std::nullopt;
        }
        TinyAlsaPcmContract contract;
        contract.sample_rate = config->rate;
        contract.channels = config->channels;
        switch (config->format)
        {
        case 0: // PCM_FORMAT_S16_LE
            contract.format = audio::PcmFormat::kSigned16;
            contract.bytes_per_sample = 2;
            break;
        case 1: // PCM_FORMAT_S32_LE
            contract.format = audio::PcmFormat::kSigned32;
            contract.bytes_per_sample = 4;
            break;
        case 4: // PCM_FORMAT_S24_3LE
            contract.format = audio::PcmFormat::kSigned24Packed;
            contract.bytes_per_sample = 3;
            break;
        default:
            // S8 is signed (our Android PCM8 contract is unsigned), while
            // S24_LE stores 24 significant bits in a 32-bit container. Both
            // require distinct conversion semantics and therefore fail closed.
            return std::nullopt;
        }
        return contract;
    }

    inline std::optional<size_t> TinyAlsaBytesForFrames(
        const TinyAlsaPcmContract &contract,
        uint32_t frames)
    {
        if (frames == 0 || contract.channels == 0 || contract.bytes_per_sample == 0)
        {
            return std::nullopt;
        }
        const uint64_t bytes = static_cast<uint64_t>(frames) * contract.channels *
                               contract.bytes_per_sample;
        if (bytes > static_cast<uint64_t>(SIZE_MAX))
        {
            return std::nullopt;
        }
        return static_cast<size_t>(bytes);
    }
} // namespace echidna::hooks
