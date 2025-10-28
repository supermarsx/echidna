#include "hooks/opensl_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <SLES/OpenSLES.h>

#include <string>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna {
namespace hooks {

namespace {
using BufferCallback = SLresult (*)(void *, void *, void *, uint32_t);
BufferCallback gOriginalCallback = nullptr;

SLresult ForwardCallback(void *caller, void *context, void *buffer, uint32_t size) {
    auto &state = state::SharedState::instance();
    state.refreshFromSharedMemory();
    const std::string process = utils::CurrentProcessName();
    if (!state.hooksEnabled() || !state.isProcessWhitelisted(process)) {
        return gOriginalCallback ? gOriginalCallback(caller, context, buffer, size)
                                 : SL_RESULT_SUCCESS;
    }
    state.setStatus(state::InternalStatus::kHooked);
    return gOriginalCallback ? gOriginalCallback(caller, context, buffer, size)
                             : SL_RESULT_SUCCESS;
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

SLresult OpenSLHookManager::Replacement(void *caller, void *context, void *buffer,
                                        uint32_t size) {
    return ForwardCallback(caller, context, buffer, size);
}

}  // namespace hooks
}  // namespace echidna
