#include "hooks/capture_buffer_router.h"

#include <array>
#include <atomic>

namespace echidna
{
    namespace hooks
    {
        namespace
        {
            constexpr size_t kScratchSlotCount = 4;

            struct alignas(64) ScratchSlot
            {
                std::atomic_flag in_use = ATOMIC_FLAG_INIT;
                std::array<float, kMaxRealtimeCaptureSamples> input{};
                std::array<float, kMaxRealtimeCaptureSamples> output{};
            };

            std::array<ScratchSlot, kScratchSlotCount> gScratchSlots;

            class ScratchLease
            {
            public:
                ScratchLease()
                {
                    for (auto &slot : gScratchSlots)
                    {
                        if (!slot.in_use.test_and_set(std::memory_order_acquire))
                        {
                            slot_ = &slot;
                            break;
                        }
                    }
                }

                ~ScratchLease()
                {
                    if (slot_)
                    {
                        slot_->in_use.clear(std::memory_order_release);
                    }
                }

                ScratchLease(const ScratchLease &) = delete;
                ScratchLease &operator=(const ScratchLease &) = delete;

                explicit operator bool() const { return slot_ != nullptr; }
                float *input() const { return slot_ ? slot_->input.data() : nullptr; }
                float *output() const { return slot_ ? slot_->output.data() : nullptr; }

            private:
                ScratchSlot *slot_{nullptr};
            };
        } // namespace

        uint32_t ResolveInt16ChannelsForByteCount(size_t byte_count,
                                                  uint32_t preferred_channels)
        {
            if (byte_count == 0 || (byte_count % sizeof(int16_t)) != 0)
            {
                return 0;
            }
            const size_t total_samples = byte_count / sizeof(int16_t);
            if (preferred_channels < 1 || preferred_channels > 8 ||
                (total_samples % preferred_channels) != 0)
            {
                return 0;
            }
            return preferred_channels;
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
            return RouteCaptureBufferInPlace(buffer,
                                             byte_count,
                                             audio::PcmFormat::kSigned16,
                                             sample_rate,
                                             channels,
                                             process_block);
        }

        bool RouteCaptureBufferInPlace(void *buffer,
                                       size_t byte_count,
                                       audio::PcmFormat format,
                                       uint32_t sample_rate,
                                       uint32_t channels,
                                       ProcessBlockFn process_block)
        {
            ScratchLease scratch;
            if (!scratch)
            {
                return false;
            }
            return audio::ProcessPcmBufferInPlace(buffer,
                                                  byte_count,
                                                  format,
                                                  sample_rate,
                                                  channels,
                                                  scratch.input(),
                                                  scratch.output(),
                                                  kMaxRealtimeCaptureSamples,
                                                  process_block) ==
                   audio::BufferProcessResult::kProcessed;
        }

        bool RouteFloatCaptureBufferInPlace(void *buffer,
                                            uint32_t frames,
                                            uint32_t sample_rate,
                                            uint32_t channels,
                                            ProcessBlockFn process_block)
        {
            if (!buffer || frames == 0 || sample_rate == 0 || channels == 0 || channels > 8 ||
                !process_block)
            {
                return false;
            }
            const size_t samples = static_cast<size_t>(frames) * channels;
            if (samples == 0 || samples > SIZE_MAX / sizeof(float))
            {
                return false;
            }
            return RouteCaptureBufferInPlace(buffer,
                                             samples * sizeof(float),
                                             audio::PcmFormat::kFloat32,
                                             sample_rate,
                                             channels,
                                             process_block);
        }

    } // namespace hooks
} // namespace echidna
