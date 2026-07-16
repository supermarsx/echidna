#include "hooks/tinyalsa_hook_manager.h"

/**
 * @file tinyalsa_hook_manager.cpp
 * @brief Gate tinyalsa hooks to mapped compatible targets and isolate pcm state.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string_view>

#include "echidna/api.h"
#include "hooks/tinyalsa_contract.h"
#include "hooks/tinyalsa_stream_registry.h"
#include "hooks/tinyalsa_target_gate.h"
#include "runtime/profile_sync_protocol.h"
#include "state/shared_state.h"
#include "utils/proc_maps_scanner.h"
#include "utils/telemetry_accumulator.h"

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
        using PcmIsReadyFn = int (*)(void *);
        using PcmFormatToBitsFn = unsigned int (*)(int32_t);

        PcmReadFn gOriginalRead = nullptr;
        PcmReadFn gOriginalReadi = nullptr;
        PcmReadFn gOriginalMmapRead = nullptr;
        PcmOpenFn gOriginalOpen = nullptr;
        PcmCloseFn gOriginalClose = nullptr;
        PcmIsReadyFn gPcmIsReady = nullptr;
        PcmFormatToBitsFn gPcmFormatToBits = nullptr;
        std::atomic<bool> gHooksReady{false};
        TinyAlsaStreamRegistry gPcmStreams;
        const TinyAlsaDspApi gDspApi{
            echidna_stream_create,
            echidna_stream_process,
            echidna_stream_update,
            echidna_stream_destroy,
        };
        thread_local uint32_t gReadDepth = 0;

        class ReadDepthGuard
        {
        public:
            ReadDepthGuard() : outermost_(gReadDepth++ == 0) {}
            ~ReadDepthGuard() { --gReadDepth; }
            [[nodiscard]] bool outermost() const { return outermost_; }

        private:
            bool outermost_;
        };

        bool TargetMapsTinyAlsa()
        {
            utils::ProcMapsScanner scanner;
            const auto &regions = scanner.regions();
            return std::any_of(regions.begin(), regions.end(), [](const auto &region)
                               { return IsTinyAlsaExecutableMapping(
                                     region.path, region.permissions); });
        }

        bool ProcessingAllowed()
        {
            return state::SharedState::instance().audioProcessingAllowed();
        }

        void RecordNonDspOutcome(uint32_t frames, TinyAlsaProcessResult result)
        {
            if (result == TinyAlsaProcessResult::kProcessed)
            {
                return;
            }
            const auto outcome = result == TinyAlsaProcessResult::kBypassed
                                     ? utils::TelemetryBlockOutcome::kBypassed
                                     : utils::TelemetryBlockOutcome::kFailure;
            state::SharedState::instance().telemetry().recordBlock(
                utils::TelemetryRoute::kTinyAlsa, frames, outcome);
        }

        int ForwardReadBytes(void *pcm,
                             void *data,
                             unsigned int bytes,
                             PcmReadFn original)
        {
            ReadDepthGuard depth;
            const int result = original ? original(pcm, data, bytes) : -1;
            const auto completed_bytes = TinyAlsaCompletedByteRead(result, bytes);
            if (!depth.outermost() || !completed_bytes || !data ||
                !gHooksReady.load(std::memory_order_acquire))
            {
                return result;
            }

            utils::ScopedTelemetryRoute route(utils::TelemetryRoute::kTinyAlsa);
            uint32_t frames = 0;
            if (!gPcmStreams.framesForBytes(pcm, *completed_bytes, &frames))
            {
                RecordNonDspOutcome(0, TinyAlsaProcessResult::kProcessorError);
                return result;
            }
            if (!ProcessingAllowed())
            {
                RecordNonDspOutcome(frames, TinyAlsaProcessResult::kBypassed);
                return result;
            }
            const TinyAlsaProcessResult processed =
                gPcmStreams.processBytes(pcm, data, *completed_bytes);
            RecordNonDspOutcome(frames, processed);
            return result;
        }

        int ForwardReadFrames(void *pcm,
                              void *data,
                              unsigned int requested_frames,
                              PcmReadFn original)
        {
            ReadDepthGuard depth;
            const int result =
                original ? original(pcm, data, requested_frames) : -1;
            const auto completed_frames =
                TinyAlsaCompletedFrameRead(result, requested_frames);
            if (!depth.outermost() || !completed_frames || !data ||
                !gHooksReady.load(std::memory_order_acquire))
            {
                return result;
            }

            const uint32_t frames = *completed_frames;
            utils::ScopedTelemetryRoute route(utils::TelemetryRoute::kTinyAlsa);
            if (!ProcessingAllowed())
            {
                RecordNonDspOutcome(frames, TinyAlsaProcessResult::kBypassed);
                return result;
            }
            const TinyAlsaProcessResult processed =
                gPcmStreams.processFrames(pcm, data, frames);
            RecordNonDspOutcome(frames, processed);
            return result;
        }

        void *ForwardOpen(unsigned int card,
                          unsigned int device,
                          unsigned int flags,
                          TinyAlsaConfigPrefix *config)
        {
            void *pcm =
                gOriginalOpen ? gOriginalOpen(card, device, flags, config) : nullptr;
            if (!pcm || !gHooksReady.load(std::memory_order_acquire) ||
                !gPcmIsReady || gPcmIsReady(pcm) != 1 || !gPcmFormatToBits ||
                !config)
            {
                return pcm;
            }
            const uint32_t format_bits = gPcmFormatToBits(config->format);
            const auto contract =
                ParseTinyAlsaContract(flags, config, format_bits);
            if (contract && !gPcmStreams.open(pcm, *contract, gDspApi))
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    "echidna",
                                    "tinyalsa pcm opened without isolated DSP handle");
            }
            return pcm;
        }

        int ForwardClose(void *pcm)
        {
            if (gHooksReady.load(std::memory_order_acquire))
            {
                gPcmStreams.close(pcm);
            }
            return gOriginalClose ? gOriginalClose(pcm) : -1;
        }
    } // namespace

    TinyAlsaHookManager::TinyAlsaHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool PublishTinyAlsaProfile(
        const runtime::DecodedProfileSnapshot &snapshot)
    {
        const bool published = gPcmStreams.publishProfile(
            snapshot.generation,
            snapshot.nativeProcessAdmitted(),
            snapshot.preset_json,
            gDspApi);
        if (!published)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                "echidna",
                                "tinyalsa profile publication failed generation=%llu",
                                static_cast<unsigned long long>(snapshot.generation));
        }
        return published;
    }

    bool TinyAlsaHookManager::install()
    {
        last_info_ = {};
        gHooksReady.store(false, std::memory_order_release);
        constexpr const char *kLibrary = "libtinyalsa.so";
        if (!TargetMapsTinyAlsa())
        {
            last_info_.reason = "library_not_mapped_in_target_process";
            return false;
        }

        void *open = resolver_.findSymbol(kLibrary, "pcm_open");
        void *close = resolver_.findSymbol(kLibrary, "pcm_close");
        void *is_ready = resolver_.findSymbol(kLibrary, "pcm_is_ready");
        void *format_to_bits =
            resolver_.findSymbol(kLibrary, "pcm_format_to_bits");
        void *read = resolver_.findSymbol(kLibrary, "pcm_read");
        void *readi = resolver_.findSymbol(kLibrary, "pcm_readi");
        void *mmap_read = resolver_.findSymbol(kLibrary, "pcm_mmap_read");
        const TinyAlsaSymbolEvidence symbols{
            .open = open != nullptr,
            .close = close != nullptr,
            .is_ready = is_ready != nullptr,
            .format_to_bits = format_to_bits != nullptr,
            .read = read != nullptr,
            .readi = readi != nullptr,
            .mmap_read = mmap_read != nullptr,
        };
        if (!IsCompatibleTinyAlsaTarget(true, symbols))
        {
            last_info_.reason = "incompatible_tinyalsa_symbols";
            return false;
        }
        gPcmIsReady = reinterpret_cast<PcmIsReadyFn>(is_ready);
        gPcmFormatToBits = reinterpret_cast<PcmFormatToBitsFn>(format_to_bits);

        const TinyAlsaHookInstallation installed = InstallTinyAlsaHookSet(
            symbols,
            [this, open, close, read, readi, mmap_read](TinyAlsaHookRole role)
            {
                switch (role)
                {
                case TinyAlsaHookRole::kClose:
                    return hook_close_.install(
                        close,
                        reinterpret_cast<void *>(&ForwardClose),
                        reinterpret_cast<void **>(&gOriginalClose));
                case TinyAlsaHookRole::kRead:
                    return hook_read_.install(
                        read,
                        reinterpret_cast<void *>(&ReplacementRead),
                        reinterpret_cast<void **>(&gOriginalRead));
                case TinyAlsaHookRole::kReadi:
                    return hook_readi_.install(
                        readi,
                        reinterpret_cast<void *>(&ReplacementReadi),
                        reinterpret_cast<void **>(&gOriginalReadi));
                case TinyAlsaHookRole::kMmapRead:
                    return hook_mmap_read_.install(
                        mmap_read,
                        reinterpret_cast<void *>(&ReplacementMmapRead),
                        reinterpret_cast<void **>(&gOriginalMmapRead));
                case TinyAlsaHookRole::kOpen:
                    return hook_open_.install(
                        open,
                        reinterpret_cast<void *>(&ForwardOpen),
                        reinterpret_cast<void **>(&gOriginalOpen));
                }
                return false;
            });
        if (!installed.complete())
        {
            last_info_.reason = "lifecycle_hook_failed";
            return false;
        }

        gHooksReady.store(true, std::memory_order_release);
        last_info_.success = true;
        last_info_.library = kLibrary;
        last_info_.symbol = installed.read
                                ? "pcm_read"
                                : (installed.readi ? "pcm_readi" : "pcm_mmap_read");
        __android_log_print(ANDROID_LOG_INFO,
                            "echidna",
                            "tinyalsa mapped target lifecycle hooks installed");
        return true;
    }

    int TinyAlsaHookManager::ReplacementRead(void *pcm,
                                             void *data,
                                             unsigned int count)
    {
        return ForwardReadBytes(pcm, data, count, gOriginalRead);
    }

    int TinyAlsaHookManager::ReplacementReadi(void *pcm,
                                              void *data,
                                              unsigned int frames)
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
