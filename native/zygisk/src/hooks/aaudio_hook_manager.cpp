#include "hooks/aaudio_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 0
#define ANDROID_LOG_INFO 0
#endif
#include <unistd.h>

#include <string>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna {
namespace hooks {

namespace {
using Callback = int (*)(void *, void *, void *, int32_t);
Callback gOriginalCallback = nullptr;

int ForwardCallback(void *stream, void *user, void *audio_data, int32_t num_frames) {
    auto &state = state::SharedState::instance();
    state.refreshFromSharedMemory();
    const std::string process = utils::CurrentProcessName();
    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        return gOriginalCallback ? gOriginalCallback(stream, user, audio_data, num_frames) : 0;
    }
    state.setStatus(state::InternalStatus::kHooked);
    return gOriginalCallback ? gOriginalCallback(stream, user, audio_data, num_frames) : 0;
}

}  // namespace

AAudioHookManager::AAudioHookManager(utils::PltResolver &resolver) : resolver_(resolver) {}

bool AAudioHookManager::install() {
    void *symbol = resolver_.findSymbol("libaaudio.so", "AAudioStream_dataCallback");
    if (!symbol) {
        return false;
    }

    if (!hook_.install(symbol, reinterpret_cast<void *>(&ForwardCallback), reinterpret_cast<void **>(&gOriginalCallback))) {
        __android_log_print(ANDROID_LOG_WARN, "echidna", "Failed to install AAudio hook");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "echidna", "AAudio hook installed");
    return true;
}

int AAudioHookManager::Replacement(void *stream, void *user, void *audio_data, int32_t num_frames) {
    return ForwardCallback(stream, user, audio_data, num_frames);
}

}  // namespace hooks
}  // namespace echidna
