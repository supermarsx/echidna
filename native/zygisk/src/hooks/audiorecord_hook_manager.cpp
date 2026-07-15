#include "hooks/audiorecord_hook_manager.h"

/**
 * @file audiorecord_hook_manager.cpp
 * @brief Hook the exact modern AudioRecord::read ABI with explicit PCM metadata.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <cstdlib>
#include <optional>
#include <string>
#include <time.h>

#include "echidna/api.h"
#include "hooks/audiohal_contract.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna::hooks
{
    namespace
    {
        using ReadFn = ssize_t (*)(void *, void *, size_t, bool);
        ReadFn gOriginalRead = nullptr;
        std::optional<AudioHalPcmContract> gPcmContract;

        std::optional<AudioHalPcmContract> ReadExplicitContract()
        {
            return ParseAudioHalPcmContract(std::getenv("ECHIDNA_AR_SR"),
                                            std::getenv("ECHIDNA_AR_CH"),
                                            std::getenv("ECHIDNA_AR_FORMAT"));
        }

        bool ProcessingAllowed()
        {
            return state::SharedState::instance().audioProcessingAllowed();
        }

        ssize_t ForwardRead(void *instance, void *buffer, size_t bytes, bool blocking)
        {
            const ssize_t result =
                gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking) : -1;
            if (result <= 0 || !buffer || !gPcmContract || !ProcessingAllowed())
            {
                return result;
            }

            timespec wall_start{};
            timespec cpu_start{};
            clock_gettime(CLOCK_MONOTONIC, &wall_start);
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
            const bool processed = RouteCaptureBufferInPlace(
                buffer,
                static_cast<size_t>(result),
                gPcmContract->format,
                gPcmContract->sample_rate,
                gPcmContract->channels,
                echidna_process_block);

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
            return result;
        }
    } // namespace

    AudioRecordHookManager::AudioRecordHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool AudioRecordHookManager::install()
    {
        last_info_ = {};
        gPcmContract = ReadExplicitContract();
        if (!gPcmContract)
        {
            last_info_.reason = "explicit_pcm_contract_required";
            return false;
        }
        if (echidna_prepare_stream(gPcmContract->sample_rate, gPcmContract->channels) !=
            ECHIDNA_RESULT_OK)
        {
            last_info_.reason = "dsp_prepare_failed";
            return false;
        }

        // This is the only ABI accepted by the replacement:
        // AudioRecord::read(void *, size_t, bool). Legacy unsigned-int and
        // no-bool overloads must not be called through this function pointer.
        constexpr const char *kSymbol = "_ZN7android11AudioRecord4readEPvmb";
        constexpr const char *kLibraries[] = {
            "libaudioclient.so",
            "libmedia.so",
        };
        for (const char *library : kLibraries)
        {
            void *target = resolver_.findSymbol(library, kSymbol);
            if (!target)
            {
                continue;
            }
            if (hook_.install(target,
                              reinterpret_cast<void *>(&ForwardRead),
                              reinterpret_cast<void **>(&gOriginalRead)))
            {
                last_info_.success = true;
                last_info_.library = library;
                last_info_.symbol = kSymbol;
                __android_log_print(ANDROID_LOG_INFO,
                                    "echidna",
                                    "AudioRecord exact-ABI capture hook installed at %s",
                                    library);
                return true;
            }
            last_info_.library = library;
            last_info_.symbol = kSymbol;
            last_info_.reason = "hook_failed";
        }
        if (last_info_.reason.empty())
        {
            last_info_.reason = "exact_symbol_not_found";
        }
        return false;
    }
} // namespace echidna::hooks
