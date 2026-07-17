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
        last_info_.reason = kAudioFlingerRoute.unavailable_reason;

        // The Zygisk module intentionally does not stay loaded in
        // system_server and is not injected into audioserver. AOSP exposes a
        // RecordThread::threadLoop entrypoint, but that function does not
        // provide a stable PCM-buffer transform ABI. Treating a telemetry-only
        // thread-loop hook as success was therefore incorrect. Likewise, the
        // formerly guessed RecordThread::read/processVolume mangled symbols
        // are not stable AOSP contracts and cannot establish PCM format or
        // object layout safely. Device-specific AudioFlinger support must be
        // supplied by a separately proven audioserver injection boundary.
        //
        // The rationale, the audioserver-injection prerequisite, and the honest
        // status (device-gated / not viable from the Zygisk vantage) are
        // documented in docs/hardening/audioflinger-route.md. The one piece that
        // is safely landable without a device — the fail-closed capture-buffer
        // admission guard a future companion would satisfy before touching a
        // RecordThread buffer — lives, host-tested and hard-OFF by default, in
        // hooks/audioflinger_format.h (ECHIDNA_AUDIOFLINGER_BOUNDARY_PROVEN).
        // It is intentionally NOT wired in here: this install() stays a plain
        // truthful refusal until that boundary is separately proven.
        __android_log_print(ANDROID_LOG_WARN,
                            "echidna",
                            "AudioFlinger transform disabled: unsupported audioserver boundary");
        return false;
    }
} // namespace echidna::hooks
