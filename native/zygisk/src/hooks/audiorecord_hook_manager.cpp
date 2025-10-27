#include "hooks/audiorecord_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <string>
#include <unistd.h>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna {
namespace hooks {

namespace {
using ReadFn = ssize_t (*)(void *, void *, size_t, bool);
ReadFn gOriginalRead = nullptr;

ssize_t ForwardRead(void *instance, void *buffer, size_t bytes, bool blocking) {
    auto &state = state::SharedState::instance();
    state.refreshFromSharedMemory();
    const std::string process = utils::CurrentProcessName();
    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        return gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking) : 0;
    }
    state.setStatus(state::InternalStatus::kHooked);
    return gOriginalRead ? gOriginalRead(instance, buffer, bytes, blocking) : static_cast<ssize_t>(bytes);
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
