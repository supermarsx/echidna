#include "hooks/aaudio_stream_registry.h"

#include <algorithm>
#include <cstring>
#include <iterator>
#include <limits>
#include <memory>
#include <new>
#include <thread>
#include <utility>

#include "hooks/aaudio_stream_contract.h"

namespace echidna::hooks
{
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "AAudio stream admission requires lock-free 32-bit atomics");
    static_assert(std::atomic<void *>::is_always_lock_free,
                  "AAudio stream lookup requires lock-free pointer atomics");

    AAudioStreamRegistry::MaintenanceGuard::MaintenanceGuard(
        AAudioStreamRegistry &registry)
        : registry_(registry)
    {
        registry_.lockMaintenance();
    }

    AAudioStreamRegistry::MaintenanceGuard::~MaintenanceGuard()
    {
        registry_.unlockMaintenance();
    }

    void AAudioStreamRegistry::lockMaintenance()
    {
        uint32_t expected = 0;
        while (!maintenance_gate_.compare_exchange_weak(expected,
                                                        1,
                                                        std::memory_order_acquire,
                                                        std::memory_order_relaxed))
        {
            expected = 0;
            std::this_thread::yield();
        }
    }

    void AAudioStreamRegistry::unlockMaintenance()
    {
        maintenance_gate_.store(0, std::memory_order_release);
    }

    bool AAudioStreamRegistry::validConfig(const echidna_stream_config_t &config)
    {
        if (config.struct_size != sizeof(config) || config.sample_rate < 8000 ||
            config.sample_rate > 384000 || config.channel_count == 0 ||
            config.channel_count > 8 || config.max_frames == 0 ||
            config.max_frames > kAAudioMaxPreparedSamples / config.channel_count ||
            (config.format != ECHIDNA_PCM_FORMAT_SIGNED_16 &&
             config.format != ECHIDNA_PCM_FORMAT_FLOAT_32))
        {
            return false;
        }
        return std::all_of(std::begin(config.reserved),
                           std::end(config.reserved),
                           [](uint32_t reserved)
                           { return reserved == 0; });
    }

    bool AAudioStreamRegistry::acquireAdmission()
    {
        uint32_t usage = admission_usage_.load(std::memory_order_acquire);
        while ((usage & kActiveMask) != 0)
        {
            if ((usage & kInFlightMask) == kInFlightMask)
            {
                return false;
            }
            if (admission_usage_.compare_exchange_weak(usage,
                                                       usage + 1,
                                                       std::memory_order_acq_rel,
                                                       std::memory_order_acquire))
            {
                return true;
            }
        }
        return false;
    }

    void AAudioStreamRegistry::releaseAdmission()
    {
        admission_usage_.fetch_sub(1, std::memory_order_acq_rel);
    }

    bool AAudioStreamRegistry::acquireSlot(Slot &slot, void *stream)
    {
        uint32_t usage = slot.usage.load(std::memory_order_acquire);
        while ((usage & kActiveMask) != 0)
        {
            if ((usage & kInFlightMask) == kInFlightMask)
            {
                return false;
            }
            if (slot.usage.compare_exchange_weak(usage,
                                                 usage + 1,
                                                 std::memory_order_acq_rel,
                                                 std::memory_order_acquire))
            {
                if (slot.stream.load(std::memory_order_acquire) != stream)
                {
                    releaseSlot(slot);
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    void AAudioStreamRegistry::releaseSlot(Slot &slot)
    {
        slot.usage.fetch_sub(1, std::memory_order_acq_rel);
    }

    void AAudioStreamRegistry::stopAdmission()
    {
        admission_usage_.fetch_and(~kActiveMask, std::memory_order_acq_rel);
        while ((admission_usage_.load(std::memory_order_acquire) & kInFlightMask) != 0)
        {
            std::this_thread::yield();
        }
    }

    bool AAudioStreamRegistry::open(void *stream,
                                    const echidna_stream_config_t &config,
                                    AAudioProcessOwner owner,
                                    const AAudioDspApi &api)
    {
        if (!stream || !validConfig(config) || !api.complete())
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        for (const Slot &slot : slots_)
        {
            if (slot.allocated && slot.stream.load(std::memory_order_acquire) == stream)
            {
                return false;
            }
        }
        for (Slot &slot : slots_)
        {
            if (slot.allocated || slot.usage.load(std::memory_order_acquire) != 0)
            {
                continue;
            }

            echidna_stream_handle_t handle = 0;
            bool ready = api.create(&config, &handle) == ECHIDNA_RESULT_OK && handle != 0;
            if (ready && has_snapshot_)
            {
                const char *preset = admitted_ ? preset_json_.data() : nullptr;
                const size_t length = admitted_ ? preset_json_.size() : 0;
                ready = api.update(handle, preset, length, publication_) == ECHIDNA_RESULT_OK;
            }
            if (!ready && handle != 0)
            {
                (void)api.destroy(handle);
                handle = 0;
            }

            slot.allocated = true;
            slot.handle = handle;
            slot.config = config;
            slot.format = config.format;
            slot.create = api.create;
            slot.process = api.process;
            slot.update = api.update;
            slot.destroy = api.destroy;
            slot.owner = static_cast<uint32_t>(owner);
            slot.scratch.reset();
            slot.scratch_bytes = 0;
            if (owner == AAudioProcessOwner::kCallback)
            {
                // The callback route must never write the platform-owned input
                // pointer, so allocate and pre-fault a private output region
                // sized to the exact stream contract. validConfig() guarantees
                // max_frames * channel_count <= kAAudioMaxPreparedSamples, so the
                // byte product cannot overflow. Allocation failure degrades to a
                // fail-open pass-through rather than breaking application audio.
                const size_t bytes_per_sample =
                    config.format == ECHIDNA_PCM_FORMAT_SIGNED_16 ? sizeof(int16_t)
                                                                  : sizeof(float);
                const size_t bytes = static_cast<size_t>(config.max_frames) *
                                     static_cast<size_t>(config.channel_count) *
                                     bytes_per_sample;
                std::unique_ptr<uint8_t[]> buffer(new (std::nothrow) uint8_t[bytes]);
                if (buffer)
                {
                    std::memset(buffer.get(), 0, bytes);
                    slot.scratch = std::move(buffer);
                    slot.scratch_bytes = bytes;
                }
            }
            slot.stream.store(stream, std::memory_order_relaxed);
            slot.usage.store(kActiveMask, std::memory_order_release);
            return ready;
        }
        return false;
    }

    AAudioProcessResult AAudioStreamRegistry::process(void *stream,
                                                      AAudioProcessOwner owner,
                                                      void *buffer,
                                                      uint32_t frames)
    {
        if (!stream || !buffer || frames == 0)
        {
            return AAudioProcessResult::kUnavailable;
        }
        if (!acquireAdmission())
        {
            return AAudioProcessResult::kBypassed;
        }

        AAudioProcessResult result = AAudioProcessResult::kUnavailable;
        for (Slot &slot : slots_)
        {
            if (slot.stream.load(std::memory_order_acquire) != stream ||
                !acquireSlot(slot, stream))
            {
                continue;
            }
            const uint32_t requested_owner = static_cast<uint32_t>(owner);
            if (slot.owner != requested_owner)
            {
                result = AAudioProcessResult::kNotOwner;
            }
            else if (slot.handle == 0 || !slot.process)
            {
                result = AAudioProcessResult::kUnavailable;
            }
            else if (frames > slot.config.max_frames)
            {
                // The platform capacity was captured at successful open. Never
                // let a read/callback overrun the preallocated DSP contract.
                result = AAudioProcessResult::kProcessorError;
            }
            else
            {
                result = slot.process(slot.handle,
                                      buffer,
                                      buffer,
                                      frames,
                                      slot.format) == ECHIDNA_RESULT_OK
                             ? AAudioProcessResult::kProcessed
                             : AAudioProcessResult::kProcessorError;
            }
            releaseSlot(slot);
            break;
        }
        releaseAdmission();
        return result;
    }

    int AAudioStreamRegistry::dispatchCallback(void *stream,
                                               void *platform_input,
                                               int32_t frames,
                                               AAudioAppDataCallback app_callback,
                                               void *user_data,
                                               AAudioProcessResult *out_result)
    {
        // The application callback must always run; it receives the transformed
        // scratch on success and the untouched platform input on every other
        // path. The platform-owned input pointer is never written here.
        void *app_buffer = platform_input;
        AAudioProcessResult result = AAudioProcessResult::kUnavailable;

        if (!stream || !platform_input || frames <= 0)
        {
            result = AAudioProcessResult::kUnavailable;
        }
        else if (!acquireAdmission())
        {
            result = AAudioProcessResult::kBypassed;
        }
        else
        {
            for (Slot &slot : slots_)
            {
                if (slot.stream.load(std::memory_order_acquire) != stream ||
                    !acquireSlot(slot, stream))
                {
                    continue;
                }
                if (slot.owner != static_cast<uint32_t>(AAudioProcessOwner::kCallback))
                {
                    result = AAudioProcessResult::kNotOwner;
                }
                else if (slot.handle == 0 || !slot.process || !slot.scratch)
                {
                    result = AAudioProcessResult::kUnavailable;
                }
                else if (static_cast<uint32_t>(frames) > slot.config.max_frames)
                {
                    // The platform capacity was captured at successful open.
                    // Never let a callback overrun the preallocated scratch.
                    result = AAudioProcessResult::kProcessorError;
                }
                else
                {
                    result = slot.process(slot.handle,
                                          platform_input,
                                          slot.scratch.get(),
                                          static_cast<uint32_t>(frames),
                                          slot.format) == ECHIDNA_RESULT_OK
                                 ? AAudioProcessResult::kProcessed
                                 : AAudioProcessResult::kProcessorError;
                    if (result == AAudioProcessResult::kProcessed)
                    {
                        app_buffer = slot.scratch.get();
                    }
                }
                // Drop the global admission reference once the DSP call is done,
                // but hold the slot's in-flight reference across the application
                // callback so the scratch cannot be reclaimed under it.
                releaseAdmission();
                const int callback_result =
                    app_callback ? app_callback(stream, user_data, app_buffer, frames)
                                 : 0;
                releaseSlot(slot);
                if (out_result)
                {
                    *out_result = result;
                }
                return callback_result;
            }
            // Admitted but no matching slot: release admission and fail open.
            releaseAdmission();
        }

        const int callback_result =
            app_callback ? app_callback(stream, user_data, app_buffer, frames) : 0;
        if (out_result)
        {
            *out_result = result;
        }
        return callback_result;
    }

    void AAudioStreamRegistry::close(void *stream)
    {
        if (!stream)
        {
            return;
        }
        MaintenanceGuard guard(*this);
        for (Slot &slot : slots_)
        {
            if (!slot.allocated || slot.stream.load(std::memory_order_acquire) != stream)
            {
                continue;
            }
            slot.usage.fetch_and(~kActiveMask, std::memory_order_acq_rel);
            slot.stream.store(nullptr, std::memory_order_release);
            while ((slot.usage.load(std::memory_order_acquire) & kInFlightMask) != 0)
            {
                std::this_thread::yield();
            }
            if (slot.handle != 0 && slot.destroy)
            {
                (void)slot.destroy(slot.handle);
            }
            slot.handle = 0;
            slot.config = {};
            slot.format = 0;
            slot.create = nullptr;
            slot.process = nullptr;
            slot.update = nullptr;
            slot.destroy = nullptr;
            slot.owner = 0;
            // The scratch is only released after every in-flight callback has
            // drained above, so no live dispatch can still hold its pointer.
            slot.scratch.reset();
            slot.scratch_bytes = 0;
            slot.allocated = false;
            slot.usage.store(0, std::memory_order_release);
            return;
        }
    }

    bool AAudioStreamRegistry::publishProfile(uint64_t snapshot_generation,
                                              bool admitted,
                                              std::string_view preset_json,
                                              const AAudioDspApi &api)
    {
        MaintenanceGuard guard(*this);
        if (!api.complete() || snapshot_generation == 0 ||
            snapshot_generation < snapshot_generation_ ||
            (admitted && preset_json.empty()))
        {
            stopAdmission();
            return false;
        }
        stopAdmission();
        if (publication_ >= kMaxPublication)
        {
            return false;
        }

        std::string retained_preset;
        try
        {
            if (admitted)
            {
                retained_preset.assign(preset_json);
            }
        }
        catch (...)
        {
            return false;
        }

        const uint64_t next_publication = publication_ + 1;
        bool updated = true;
        for (Slot &slot : slots_)
        {
            if (!slot.allocated)
            {
                continue;
            }
            const char *preset = admitted ? retained_preset.data() : nullptr;
            const size_t length = admitted ? retained_preset.size() : 0;
            if (slot.handle == 0)
            {
                echidna_stream_handle_t recovered = 0;
                const bool created = slot.create && slot.update && slot.destroy &&
                                     slot.create(&slot.config, &recovered) ==
                                         ECHIDNA_RESULT_OK &&
                                     recovered != 0;
                if (created &&
                    slot.update(recovered, preset, length, next_publication) ==
                        ECHIDNA_RESULT_OK)
                {
                    slot.handle = recovered;
                }
                else
                {
                    if (recovered != 0 && slot.destroy)
                    {
                        (void)slot.destroy(recovered);
                    }
                    updated = false;
                }
            }
            else if (!slot.update ||
                     slot.update(slot.handle, preset, length, next_publication) !=
                         ECHIDNA_RESULT_OK)
            {
                updated = false;
            }
        }

        snapshot_generation_ = snapshot_generation;
        publication_ = next_publication;
        has_snapshot_ = true;
        admitted_ = admitted && updated;
        preset_json_ = admitted_ ? std::move(retained_preset) : std::string{};
        if (admitted_)
        {
            admission_usage_.store(kActiveMask, std::memory_order_release);
        }
        return updated;
    }
} // namespace echidna::hooks
