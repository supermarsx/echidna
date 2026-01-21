#include "utils/telemetry_shared_memory.h"

/**
 * @file telemetry_shared_memory.cpp
 * @brief Implementation of the TelemetrySharedMemory mapping and helpers.
 */

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <time.h>

namespace echidna
{
    namespace utils
    {

        namespace
        {
            struct TelemetrySamplePacked
            {
                TelemetrySampleRecord record;
            };

            struct TelemetryHookPacked
            {
                TelemetryHookRecord record;
            };

            /**
             * @brief On-disk/shared memory packed layout used by the Telemetry region.
             */
            struct TelemetrySharedMemory::SharedLayout
            {
                uint32_t magic;
                uint32_t version;
                uint32_t layout_size;
                uint32_t sample_capacity;
                uint32_t write_index;
                uint32_t sample_count;
                uint64_t total_callbacks;
                uint64_t total_callback_ns;
                uint64_t total_cpu_ns;
                uint32_t hook_capacity;
                uint32_t hook_count;
                float rolling_latency_ms;
                float rolling_cpu_percent;
                float input_rms;
                float output_rms;
                float input_peak;
                float output_peak;
                float detected_pitch_hz;
                float target_pitch_hz;
                float formant_shift_cents;
                float formant_width;
                uint32_t xruns;
                uint32_t warning_flags;
                std::array<TelemetrySamplePacked, kTelemetryMaxSamples> samples;
                std::array<TelemetryHookPacked, kTelemetryMaxHooks> hooks;
            };

        } // namespace

        TelemetrySharedMemory::TelemetrySharedMemory()
            : layout_(nullptr), layout_size_(sizeof(SharedLayout)), fd_(-1)
        {
            ensureInitialized();
        }

        TelemetrySharedMemory::~TelemetrySharedMemory()
        {
            if (layout_)
            {
                munmap(layout_, layout_size_);
            }
            if (fd_ >= 0)
            {
                close(fd_);
            }
        }

        void TelemetrySharedMemory::ensureInitialized()
        {
            std::scoped_lock lock(mutex_);
            if (layout_)
            {
                return;
            }

            fd_ = shm_open(kTelemetrySharedMemoryName, O_RDWR | O_CREAT, 0666);
            if (fd_ < 0)
            {
                return;
            }

            if (ftruncate(fd_, static_cast<off_t>(layout_size_)) != 0)
            {
                close(fd_);
                fd_ = -1;
                return;
            }

            void *mapped = mmap(nullptr, layout_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
            if (mapped == MAP_FAILED)
            {
                close(fd_);
                fd_ = -1;
                return;
            }

            layout_ = reinterpret_cast<SharedLayout *>(mapped);
            if (layout_->magic != kTelemetryMagic || layout_->version != kTelemetryVersion ||
                layout_->layout_size != layout_size_)
            {
                std::memset(layout_, 0, sizeof(SharedLayout));
                layout_->magic = kTelemetryMagic;
                layout_->version = kTelemetryVersion;
                layout_->layout_size = static_cast<uint32_t>(layout_size_);
                layout_->sample_capacity = static_cast<uint32_t>(kTelemetryMaxSamples);
                layout_->hook_capacity = static_cast<uint32_t>(kTelemetryMaxHooks);
                layout_->rolling_latency_ms = 0.0f;
                layout_->rolling_cpu_percent = 0.0f;
                layout_->input_rms = -120.0f;
                layout_->output_rms = -120.0f;
                layout_->input_peak = -120.0f;
                layout_->output_peak = -120.0f;
                layout_->detected_pitch_hz = 0.0f;
                layout_->target_pitch_hz = 0.0f;
                layout_->formant_shift_cents = 0.0f;
                layout_->formant_width = 0.0f;
                layout_->xruns = 0;
                layout_->warning_flags = 0;
            }
        }

        void TelemetrySharedMemory::recordCallback(uint64_t timestamp_ns,
                                                   uint32_t duration_us,
                                                   uint32_t cpu_time_us,
                                                   uint32_t flags,
                                                   uint32_t xruns)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }

            const uint32_t capacity = layout_->sample_capacity;
            if (capacity == 0)
            {
                return;
            }

            const uint32_t index = layout_->write_index % capacity;
            TelemetrySampleRecord &slot = layout_->samples[index].record;
            slot.timestamp_ns = timestamp_ns;
            slot.duration_us = duration_us;
            slot.cpu_time_us = cpu_time_us;
            slot.flags = flags;
            slot.xruns = xruns;

            layout_->write_index = (index + 1) % capacity;
            if (layout_->sample_count < capacity)
            {
                layout_->sample_count += 1;
            }
            layout_->total_callbacks += 1;
            layout_->total_callback_ns += static_cast<uint64_t>(duration_us) * 1000ull;
            layout_->total_cpu_ns += static_cast<uint64_t>(cpu_time_us) * 1000ull;
            layout_->xruns = xruns;

            if (layout_->total_callbacks > 0)
            {
                const double avg_latency_ns = static_cast<double>(layout_->total_callback_ns) /
                                              static_cast<double>(layout_->total_callbacks);
                layout_->rolling_latency_ms = static_cast<float>(avg_latency_ns / 1'000'000.0);
            }
            if (layout_->total_callback_ns > 0)
            {
                const double cpu_ratio = static_cast<double>(layout_->total_cpu_ns) /
                                         static_cast<double>(layout_->total_callback_ns);
                layout_->rolling_cpu_percent = static_cast<float>(cpu_ratio * 100.0);
            }

            layout_->warning_flags &= ~(kTelemetryWarningHighLatency |
                                        kTelemetryWarningHighCpu |
                                        kTelemetryWarningXrun);
            if (duration_us > 30000)
            {
                layout_->warning_flags |= kTelemetryWarningHighLatency;
            }
            if (layout_->rolling_cpu_percent > 75.0f)
            {
                layout_->warning_flags |= kTelemetryWarningHighCpu;
            }
            if (xruns > 0)
            {
                layout_->warning_flags |= kTelemetryWarningXrun;
            }
        }

        void TelemetrySharedMemory::updateAudioLevels(float input_rms,
                                                      float output_rms,
                                                      float input_peak,
                                                      float output_peak,
                                                      float detected_pitch_hz,
                                                      float target_pitch_hz,
                                                      float formant_shift_cents,
                                                      float formant_width,
                                                      uint32_t xruns)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }

            layout_->input_rms = input_rms;
            layout_->output_rms = output_rms;
            layout_->input_peak = input_peak;
            layout_->output_peak = output_peak;
            layout_->detected_pitch_hz = detected_pitch_hz;
            layout_->target_pitch_hz = target_pitch_hz;
            layout_->formant_shift_cents = formant_shift_cents;
            layout_->formant_width = formant_width;
            layout_->xruns = xruns;
        }

        void TelemetrySharedMemory::registerHookResult(const std::string &hook_name,
                                                       bool success,
                                                       uint64_t timestamp_ns,
                                                       const std::string &library,
                                                       const std::string &symbol,
                                                       const std::string &reason)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }

            if (hook_name.empty())
            {
                return;
            }

            TelemetryHookRecord *record = nullptr;
            for (uint32_t i = 0; i < layout_->hook_count; ++i)
            {
                TelemetryHookRecord &candidate = layout_->hooks[i].record;
                if (std::strncmp(candidate.name, hook_name.c_str(), sizeof(candidate.name)) == 0)
                {
                    record = &candidate;
                    break;
                }
            }

            if (!record)
            {
                if (layout_->hook_count >= layout_->hook_capacity)
                {
                    // Replace the oldest record when capacity exceeded.
                    record = &layout_->hooks[layout_->hook_count % layout_->hook_capacity].record;
                }
                else
                {
                    record = &layout_->hooks[layout_->hook_count++].record;
                }
                std::memset(record, 0, sizeof(TelemetryHookRecord));
                std::strncpy(record->name, hook_name.c_str(), sizeof(record->name) - 1);
            }

            std::memset(record->library, 0, sizeof(record->library));
            std::memset(record->symbol, 0, sizeof(record->symbol));
            std::memset(record->reason, 0, sizeof(record->reason));
            if (!library.empty())
            {
                std::strncpy(record->library, library.c_str(), sizeof(record->library) - 1);
            }
            if (!symbol.empty())
            {
                std::strncpy(record->symbol, symbol.c_str(), sizeof(record->symbol) - 1);
            }
            if (!reason.empty())
            {
                std::strncpy(record->reason, reason.c_str(), sizeof(record->reason) - 1);
            }
            record->attempts += 1;
            record->last_attempt_ns = timestamp_ns;
            if (success)
            {
                record->successes += 1;
                record->last_success_ns = timestamp_ns;
            }
            else
            {
                record->failures += 1;
            }
        }

        void TelemetrySharedMemory::setWarningFlags(uint32_t flags)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }
            layout_->warning_flags = flags;
        }

        TelemetrySnapshot TelemetrySharedMemory::snapshot() const
        {
            std::scoped_lock lock(mutex_);
            TelemetrySnapshot snapshot;
            if (!layout_)
            {
                return snapshot;
            }

            snapshot.total_callbacks = layout_->total_callbacks;
            snapshot.total_callback_ns = layout_->total_callback_ns;
            snapshot.total_cpu_ns = layout_->total_cpu_ns;
            snapshot.rolling_latency_ms = layout_->rolling_latency_ms;
            snapshot.rolling_cpu_percent = layout_->rolling_cpu_percent;
            snapshot.input_rms = layout_->input_rms;
            snapshot.output_rms = layout_->output_rms;
            snapshot.input_peak = layout_->input_peak;
            snapshot.output_peak = layout_->output_peak;
            snapshot.detected_pitch_hz = layout_->detected_pitch_hz;
            snapshot.target_pitch_hz = layout_->target_pitch_hz;
            snapshot.formant_shift_cents = layout_->formant_shift_cents;
            snapshot.formant_width = layout_->formant_width;
            snapshot.xruns = layout_->xruns;
            snapshot.warning_flags = layout_->warning_flags;

            const uint32_t capacity = layout_->sample_capacity;
            const uint32_t count = std::min(layout_->sample_count, capacity);
            const uint32_t write_index = layout_->write_index % (capacity == 0 ? 1u : capacity);
            snapshot.samples.reserve(count);
            for (uint32_t i = 0; i < count; ++i)
            {
                uint32_t index = (write_index + capacity - count + i) % capacity;
                snapshot.samples.push_back(layout_->samples[index].record);
            }

            const uint32_t hook_count = std::min(layout_->hook_count, layout_->hook_capacity);
            snapshot.hooks.reserve(hook_count);
            for (uint32_t i = 0; i < hook_count; ++i)
            {
                snapshot.hooks.push_back(layout_->hooks[i].record);
            }

            return snapshot;
        }

    } // namespace utils
} // namespace echidna
