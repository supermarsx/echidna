#include "hooks/audioflinger_hook_manager.h"

/**
 * @file audioflinger_hook_manager.cpp
 * @brief Truthful guard for the unsupported AudioFlinger service boundary.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 0
#endif

namespace echidna::hooks
{
    AudioFlingerHookManager::AudioFlingerHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool AudioFlingerHookManager::install()
    {
        (void)resolver_;
        last_info_ = {};
        last_info_.library = "libaudioflinger.so";
        last_info_.reason = "unsupported_injection_boundary";

        // The Zygisk module intentionally does not stay loaded in
        // system_server and is not injected into audioserver. AOSP exposes a
        // RecordThread::threadLoop entrypoint, but that function does not
        // provide a stable PCM-buffer transform ABI. Treating a telemetry-only
        // thread-loop hook as success was therefore incorrect. Likewise, the
        // formerly guessed RecordThread::read/processVolume mangled symbols
        // are not stable AOSP contracts and cannot establish PCM format or
        // object layout safely. Device-specific AudioFlinger support must be
        // supplied by a separately proven audioserver injection boundary.
        __android_log_print(ANDROID_LOG_WARN,
                            "echidna",
                            "AudioFlinger transform disabled: unsupported audioserver boundary");
        return false;
    }
} // namespace echidna::hooks
