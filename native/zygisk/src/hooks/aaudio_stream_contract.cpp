#include "hooks/aaudio_stream_contract.h"

namespace echidna::hooks
{
    namespace
    {
        constexpr int32_t kAAudioFormatI16 = 1;
        constexpr int32_t kAAudioFormatFloat = 2;
        constexpr int32_t kAAudioDirectionInput = 1;
    } // namespace

    bool QueryAAudioStreamConfig(void *stream,
                                 const AAudioStreamQueryApi &api,
                                 echidna_stream_config_t *config)
    {
        if (!config)
        {
            return false;
        }
        *config = {};
        if (!stream || !api.complete())
        {
            return false;
        }

        const int32_t sample_rate = api.get_sample_rate(stream);
        const int32_t channels = api.get_channel_count(stream);
        const int32_t format = api.get_format(stream);
        const int32_t direction = api.get_direction(stream);
        const int32_t capacity_frames = api.get_buffer_capacity_frames(stream);
        const int32_t frames_per_burst = api.get_frames_per_burst(stream);
        if (sample_rate < 8000 || sample_rate > 384000 || channels < 1 ||
            channels > 8 || direction != kAAudioDirectionInput ||
            capacity_frames <= 0 || frames_per_burst <= 0 ||
            frames_per_burst > capacity_frames)
        {
            return false;
        }

        const uint32_t channel_count = static_cast<uint32_t>(channels);
        const uint32_t capacity = static_cast<uint32_t>(capacity_frames);
        if (capacity > kAAudioMaxPreparedSamples / channel_count)
        {
            return false;
        }

        uint32_t pcm_format = 0;
        if (format == kAAudioFormatI16)
        {
            pcm_format = ECHIDNA_PCM_FORMAT_SIGNED_16;
        }
        else if (format == kAAudioFormatFloat)
        {
            pcm_format = ECHIDNA_PCM_FORMAT_FLOAT_32;
        }
        else
        {
            return false;
        }

        config->struct_size = sizeof(*config);
        config->sample_rate = static_cast<uint32_t>(sample_rate);
        config->channel_count = channel_count;
        config->max_frames = capacity;
        config->format = pcm_format;
        return true;
    }
} // namespace echidna::hooks
