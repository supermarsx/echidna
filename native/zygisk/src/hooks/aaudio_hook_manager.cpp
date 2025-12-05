#include "hooks/aaudio_hook_manager.h"

/**
 * @file aaudio_hook_manager.cpp
 * @brief Install inline hooks for AAudio data callback and forward telemetry
 * events.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 0
#define ANDROID_LOG_INFO 0
#endif
#include <unistd.h>

#include <algorithm>
#include <string>
#include <time.h>

#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"
#include "utils/process_utils.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using Callback = int (*)(void *, void *, void *, int32_t);
            Callback gOriginalCallback = nullptr;

            int ForwardCallback(void *stream, void *user, void *audio_data, int32_t num_frames)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalCallback ? gOriginalCallback(stream, user, audio_data, num_frames) : 0;
                }
                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
                int result = gOriginalCallback ? gOriginalCallback(stream, user, audio_data, num_frames) : 0;
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

                const int64_t wall_ns_raw = (static_cast<int64_t>(wall_end.tv_sec) - static_cast<int64_t>(wall_start.tv_sec)) *
                                                1000000000ll +
                                            (static_cast<int64_t>(wall_end.tv_nsec) - static_cast<int64_t>(wall_start.tv_nsec));
                const int64_t cpu_ns_raw = (static_cast<int64_t>(cpu_end.tv_sec) - static_cast<int64_t>(cpu_start.tv_sec)) *
                                               1000000000ll +
                                           (static_cast<int64_t>(cpu_end.tv_nsec) - static_cast<int64_t>(cpu_start.tv_nsec));
                const uint32_t wall_us = static_cast<uint32_t>(std::max<int64_t>(wall_ns_raw, 0ll) / 1000ll);
                const uint32_t cpu_us = static_cast<uint32_t>(std::max<int64_t>(cpu_ns_raw, 0ll) / 1000ll);
                const uint64_t timestamp_ns = static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
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

        AAudioHookManager::AAudioHookManager(utils::PltResolver &resolver) : resolver_(resolver) {}

        bool AAudioHookManager::install()
        {
            void *symbol = resolver_.findSymbol("libaaudio.so", "AAudioStream_dataCallback");
            if (!symbol)
            {
                return false;
            }

            if (!hook_.install(symbol, reinterpret_cast<void *>(&ForwardCallback), reinterpret_cast<void **>(&gOriginalCallback)))
            {
                __android_log_print(ANDROID_LOG_WARN, "echidna", "Failed to install AAudio hook");
                return false;
            }
            __android_log_print(ANDROID_LOG_INFO, "echidna", "AAudio hook installed");
            return true;
        }

        int AAudioHookManager::Replacement(void *stream, void *user, void *audio_data, int32_t num_frames)
        {
            return ForwardCallback(stream, user, audio_data, num_frames);
        }

    } // namespace hooks
} // namespace echidna
