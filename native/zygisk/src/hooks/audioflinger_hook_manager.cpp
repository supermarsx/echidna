#include "hooks/audioflinger_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <algorithm>
#include <string>
#include <time.h>
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

bool ForwardThreadLoop(void *thiz) {
    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    const bool allow = state.hooksEnabled() &&
                       (state.isProcessWhitelisted(process) || process == "audioserver");
    if (!allow) {
        return gOriginalThreadLoop ? gOriginalThreadLoop(thiz) : false;
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

    const uint32_t sample_rate = []() {
        static uint32_t cached = 0;
        if (cached == 0) {
            if (const char *env = std::getenv("ECHIDNA_AF_SAMPLE_RATE")) {
                cached = static_cast<uint32_t>(std::max(1, std::atoi(env)));
            }
            if (cached == 0) {
                cached = 48000u;
            }
        }
        return cached;
    }();
    const uint32_t channels = []() {
        static uint32_t cached = 0;
        if (cached == 0) {
            if (const char *env = std::getenv("ECHIDNA_AF_CHANNELS")) {
                cached = static_cast<uint32_t>(std::max(1, std::atoi(env)));
            }
            if (cached == 0) {
                cached = 2u;
            }
        }
        return cached;
    }();

    const size_t frame_bytes = channels * sizeof(int16_t);
    if (frame_bytes == 0 || (static_cast<size_t>(read_bytes) % frame_bytes) != 0) {
        return read_bytes;
    }
    const size_t frames = static_cast<size_t>(read_bytes) / frame_bytes;
    const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
    std::vector<float> in(frames * channels);
    for (size_t i = 0; i < frames * channels; ++i) {
        in[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
    }
    std::vector<float> out(frames * channels);
    const echidna_result_t result =
            echidna_process_block(in.data(), out.data(), static_cast<uint32_t>(frames), sample_rate, channels);
    if (result != ECHIDNA_RESULT_OK) {
        return read_bytes;
    }
    int16_t *pcm_out = static_cast<int16_t *>(buffer);
    for (size_t i = 0; i < frames * channels; ++i) {
        const float sample = std::clamp(out[i], -1.0f, 1.0f);
        pcm_out[i] = static_cast<int16_t>(sample * 32767.0f);
    }
    return read_bytes;
}

ssize_t AudioFlingerHookManager::ReplacementProcess(void *thiz, void *buffer, size_t bytes) {
    // For now, simply forward; data is already processed in ReplacementRead.
    return gOriginalProcess ? gOriginalProcess(thiz, buffer, bytes) : -1;
}

}  // namespace hooks
}  // namespace echidna
