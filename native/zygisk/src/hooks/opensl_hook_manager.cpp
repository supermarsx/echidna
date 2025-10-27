#include "hooks/opensl_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <string>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna {
namespace hooks {

namespace {
using BufferCallback = void (*)(void *, void *, void *, uint32_t);
BufferCallback gOriginalCallback = nullptr;

void ForwardCallback(void *caller, void *context, void *buffer, uint32_t size) {
    auto &state = state::SharedState::instance();
    state.refreshFromSharedMemory();
    const std::string process = utils::CurrentProcessName();
    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        if (gOriginalCallback) {
            gOriginalCallback(caller, context, buffer, size);
        }
        return;
    }
    state.setStatus(state::InternalStatus::kHooked);
    if (gOriginalCallback) {
        gOriginalCallback(caller, context, buffer, size);
    }
}

}  // namespace

OpenSLHookManager::OpenSLHookManager(utils::PltResolver &resolver)
    : resolver_(resolver) {}

bool OpenSLHookManager::install() {
    static const char *kCandidates[] = {
        "SLAndroidSimpleBufferQueueItf_Enqueue",
        "SLBufferQueueItf_CallbackProxy",
        "SLBufferQueueItf_RegisterCallback",
    };

    for (const char *symbol : kCandidates) {
        void *target = resolver_.findSymbol("libOpenSLES.so", symbol);
        if (!target) {
            continue;
        }
        if (hook_.install(target, reinterpret_cast<void *>(&ForwardCallback),
                          reinterpret_cast<void **>(&gOriginalCallback))) {
            active_symbol_ = symbol;
            __android_log_print(ANDROID_LOG_INFO, "echidna", "OpenSL hook installed at %s", symbol);
            return true;
        }
    }
    return false;
}

void OpenSLHookManager::Replacement(void *caller, void *context, void *buffer, uint32_t size) {
    ForwardCallback(caller, context, buffer, size);
}

}  // namespace hooks
}  // namespace echidna
