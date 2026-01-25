#include "hooks/audioflinger_hook_manager.h"

/**
 * @file audioflinger_hook_manager.cpp
 * @brief Hooks into AudioFlinger thread loop and buffer handling to capture
 * device-level audio and forward it through the DSP pipeline.
 */

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <algorithm>
#include <bit>
#include <string>
#include <time.h>
#include <unordered_map>
#include <vector>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/api_level_probe.h"
#include "utils/offset_probe.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using ThreadLoopFn = bool (*)(void *);
            using RecordTrackReadFn = ssize_t (*)(void *, void *, size_t);
            using ProcessChunkFn = ssize_t (*)(void *, void *, size_t);
            ThreadLoopFn gOriginalThreadLoop = nullptr;
            RecordTrackReadFn gOriginalRead = nullptr;
            ProcessChunkFn gOriginalProcess = nullptr;
            struct SymbolCandidate
            {
                const char *symbol;
                int min_api;
                int max_api;
            };

            struct CaptureContext
            {
                uint32_t sample_rate{48000};
                uint32_t channels{2};
                bool validated{false};
            };

            std::unordered_map<void *, CaptureContext> gContexts;
            std::mutex gContextMutex;
            int32_t gSampleRateOffset = -1;
            int32_t gChannelMaskOffset = -1;
            const char *kOffsetsPath = "/data/local/tmp/echidna_af_offsets.txt";
            constexpr const char *kOffsetsTag = "audioflinger";
            bool gLoggedOffsets = false;

            uint32_t DefaultSampleRate()
            {
#ifdef __ANDROID__
                char prop[PROP_VALUE_MAX] = {0};
                if (__system_property_get("ro.audio.samplerate", prop) > 0)
                {
                    int sr = std::atoi(prop);
                    if (sr > 8000 && sr < 192000)
                    {
                        return static_cast<uint32_t>(sr);
                    }
                }
                if (__system_property_get("ro.vendor.audio.samplerate", prop) > 0)
                {
                    int sr = std::atoi(prop);
                    if (sr > 8000 && sr < 192000)
                    {
                        return static_cast<uint32_t>(sr);
                    }
                }
#endif
                if (const char *env = std::getenv("ECHIDNA_AF_SAMPLE_RATE"))
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
                if (const char *env = std::getenv("ECHIDNA_AF_CHANNELS"))
                {
                    const int ch = std::atoi(env);
                    if (ch >= 1 && ch <= 8)
                    {
                        return static_cast<uint32_t>(ch);
                    }
                }
                return 2u;
            }

            void LoadOffsetOverrides()
            {
                static std::once_flag once;
                std::call_once(once, []() {
                    if (const char *env = std::getenv("ECHIDNA_AF_DISCOVER"))
                    {
                        if (std::atoi(env) != 0)
                        {
                            gSampleRateOffset = -2;  // sentinel to signal discovery mode.
                            gChannelMaskOffset = -2;
                        }
                    }
                    if (const char *env = std::getenv("ECHIDNA_AF_SR_OFFSET"))
                    {
                        gSampleRateOffset = std::atoi(env);
                    }
                    if (const char *env = std::getenv("ECHIDNA_AF_CH_MASK_OFFSET"))
                    {
                        gChannelMaskOffset = std::atoi(env);
                    }
                    // If offsets were packaged from discovery, preload them.
                    FILE *file = std::fopen("/data/local/tmp/echidna_af_offsets.txt", "r");
                    if (file)
                    {
                        int sr = -1;
                        int ch = -1;
                        if (std::fscanf(file, "sr_offset=%d\nch_mask_offset=%d", &sr, &ch) == 2)
                        {
                            if (gSampleRateOffset < 0) gSampleRateOffset = sr;
                            if (gChannelMaskOffset < 0) gChannelMaskOffset = ch;
                        }
                        std::fclose(file);
                    }
                });
            }

            CaptureContext ResolveContext(void *thiz)
            {
                LoadOffsetOverrides();
                {
                    std::lock_guard<std::mutex> lock(gContextMutex);
                    auto it = gContexts.find(thiz);
                    if (it != gContexts.end())
                    {
                        return it->second;
                    }
                }

                CaptureContext ctx;
                ctx.sample_rate = DefaultSampleRate();
                ctx.channels = DefaultChannels();

                const uint8_t *base = reinterpret_cast<uint8_t *>(thiz);
                if (gSampleRateOffset >= 0 && gChannelMaskOffset >= 0)
                {
                    uint32_t sr = 0;
                    uint32_t mask = 0;
                    std::memcpy(&sr, base + static_cast<size_t>(gSampleRateOffset), sizeof(sr));
                    std::memcpy(&mask,
                                base + static_cast<size_t>(gChannelMaskOffset),
                                sizeof(mask));
                    if (sr > 8000 && sr < 192000)
                    {
                        ctx.sample_rate = sr;
                    }
                    const uint32_t channels = std::popcount(mask);
                    if (channels > 0 && channels <= 8)
                    {
                        ctx.channels = channels;
                    }
                    ctx.validated = true;
                }

                // Heuristic: scan early object region for plausible sample rate/channel mask pairs.
                const size_t scan_limit = 256;
                for (size_t offset = 0x8; offset + 8 <= scan_limit; offset += 4)
                {
                    uint32_t sr = 0;
                    uint32_t mask = 0;
                    std::memcpy(&sr, base + offset, sizeof(sr));
                    std::memcpy(&mask, base + offset + 4, sizeof(mask));
                    if (sr > 8000 && sr < 192000)
                    {
                        ctx.sample_rate = sr;
                    }
                    const uint32_t channels = std::popcount(mask);
                    if (channels > 0 && channels <= 8)
                    {
                        ctx.channels = channels;
                    }
                    if (sr > 8000 && sr < 192000 && channels > 0)
                    {
                        ctx.validated = true;
                        break;
                    }
                    if (gSampleRateOffset == -2 && sr > 8000 && sr < 192000 && channels > 0)
                    {
                        // Discovery mode: record offsets for future runs.
                        gSampleRateOffset = static_cast<int32_t>(offset);
                        gChannelMaskOffset = static_cast<int32_t>(offset + 4);
                        utils::OffsetProbe::LogOffsets(kOffsetsTag,
                                                       gSampleRateOffset,
                                                       gChannelMaskOffset);
                        utils::OffsetProbe::WriteOffsetsToFile(kOffsetsPath,
                                                               gSampleRateOffset,
                                                               gChannelMaskOffset);
                    }
                }

                {
                    std::lock_guard<std::mutex> lock(gContextMutex);
                    gContexts[thiz] = ctx;
                    if (!gLoggedOffsets && gSampleRateOffset >= 0 && gChannelMaskOffset >= 0)
                    {
                        gLoggedOffsets = true;
                        utils::OffsetProbe::LogOffsets(kOffsetsTag,
                                                       gSampleRateOffset,
                                                       gChannelMaskOffset);
                    }
                }
                return ctx;
            }

            bool ForwardThreadLoop(void *thiz)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                const bool allow = state.hooksEnabled() &&
                                   (state.isProcessWhitelisted(process) ||
                                    process == "audioserver");
                if (!allow)
                {
                    return gOriginalThreadLoop ? gOriginalThreadLoop(thiz) : false;
                }

                // Probe and cache context; fallback to defaults if fields are inaccessible.
                CaptureContext ctx = ResolveContext(thiz);
                if (!ctx.validated && gSampleRateOffset >= 0 && gChannelMaskOffset >= 0)
                {
                    __android_log_print(ANDROID_LOG_INFO,
                                        "echidna",
                                        "AudioFlinger offsets sr=%d chmask=%d",
                                        gSampleRateOffset,
                                        gChannelMaskOffset);
                }

                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);

                const bool result = gOriginalThreadLoop ? gOriginalThreadLoop(thiz) : false;

                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

                const int64_t wall_ns_raw = (static_cast<int64_t>(wall_end.tv_sec) -
                                             static_cast<int64_t>(wall_start.tv_sec)) *
                                                1000000000ll +
                                            (static_cast<int64_t>(wall_end.tv_nsec) -
                                             static_cast<int64_t>(wall_start.tv_nsec));
                const int64_t cpu_ns_raw = (static_cast<int64_t>(cpu_end.tv_sec) -
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

        AudioFlingerHookManager::AudioFlingerHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool AudioFlingerHookManager::install()
        {
            last_info_ = {};
            const char *library = "libaudioflinger.so";
            static const SymbolCandidate kCandidates[] = {
                {"_ZN7android12AudioFlinger17RecordTrackHandle10threadLoopEv", 29, -1},
                {"_ZN7android12AudioFlinger11RecordThread10threadLoopEv", 0, 28},
            };
            const int api_level = utils::ApiLevelProbe().apiLevel();
            bool skipped_by_guard = false;
            bool installed = false;
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
                    void *target = resolver_.findSymbol(library, candidate.symbol);
                    if (!target)
                    {
                        continue;
                    }
                    if (hook_.install(target,
                                      reinterpret_cast<void *>(&ForwardThreadLoop),
                                      reinterpret_cast<void **>(&gOriginalThreadLoop)))
                    {
                        __android_log_print(ANDROID_LOG_INFO,
                                            "echidna",
                                            "AudioFlinger threadLoop hook installed at %s",
                                            candidate.symbol);
                        last_info_.success = true;
                        last_info_.library = library;
                        last_info_.symbol = candidate.symbol;
                        last_info_.reason.clear();
                        return true;
                    }
                    last_info_.reason = "hook_failed";
                }
                return false;
            };

            installed = attempt_install(true);
            if (!installed)
            {
                installed = attempt_install(false);
                if (installed && last_info_.reason.empty())
                {
                    last_info_.reason = "api_guard_relaxed";
                }
            }

            void *read_target =
                resolver_.findSymbol(library,
                                     "_ZN7android12AudioFlinger10RecordThread4readEPvjj");
            if (read_target)
            {
                hook_read_.install(read_target,
                                   reinterpret_cast<void *>(&ReplacementRead),
                                   reinterpret_cast<void **>(&gOriginalRead));
            }

            void *process_target =
                resolver_.findSymbol(library,
                                     "_ZN7android12AudioFlinger10RecordThread13processVolumeEPKvj");
            if (process_target)
            {
                hook_process_.install(process_target,
                                      reinterpret_cast<void *>(&ReplacementProcess),
                                      reinterpret_cast<void **>(&gOriginalProcess));
            }

            if (!installed)
            {
                if (last_info_.reason.empty())
                {
                    last_info_.reason = skipped_by_guard ? "api_guard_blocked" : "symbol_not_found";
                }
                __android_log_print(ANDROID_LOG_WARN, "echidna", "AudioFlinger hook not installed");
            }
            return installed;
        }

        bool AudioFlingerHookManager::Replacement(void *thiz)
        {
            return ForwardThreadLoop(thiz);
        }

        bool ProcessPcmBuffer(void *thiz,
                              void *buffer,
                              size_t bytes,
                              CaptureContext ctx,
                              RecordTrackReadFn passthrough)
        {
            if (!buffer || bytes == 0 || ctx.channels == 0)
            {
                return false;
            }
            size_t frame_bytes = ctx.channels * sizeof(int16_t);
            const size_t total_samples = bytes / sizeof(int16_t);
            if (bytes % frame_bytes != 0)
            {
                // Try to infer channel count from buffer length (1..8 channels).
                for (uint32_t ch = 1; ch <= 8; ++ch)
                {
                    if (total_samples % ch != 0)
                    {
                        continue;
                    }
                    const size_t frames_candidate = total_samples / ch;
                    if (frames_candidate >= 8 && frames_candidate <= 4096)
                    {
                        ctx.channels = ch;
                        frame_bytes = ch * sizeof(int16_t);
                        ctx.validated = true;
                        {
                            std::lock_guard<std::mutex> lock(gContextMutex);
                            gContexts[thiz] = ctx;
                        }
                        break;
                    }
                }
            }
            const size_t min_block = static_cast<size_t>(ctx.channels) * sizeof(int16_t) * 8;
            const size_t max_block = static_cast<size_t>(ctx.channels) * sizeof(int16_t) * 4096;
            if (frame_bytes == 0 || (bytes % frame_bytes) != 0 ||
                bytes < min_block || bytes > max_block)
            {
                return false;
            }
            const size_t frames = bytes / frame_bytes;
            const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
            std::vector<float> in(frames * ctx.channels);
            for (size_t i = 0; i < frames * ctx.channels; ++i)
            {
                in[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
            }
            std::vector<float> out(frames * ctx.channels);
            const echidna_result_t result = echidna_process_block(in.data(),
                                                                  out.data(),
                                                                  static_cast<uint32_t>(frames),
                                                                  ctx.sample_rate,
                                                                  ctx.channels);
            if (result != ECHIDNA_RESULT_OK)
            {
                if (passthrough)
                {
                    passthrough(thiz, buffer, bytes);
                }
                return false;
            }
            int16_t *pcm_out = static_cast<int16_t *>(buffer);
            for (size_t i = 0; i < frames * ctx.channels; ++i)
            {
                const float sample = std::clamp(out[i], -1.0f, 1.0f);
                pcm_out[i] = static_cast<int16_t>(sample * 32767.0f);
            }
            return true;
        }

        ssize_t AudioFlingerHookManager::ReplacementRead(void *thiz, void *buffer, size_t bytes)
        {
            const ssize_t read_bytes = gOriginalRead ? gOriginalRead(thiz, buffer, bytes) : -1;
            if (read_bytes <= 0 || !buffer)
            {
                return read_bytes;
            }

            auto &state = state::SharedState::instance();
            const std::string &process = utils::CachedProcessName();
            if (!state.hooksEnabled() ||
                (!state.isProcessWhitelisted(process) && process != "audioserver"))
            {
                return read_bytes;
            }

            const CaptureContext ctx = ResolveContext(thiz);

            if (!ProcessPcmBuffer(thiz,
                                  buffer,
                                  static_cast<size_t>(read_bytes),
                                  ctx,
                                  gOriginalRead))
            {
                return read_bytes;
            }
            return read_bytes;
        }

        ssize_t AudioFlingerHookManager::ReplacementProcess(void *thiz, void *buffer, size_t bytes)
        {
            auto &state = state::SharedState::instance();
            const std::string &process = utils::CachedProcessName();
            if (!state.hooksEnabled() ||
                (!state.isProcessWhitelisted(process) && process != "audioserver"))
            {
                return gOriginalProcess ? gOriginalProcess(thiz, buffer, bytes) : -1;
            }
            // processVolume buffers may be const on some vendors; avoid mutating for stability.
            return gOriginalProcess ? gOriginalProcess(thiz, buffer, bytes) : -1;
        }

    } // namespace hooks
} // namespace echidna
