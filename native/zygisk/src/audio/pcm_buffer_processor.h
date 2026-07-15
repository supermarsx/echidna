#pragma once

#include <cstddef>
#include <cstdint>

#include "echidna_api.h"

namespace echidna::audio
{

    enum class PcmFormat : uint8_t
    {
        kUnsigned8,
        kSigned16,
        kSigned24Packed,
        kSigned32,
        kFloat32,
    };

    enum class BufferProcessResult : uint8_t
    {
        kProcessed,
        kInvalidArgument,
        kUnsupportedFormat,
        kScratchTooSmall,
        kProcessorError,
        kNonFiniteSamples,
    };

    using ProcessBlockFn = echidna_result_t (*)(const float *input,
                                                float *output,
                                                uint32_t frames,
                                                uint32_t sample_rate,
                                                uint32_t channel_count);

    struct BufferLayout
    {
        size_t samples{0};
        uint32_t frames{0};
        size_t bytes_per_sample{0};
    };

    bool PcmFormatFromAndroidEncoding(int32_t encoding, PcmFormat *format);

    bool ResolveBufferLayout(size_t byte_count,
                             PcmFormat format,
                             uint32_t channels,
                             BufferLayout *layout);

    BufferProcessResult ProcessPcmBufferInPlace(void *buffer,
                                                size_t byte_count,
                                                PcmFormat format,
                                                uint32_t sample_rate,
                                                uint32_t channels,
                                                float *input_scratch,
                                                float *output_scratch,
                                                size_t scratch_samples,
                                                ProcessBlockFn process_block);

} // namespace echidna::audio
