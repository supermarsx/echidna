#include "hooks/audioflinger_hook_manager.h"

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
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna {
namespace hooks {

namespace {
using ThreadLoopFn = bool (*)(void *);
using RecordTrackReadFn = ssize_t (*)(void *, void *, size_t);
using ProcessChunkFn = ssize_t (*)(void *, void *, size_t);
ThreadLoopFn gOriginalThreadLoop = nullptr;
RecordTrackReadFn gOriginalRead = nullptr;
ProcessChunkFn gOriginalProcess = nullptr;

struct CaptureContext {
    uint32_t sample_rate{48000};
    uint32_t channels{2};
};

std::unordered_map<void *, CaptureContext> gContexts;
std::mutex gContextMutex;

uint32_t DefaultSampleRate() {
#ifdef __ANDROID__
    char prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.audio.samplerate", prop) > 0) {
        int sr = std::atoi(prop);
        if (sr > 8000 && sr < 192000) {
            return static_cast<uint32_t>(sr);
        }
    }
#endif
    if (const char *env = std::getenv("ECHIDNA_AF_SAMPLE_RATE")) {
        const int sr = std::atoi(env);
        if (sr > 8000 && sr < 192000) {
            return static_cast<uint32_t>(sr);
        }
    }
    return 48000u;
}

uint32_t DefaultChannels() {
    if (const char *env = std::getenv("ECHIDNA_AF_CHANNELS")) {
        const int ch = std::atoi(env);
        if (ch >= 1 && ch <= 8) {
            return static_cast<uint32_t>(ch);
        }
    }
    return 2u;
}

CaptureContext ResolveContext(void *thiz) {
    {
        std::lock_guard<std::mutex> lock(gContextMutex);
        auto it = gContexts.find(thiz);
        if (it != gContexts.end()) {
            return it->second;
        }
    }

    CaptureContext ctx;
    ctx.sample_rate = DefaultSampleRate();
    ctx.channels = DefaultChannels();

    // Heuristic: scan early object region for plausible sample rate/channel mask pairs.
    const uint8_t *base = reinterpret_cast<uint8_t *>(thiz);
    for (size_t offset = 0x8; offset + 8 <= 128; offset += 4) {
        uint32_t sr = 0;
        uint32_t mask = 0;
        std::memcpy(&sr, base + offset, sizeof(sr));
        std::memcpy(&mask, base + offset + 4, sizeof(mask));
        if (sr > 8000 && sr < 192000) {
            ctx.sample_rate = sr;
        }
        const uint32_t channels = std::popcount(mask);
        if (channels > 0 && channels <= 8) {
            ctx.channels = channels;
        }
        if (sr > 8000 && sr < 192000 && channels > 0) {
            break;
        }
    }

    {
        std::lock_guard<std::mutex> lock(gContextMutex);
        gContexts[thiz] = ctx;
    }
    return ctx;
}

bool ForwardThreadLoop(void *thiz) {
    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    const bool allow = state.hooksEnabled() &&
                       (state.isProcessWhitelisted(process) || process == "audioserver");
    if (!allow) {
        return gOriginalThreadLoop ? gOriginalThreadLoop(thiz) : false;
    }

    // Probe and cache context; fallback to defaults if fields are inaccessible.
    (void)ResolveContext(thiz);

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

}  // namespace

AudioFlingerHookManager::AudioFlingerHookManager(utils::PltResolver &resolver)
    : resolver_(resolver) {}

bool AudioFlingerHookManager::install() {
    static const char *kCandidates[] = {
        "_ZN7android12AudioFlinger11RecordThread10threadLoopEv",
        "_ZN7android12AudioFlinger17RecordTrackHandle10threadLoopEv",
    };
    bool installed = false;
    for (const char *symbol : kCandidates) {
        void *target = resolver_.findSymbol("libaudioflinger.so", symbol);
        if (!target) {
            continue;
        }
        if (hook_.install(target,
                          reinterpret_cast<void *>(&ForwardThreadLoop),
                          reinterpret_cast<void **>(&gOriginalThreadLoop))) {
            __android_log_print(ANDROID_LOG_INFO,
                                "echidna",
                                "AudioFlinger threadLoop hook installed at %s",
                                symbol);
            installed = true;
            break;
        }
    }

    void *read_target = resolver_.findSymbol("libaudioflinger.so",
                                             "_ZN7android12AudioFlinger10RecordThread4readEPvjj");
    if (read_target) {
        hook_read_.install(read_target,
                           reinterpret_cast<void *>(&ReplacementRead),
                           reinterpret_cast<void **>(&gOriginalRead));
    }

    void *process_target =
            resolver_.findSymbol("libaudioflinger.so",
                                 "_ZN7android12AudioFlinger10RecordThread13processVolumeEPKvj");
    if (process_target) {
        hook_process_.install(process_target,
                              reinterpret_cast<void *>(&ReplacementProcess),
                              reinterpret_cast<void **>(&gOriginalProcess));
    }

    if (!installed) {
        __android_log_print(ANDROID_LOG_WARN, "echidna", "AudioFlinger hook not installed");
    }
    return installed;
}

bool AudioFlingerHookManager::Replacement(void *thiz) {
    return ForwardThreadLoop(thiz);
}

bool ProcessPcmBuffer(void *thiz,
                      void *buffer,
                      size_t bytes,
                      CaptureContext ctx,
                      RecordTrackReadFn passthrough) {
    if (!buffer || bytes == 0 || ctx.channels == 0) {
        return false;
    }
    size_t frame_bytes = ctx.channels * sizeof(int16_t);
    const size_t total_samples = bytes / sizeof(int16_t);
    if (bytes % frame_bytes != 0) {
        // Try to infer channel count from buffer length (1..8 channels).
        for (uint32_t ch = 1; ch <= 8; ++ch) {
            if (total_samples % ch != 0) {
                continue;
            }
            const size_t frames_candidate = total_samples / ch;
            if (frames_candidate >= 8 && frames_candidate <= 4096) {
                ctx.channels = ch;
                frame_bytes = ch * sizeof(int16_t);
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
        bytes < min_block || bytes > max_block) {
        return false;
    }
    const size_t frames = bytes / frame_bytes;
    const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
    std::vector<float> in(frames * ctx.channels);
    for (size_t i = 0; i < frames * ctx.channels; ++i) {
        in[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
    }
    std::vector<float> out(frames * ctx.channels);
    const echidna_result_t result = echidna_process_block(in.data(),
                                                          out.data(),
                                                          static_cast<uint32_t>(frames),
                                                          ctx.sample_rate,
                                                          ctx.channels);
    if (result != ECHIDNA_RESULT_OK) {
        if (passthrough) {
            passthrough(thiz, buffer, bytes);
        }
        return false;
    }
    int16_t *pcm_out = static_cast<int16_t *>(buffer);
    for (size_t i = 0; i < frames * ctx.channels; ++i) {
        const float sample = std::clamp(out[i], -1.0f, 1.0f);
        pcm_out[i] = static_cast<int16_t>(sample * 32767.0f);
    }
    return true;
}

ssize_t AudioFlingerHookManager::ReplacementRead(void *thiz, void *buffer, size_t bytes) {
    const ssize_t read_bytes = gOriginalRead ? gOriginalRead(thiz, buffer, bytes) : -1;
    if (read_bytes <= 0 || !buffer) {
        return read_bytes;
    }

    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    if (!state.hooksEnabled() || (!state.isProcessWhitelisted(process) && process != "audioserver")) {
        return read_bytes;
    }

    const CaptureContext ctx = ResolveContext(thiz);

    if (!ProcessPcmBuffer(thiz, buffer, static_cast<size_t>(read_bytes), ctx, gOriginalRead)) {
        return read_bytes;
    }
    return read_bytes;
}

ssize_t AudioFlingerHookManager::ReplacementProcess(void *thiz, void *buffer, size_t bytes) {
    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    if (!state.hooksEnabled() || (!state.isProcessWhitelisted(process) && process != "audioserver")) {
        return gOriginalProcess ? gOriginalProcess(thiz, buffer, bytes) : -1;
    }
    const CaptureContext ctx = ResolveContext(thiz);

    // Try processing; if it fails, fall back to original.
    if (ProcessPcmBuffer(thiz, buffer, bytes, ctx, gOriginalProcess)) {
        return static_cast<ssize_t>(bytes);
    }
    return gOriginalProcess ? gOriginalProcess(thiz, buffer, bytes) : -1;
}

}  // namespace hooks
}  // namespace echidna
