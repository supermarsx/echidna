#include "hooks/capture_buffer_router.h"

#include <algorithm>
#include <vector>

namespace echidna
{
    namespace hooks
    {

        uint32_t ResolveInt16ChannelsForByteCount(size_t byte_count,
                                                  uint32_t preferred_channels)
        {
            if (byte_count == 0 || (byte_count % sizeof(int16_t)) != 0)
            {
                return 0;
            }
            const size_t total_samples = byte_count / sizeof(int16_t);
            if (preferred_channels >= 1 && preferred_channels <= 8 &&
                (total_samples % preferred_channels) == 0)
            {
                return preferred_channels;
            }
            for (uint32_t channels = 1; channels <= 8; ++channels)
            {
                if ((total_samples % channels) == 0)
                {
                    return channels;
                }
            }
            return 0;
        }

        bool RouteInt16CaptureBufferInPlace(void *buffer,
                                            size_t byte_count,
                                            uint32_t sample_rate,
                                            uint32_t preferred_channels,
                                            ProcessBlockFn process_block)
        {
            if (!buffer || sample_rate == 0 || !process_block)
            {
                return false;
            }
            const uint32_t channels =
                ResolveInt16ChannelsForByteCount(byte_count, preferred_channels);
            if (channels == 0)
            {
                return false;
            }
            const size_t samples = byte_count / sizeof(int16_t);
            const size_t frames = samples / channels;
            if (frames == 0 || frames > UINT32_MAX)
            {
                return false;
            }

            std::vector<float> input(samples);
            const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
            for (size_t i = 0; i < samples; ++i)
            {
                input[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
            }
            std::vector<float> output(samples);
            const echidna_result_t result =
                process_block(input.data(),
                              output.data(),
                              static_cast<uint32_t>(frames),
                              sample_rate,
                              channels);
            if (result != ECHIDNA_RESULT_OK)
            {
                return false;
            }
            int16_t *pcm_out = static_cast<int16_t *>(buffer);
            for (size_t i = 0; i < samples; ++i)
            {
                const float clamped = std::clamp(output[i], -1.0f, 1.0f);
                pcm_out[i] = static_cast<int16_t>(clamped * 32767.0f);
            }
            return true;
        }

        bool RouteFloatCaptureBufferInPlace(void *buffer,
                                            uint32_t frames,
                                            uint32_t sample_rate,
                                            uint32_t channels,
                                            ProcessBlockFn process_block)
        {
            if (!buffer || frames == 0 || sample_rate == 0 || channels == 0 || !process_block)
            {
                return false;
            }
            const size_t samples = static_cast<size_t>(frames) * channels;
            if (samples == 0)
            {
                return false;
            }

            const float *input = static_cast<const float *>(buffer);
            std::vector<float> output(samples);
            const echidna_result_t result =
                process_block(input, output.data(), frames, sample_rate, channels);
            if (result != ECHIDNA_RESULT_OK)
            {
                return false;
            }
            float *pcm_out = static_cast<float *>(buffer);
            std::copy(output.begin(), output.end(), pcm_out);
            return true;
        }

    } // namespace hooks
} // namespace echidna
