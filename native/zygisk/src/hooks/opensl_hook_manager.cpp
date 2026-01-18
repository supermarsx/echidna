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
#include <cstdlib>
#include <string>
#include <time.h>
#include <vector>

#include "echidna/api.h"
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

            void ProcessPcmBuffer(void *buffer,
                                  uint32_t size,
                                  uint32_t sample_rate,
                                  uint32_t channels)
            {
                if (!buffer || size == 0 || channels == 0)
                {
                    return;
                }
                size_t frame_bytes = channels * sizeof(int16_t);
                if (frame_bytes == 0 || (size % frame_bytes) != 0)
                {
                    const size_t total_samples = size / sizeof(int16_t);
                    for (uint32_t ch = 1; ch <= 8; ++ch)
                    {
                        if (total_samples % ch == 0)
                        {
                            channels = ch;
                            frame_bytes = channels * sizeof(int16_t);
                            break;
                        }
                    }
                }
                if (frame_bytes == 0 || (size % frame_bytes) != 0)
                {
                    return;
                }
                const size_t frames = size / frame_bytes;
                const size_t samples = frames * channels;
                std::vector<float> in(samples);
                const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
                for (size_t i = 0; i < samples; ++i)
                {
                    in[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
                }
                std::vector<float> out(samples);
                if (echidna_process_block(in.data(),
                                          out.data(),
                                          static_cast<uint32_t>(frames),
                                          sample_rate,
                                          channels) != ECHIDNA_RESULT_OK)
                {
                    return;
                }
                int16_t *pcm_out = static_cast<int16_t *>(buffer);
                for (size_t i = 0; i < samples; ++i)
                {
                    const float clamped = std::clamp(out[i], -1.0f, 1.0f);
                    pcm_out[i] = static_cast<int16_t>(clamped * 32767.0f);
                }
            }

            SLresult ForwardCallback(void *caller, void *context, void *buffer, uint32_t size)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalCallback ? gOriginalCallback(caller, context, buffer, size)
                                             : SL_RESULT_SUCCESS;
                }
                const uint32_t sample_rate = DefaultSampleRate();
                const uint32_t channels = DefaultChannels();
                ProcessPcmBuffer(buffer, size, sample_rate, channels);
                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
                SLresult result = gOriginalCallback
                    ? gOriginalCallback(caller, context, buffer, size)
                    : SL_RESULT_SUCCESS;
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

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
                    __android_log_print(ANDROID_LOG_INFO,
                                        "echidna",
                                        "OpenSL hook installed at %s",
                                        symbol);
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
