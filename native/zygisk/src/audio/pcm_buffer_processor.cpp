#include "audio/pcm_buffer_processor.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace echidna::audio
{
    namespace
    {
        constexpr float kUnsigned8Scale = 1.0f / 128.0f;
        constexpr float kSigned16Scale = 1.0f / 32768.0f;
        constexpr float kSigned24Scale = 1.0f / 8388608.0f;
        constexpr float kSigned32Scale = 1.0f / 2147483648.0f;

        size_t BytesPerSample(PcmFormat format)
        {
            switch (format)
            {
            case PcmFormat::kUnsigned8:
                return 1;
            case PcmFormat::kSigned16:
                return 2;
            case PcmFormat::kSigned24Packed:
                return 3;
            case PcmFormat::kSigned32:
            case PcmFormat::kFloat32:
                return 4;
            }
            return 0;
        }

        bool Decode(const void *buffer,
                    size_t samples,
                    PcmFormat format,
                    float *output)
        {
            if (!buffer || !output)
            {
                return false;
            }
            const auto *bytes = static_cast<const uint8_t *>(buffer);
            for (size_t i = 0; i < samples; ++i)
            {
                float sample = 0.0f;
                switch (format)
                {
                case PcmFormat::kUnsigned8:
                    sample = (static_cast<float>(bytes[i]) - 128.0f) * kUnsigned8Scale;
                    break;
                case PcmFormat::kSigned16:
                {
                    int16_t value = 0;
                    std::memcpy(&value, bytes + i * sizeof(value), sizeof(value));
                    sample = static_cast<float>(value) * kSigned16Scale;
                    break;
                }
                case PcmFormat::kSigned24Packed:
                {
                    const size_t offset = i * 3;
                    uint32_t value = static_cast<uint32_t>(bytes[offset]) |
                                     (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
                                     (static_cast<uint32_t>(bytes[offset + 2]) << 16);
                    if ((value & 0x00800000u) != 0)
                    {
                        value |= 0xff000000u;
                    }
                    sample = static_cast<float>(static_cast<int32_t>(value)) * kSigned24Scale;
                    break;
                }
                case PcmFormat::kSigned32:
                {
                    int32_t value = 0;
                    std::memcpy(&value, bytes + i * sizeof(value), sizeof(value));
                    sample = static_cast<float>(value) * kSigned32Scale;
                    break;
                }
                case PcmFormat::kFloat32:
                    std::memcpy(&sample, bytes + i * sizeof(sample), sizeof(sample));
                    break;
                }
                if (!std::isfinite(sample))
                {
                    return false;
                }
                output[i] = sample;
            }
            return true;
        }

        bool AllFinite(const float *samples, size_t count)
        {
            if (!samples)
            {
                return false;
            }
            for (size_t i = 0; i < count; ++i)
            {
                if (!std::isfinite(samples[i]))
                {
                    return false;
                }
            }
            return true;
        }

        int16_t EncodeSigned16(float sample)
        {
            const float clamped = std::clamp(sample, -1.0f, 1.0f);
            if (clamped <= -1.0f)
            {
                return std::numeric_limits<int16_t>::min();
            }
            return static_cast<int16_t>(std::lround(clamped * 32767.0f));
        }

        int32_t EncodeSigned24(float sample)
        {
            const float clamped = std::clamp(sample, -1.0f, 1.0f);
            if (clamped <= -1.0f)
            {
                return -8388608;
            }
            return static_cast<int32_t>(std::lround(clamped * 8388607.0f));
        }

        int32_t EncodeSigned32(float sample)
        {
            const double clamped = std::clamp(static_cast<double>(sample), -1.0, 1.0);
            if (clamped <= -1.0)
            {
                return std::numeric_limits<int32_t>::min();
            }
            return static_cast<int32_t>(std::llround(clamped * 2147483647.0));
        }

        void Encode(const float *input,
                    size_t samples,
                    PcmFormat format,
                    void *buffer)
        {
            auto *bytes = static_cast<uint8_t *>(buffer);
            for (size_t i = 0; i < samples; ++i)
            {
                switch (format)
                {
                case PcmFormat::kUnsigned8:
                {
                    const float clamped = std::clamp(input[i], -1.0f, 1.0f);
                    const long encoded = std::lround((clamped * 127.5f) + 127.5f);
                    bytes[i] = static_cast<uint8_t>(std::clamp(encoded, 0l, 255l));
                    break;
                }
                case PcmFormat::kSigned16:
                {
                    const int16_t value = EncodeSigned16(input[i]);
                    std::memcpy(bytes + i * sizeof(value), &value, sizeof(value));
                    break;
                }
                case PcmFormat::kSigned24Packed:
                {
                    const uint32_t value = static_cast<uint32_t>(EncodeSigned24(input[i]));
                    const size_t offset = i * 3;
                    bytes[offset] = static_cast<uint8_t>(value & 0xffu);
                    bytes[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xffu);
                    bytes[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xffu);
                    break;
                }
                case PcmFormat::kSigned32:
                {
                    const int32_t value = EncodeSigned32(input[i]);
                    std::memcpy(bytes + i * sizeof(value), &value, sizeof(value));
                    break;
                }
                case PcmFormat::kFloat32:
                    std::memcpy(bytes + i * sizeof(float), input + i, sizeof(float));
                    break;
                }
            }
        }
    } // namespace

    bool PcmFormatFromAndroidEncoding(int32_t encoding, PcmFormat *format)
    {
        if (!format)
        {
            return false;
        }
        switch (encoding)
        {
        case 1: // AudioFormat.ENCODING_DEFAULT (PCM16 for AudioRecord).
        case 2: // AudioFormat.ENCODING_PCM_16BIT.
            *format = PcmFormat::kSigned16;
            return true;
        case 3: // AudioFormat.ENCODING_PCM_8BIT (unsigned, midpoint 128).
            *format = PcmFormat::kUnsigned8;
            return true;
        case 4: // AudioFormat.ENCODING_PCM_FLOAT.
            *format = PcmFormat::kFloat32;
            return true;
        case 21: // AudioFormat.ENCODING_PCM_24BIT_PACKED.
            *format = PcmFormat::kSigned24Packed;
            return true;
        case 22: // AudioFormat.ENCODING_PCM_32BIT.
            *format = PcmFormat::kSigned32;
            return true;
        default:
            return false;
        }
    }

    bool ResolveBufferLayout(size_t byte_count,
                             PcmFormat format,
                             uint32_t channels,
                             BufferLayout *layout)
    {
        if (!layout || byte_count == 0 || channels == 0 || channels > 8)
        {
            return false;
        }
        const size_t bytes_per_sample = BytesPerSample(format);
        if (bytes_per_sample == 0 || (byte_count % bytes_per_sample) != 0)
        {
            return false;
        }
        const size_t samples = byte_count / bytes_per_sample;
        if (samples == 0 || (samples % channels) != 0)
        {
            return false;
        }
        const size_t frames = samples / channels;
        if (frames == 0 || frames > std::numeric_limits<uint32_t>::max())
        {
            return false;
        }
        *layout = {samples, static_cast<uint32_t>(frames), bytes_per_sample};
        return true;
    }

    BufferProcessResult ProcessPcmBufferInPlace(void *buffer,
                                                size_t byte_count,
                                                PcmFormat format,
                                                uint32_t sample_rate,
                                                uint32_t channels,
                                                float *input_scratch,
                                                float *output_scratch,
                                                size_t scratch_samples,
                                                ProcessBlockFn process_block)
    {
        if (!buffer || sample_rate == 0 || !input_scratch || !output_scratch ||
            input_scratch == output_scratch || !process_block)
        {
            return BufferProcessResult::kInvalidArgument;
        }
        BufferLayout layout;
        if (!ResolveBufferLayout(byte_count, format, channels, &layout))
        {
            return BufferProcessResult::kInvalidArgument;
        }
        if (layout.samples > scratch_samples)
        {
            return BufferProcessResult::kScratchTooSmall;
        }
        if (!Decode(buffer, layout.samples, format, input_scratch))
        {
            return BufferProcessResult::kNonFiniteSamples;
        }
        const echidna_result_t result = process_block(input_scratch,
                                                      output_scratch,
                                                      layout.frames,
                                                      sample_rate,
                                                      channels);
        if (result != ECHIDNA_RESULT_OK)
        {
            return BufferProcessResult::kProcessorError;
        }
        if (!AllFinite(output_scratch, layout.samples))
        {
            return BufferProcessResult::kNonFiniteSamples;
        }
        Encode(output_scratch, layout.samples, format, buffer);
        return BufferProcessResult::kProcessed;
    }

} // namespace echidna::audio
