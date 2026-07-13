#include "hooks/audiorecord_hook_manager.h"

/**
 * @file audiorecord_hook_manager.cpp
 * @brief Install inline hooks for AudioRecord::read and record telemetry on each
 * call.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_DEBUG 0
#define ANDROID_LOG_INFO 0
#endif

#include <atomic>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <time.h>
#include <unistd.h>

#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/api_level_probe.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"
#include "echidna/api.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using ReadFn = ssize_t (*)(void *, void *, size_t, bool);
            ReadFn gOriginalRead = nullptr;
            std::atomic<uint32_t> gLoggedReads{0};
            constexpr uint32_t kMaxLoggedReads = 8;
            constexpr const char *kProbePackage = "com.echidna.interceptionprobe";
            constexpr const char *kProbeEvidencePath =
                "/data/data/com.echidna.interceptionprobe/cache/"
                "echidna_audio_record_probe.log";

            struct SymbolCandidate
            {
                const char *library;
                const char *symbol;
                int min_api;
                int max_api;
            };

            uint32_t DefaultSampleRate()
            {
                if (const char *env = std::getenv("ECHIDNA_AR_SR"))
                {
                    const int sr = std::atoi(env);
                    if (sr > 8000 && sr < 192000)
                    {
                        return static_cast<uint32_t>(sr);
                    }
                }
                return 48000u;
            }

            uint32_t DefaultChannels()
            {
                if (const char *env = std::getenv("ECHIDNA_AR_CH"))
                {
                    const int ch = std::atoi(env);
                    if (ch >= 1 && ch <= 8)
                    {
                        return static_cast<uint32_t>(ch);
                    }
                }
                return 2u;
            }

            void WriteProbeEvidence(const std::string &process,
                                    ssize_t bytes,
                                    bool processed)
            {
                if (process != kProbePackage)
                {
                    return;
                }

                const int fd = open(kProbeEvidencePath,
                                    O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC,
                                    0600);
                if (fd < 0)
                {
                    return;
                }

                char line[192];
                const int length = std::snprintf(line,
                                                 sizeof(line),
                                                 "AudioRecord read intercepted "
                                                 "process='%s' bytes=%zd "
                                                 "processed=%d\n",
                                                 process.c_str(),
                                                 bytes,
                                                 processed ? 1 : 0);
                if (length > 0)
                {
                    const size_t write_length =
                        std::min(static_cast<size_t>(length), sizeof(line) - 1);
                    (void)write(fd, line, write_length);
                }
                close(fd);
            }

            ssize_t ForwardRead(void *instance, void *buffer, size_t bytes, bool blocking)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking) : 0;
                }

                uint32_t sample_rate = DefaultSampleRate();
                uint32_t channels = DefaultChannels();
                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
                ssize_t result = gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking)
                                               : static_cast<ssize_t>(bytes);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

                bool processed = false;
                if (result > 0 && buffer)
                {
                    processed =
                        RouteInt16CaptureBufferInPlace(buffer,
                                                       static_cast<size_t>(result),
                                                       sample_rate,
                                                       channels,
                                                       echidna_process_block);
                    const uint32_t logged =
                        gLoggedReads.fetch_add(1, std::memory_order_relaxed);
                    if (logged < kMaxLoggedReads)
                    {
                        WriteProbeEvidence(process, result, processed);
                        __android_log_print(ANDROID_LOG_DEBUG,
                                            "echidna",
                                            "AudioRecord read intercepted "
                                            "process='%s' bytes=%zd processed=%d",
                                            process.c_str(),
                                            result,
                                            processed ? 1 : 0);
                    }
                }

                const int64_t wall_ns_raw =
                    (static_cast<int64_t>(wall_end.tv_sec) -
                     static_cast<int64_t>(wall_start.tv_sec)) *
                        1000000000ll +
                    (static_cast<int64_t>(wall_end.tv_nsec) -
                     static_cast<int64_t>(wall_start.tv_nsec));
                const int64_t cpu_ns_raw =
                    (static_cast<int64_t>(cpu_end.tv_sec) -
                     static_cast<int64_t>(cpu_start.tv_sec)) *
                        1000000000ll +
                    (static_cast<int64_t>(cpu_end.tv_nsec) -
                     static_cast<int64_t>(cpu_start.tv_nsec));
                const uint32_t wall_us = static_cast<uint32_t>(
                    std::max<int64_t>(wall_ns_raw, 0ll) / 1000ll);
                const uint32_t cpu_us = static_cast<uint32_t>(
                    std::max<int64_t>(cpu_ns_raw, 0ll) / 1000ll);
                const uint64_t timestamp_ns =
                    static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                    static_cast<uint64_t>(wall_end.tv_nsec);

                state.telemetry().recordCallback(timestamp_ns,
                                                 wall_us,
                                                 cpu_us,
                                                 echidna::utils::kTelemetryFlagCallback,
                                                 0);
                state.setStatus(state::InternalStatus::kHooked);
                return result;
            }

        } // namespace

        AudioRecordHookManager::AudioRecordHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool AudioRecordHookManager::install()
        {
            last_info_ = {};
            static const SymbolCandidate kCandidates[] = {
                {"libaudioclient.so", "_ZN7android11AudioRecord4readEPvmb", 23, -1},
                // AudioRecord::read(void*, size_t, bool)
                {"libmedia.so", "_ZN7android11AudioRecord4readEPvmb", 23, -1},
                {"libaudioclient.so", "_ZN7android11AudioRecord4readEPvjb", 23, -1},
                {"libmedia.so", "_ZN7android11AudioRecord4readEPvjb", 23, -1},
                // AudioRecord::read(void*, unsigned int, bool)
                {"libmedia.so", "_ZN7android11AudioRecord4readEPvj", 0, 22},
                // AudioRecord::read(void*, unsigned int)
            };

            const int api_level = utils::ApiLevelProbe().apiLevel();
            bool skipped_by_guard = false;
            auto attempt_install = [&](bool enforce_guard) -> bool
            {
                for (const auto &candidate : kCandidates)
                {
                    if (enforce_guard)
                    {
                        if (api_level < candidate.min_api ||
                            (candidate.max_api >= 0 && api_level > candidate.max_api))
                        {
                            skipped_by_guard = true;
                            continue;
                        }
                    }
                    void *target = resolver_.findSymbol(candidate.library, candidate.symbol);
                    if (!target)
                    {
                        continue;
                    }
                    if (hook_.install(target, reinterpret_cast<void *>(&ForwardRead),
                                      reinterpret_cast<void **>(&gOriginalRead)))
                    {
                        active_symbol_ = candidate.symbol;
                        last_info_.success = true;
                        last_info_.library = candidate.library;
                        last_info_.symbol = candidate.symbol;
                        last_info_.reason.clear();
                        __android_log_print(ANDROID_LOG_INFO,
                                            "echidna",
                                            "AudioRecord hook installed at %s!%s",
                                            candidate.library,
                                            candidate.symbol);
                        return true;
                    }
                    last_info_.reason = "hook_failed";
                }
                return false;
            };

            if (attempt_install(true))
            {
                return true;
            }
            if (attempt_install(false))
            {
                if (last_info_.reason.empty())
                {
                    last_info_.reason = "api_guard_relaxed";
                }
                return true;
            }
            if (last_info_.reason.empty())
            {
                last_info_.reason = skipped_by_guard ? "api_guard_blocked" : "symbol_not_found";
            }
            return false;
        }

        ssize_t AudioRecordHookManager::Replacement(void *instance,
                                                    void *buffer,
                                                    size_t bytes,
                                                    bool blocking)
        {
            return ForwardRead(instance, buffer, bytes, blocking);
        }

    } // namespace hooks
} // namespace echidna
