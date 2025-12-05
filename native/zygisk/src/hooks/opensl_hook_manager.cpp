#include "hooks/opensl_hook_manager.h"

/**
 * @file opensl_hook_manager.cpp
 * @brief Install hooks for OpenSL ES buffer queue callbacks and capture timing
 * telemetry per callback.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#ifdef __ANDROID__
#include <SLES/OpenSLES.h>
#endif

#include <algorithm>
#include <string>
#include <time.h>

#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using BufferCallback = SLresult (*)(void *, void *, void *, uint32_t);
            BufferCallback gOriginalCallback = nullptr;

            SLresult ForwardCallback(void *caller, void *context, void *buffer, uint32_t size)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalCallback ? gOriginalCallback(caller, context, buffer, size)
                                             : SL_RESULT_SUCCESS;
                }
                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
                SLresult result = gOriginalCallback ? gOriginalCallback(caller, context, buffer, size)
                                                    : SL_RESULT_SUCCESS;
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

        OpenSLHookManager::OpenSLHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool OpenSLHookManager::install()
        {
            static const char *kCandidates[] = {
                "SLAndroidSimpleBufferQueueItf_Enqueue",
                "SLBufferQueueItf_CallbackProxy",
                "SLBufferQueueItf_RegisterCallback",
            };

            for (const char *symbol : kCandidates)
            {
                void *target = resolver_.findSymbol("libOpenSLES.so", symbol);
                if (!target)
                {
                    continue;
                }
                if (hook_.install(target, reinterpret_cast<void *>(&ForwardCallback),
                                  reinterpret_cast<void **>(&gOriginalCallback)))
                {
                    active_symbol_ = symbol;
                    __android_log_print(ANDROID_LOG_INFO, "echidna", "OpenSL hook installed at %s", symbol);
                    return true;
                }
            }
            return false;
        }

        SLresult OpenSLHookManager::Replacement(void *caller, void *context, void *buffer,
                                                uint32_t size)
        {
            return ForwardCallback(caller, context, buffer, size);
        }

    } // namespace hooks
} // namespace echidna
