#include "hooks/audiorecord_hook_manager.h"

/**
 * @file audiorecord_hook_manager.cpp
 * @brief Install inline hooks for AudioRecord::read and record telemetry on each
 * call.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <string>
#include <time.h>
#include <unistd.h>

#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna {
namespace hooks {

namespace {
using ReadFn = ssize_t (*)(void *, void *, size_t, bool);
ReadFn gOriginalRead = nullptr;

ssize_t ForwardRead(void *instance, void *buffer, size_t bytes, bool blocking) {
    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        return gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking) : 0;
    }
    timespec wall_start{};
    timespec wall_end{};
    timespec cpu_start{};
    timespec cpu_end{};
    clock_gettime(CLOCK_MONOTONIC, &wall_start);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);
    ssize_t result = gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking)
                                   : static_cast<ssize_t>(bytes);
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

}  // namespace

AudioRecordHookManager::AudioRecordHookManager(utils::PltResolver &resolver)
    : resolver_(resolver) {}

bool AudioRecordHookManager::install() {
    static const char *kCandidates[] = {
        "_ZN7android11AudioRecord4readEPvj",  // AudioRecord::read(void*, unsigned int)
        "_ZN7android11AudioRecord4readEPvjb",  // AudioRecord::read(void*, unsigned int, bool)
    };

    for (const char *symbol : kCandidates) {
        void *target = resolver_.findSymbol("libmedia.so", symbol);
        if (!target) {
            continue;
        }
        if (hook_.install(target, reinterpret_cast<void *>(&ForwardRead),
                          reinterpret_cast<void **>(&gOriginalRead))) {
            active_symbol_ = symbol;
            __android_log_print(ANDROID_LOG_INFO, "echidna", "AudioRecord hook installed at %s", symbol);
            return true;
        }
    }
    return false;
}

ssize_t AudioRecordHookManager::Replacement(void *instance, void *buffer, size_t bytes, bool blocking) {
    return ForwardRead(instance, buffer, bytes, blocking);
}

}  // namespace hooks
}  // namespace echidna
