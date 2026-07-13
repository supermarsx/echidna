#pragma once

#include <cstddef>
#include <cstdint>

#include "echidna_api.h"

namespace echidna
{
    namespace hooks
    {

        using ProcessBlockFn = echidna_result_t (*)(const float *input,
                                                    float *output,
                                                    uint32_t frames,
                                                    uint32_t sample_rate,
                                                    uint32_t channel_count);

        uint32_t ResolveInt16ChannelsForByteCount(size_t byte_count, uint32_t preferred_channels);

        bool RouteInt16CaptureBufferInPlace(void *buffer,
                                            size_t byte_count,
                                            uint32_t sample_rate,
                                            uint32_t preferred_channels,
                                            ProcessBlockFn process_block);

        bool RouteFloatCaptureBufferInPlace(void *buffer,
                                            uint32_t frames,
                                            uint32_t sample_rate,
                                            uint32_t channels,
                                            ProcessBlockFn process_block);

    } // namespace hooks
} // namespace echidna
