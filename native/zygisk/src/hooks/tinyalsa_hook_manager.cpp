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

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
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
            using PcmGetUnsignedFn = unsigned int (*)(void *);
            using PcmGetFormatFn = int (*)(void *);
            using PcmFramesToBytesFn = unsigned int (*)(void *, unsigned int);
            using PcmFormatToBitsFn = unsigned int (*)(int);
            PcmReadFn gOriginalRead = nullptr;
            PcmReadFn gOriginalReadi = nullptr;
            PcmReadFn gOriginalMmapRead = nullptr;

            struct LegacyPcmConfig
            {
                unsigned int channels{0};
                unsigned int rate{0};
                unsigned int period_size{0};
                unsigned int period_count{0};
                int format{0};
            };
            using LegacyPcmGetConfigFn = int (*)(void *, LegacyPcmConfig *);

            struct PcmContext
            {
                uint32_t sample_rate{48000};
                uint32_t channels{2};
                uint32_t bits_per_sample{16};
                PcmFramesToBytesFn frames_to_bytes{nullptr};
            };

            template <typename Fn>
            Fn ResolveTinyAlsaSymbol(const char *name)
            {
                return reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT, name));
            }

            uint32_t ResolveFormatBits(int format)
            {
                static auto format_to_bits =
                    ResolveTinyAlsaSymbol<PcmFormatToBitsFn>("pcm_format_to_bits");
                if (format_to_bits)
                {
                    const unsigned int bits = format_to_bits(format);
                    if (bits == 8 || bits == 16 || bits == 24 || bits == 32)
                    {
                        return bits;
                    }
                }
                return format == 0 ? 16u : 0u;
            }

            PcmContext ResolvePcmContext(void *pcm)
            {
                PcmContext ctx;
                static auto get_channels =
                    ResolveTinyAlsaSymbol<PcmGetUnsignedFn>("pcm_get_channels");
                static auto get_rate =
                    ResolveTinyAlsaSymbol<PcmGetUnsignedFn>("pcm_get_rate");
                static auto get_format =
                    ResolveTinyAlsaSymbol<PcmGetFormatFn>("pcm_get_format");
                static auto get_legacy_config =
                    ResolveTinyAlsaSymbol<LegacyPcmGetConfigFn>("pcm_get_config");
                static auto frames_to_bytes =
                    ResolveTinyAlsaSymbol<PcmFramesToBytesFn>("pcm_frames_to_bytes");

                ctx.frames_to_bytes = frames_to_bytes;
                if (pcm && get_channels && get_rate)
                {
                    const unsigned int channels = get_channels(pcm);
                    const unsigned int rate = get_rate(pcm);
                    if (channels >= 1 && channels <= 8)
                    {
                        ctx.channels = channels;
                    }
                    if (rate > 8000 && rate < 192000)
                    {
                        ctx.sample_rate = rate;
                    }
                    if (get_format)
                    {
                        const uint32_t bits = ResolveFormatBits(get_format(pcm));
                        if (bits != 0)
                        {
                            ctx.bits_per_sample = bits;
                        }
                    }
                }
                else if (pcm && get_legacy_config)
                {
                    LegacyPcmConfig config{};
                    if (get_legacy_config(pcm, &config) == 0)
                    {
                        if (config.channels >= 1 && config.channels <= 8)
                        {
                            ctx.channels = config.channels;
                        }
                        if (config.rate > 8000 && config.rate < 192000)
                        {
                            ctx.sample_rate = config.rate;
                        }
                        const uint32_t bits = ResolveFormatBits(config.format);
                        if (bits != 0)
                        {
                            ctx.bits_per_sample = bits;
                        }
                    }
                }
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
                return ctx;
            }

            size_t BytesForFrames(void *pcm, unsigned int frames, const PcmContext &ctx)
            {
                if (ctx.frames_to_bytes)
                {
                    const unsigned int bytes = ctx.frames_to_bytes(pcm, frames);
                    if (bytes != 0)
                    {
                        return bytes;
                    }
                }
                return static_cast<size_t>(frames) * ctx.channels * (ctx.bits_per_sample / 8);
            }

            bool ProcessBuffer(void *data, size_t bytes, const PcmContext &ctx)
            {
                if (!data || bytes == 0 || ctx.channels == 0 || ctx.bits_per_sample != 16)
                {
                    return false;
                }
                const size_t frame_bytes = static_cast<size_t>(ctx.channels) * sizeof(int16_t);
                if (frame_bytes == 0 || bytes % frame_bytes != 0)
                {
                    return false;
                }
                return RouteInt16CaptureBufferInPlace(data,
                                                      bytes,
                                                      ctx.sample_rate,
                                                      ctx.channels,
                                                      echidna_process_block);
            }

            int ForwardReadBytes(void *pcm, void *data, unsigned int bytes, PcmReadFn original)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return original ? original(pcm, data, bytes) : -1;
                }

                const PcmContext ctx = ResolvePcmContext(pcm);
                if (bytes == 0 || !data)
                {
                    return original ? original(pcm, data, bytes) : -1;
                }

                const int result = original ? original(pcm, data, bytes) : -1;
                if (result < 0)
                {
                    return result;
                }

                const size_t processed_bytes =
                    result > 0 ? std::min(static_cast<size_t>(result),
                                          static_cast<size_t>(bytes))
                               : static_cast<size_t>(bytes);
                ProcessBuffer(data, processed_bytes, ctx);
                return result;
            }

            int ForwardReadFrames(void *pcm, void *data, unsigned int frames, PcmReadFn original)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process))
                {
                    return original ? original(pcm, data, frames) : -1;
                }

                if (frames == 0 || !data)
                {
                    return original ? original(pcm, data, frames) : -1;
                }

                const int result = original ? original(pcm, data, frames) : -1;
                if (result <= 0)
                {
                    return result;
                }

                const PcmContext ctx = ResolvePcmContext(pcm);
                const unsigned int frames_read =
                    std::min(static_cast<unsigned int>(result), frames);
                const size_t bytes = BytesForFrames(pcm, frames_read, ctx);
                ProcessBuffer(data, bytes, ctx);
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
            void *mmap_read_target = resolver_.findSymbol(library, "pcm_mmap_read");
            if (mmap_read_target)
            {
                hook_mmap_read_.install(mmap_read_target,
                                        reinterpret_cast<void *>(&ReplacementMmapRead),
                                        reinterpret_cast<void **>(&gOriginalMmapRead));
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
            if (gOriginalMmapRead)
            {
                last_info_.success = true;
                last_info_.library = library;
                last_info_.symbol = "pcm_mmap_read";
                last_info_.reason.clear();
                return true;
            }
            last_info_.reason = "symbol_not_found";
            return false;
        }

        int TinyAlsaHookManager::ReplacementRead(void *pcm, void *data, unsigned int count)
        {
            return ForwardReadBytes(pcm, data, count, gOriginalRead);
        }

        int TinyAlsaHookManager::ReplacementReadi(void *pcm, void *data, unsigned int frames)
        {
            return ForwardReadFrames(pcm, data, frames, gOriginalReadi);
        }

        int TinyAlsaHookManager::ReplacementMmapRead(void *pcm, void *data, unsigned int count)
        {
            return ForwardReadBytes(pcm, data, count, gOriginalMmapRead);
        }

    } // namespace hooks
} // namespace echidna
