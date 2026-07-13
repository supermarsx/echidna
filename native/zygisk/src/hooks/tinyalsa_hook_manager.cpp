#include "hooks/tinyalsa_hook_manager.h"

/**
 * @file tinyalsa_hook_manager.cpp
 * @brief Interpose tinyalsa PCM read functions and route buffers through the
 * DSP pipeline.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <cstdlib>
#include <cstring>
#include <string>

#include "echidna/api.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using PcmReadFn = int (*)(void *, void *, unsigned int);
            PcmReadFn gOriginalRead = nullptr;
            PcmReadFn gOriginalReadi = nullptr;

            struct PcmContext
            {
                uint32_t sample_rate{48000};
                uint32_t channels{2};
            };

            PcmContext ResolvePcmContext(void *pcm)
            {
                PcmContext ctx;
                if (const char *env = std::getenv("ECHIDNA_PCM_SR"))
                {
                    const int sr = std::atoi(env);
                    if (sr > 8000 && sr < 192000)
                    {
                        ctx.sample_rate = static_cast<uint32_t>(sr);
                    }
                }
                if (const char *env = std::getenv("ECHIDNA_PCM_CH"))
                {
                    const int ch = std::atoi(env);
                    if (ch >= 1 && ch <= 8)
                    {
                        ctx.channels = static_cast<uint32_t>(ch);
                    }
                }
                if (pcm)
                {
                    // Tinyalsa pcm struct stores config near start; attempt to read.
                    struct
                    {
                        uint32_t flags;
                        uint32_t channels;
                        uint32_t rate;
                    } probe{};
                    if (std::memcpy(&probe, pcm, sizeof(probe)))
                    {
                        if (probe.rate > 8000 && probe.rate < 192000)
                        {
                            ctx.sample_rate = probe.rate;
                        }
                        if (probe.channels >= 1 && probe.channels <= 8)
                        {
                            ctx.channels = probe.channels;
                        }
                    }
                }
                return ctx;
            }

            int ForwardRead(void *pcm, void *data, unsigned int frames, PcmReadFn original)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return original ? original(pcm, data, frames) : -1;
                }

                const PcmContext ctx = ResolvePcmContext(pcm);
                const size_t samples = static_cast<size_t>(frames) * ctx.channels;
                if (samples == 0 || !data)
                {
                    return original ? original(pcm, data, frames) : -1;
                }

                const int result = original ? original(pcm, data, frames) : -1;
                if (result != 0)
                {
                    return result;
                }

                RouteInt16CaptureBufferInPlace(data,
                                               samples * sizeof(int16_t),
                                               ctx.sample_rate,
                                               ctx.channels,
                                               echidna_process_block);
                return result;
            }

        } // namespace

        TinyAlsaHookManager::TinyAlsaHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool TinyAlsaHookManager::install()
        {
            last_info_ = {};
            const char *library = "libtinyalsa.so";
            void *read_target = resolver_.findSymbol(library, "pcm_read");
            if (read_target)
            {
                hook_read_.install(read_target,
                                   reinterpret_cast<void *>(&ReplacementRead),
                                   reinterpret_cast<void **>(&gOriginalRead));
            }
            void *readi_target = resolver_.findSymbol(library, "pcm_readi");
            if (readi_target)
            {
                hook_readi_.install(readi_target,
                                    reinterpret_cast<void *>(&ReplacementReadi),
                                    reinterpret_cast<void **>(&gOriginalReadi));
            }
            if (gOriginalRead)
            {
                last_info_.success = true;
                last_info_.library = library;
                last_info_.symbol = "pcm_read";
                last_info_.reason.clear();
                return true;
            }
            if (gOriginalReadi)
            {
                last_info_.success = true;
                last_info_.library = library;
                last_info_.symbol = "pcm_readi";
                last_info_.reason.clear();
                return true;
            }
            last_info_.reason = "symbol_not_found";
            return false;
        }

        int TinyAlsaHookManager::ReplacementRead(void *pcm, void *data, unsigned int count)
        {
            return ForwardRead(pcm, data, count, gOriginalRead);
        }

        int TinyAlsaHookManager::ReplacementReadi(void *pcm, void *data, unsigned int frames)
        {
            return ForwardRead(pcm, data, frames, gOriginalReadi);
        }

    } // namespace hooks
} // namespace echidna
