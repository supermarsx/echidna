#include "hooks/audiohal_hook_manager.h"

/**
 * @file audiohal_hook_manager.cpp
 * @brief Truthfully reports the unavailable vendor Audio HAL injection boundary.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 0
#endif

namespace echidna::hooks
{
    AudioHalHookManager::AudioHalHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool AudioHalHookManager::install()
    {
        (void)resolver_;
        last_info_ = {};
        last_info_.library = "vendor_audio_hal";
        last_info_.reason = kAudioHalRoute.unavailable_reason;

        // Zygisk injects whitelisted application processes, not audioserver.
        // Vendor audio_stream_in objects and their read vtables live behind that
        // service boundary, and neither their object layout nor PCM metadata is
        // a stable application-process ABI. Environment-only format declarations
        // did not make the boundary reachable and could incorrectly report a
        // successful normal route if manually populated. Keep this route
        // unsupported until a separately proven audioserver injection mechanism
        // supplies stable per-stream metadata and lifecycle ownership.
        __android_log_print(ANDROID_LOG_WARN,
                            "echidna",
                            "Audio HAL transform disabled: unsupported audioserver boundary");
        return false;
    }
} // namespace echidna::hooks
