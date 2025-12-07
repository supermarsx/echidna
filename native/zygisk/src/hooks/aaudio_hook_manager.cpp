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
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <time.h>
#include <unordered_map>
#include <vector>

#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"
#include "utils/process_utils.h"
#include "echidna/api.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using Callback = int (*)(void *, void *, void *, int32_t);
            Callback gOriginalCallback = nullptr;

            // Minimal subset of AAudio types/constants to avoid extra headers.
            enum
            {
                kAAudioFormatInvalid = 0,
                kAAudioFormatI16 = 1,
                kAAudioFormatFloat = 2
            };

            struct StreamConfig
            {
                uint32_t sample_rate{48000};
                uint32_t channels{2};
                int32_t format{kAAudioFormatI16};
                bool valid{false};
            };

            struct StreamFns
            {
                using GetIntFn = int32_t (*)(void *);
                GetIntFn get_sample_rate{nullptr};
                GetIntFn get_channel_count{nullptr};
                GetIntFn get_format{nullptr};
                bool resolved{false};
            };

            StreamFns &ResolveStreamFns()
            {
                static StreamFns fns;
                static std::once_flag once;
                std::call_once(once, []() {
                    fns.get_sample_rate = reinterpret_cast<StreamFns::GetIntFn>(
                        dlsym(RTLD_DEFAULT, "AAudioStream_getSampleRate"));
                    fns.get_channel_count = reinterpret_cast<StreamFns::GetIntFn>(
                        dlsym(RTLD_DEFAULT, "AAudioStream_getChannelCount"));
                    fns.get_format = reinterpret_cast<StreamFns::GetIntFn>(
                        dlsym(RTLD_DEFAULT, "AAudioStream_getFormat"));
                    fns.resolved = true;
                });
                return fns;
            }

            StreamConfig QueryStreamConfig(void *stream)
            {
                StreamConfig cfg;
                if (!stream)
                {
                    return cfg;
                }
                const auto &fns = ResolveStreamFns();
                if (fns.get_sample_rate)
                {
                    const int32_t rate = fns.get_sample_rate(stream);
                    if (rate > 8000 && rate < 192000)
                    {
                        cfg.sample_rate = static_cast<uint32_t>(rate);
                    }
                }
                if (fns.get_channel_count)
                {
                    const int32_t ch = fns.get_channel_count(stream);
                    if (ch >= 1 && ch <= 8)
                    {
                        cfg.channels = static_cast<uint32_t>(ch);
                    }
                }
                if (fns.get_format)
                {
                    const int32_t fmt = fns.get_format(stream);
                    if (fmt == kAAudioFormatFloat || fmt == kAAudioFormatI16)
                    {
                        cfg.format = fmt;
                    }
                }
                cfg.valid = true;
                return cfg;
            }

            std::mutex gStreamCacheMutex;
            std::unordered_map<void *, StreamConfig> gStreamConfigCache;

            StreamConfig CachedConfig(void *stream)
            {
                if (!stream)
                {
                    return {};
                }
                {
                    std::scoped_lock lock(gStreamCacheMutex);
                    auto it = gStreamConfigCache.find(stream);
                    if (it != gStreamConfigCache.end())
                    {
                        return it->second;
                    }
                }
                StreamConfig cfg = QueryStreamConfig(stream);
                {
                    std::scoped_lock lock(gStreamCacheMutex);
                    gStreamConfigCache[stream] = cfg;
                }
                return cfg;
            }

            int ForwardCallback(void *stream, void *user, void *audio_data, int32_t num_frames)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalCallback ? gOriginalCallback(stream, user, audio_data, num_frames) : 0;
                }

                // Capture context before calling the app's callback so the processed buffer is
                // delivered to the app.
                const StreamConfig cfg = CachedConfig(stream);
                const uint32_t channels = std::max<uint32_t>(1u, cfg.channels);
                const uint32_t frames = num_frames > 0 ? static_cast<uint32_t>(num_frames) : 0u;
                const size_t samples = static_cast<size_t>(frames) * channels;

                std::vector<float> input;
                std::vector<float> output;
                input.reserve(samples);
                output.resize(samples);

                if (audio_data && frames > 0 && samples > 0)
                {
                    if (cfg.format == kAAudioFormatFloat)
                    {
                        const float *pcm = static_cast<const float *>(audio_data);
                        input.assign(pcm, pcm + samples);
                    }
                    else
                    {
                        const int16_t *pcm = static_cast<const int16_t *>(audio_data);
                        input.resize(samples);
                        for (size_t i = 0; i < samples; ++i)
                        {
                            input[i] = static_cast<float>(pcm[i]) / 32768.0f;
                        }
                    }
                    if (echidna_process_block(input.data(),
                                              output.data(),
                                              frames,
                                              cfg.sample_rate,
                                              channels) == ECHIDNA_RESULT_OK)
                    {
                        if (cfg.format == kAAudioFormatFloat)
                        {
                            float *pcm_out = static_cast<float *>(audio_data);
                            std::copy(output.begin(), output.end(), pcm_out);
                        }
                        else
                        {
                            int16_t *pcm_out = static_cast<int16_t *>(audio_data);
                            for (size_t i = 0; i < samples; ++i)
                            {
                                const float clamped = std::clamp(output[i], -1.0f, 1.0f);
                                pcm_out[i] = static_cast<int16_t>(clamped * 32767.0f);
                            }
                        }
                    }
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
