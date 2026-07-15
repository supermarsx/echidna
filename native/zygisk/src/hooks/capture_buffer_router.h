#pragma once

#include <cstddef>
#include <cstdint>

#include "audio/pcm_buffer_processor.h"
#include "echidna_api.h"

namespace echidna
{
    namespace hooks
    {

        using ProcessBlockFn = audio::ProcessBlockFn;

        constexpr size_t kMaxRealtimeCaptureSamples = 32768;

        uint32_t ResolveInt16ChannelsForByteCount(size_t byte_count, uint32_t preferred_channels);

        bool RouteInt16CaptureBufferInPlace(void *buffer,
                                            size_t byte_count,
                                            uint32_t sample_rate,
                                            uint32_t preferred_channels,
                                            ProcessBlockFn process_block);

        bool RouteCaptureBufferInPlace(void *buffer,
                                       size_t byte_count,
                                       audio::PcmFormat format,
                                       uint32_t sample_rate,
                                       uint32_t channels,
                                       ProcessBlockFn process_block);

        bool RouteFloatCaptureBufferInPlace(void *buffer,
                                            uint32_t frames,
                                            uint32_t sample_rate,
                                            uint32_t channels,
                                            ProcessBlockFn process_block);

    } // namespace hooks
} // namespace echidna
