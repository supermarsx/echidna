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

#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna {
namespace hooks {

namespace {
using ThreadLoopFn = bool (*)(void *);
ThreadLoopFn gOriginalThreadLoop = nullptr;

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
                                "AudioFlinger hook installed at %s",
                                symbol);
            return true;
        }
    }
    __android_log_print(ANDROID_LOG_WARN, "echidna", "AudioFlinger hook not installed");
    return false;
}

bool AudioFlingerHookManager::Replacement(void *thiz) {
    return ForwardThreadLoop(thiz);
}

}  // namespace hooks
}  // namespace echidna
