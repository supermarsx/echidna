#include "hooks/tinyalsa_stream_registry.h"

#include <thread>
#include <utility>

namespace echidna::hooks
{
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "tinyalsa RT admission requires lock-free 32-bit atomics");
    static_assert(std::atomic<void *>::is_always_lock_free,
                  "tinyalsa RT lookup requires lock-free pointer atomics");

    TinyAlsaStreamRegistry::MaintenanceGuard::MaintenanceGuard(
        TinyAlsaStreamRegistry &registry)
        : registry_(registry)
    {
        registry_.lockMaintenance();
    }

    TinyAlsaStreamRegistry::MaintenanceGuard::~MaintenanceGuard()
    {
        registry_.unlockMaintenance();
    }

    void TinyAlsaStreamRegistry::lockMaintenance()
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

    void TinyAlsaStreamRegistry::unlockMaintenance()
    {
        maintenance_gate_.store(0, std::memory_order_release);
    }

    bool TinyAlsaStreamRegistry::validContract(
        const TinyAlsaPcmContract &contract)
    {
        const auto &config = contract.stream;
        const uint32_t expected_bytes =
            config.format == ECHIDNA_PCM_FORMAT_SIGNED_16 ? 2U : 4U;
        return config.struct_size == sizeof(config) &&
               config.sample_rate >= 8000 && config.sample_rate <= 384000 &&
               config.channel_count > 0 && config.channel_count <= 8 &&
               config.max_frames > 0 &&
               config.max_frames <=
                   kTinyAlsaMaxPreparedSamples / config.channel_count &&
               (config.format == ECHIDNA_PCM_FORMAT_SIGNED_16 ||
                config.format == ECHIDNA_PCM_FORMAT_FLOAT_32) &&
               contract.bytes_per_frame ==
                   config.channel_count * expected_bytes;
    }

    bool TinyAlsaStreamRegistry::acquireAdmission()
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

    void TinyAlsaStreamRegistry::releaseAdmission()
    {
        admission_usage_.fetch_sub(1, std::memory_order_acq_rel);
    }

    bool TinyAlsaStreamRegistry::acquireSlot(Slot &slot, void *pcm)
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
                if (slot.pcm.load(std::memory_order_acquire) != pcm)
                {
                    releaseSlot(slot);
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    void TinyAlsaStreamRegistry::releaseSlot(Slot &slot)
    {
        slot.usage.fetch_sub(1, std::memory_order_acq_rel);
    }

    void TinyAlsaStreamRegistry::retireSlot(Slot &slot)
    {
        slot.usage.fetch_and(~kActiveMask, std::memory_order_acq_rel);
        slot.pcm.store(nullptr, std::memory_order_release);
        while ((slot.usage.load(std::memory_order_acquire) & kInFlightMask) != 0)
        {
            std::this_thread::yield();
        }
        if (slot.handle != 0 && slot.destroy)
        {
            (void)slot.destroy(slot.handle);
        }
        slot.handle = 0;
        slot.contract = {};
        slot.create = nullptr;
        slot.process = nullptr;
        slot.update = nullptr;
        slot.destroy = nullptr;
        slot.allocated = false;
        slot.usage.store(0, std::memory_order_release);
    }

    void TinyAlsaStreamRegistry::stopAdmission()
    {
        admission_usage_.fetch_and(~kActiveMask, std::memory_order_acq_rel);
        while ((admission_usage_.load(std::memory_order_acquire) & kInFlightMask) != 0)
        {
            std::this_thread::yield();
        }
    }

    bool TinyAlsaStreamRegistry::open(void *pcm,
                                      const TinyAlsaPcmContract &contract,
                                      const TinyAlsaDspApi &api)
    {
        if (!pcm || !validContract(contract) || !api.complete())
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        for (Slot &slot : slots_)
        {
            if (slot.allocated && slot.pcm.load(std::memory_order_acquire) == pcm)
            {
                retireSlot(slot);
                break;
            }
        }

        Slot *target = nullptr;
        for (Slot &slot : slots_)
        {
            if (!slot.allocated && slot.usage.load(std::memory_order_acquire) == 0)
            {
                target = &slot;
                break;
            }
        }
        if (!target)
        {
            return false;
        }

        echidna_stream_handle_t handle = 0;
        bool ready = api.create(&contract.stream, &handle) == ECHIDNA_RESULT_OK &&
                     handle != 0;
        if (ready && has_snapshot_)
        {
            const char *preset = admitted_ ? preset_json_.data() : nullptr;
            const size_t length = admitted_ ? preset_json_.size() : 0;
            ready = api.update(handle, preset, length, publication_) ==
                    ECHIDNA_RESULT_OK;
        }
        if (!ready)
        {
            if (handle != 0)
            {
                (void)api.destroy(handle);
            }
            return false;
        }

        target->handle = handle;
        target->contract = contract;
        target->create = api.create;
        target->process = api.process;
        target->update = api.update;
        target->destroy = api.destroy;
        target->allocated = true;
        target->pcm.store(pcm, std::memory_order_relaxed);
        target->usage.store(kActiveMask, std::memory_order_release);
        return true;
    }

    TinyAlsaProcessResult TinyAlsaStreamRegistry::processBytes(
        void *pcm,
        void *buffer,
        uint32_t bytes)
    {
        return process(pcm, buffer, bytes, true);
    }

    bool TinyAlsaStreamRegistry::framesForBytes(void *pcm,
                                                uint32_t bytes,
                                                uint32_t *frames)
    {
        if (frames)
        {
            *frames = 0;
        }
        if (!pcm || bytes == 0 || !frames)
        {
            return false;
        }
        for (Slot &slot : slots_)
        {
            if (slot.pcm.load(std::memory_order_acquire) != pcm ||
                !acquireSlot(slot, pcm))
            {
                continue;
            }
            const uint32_t bytes_per_frame = slot.contract.bytes_per_frame;
            const bool valid = bytes_per_frame != 0 &&
                               bytes % bytes_per_frame == 0 &&
                               bytes / bytes_per_frame > 0 &&
                               bytes / bytes_per_frame <=
                                   slot.contract.stream.max_frames;
            if (valid)
            {
                *frames = bytes / bytes_per_frame;
            }
            releaseSlot(slot);
            return valid;
        }
        return false;
    }

    TinyAlsaProcessResult TinyAlsaStreamRegistry::processFrames(
        void *pcm,
        void *buffer,
        uint32_t frames)
    {
        return process(pcm, buffer, frames, false);
    }

    TinyAlsaProcessResult TinyAlsaStreamRegistry::process(
        void *pcm,
        void *buffer,
        uint32_t amount,
        bool amount_is_bytes)
    {
        if (!pcm || !buffer || amount == 0)
        {
            return TinyAlsaProcessResult::kUnavailable;
        }
        if (!acquireAdmission())
        {
            return TinyAlsaProcessResult::kBypassed;
        }

        TinyAlsaProcessResult result = TinyAlsaProcessResult::kUnavailable;
        for (Slot &slot : slots_)
        {
            if (slot.pcm.load(std::memory_order_acquire) != pcm ||
                !acquireSlot(slot, pcm))
            {
                continue;
            }
            uint32_t frames = amount;
            if (amount_is_bytes)
            {
                if (slot.contract.bytes_per_frame == 0 ||
                    amount % slot.contract.bytes_per_frame != 0)
                {
                    result = TinyAlsaProcessResult::kProcessorError;
                    releaseSlot(slot);
                    break;
                }
                frames = amount / slot.contract.bytes_per_frame;
            }
            if (slot.handle == 0 || !slot.process)
            {
                result = TinyAlsaProcessResult::kUnavailable;
            }
            else if (frames == 0 || frames > slot.contract.stream.max_frames)
            {
                result = TinyAlsaProcessResult::kProcessorError;
            }
            else
            {
                result = slot.process(slot.handle,
                                      buffer,
                                      buffer,
                                      frames,
                                      slot.contract.stream.format) ==
                                 ECHIDNA_RESULT_OK
                             ? TinyAlsaProcessResult::kProcessed
                             : TinyAlsaProcessResult::kProcessorError;
            }
            releaseSlot(slot);
            break;
        }
        releaseAdmission();
        return result;
    }

    void TinyAlsaStreamRegistry::close(void *pcm)
    {
        if (!pcm)
        {
            return;
        }
        MaintenanceGuard guard(*this);
        for (Slot &slot : slots_)
        {
            if (slot.allocated && slot.pcm.load(std::memory_order_acquire) == pcm)
            {
                retireSlot(slot);
                return;
            }
        }
    }

    bool TinyAlsaStreamRegistry::publishProfile(
        uint64_t snapshot_generation,
        bool admitted,
        std::string_view preset_json,
        const TinyAlsaDspApi &api)
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
        if (publication_ > kMaxPublication - 2)
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
            if (!slot.allocated || slot.handle == 0 || !slot.update)
            {
                continue;
            }
            const char *preset = admitted ? retained_preset.data() : nullptr;
            const size_t length = admitted ? retained_preset.size() : 0;
            if (slot.update(slot.handle, preset, length, next_publication) !=
                ECHIDNA_RESULT_OK)
            {
                updated = false;
            }
        }

        snapshot_generation_ = snapshot_generation;
        has_snapshot_ = true;
        if (!updated)
        {
            const uint64_t revoke_publication = next_publication + 1;
            for (Slot &slot : slots_)
            {
                if (slot.allocated && slot.handle != 0 && slot.update)
                {
                    (void)slot.update(slot.handle,
                                      nullptr,
                                      0,
                                      revoke_publication);
                }
            }
            publication_ = revoke_publication;
            admitted_ = false;
            preset_json_.clear();
            return false;
        }

        publication_ = next_publication;
        admitted_ = admitted;
        preset_json_ = admitted ? std::move(retained_preset) : std::string{};
        if (admitted_)
        {
            admission_usage_.store(kActiveMask, std::memory_order_release);
        }
        return true;
    }
} // namespace echidna::hooks
