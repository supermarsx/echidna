#include "hooks/audiohal_hook_manager.h"

/**
 * @file audiohal_hook_manager.cpp
 * @brief Hooks C audio_stream_in read entrypoints only when an explicit PCM
 * contract is supplied. Opaque vendor objects are never scanned or guessed.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <cstdlib>
#include <optional>

#include "echidna/api.h"
#include "hooks/audiohal_contract.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"

namespace echidna::hooks
{
    namespace
    {
        using StreamReadFn = ssize_t (*)(void *, void *, size_t);
        StreamReadFn gOriginalRead = nullptr;
        std::optional<AudioHalPcmContract> gPcmContract;

        std::optional<AudioHalPcmContract> ReadExplicitContract()
        {
            return ParseAudioHalPcmContract(std::getenv("ECHIDNA_HAL_SR"),
                                            std::getenv("ECHIDNA_HAL_CH"),
                                            std::getenv("ECHIDNA_HAL_FORMAT"));
        }

        ssize_t ForwardRead(void *stream, void *buffer, size_t bytes)
        {
            const ssize_t read_bytes =
                gOriginalRead ? gOriginalRead(stream, buffer, bytes) : -1;
            if (read_bytes <= 0 || !buffer || !gPcmContract)
            {
                return read_bytes;
            }
            if (!state::SharedState::instance().audioProcessingAllowed())
            {
                return read_bytes;
            }

            const auto &contract = *gPcmContract;
            (void)RouteCaptureBufferInPlace(buffer,
                                            static_cast<size_t>(read_bytes),
                                            contract.format,
                                            contract.sample_rate,
                                            contract.channels,
                                            echidna_process_block);
            return read_bytes;
        }
    } // namespace

    AudioHalHookManager::AudioHalHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool AudioHalHookManager::install()
    {
        last_info_ = {};
        gPcmContract = ReadExplicitContract();
        if (!gPcmContract)
        {
            last_info_.reason = "explicit_pcm_contract_required";
            return false;
        }
        if (echidna_prepare_stream(gPcmContract->sample_rate, gPcmContract->channels) !=
            ECHIDNA_RESULT_OK)
        {
            last_info_.reason = "dsp_prepare_failed";
            return false;
        }

        // These candidates use the C audio_stream_in read ABI
        // (stream, buffer, byte_count). Mangled C++ candidates are deliberately
        // excluded because their object layout and signatures are vendor-private.
        static const char *kLibraries[] = {
            "libaudiohal.so",
            "libaudio.primary.so",
            "libaudio.primary.default.so",
            "libaudio.primary.vendor.so",
        };
        static const char *kSymbols[] = {
            "audio_stream_in_read",
            "in_read",
            "adev_in_read",
        };

        for (const char *library : kLibraries)
        {
            for (const char *symbol : kSymbols)
            {
                void *target = resolver_.findSymbol(library, symbol);
                if (!target)
                {
                    continue;
                }
                if (hook_.install(target,
                                  reinterpret_cast<void *>(&Replacement),
                                  reinterpret_cast<void **>(&gOriginalRead)))
                {
                    LogHookSource(library, symbol);
                    last_info_.success = true;
                    last_info_.library = library;
                    last_info_.symbol = symbol;
                    return true;
                }
                last_info_.library = library;
                last_info_.symbol = symbol;
                last_info_.reason = "hook_failed";
            }
        }
        if (last_info_.reason.empty())
        {
            last_info_.reason = "symbol_not_found";
        }
        return false;
    }

    ssize_t AudioHalHookManager::Replacement(void *stream, void *buffer, size_t bytes)
    {
        return ForwardRead(stream, buffer, bytes);
    }

    void AudioHalHookManager::LogHookSource(const char *library, const char *symbol)
    {
        __android_log_print(ANDROID_LOG_INFO,
                            "echidna",
                            "Audio HAL hook installed at %s in %s",
                            symbol,
                            library);
    }

} // namespace echidna::hooks
