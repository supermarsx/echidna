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
#include <cstdlib>
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
            using ReadFn = int32_t (*)(void *, void *, int32_t, int64_t);
            using WriteFn = int32_t (*)(void *, const void *, int32_t, int64_t);
            Callback gOriginalCallback = nullptr;
            ReadFn gOriginalRead = nullptr;
            WriteFn gOriginalWrite = nullptr;

            // Minimal subset of AAudio types/constants to avoid extra headers.
            enum
            {
                kAAudioFormatInvalid = 0,
                kAAudioFormatI16 = 1,
                kAAudioFormatFloat = 2,
                kAAudioDirectionOutput = 0,
                kAAudioDirectionInput = 1
            };

            struct StreamConfig
            {
                uint32_t sample_rate{48000};
                uint32_t channels{2};
                int32_t format{kAAudioFormatI16};
                int32_t direction{-1};
                bool valid{false};
            };

            struct StreamFns
            {
                using GetIntFn = int32_t (*)(void *);
                GetIntFn get_sample_rate{nullptr};
                GetIntFn get_channel_count{nullptr};
                GetIntFn get_format{nullptr};
                GetIntFn get_direction{nullptr};
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
                    fns.get_direction = reinterpret_cast<StreamFns::GetIntFn>(
                        dlsym(RTLD_DEFAULT, "AAudioStream_getDirection"));
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
                if (fns.get_direction)
                {
                    const int32_t direction = fns.get_direction(stream);
                    if (direction == kAAudioDirectionInput ||
                        direction == kAAudioDirectionOutput)
                    {
                        cfg.direction = direction;
                    }
                }
                cfg.valid = true;
                return cfg;
            }

            std::mutex gStreamCacheMutex;
            std::unordered_map<void *, StreamConfig> gStreamConfigCache;

            bool AllowOutputProcessing()
            {
                if (const char *env = std::getenv("ECHIDNA_AAUDIO_PROCESS_OUTPUT"))
                {
                    return std::atoi(env) != 0;
                }
                return false;
            }

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

            bool ShouldProcess(const StreamConfig &cfg)
            {
                if (cfg.direction == kAAudioDirectionOutput && !AllowOutputProcessing())
                {
                    return false;
                }
                return true;
            }

            bool ProcessPcmBuffer(const StreamConfig &cfg,
                                  void *buffer,
                                  uint32_t frames)
            {
                if (!buffer || frames == 0)
                {
                    return false;
                }
                const uint32_t channels = std::max<uint32_t>(1u, cfg.channels);
                const size_t samples = static_cast<size_t>(frames) * channels;
                if (samples == 0)
                {
                    return false;
                }
                std::vector<float> input;
                std::vector<float> output;
                input.reserve(samples);
                output.resize(samples);

                if (cfg.format == kAAudioFormatFloat)
                {
                    const float *pcm = static_cast<const float *>(buffer);
                    input.assign(pcm, pcm + samples);
                }
                else
                {
                    const int16_t *pcm = static_cast<const int16_t *>(buffer);
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
                                          channels) != ECHIDNA_RESULT_OK)
                {
                    return false;
                }
                if (cfg.format == kAAudioFormatFloat)
                {
                    float *pcm_out = static_cast<float *>(buffer);
                    std::copy(output.begin(), output.end(), pcm_out);
                }
                else
                {
                    int16_t *pcm_out = static_cast<int16_t *>(buffer);
                    for (size_t i = 0; i < samples; ++i)
                    {
                        const float clamped = std::clamp(output[i], -1.0f, 1.0f);
                        pcm_out[i] = static_cast<int16_t>(clamped * 32767.0f);
                    }
                }
                return true;
            }

            int32_t ForwardRead(void *stream,
                                void *buffer,
                                int32_t frames,
                                int64_t timeout_ns)
            {
                const int32_t read_frames =
                    gOriginalRead ? gOriginalRead(stream, buffer, frames, timeout_ns) : 0;
                if (read_frames <= 0 || !buffer)
                {
                    return read_frames;
                }
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return read_frames;
                }
                const StreamConfig cfg = CachedConfig(stream);
                if (!ShouldProcess(cfg))
                {
                    return read_frames;
                }
                ProcessPcmBuffer(cfg, buffer, static_cast<uint32_t>(read_frames));
                return read_frames;
            }

            int32_t ForwardWrite(void *stream,
                                 const void *buffer,
                                 int32_t frames,
                                 int64_t timeout_ns)
            {
                if (!buffer || frames <= 0)
                {
                    return gOriginalWrite ? gOriginalWrite(stream, buffer, frames, timeout_ns)
                                          : 0;
                }
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalWrite ? gOriginalWrite(stream, buffer, frames, timeout_ns)
                                          : 0;
                }
                const StreamConfig cfg = CachedConfig(stream);
                if (!ShouldProcess(cfg))
                {
                    return gOriginalWrite ? gOriginalWrite(stream, buffer, frames, timeout_ns)
                                          : 0;
                }
                const uint32_t channels = std::max<uint32_t>(1u, cfg.channels);
                const size_t samples = static_cast<size_t>(frames) * channels;
                if (samples == 0)
                {
                    return gOriginalWrite ? gOriginalWrite(stream, buffer, frames, timeout_ns)
                                          : 0;
                }
                if (cfg.format == kAAudioFormatFloat)
                {
                    std::vector<float> input(samples);
                    const float *pcm = static_cast<const float *>(buffer);
                    std::copy(pcm, pcm + samples, input.begin());
                    std::vector<float> output(samples);
                    if (echidna_process_block(input.data(),
                                              output.data(),
                                              static_cast<uint32_t>(frames),
                                              cfg.sample_rate,
                                              channels) == ECHIDNA_RESULT_OK)
                    {
                        return gOriginalWrite
                            ? gOriginalWrite(stream,
                                             output.data(),
                                             frames,
                                             timeout_ns)
                            : frames;
                    }
                }
                else
                {
                    std::vector<float> input(samples);
                    const int16_t *pcm = static_cast<const int16_t *>(buffer);
                    for (size_t i = 0; i < samples; ++i)
                    {
                        input[i] = static_cast<float>(pcm[i]) / 32768.0f;
                    }
                    std::vector<float> output(samples);
                    if (echidna_process_block(input.data(),
                                              output.data(),
                                              static_cast<uint32_t>(frames),
                                              cfg.sample_rate,
                                              channels) == ECHIDNA_RESULT_OK)
                    {
                        std::vector<int16_t> out_pcm(samples);
                        for (size_t i = 0; i < samples; ++i)
                        {
                            const float clamped =
                                std::clamp(output[i], -1.0f, 1.0f);
                            out_pcm[i] = static_cast<int16_t>(clamped * 32767.0f);
                        }
                        return gOriginalWrite
                            ? gOriginalWrite(stream,
                                             out_pcm.data(),
                                             frames,
                                             timeout_ns)
                            : frames;
                    }
                }
                return gOriginalWrite ? gOriginalWrite(stream, buffer, frames, timeout_ns) : 0;
            }

            int ForwardCallback(void *stream, void *user, void *audio_data, int32_t num_frames)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return gOriginalCallback
                        ? gOriginalCallback(stream, user, audio_data, num_frames)
                        : 0;
                }

                // Capture context before calling the app's callback so the processed buffer is
                // delivered to the app.
                const StreamConfig cfg = CachedConfig(stream);
                if (audio_data && num_frames > 0 && ShouldProcess(cfg))
                {
                    ProcessPcmBuffer(cfg,
                                     audio_data,
                                     static_cast<uint32_t>(num_frames));
                }

                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
                int result = gOriginalCallback
                    ? gOriginalCallback(stream, user, audio_data, num_frames)
                    : 0;
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

        AAudioHookManager::AAudioHookManager(utils::PltResolver &resolver) : resolver_(resolver) {}

        bool AAudioHookManager::install()
        {
            last_info_ = {};
            bool installed = false;
            const char *library = "libaaudio.so";
            if (void *symbol = resolver_.findSymbol(library, "AAudioStream_dataCallback"))
            {
                if (hook_.install(symbol,
                                  reinterpret_cast<void *>(&ForwardCallback),
                                  reinterpret_cast<void **>(&gOriginalCallback)))
                {
                    __android_log_print(ANDROID_LOG_INFO,
                                        "echidna",
                                        "AAudio data callback hook installed");
                    last_info_.success = true;
                    last_info_.library = library;
                    last_info_.symbol = "AAudioStream_dataCallback";
                    last_info_.reason.clear();
                    installed = true;
                }
                else
                {
                    __android_log_print(ANDROID_LOG_WARN,
                                        "echidna",
                                        "Failed to install AAudio data callback hook");
                    last_info_.reason = "hook_failed";
                }
            }

            if (void *symbol = resolver_.findSymbol(library, "AAudioStream_read"))
            {
                if (hook_read_.install(symbol,
                                       reinterpret_cast<void *>(&ForwardRead),
                                       reinterpret_cast<void **>(&gOriginalRead)))
                {
                    __android_log_print(ANDROID_LOG_INFO,
                                        "echidna",
                                        "AAudio read hook installed");
                    last_info_.success = true;
                    last_info_.library = library;
                    last_info_.symbol = "AAudioStream_read";
                    last_info_.reason.clear();
                    installed = true;
                }
            }

            if (void *symbol = resolver_.findSymbol(library, "AAudioStream_write"))
            {
                if (hook_write_.install(symbol,
                                        reinterpret_cast<void *>(&ForwardWrite),
                                        reinterpret_cast<void **>(&gOriginalWrite)))
                {
                    __android_log_print(ANDROID_LOG_INFO,
                                        "echidna",
                                        "AAudio write hook installed");
                    last_info_.success = true;
                    last_info_.library = library;
                    last_info_.symbol = "AAudioStream_write";
                    last_info_.reason.clear();
                    installed = true;
                }
            }

            if (!installed)
            {
                if (last_info_.reason.empty())
                {
                    last_info_.reason = "symbol_not_found";
                }
                __android_log_print(ANDROID_LOG_WARN, "echidna", "AAudio hook not installed");
            }
            return installed;
        }

        int AAudioHookManager::Replacement(void *stream,
                                           void *user,
                                           void *audio_data,
                                           int32_t num_frames)
        {
            return ForwardCallback(stream, user, audio_data, num_frames);
        }

        int32_t AAudioHookManager::ReplacementRead(void *stream,
                                                   void *buffer,
                                                   int32_t frames,
                                                   int64_t timeout_ns)
        {
            return ForwardRead(stream, buffer, frames, timeout_ns);
        }

        int32_t AAudioHookManager::ReplacementWrite(void *stream,
                                                    const void *buffer,
                                                    int32_t frames,
                                                    int64_t timeout_ns)
        {
            return ForwardWrite(stream, buffer, frames, timeout_ns);
        }

    } // namespace hooks
} // namespace echidna
