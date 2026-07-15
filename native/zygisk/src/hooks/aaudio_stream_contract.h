#pragma once

#include <cstdint>

#include "echidna_api.h"

namespace echidna::hooks
{
    struct AAudioStreamQueryApi
    {
        using GetIntFn = int32_t (*)(void *);

        GetIntFn get_sample_rate{nullptr};
        GetIntFn get_channel_count{nullptr};
        GetIntFn get_format{nullptr};
        GetIntFn get_direction{nullptr};
        GetIntFn get_buffer_capacity_frames{nullptr};
        GetIntFn get_frames_per_burst{nullptr};

        [[nodiscard]] bool complete() const
        {
            return get_sample_rate && get_channel_count && get_format &&
                   get_direction && get_buffer_capacity_frames &&
                   get_frames_per_burst;
        }
    };

    /**
     * Hard ceiling for interleaved samples preallocated by one capture stream.
     * The platform capacity remains expressed and stored in frames; multiplying
     * by the validated channel count may not exceed this memory-safety bound.
     */
    inline constexpr uint32_t kAAudioMaxPreparedSamples = 32768U;

    /** Queries and validates immutable metadata after a successful stream open. */
    bool QueryAAudioStreamConfig(void *stream,
                                 const AAudioStreamQueryApi &api,
                                 echidna_stream_config_t *config);
} // namespace echidna::hooks
