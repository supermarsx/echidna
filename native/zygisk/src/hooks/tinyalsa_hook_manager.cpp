#include "hooks/tinyalsa_hook_manager.h"

/**
 * @file tinyalsa_hook_manager.cpp
 * @brief Track exact tinyalsa input contracts from pcm_open through pcm_close.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <time.h>

#include "echidna/api.h"
#include "hooks/capture_buffer_router.h"
#include "hooks/tinyalsa_contract.h"
#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna::hooks
{
    namespace
    {
        using PcmReadFn = int (*)(void *, void *, unsigned int);
        using PcmOpenFn = void *(*)(unsigned int,
                                    unsigned int,
                                    unsigned int,
                                    TinyAlsaConfigPrefix *);
        using PcmCloseFn = int (*)(void *);

        PcmReadFn gOriginalRead = nullptr;
        PcmReadFn gOriginalReadi = nullptr;
        PcmReadFn gOriginalMmapRead = nullptr;
        PcmOpenFn gOriginalOpen = nullptr;
        PcmCloseFn gOriginalClose = nullptr;

        struct alignas(64) PcmContext
        {
            std::atomic<bool> claimed{false};
            std::atomic<bool> active{false};
            void *pcm{nullptr};
            TinyAlsaPcmContract contract{};
        };

        constexpr size_t kMaxPcmLifetimes = 1024;
        std::array<PcmContext, kMaxPcmLifetimes> gPcmContexts;
        thread_local uint32_t gReadDepth = 0;

        PcmContext *FindPcm(void *pcm)
        {
            if (!pcm)
            {
                return nullptr;
            }
            for (auto &context : gPcmContexts)
            {
                if (context.active.load(std::memory_order_acquire) && context.pcm == pcm)
                {
                    return &context;
                }
            }
            return nullptr;
        }

        void RegisterPcm(void *pcm, const TinyAlsaPcmContract &contract)
        {
            if (!pcm)
            {
                return;
            }
            for (auto &context : gPcmContexts)
            {
                if (context.active.load(std::memory_order_acquire) && context.pcm == pcm)
                {
                    context.active.store(false, std::memory_order_release);
                }
            }
            for (auto &context : gPcmContexts)
            {
                bool expected = false;
                if (!context.claimed.compare_exchange_strong(expected,
                                                             true,
                                                             std::memory_order_acq_rel,
                                                             std::memory_order_relaxed))
                {
                    continue;
                }
                context.pcm = pcm;
                context.contract = contract;
                context.active.store(true, std::memory_order_release);
                return;
            }
        }

        void RetirePcm(void *pcm)
        {
            for (auto &context : gPcmContexts)
            {
                if (context.active.load(std::memory_order_acquire) && context.pcm == pcm)
                {
                    context.active.store(false, std::memory_order_release);
                }
            }
        }

        class ReadDepthGuard
        {
        public:
            ReadDepthGuard() : outermost_(gReadDepth++ == 0) {}
            ~ReadDepthGuard() { --gReadDepth; }
            bool outermost() const { return outermost_; }

        private:
            bool outermost_;
        };

        bool ProcessingAllowed()
        {
            return state::SharedState::instance().audioProcessingAllowed();
        }

        bool ProcessBuffer(void *data,
                           size_t bytes,
                           const TinyAlsaPcmContract &contract)
        {
            return RouteCaptureBufferInPlace(data,
                                             bytes,
                                             contract.format,
                                             contract.sample_rate,
                                             contract.channels,
                                             echidna_process_block);
        }

        void RecordProcessing(const timespec &wall_start,
                              const timespec &cpu_start,
                              bool processed)
        {
            timespec wall_end{};
            timespec cpu_end{};
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
            clock_gettime(CLOCK_MONOTONIC, &wall_end);
            const int64_t wall_ns =
                (static_cast<int64_t>(wall_end.tv_sec) - wall_start.tv_sec) * 1000000000ll +
                (static_cast<int64_t>(wall_end.tv_nsec) - wall_start.tv_nsec);
            const int64_t cpu_ns =
                (static_cast<int64_t>(cpu_end.tv_sec) - cpu_start.tv_sec) * 1000000000ll +
                (static_cast<int64_t>(cpu_end.tv_nsec) - cpu_start.tv_nsec);
            const uint64_t timestamp_ns =
                static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                static_cast<uint64_t>(wall_end.tv_nsec);
            auto &shared_state = state::SharedState::instance();
            shared_state.telemetry().recordCallback(
                timestamp_ns,
                static_cast<uint32_t>(std::max<int64_t>(wall_ns, 0) / 1000),
                static_cast<uint32_t>(std::max<int64_t>(cpu_ns, 0) / 1000),
                utils::kTelemetryFlagCallback |
                    (processed ? utils::kTelemetryFlagDsp
                               : utils::kTelemetryFlagError),
                0);
            shared_state.setStatus(state::InternalStatus::kHooked);
        }

        int ForwardReadBytes(void *pcm,
                             void *data,
                             unsigned int bytes,
                             PcmReadFn original)
        {
            ReadDepthGuard depth;
            const int result = original ? original(pcm, data, bytes) : -1;
            // pcm_read and pcm_mmap_read are documented to return zero only
            // after filling the requested byte count. A nested pcm_readi call
            // is processed by the outer wrapper exactly once.
            if (!depth.outermost() || result != 0 || !data || bytes == 0 ||
                !ProcessingAllowed())
            {
                return result;
            }
            PcmContext *context = FindPcm(pcm);
            if (!context)
            {
                return result;
            }
            timespec wall_start{};
            timespec cpu_start{};
            clock_gettime(CLOCK_MONOTONIC, &wall_start);
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
            const bool processed = ProcessBuffer(data, bytes, context->contract);
            RecordProcessing(wall_start, cpu_start, processed);
            return result;
        }

        int ForwardReadFrames(void *pcm,
                              void *data,
                              unsigned int requested_frames,
                              PcmReadFn original)
        {
            ReadDepthGuard depth;
            const int result = original ? original(pcm, data, requested_frames) : -1;
            if (!depth.outermost() || result <= 0 || !data || !ProcessingAllowed())
            {
                return result;
            }
            PcmContext *context = FindPcm(pcm);
            if (!context)
            {
                return result;
            }
            const uint32_t frames = std::min<uint32_t>(
                static_cast<uint32_t>(result), requested_frames);
            const auto bytes = TinyAlsaBytesForFrames(context->contract, frames);
            if (!bytes)
            {
                return result;
            }
            timespec wall_start{};
            timespec cpu_start{};
            clock_gettime(CLOCK_MONOTONIC, &wall_start);
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
            const bool processed = ProcessBuffer(data, *bytes, context->contract);
            RecordProcessing(wall_start, cpu_start, processed);
            return result;
        }

        void *ForwardOpen(unsigned int card,
                          unsigned int device,
                          unsigned int flags,
                          TinyAlsaConfigPrefix *config)
        {
            void *pcm = gOriginalOpen ? gOriginalOpen(card, device, flags, config) : nullptr;
            const auto contract = ParseTinyAlsaContract(flags, config);
            if (pcm && contract &&
                echidna_prepare_stream(contract->sample_rate, contract->channels) ==
                    ECHIDNA_RESULT_OK)
            {
                RegisterPcm(pcm, *contract);
            }
            return pcm;
        }

        int ForwardClose(void *pcm)
        {
            const int result = gOriginalClose ? gOriginalClose(pcm) : -1;
            if (result == 0)
            {
                RetirePcm(pcm);
            }
            return result;
        }
    } // namespace

    TinyAlsaHookManager::TinyAlsaHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool TinyAlsaHookManager::install()
    {
        last_info_ = {};
        constexpr const char *kLibrary = "libtinyalsa.so";
        void *open = resolver_.findSymbol(kLibrary, "pcm_open");
        void *close = resolver_.findSymbol(kLibrary, "pcm_close");
        if (!open || !close)
        {
            last_info_.reason = "lifecycle_symbols_not_found";
            return false;
        }
        const bool open_installed = hook_open_.install(
            open,
            reinterpret_cast<void *>(&ForwardOpen),
            reinterpret_cast<void **>(&gOriginalOpen));
        const bool close_installed = hook_close_.install(
            close,
            reinterpret_cast<void *>(&ForwardClose),
            reinterpret_cast<void **>(&gOriginalClose));
        if (!open_installed || !close_installed)
        {
            last_info_.reason = "lifecycle_hook_failed";
            return false;
        }

        struct ReadCandidate
        {
            const char *symbol;
            runtime::InlineHook *hook;
            void *replacement;
            PcmReadFn *original;
        };
        ReadCandidate candidates[] = {
            {"pcm_read", &hook_read_, reinterpret_cast<void *>(&ReplacementRead), &gOriginalRead},
            {"pcm_readi", &hook_readi_, reinterpret_cast<void *>(&ReplacementReadi), &gOriginalReadi},
            {"pcm_mmap_read", &hook_mmap_read_, reinterpret_cast<void *>(&ReplacementMmapRead), &gOriginalMmapRead},
        };
        bool installed = false;
        bool failed = false;
        for (auto &candidate : candidates)
        {
            void *target = resolver_.findSymbol(kLibrary, candidate.symbol);
            if (!target)
            {
                continue;
            }
            const bool success = candidate.hook->install(
                target,
                candidate.replacement,
                reinterpret_cast<void **>(candidate.original));
            if (success && !installed)
            {
                last_info_.symbol = candidate.symbol;
            }
            installed = installed || success;
            failed = failed || !success;
        }
        if (!installed)
        {
            last_info_.reason = failed ? "read_hook_failed" : "read_symbols_not_found";
            return false;
        }
        last_info_.success = true;
        last_info_.library = kLibrary;
        __android_log_print(ANDROID_LOG_INFO,
                            "echidna",
                            "tinyalsa input lifecycle and read hooks installed");
        return true;
    }

    int TinyAlsaHookManager::ReplacementRead(void *pcm, void *data, unsigned int count)
    {
        return ForwardReadBytes(pcm, data, count, gOriginalRead);
    }

    int TinyAlsaHookManager::ReplacementReadi(void *pcm, void *data, unsigned int frames)
    {
        return ForwardReadFrames(pcm, data, frames, gOriginalReadi);
    }

    int TinyAlsaHookManager::ReplacementMmapRead(void *pcm,
                                                 void *data,
                                                 unsigned int count)
    {
        return ForwardReadBytes(pcm, data, count, gOriginalMmapRead);
    }
} // namespace echidna::hooks
