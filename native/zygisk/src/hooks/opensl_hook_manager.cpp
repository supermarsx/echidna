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

#include <algorithm>
#include <cstdlib>
#include <cstdint>
#include <mutex>
#include <string>
#include <time.h>
#include <unordered_map>

#include "echidna/api.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using OpenSlCallback = void (*)(void *, void *);
            using OpenSlEnqueueFn = SLresult (*)(void *, const void *, uint32_t);
            using OpenSlRegisterCallbackFn = SLresult (*)(void *, OpenSlCallback, void *);

            struct QueueState
            {
                void *buffer{nullptr};
                uint32_t size{0};
                OpenSlCallback callback{nullptr};
                void *context{nullptr};
            };

            struct TimingStart
            {
                timespec wall{};
                timespec cpu{};
            };

            struct OpenSlCandidate
            {
                const char *name;
                const char *enqueue_symbol;
                const char *register_symbol;
            };

            OpenSlEnqueueFn gOriginalEnqueue = nullptr;
            OpenSlRegisterCallbackFn gOriginalRegisterCallback = nullptr;
            std::mutex gQueueStateMutex;
            std::unordered_map<void *, QueueState> gQueueStates;

            uint32_t DefaultSampleRate()
            {
                if (const char *env = std::getenv("ECHIDNA_OPENSL_SR"))
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
                if (const char *env = std::getenv("ECHIDNA_OPENSL_CH"))
                {
                    const int ch = std::atoi(env);
                    if (ch >= 1 && ch <= 8)
                    {
                        return static_cast<uint32_t>(ch);
                    }
                }
                return 2u;
            }

            TimingStart StartTiming()
            {
                TimingStart start;
                clock_gettime(CLOCK_MONOTONIC, &start.wall);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &start.cpu);
                return start;
            }

            void RecordHookCall(state::SharedState &shared_state,
                                const TimingStart &start,
                                uint32_t flags)
            {
                timespec wall_end{};
                timespec cpu_end{};
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

                const int64_t wall_ns_raw =
                    (static_cast<int64_t>(wall_end.tv_sec) -
                     static_cast<int64_t>(start.wall.tv_sec)) *
                        1000000000ll +
                    (static_cast<int64_t>(wall_end.tv_nsec) -
                     static_cast<int64_t>(start.wall.tv_nsec));
                const int64_t cpu_ns_raw =
                    (static_cast<int64_t>(cpu_end.tv_sec) -
                     static_cast<int64_t>(start.cpu.tv_sec)) *
                        1000000000ll +
                    (static_cast<int64_t>(cpu_end.tv_nsec) -
                     static_cast<int64_t>(start.cpu.tv_nsec));
                const uint32_t wall_us = static_cast<uint32_t>(
                    std::max<int64_t>(wall_ns_raw, 0ll) / 1000ll);
                const uint32_t cpu_us = static_cast<uint32_t>(
                    std::max<int64_t>(cpu_ns_raw, 0ll) / 1000ll);
                const uint64_t timestamp_ns =
                    static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                    static_cast<uint64_t>(wall_end.tv_nsec);

                shared_state.telemetry().recordCallback(timestamp_ns,
                                                        wall_us,
                                                        cpu_us,
                                                        flags,
                                                        0);
                shared_state.setStatus(state::InternalStatus::kHooked);
            }

            bool ProcessPcmBuffer(void *buffer,
                                  uint32_t size,
                                  uint32_t sample_rate,
                                  uint32_t channels)
            {
                if (!buffer || size == 0 || channels == 0)
                {
                    return false;
                }
                return RouteInt16CaptureBufferInPlace(buffer,
                                                      size,
                                                      sample_rate,
                                                      channels,
                                                      echidna_process_block);
            }

            void QueueCallbackProxy(void *caller, void *context)
            {
                QueueState queue_state;
                bool has_state = false;
                {
                    std::scoped_lock lock(gQueueStateMutex);
                    auto it = gQueueStates.find(caller);
                    if (it != gQueueStates.end())
                    {
                        queue_state = it->second;
                        has_state = true;
                    }
                }

                auto &shared_state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (has_state && shared_state.hooksEnabled() &&
                    shared_state.isProcessWhitelisted(process))
                {
                    const TimingStart timing = StartTiming();
                    const bool processed =
                        ProcessPcmBuffer(queue_state.buffer,
                                         queue_state.size,
                                         DefaultSampleRate(),
                                         DefaultChannels());
                    RecordHookCall(shared_state,
                                   timing,
                                   echidna::utils::kTelemetryFlagCallback |
                                       (processed ? echidna::utils::kTelemetryFlagDsp
                                                  : echidna::utils::kTelemetryFlagError));
                }

                if (queue_state.callback)
                {
                    queue_state.callback(caller, queue_state.context);
                }
                else
                {
                    (void)context;
                }
            }

            SLresult ForwardEnqueue(void *queue, const void *buffer, uint32_t size)
            {
                if (queue && buffer && size > 0)
                {
                    std::scoped_lock lock(gQueueStateMutex);
                    QueueState &queue_state = gQueueStates[queue];
                    queue_state.buffer = const_cast<void *>(buffer);
                    queue_state.size = size;
                }
                return gOriginalEnqueue ? gOriginalEnqueue(queue, buffer, size)
                                        : SL_RESULT_SUCCESS;
            }

            SLresult ForwardRegisterCallback(void *queue,
                                             OpenSlCallback callback,
                                             void *context)
            {
                if (queue)
                {
                    std::scoped_lock lock(gQueueStateMutex);
                    QueueState &queue_state = gQueueStates[queue];
                    queue_state.callback = callback;
                    queue_state.context = context;
                }

                OpenSlCallback replacement = callback ? QueueCallbackProxy : nullptr;
                return gOriginalRegisterCallback
                           ? gOriginalRegisterCallback(queue, replacement, context)
                           : SL_RESULT_SUCCESS;
            }

        } // namespace

        OpenSLHookManager::OpenSLHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool OpenSLHookManager::install()
        {
            last_info_ = {};
            const char *library = "libOpenSLES.so";
            static const OpenSlCandidate kCandidates[] = {
                {
                    "SLAndroidSimpleBufferQueueItf",
                    "SLAndroidSimpleBufferQueueItf_Enqueue",
                    "SLAndroidSimpleBufferQueueItf_RegisterCallback",
                },
                {
                    "SLBufferQueueItf",
                    "SLBufferQueueItf_Enqueue",
                    "SLBufferQueueItf_RegisterCallback",
                },
            };

            for (const OpenSlCandidate &candidate : kCandidates)
            {
                void *enqueue_target = resolver_.findSymbol(library, candidate.enqueue_symbol);
                void *register_target = resolver_.findSymbol(library, candidate.register_symbol);
                if (!enqueue_target || !register_target)
                {
                    last_info_.library = library;
                    last_info_.symbol = candidate.name;
                    last_info_.reason = "symbol_not_found";
                    continue;
                }

                if (!hook_enqueue_.install(enqueue_target,
                                           reinterpret_cast<void *>(&ForwardEnqueue),
                                           reinterpret_cast<void **>(&gOriginalEnqueue)))
                {
                    last_info_.library = library;
                    last_info_.symbol = candidate.enqueue_symbol;
                    last_info_.reason = "enqueue_hook_failed";
                    continue;
                }

                if (!hook_register_.install(
                        register_target,
                        reinterpret_cast<void *>(&ForwardRegisterCallback),
                        reinterpret_cast<void **>(&gOriginalRegisterCallback)))
                {
                    last_info_.library = library;
                    last_info_.symbol = candidate.register_symbol;
                    last_info_.reason = "register_callback_hook_failed";
                    return false;
                }

                active_symbol_ = candidate.name;
                last_info_.success = true;
                last_info_.library = library;
                last_info_.symbol = candidate.name;
                last_info_.reason.clear();
                __android_log_print(ANDROID_LOG_INFO,
                                    "echidna",
                                    "OpenSL queue hooks installed for %s",
                                    candidate.name);
                return true;
            }

            if (last_info_.reason.empty())
            {
                last_info_.reason = "symbol_not_found";
            }
            return false;
        }

        SLresult OpenSLHookManager::ReplacementEnqueue(void *queue,
                                                       const void *buffer,
                                                       uint32_t size)
        {
            return ForwardEnqueue(queue, buffer, size);
        }

        SLresult OpenSLHookManager::ReplacementRegisterCallback(void *queue,
                                                                OpenSlCallback callback,
                                                                void *context)
        {
            return ForwardRegisterCallback(queue, callback, context);
        }

    } // namespace hooks
} // namespace echidna
