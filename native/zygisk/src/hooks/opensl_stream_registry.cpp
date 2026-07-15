#include "hooks/opensl_stream_registry.h"

#include <thread>
#include <utility>

namespace echidna::hooks
{
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "OpenSL stream admission requires lock-free 32-bit atomics");
    static_assert(std::atomic<uintptr_t>::is_always_lock_free,
                  "OpenSL recorder lookup requires lock-free identity atomics");

    OpenSlStreamRegistry::MaintenanceGuard::MaintenanceGuard(
        OpenSlStreamRegistry &registry)
        : registry_(registry)
    {
        registry_.lockMaintenance();
    }

    OpenSlStreamRegistry::MaintenanceGuard::~MaintenanceGuard()
    {
        registry_.unlockMaintenance();
    }

    void OpenSlStreamRegistry::lockMaintenance()
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

    void OpenSlStreamRegistry::unlockMaintenance()
    {
        maintenance_gate_.store(0, std::memory_order_release);
    }

    bool OpenSlStreamRegistry::validConfig(const echidna_stream_config_t &config)
    {
        if (config.struct_size != sizeof(config) || config.sample_rate < 8000 ||
            config.sample_rate > 384000 || config.channel_count == 0 ||
            config.channel_count > 8 || config.max_frames == 0 ||
            config.max_frames > static_cast<uint32_t>(32768U / config.channel_count) ||
            (config.format != ECHIDNA_PCM_FORMAT_SIGNED_16 &&
             config.format != ECHIDNA_PCM_FORMAT_FLOAT_32))
        {
            return false;
        }
        for (uint32_t reserved : config.reserved)
        {
            if (reserved != 0)
            {
                return false;
            }
        }
        return true;
    }

    bool OpenSlStreamRegistry::acquireAdmission()
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

    void OpenSlStreamRegistry::releaseAdmission()
    {
        admission_usage_.fetch_sub(1, std::memory_order_acq_rel);
    }

    bool OpenSlStreamRegistry::acquireSlot(Slot &slot, uintptr_t recorder)
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
                if (slot.recorder.load(std::memory_order_acquire) != recorder)
                {
                    releaseSlot(slot);
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    void OpenSlStreamRegistry::releaseSlot(Slot &slot)
    {
        slot.usage.fetch_sub(1, std::memory_order_acq_rel);
    }

    void OpenSlStreamRegistry::stopAdmission()
    {
        admission_usage_.fetch_and(~kActiveMask, std::memory_order_acq_rel);
        while ((admission_usage_.load(std::memory_order_acquire) & kInFlightMask) != 0)
        {
            std::this_thread::yield();
        }
    }

    bool OpenSlStreamRegistry::open(uintptr_t recorder,
                                    const echidna_stream_config_t &config,
                                    const OpenSlDspApi &api)
    {
        if (!recorder || !validConfig(config) || !api.complete())
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        for (const Slot &slot : slots_)
        {
            if (slot.allocated && slot.recorder.load(std::memory_order_acquire) == recorder)
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
            }
            if (!ready)
            {
                return false;
            }

            slot.allocated = true;
            slot.handle = handle;
            slot.format = config.format;
            slot.process = api.process;
            slot.destroy = api.destroy;
            slot.recorder.store(recorder, std::memory_order_relaxed);
            slot.usage.store(kActiveMask, std::memory_order_release);
            return ready;
        }
        return false;
    }

    OpenSlProcessResult OpenSlStreamRegistry::process(uintptr_t recorder,
                                                      void *buffer,
                                                      uint32_t frames)
    {
        if (!recorder || !buffer || frames == 0)
        {
            return OpenSlProcessResult::kUnavailable;
        }
        if (!acquireAdmission())
        {
            return OpenSlProcessResult::kBypassed;
        }

        OpenSlProcessResult result = OpenSlProcessResult::kUnavailable;
        for (Slot &slot : slots_)
        {
            if (slot.recorder.load(std::memory_order_acquire) != recorder ||
                !acquireSlot(slot, recorder))
            {
                continue;
            }
            if (slot.handle == 0 || !slot.process)
            {
                result = OpenSlProcessResult::kUnavailable;
            }
            else
            {
                result = slot.process(slot.handle,
                                      buffer,
                                      buffer,
                                      frames,
                                      slot.format) == ECHIDNA_RESULT_OK
                             ? OpenSlProcessResult::kProcessed
                             : OpenSlProcessResult::kProcessorError;
            }
            releaseSlot(slot);
            break;
        }
        releaseAdmission();
        return result;
    }

    void OpenSlStreamRegistry::close(uintptr_t recorder)
    {
        if (!recorder)
        {
            return;
        }
        MaintenanceGuard guard(*this);
        for (Slot &slot : slots_)
        {
            if (!slot.allocated ||
                slot.recorder.load(std::memory_order_acquire) != recorder)
            {
                continue;
            }
            slot.usage.fetch_and(~kActiveMask, std::memory_order_acq_rel);
            slot.recorder.store(0, std::memory_order_release);
            while ((slot.usage.load(std::memory_order_acquire) & kInFlightMask) != 0)
            {
                std::this_thread::yield();
            }
            if (slot.handle != 0 && slot.destroy)
            {
                (void)slot.destroy(slot.handle);
            }
            slot.handle = 0;
            slot.format = 0;
            slot.process = nullptr;
            slot.destroy = nullptr;
            slot.allocated = false;
            slot.usage.store(0, std::memory_order_release);
            return;
        }
    }

    bool OpenSlStreamRegistry::publishProfile(uint64_t snapshot_generation,
                                              bool admitted,
                                              std::string_view preset_json,
                                              const OpenSlDspApi &api)
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
            if (!slot.allocated || slot.handle == 0)
            {
                continue;
            }
            const char *preset = admitted ? retained_preset.data() : nullptr;
            const size_t length = admitted ? retained_preset.size() : 0;
            if (api.update(slot.handle, preset, length, next_publication) !=
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
