#include "hooks/audiohal_hook_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <cstdlib>
#include <string>
#include <vector>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna {
namespace hooks {

namespace {
using StreamReadFn = ssize_t (*)(void *, void *, size_t);
StreamReadFn gOriginalRead = nullptr;

struct HalContext {
    uint32_t sample_rate{48000};
    uint32_t channels{2};
    bool validated{false};
};

HalContext DefaultHalContext() {
    HalContext ctx;
    if (const char *env = std::getenv("ECHIDNA_HAL_SR")) {
        int sr = std::atoi(env);
        if (sr > 8000 && sr < 192000) {
            ctx.sample_rate = static_cast<uint32_t>(sr);
        }
    }
    if (const char *env = std::getenv("ECHIDNA_HAL_CH")) {
        int ch = std::atoi(env);
        if (ch >= 1 && ch <= 8) {
            ctx.channels = static_cast<uint32_t>(ch);
        }
    }
    return ctx;
}

ssize_t ForwardRead(void *stream, void *buffer, size_t bytes) {
    const ssize_t read_bytes = gOriginalRead ? gOriginalRead(stream, buffer, bytes) : -1;
    if (read_bytes <= 0 || !buffer) {
        return read_bytes;
    }
    auto &state = state::SharedState::instance();
    const std::string &process = utils::CachedProcessName();
    if (!state.hooksEnabled() || (!state.isProcessWhitelisted(process) && process != "audioserver")) {
        return read_bytes;
    }

    HalContext ctx = DefaultHalContext();
    size_t frame_bytes = ctx.channels * sizeof(int16_t);
    const size_t total_samples = static_cast<size_t>(read_bytes) / sizeof(int16_t);
    if (frame_bytes == 0 || (static_cast<size_t>(read_bytes) % frame_bytes) != 0) {
        // Infer channel count when misaligned (1..8).
        for (uint32_t ch = 1; ch <= 8; ++ch) {
            if (total_samples % ch == 0) {
                ctx.channels = ch;
                frame_bytes = ch * sizeof(int16_t);
                ctx.validated = true;
                break;
            }
        }
    }
    if (frame_bytes == 0 || (static_cast<size_t>(read_bytes) % frame_bytes) != 0) {
        return read_bytes;
    }
    const size_t frames = static_cast<size_t>(read_bytes) / frame_bytes;
    const int16_t *pcm_in = static_cast<const int16_t *>(buffer);
    std::vector<float> in(frames * ctx.channels);
    for (size_t i = 0; i < frames * ctx.channels; ++i) {
        in[i] = static_cast<float>(pcm_in[i]) / 32768.0f;
    }
    std::vector<float> out(frames * ctx.channels);
    if (echidna_process_block(in.data(),
                              out.data(),
                              static_cast<uint32_t>(frames),
                              ctx.sample_rate,
                              ctx.channels) != ECHIDNA_RESULT_OK) {
        return read_bytes;
    }
    int16_t *pcm_out = static_cast<int16_t *>(buffer);
    for (size_t i = 0; i < frames * ctx.channels; ++i) {
        pcm_out[i] = static_cast<int16_t>(std::clamp(out[i], -1.0f, 1.0f) * 32767.0f);
    }
    return read_bytes;
}

}  // namespace

AudioHalHookManager::AudioHalHookManager(utils::PltResolver &resolver)
    : resolver_(resolver) {}

bool AudioHalHookManager::install() {
    static const char *kLibs[] = {
        "libaudiohal.so",
        "libaudio.so",
        "libaudio.primary.so",
        "libaudioclient.so",
    };
    static const char *kSymbols[] = {
        "audio_stream_in_read",
        "_ZN7android13AudioHwDevice5readEP18audio_stream_in_siPvj",
        "_ZN7android13AudioStreamIn10readFramesEPvj",
    };

    for (const char *lib : kLibs) {
        for (const char *sym : kSymbols) {
            void *target = resolver_.findSymbol(lib, sym);
            if (!target) {
                continue;
            }
            if (hook_.install(target,
                              reinterpret_cast<void *>(&Replacement),
                              reinterpret_cast<void **>(&gOriginalRead))) {
                __android_log_print(ANDROID_LOG_INFO,
                                    "echidna",
                                    "Audio HAL hook installed at %s in %s",
                                    sym,
                                    lib);
                return true;
            }
        }
    }
    return false;
}

ssize_t AudioHalHookManager::Replacement(void *stream, void *buffer, size_t bytes) {
    return ForwardRead(stream, buffer, bytes);
}

}  // namespace hooks
}  // namespace echidna
