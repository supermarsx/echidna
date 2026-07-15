#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>

#include "echidna_api.h"

namespace echidna::hooks
{
    constexpr uint32_t kTinyAlsaPcmIn = 0x10000000U;
    constexpr int32_t kTinyAlsaFormatS16Le = 0;
    constexpr int32_t kTinyAlsaFormatS32Le = 1;
    constexpr int32_t kTinyAlsaFormatS24Le = 3;
    constexpr int32_t kTinyAlsaFormatS24PackedLe = 4;
    constexpr int32_t kTinyAlsaFormatFloatLe = 9;
    constexpr uint32_t kTinyAlsaMaxPreparedSamples = 32768U;

    /** ABI-stable prefix of upstream tinyalsa's pcm_config. */
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
        echidna_stream_config_t stream{};
        uint32_t bytes_per_frame{0};
    };

    inline std::optional<TinyAlsaPcmContract> ParseTinyAlsaContract(
        uint32_t flags,
        const TinyAlsaConfigPrefix *config,
        uint32_t format_bits)
    {
        if (!config || (flags & kTinyAlsaPcmIn) == 0 ||
            config->channels == 0 || config->channels > 8 ||
            config->rate < 8000 || config->rate > 384000 ||
            config->period_size == 0 || config->period_count == 0)
        {
            return std::nullopt;
        }

        const uint64_t capacity_frames =
            static_cast<uint64_t>(config->period_size) * config->period_count;
        if (capacity_frames > UINT32_MAX ||
            capacity_frames * config->channels > kTinyAlsaMaxPreparedSamples)
        {
            return std::nullopt;
        }

        TinyAlsaPcmContract contract;
        contract.stream.struct_size = sizeof(contract.stream);
        contract.stream.sample_rate = config->rate;
        contract.stream.channel_count = config->channels;
        contract.stream.max_frames = static_cast<uint32_t>(capacity_frames);
        switch (config->format)
        {
        case kTinyAlsaFormatS16Le:
            if (format_bits != 16)
            {
                return std::nullopt;
            }
            contract.stream.format = ECHIDNA_PCM_FORMAT_SIGNED_16;
            contract.bytes_per_frame = config->channels * 2U;
            break;
        case kTinyAlsaFormatFloatLe:
            if (format_bits != 32)
            {
                return std::nullopt;
            }
            contract.stream.format = ECHIDNA_PCM_FORMAT_FLOAT_32;
            contract.bytes_per_frame = config->channels * 4U;
            break;
        default:
            // S24/S32 and endian/vendor extensions do not match the committed
            // DSP stream ABI, even when their storage width is 32 bits.
            return std::nullopt;
        }
        return contract;
    }

    inline std::optional<size_t> TinyAlsaBytesForFrames(
        const TinyAlsaPcmContract &contract,
        uint32_t frames)
    {
        if (frames == 0 || contract.bytes_per_frame == 0)
        {
            return std::nullopt;
        }
        const uint64_t bytes =
            static_cast<uint64_t>(frames) * contract.bytes_per_frame;
        if (bytes > static_cast<uint64_t>(SIZE_MAX))
        {
            return std::nullopt;
        }
        return static_cast<size_t>(bytes);
    }

    inline std::optional<uint32_t> TinyAlsaCompletedByteRead(
        int result,
        uint32_t requested_bytes)
    {
        if (result != 0 || requested_bytes == 0)
        {
            return std::nullopt;
        }
        return requested_bytes;
    }

    inline std::optional<uint32_t> TinyAlsaCompletedFrameRead(
        int result,
        uint32_t requested_frames)
    {
        if (result <= 0 || static_cast<uint32_t>(result) > requested_frames)
        {
            return std::nullopt;
        }
        return static_cast<uint32_t>(result);
    }
} // namespace echidna::hooks
