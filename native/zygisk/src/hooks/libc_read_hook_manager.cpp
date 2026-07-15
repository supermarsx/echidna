#include "hooks/libc_read_hook_manager.h"

/**
 * @file libc_read_hook_manager.cpp
 * @brief Optional raw audio-device read hook with an explicit PCM contract.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "echidna/api.h"
#include "hooks/audiohal_contract.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/telemetry_accumulator.h"

namespace echidna::hooks
{
    namespace
    {
        using ReadFn = ssize_t (*)(int, void *, size_t);
        ReadFn gOriginalRead = nullptr;
        std::optional<AudioHalPcmContract> gPcmContract;

        std::optional<AudioHalPcmContract> ReadExplicitContract()
        {
            return ParseAudioHalPcmContract(std::getenv("ECHIDNA_LIBC_SR"),
                                            std::getenv("ECHIDNA_LIBC_CH"),
                                            std::getenv("ECHIDNA_LIBC_FORMAT"));
        }

        bool IsRawAudioDevice(int fd)
        {
            struct stat status{};
            if (fd < 0 || fstat(fd, &status) != 0 || !S_ISCHR(status.st_mode))
            {
                return false;
            }
            char descriptor_path[48]{};
            const int path_length = std::snprintf(descriptor_path,
                                                  sizeof(descriptor_path),
                                                  "/proc/self/fd/%d",
                                                  fd);
            if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(descriptor_path))
            {
                return false;
            }
            char target[256]{};
            const ssize_t length = readlink(descriptor_path, target, sizeof(target) - 1);
            if (length <= 0 || static_cast<size_t>(length) >= sizeof(target))
            {
                return false;
            }
            target[length] = '\0';
            return std::strncmp(target, "/dev/snd/", 9) == 0 ||
                   std::strcmp(target, "/dev/audio") == 0;
        }

        bool ProcessingAllowed()
        {
            return state::SharedState::instance().audioProcessingAllowed();
        }

        ssize_t ForwardRead(int fd, void *buffer, size_t bytes)
        {
            const ssize_t result = gOriginalRead ? gOriginalRead(fd, buffer, bytes) : -1;
            if (result <= 0 || !buffer || !gPcmContract || !IsRawAudioDevice(fd) ||
                !ProcessingAllowed())
            {
                return result;
            }

            utils::ScopedTelemetryRoute telemetry_route(utils::TelemetryRoute::kLibcRead);
            (void)RouteCaptureBufferInPlace(
                buffer,
                static_cast<size_t>(result),
                gPcmContract->format,
                gPcmContract->sample_rate,
                gPcmContract->channels,
                echidna_process_block);
            return result;
        }
    } // namespace

    LibcReadHookManager::LibcReadHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool LibcReadHookManager::install()
    {
        last_info_ = {};
        gPcmContract = ReadExplicitContract();
        if (!gPcmContract)
        {
            last_info_.reason = kLibcReadRoute.unavailable_reason;
            return false;
        }
        if (echidna_prepare_stream(gPcmContract->sample_rate, gPcmContract->channels) !=
            ECHIDNA_RESULT_OK)
        {
            last_info_.reason = "developer_contract_dsp_prepare_failed";
            return false;
        }
        constexpr const char *kLibrary = "libc.so";
        constexpr const char *kSymbol = "read";
        void *target = resolver_.findSymbol(kLibrary, kSymbol);
        if (!target)
        {
            last_info_.reason = "developer_contract_symbol_not_found";
            return false;
        }
        if (!hook_.install(target,
                           reinterpret_cast<void *>(&ForwardRead),
                           reinterpret_cast<void **>(&gOriginalRead)))
        {
            last_info_.library = kLibrary;
            last_info_.symbol = kSymbol;
            last_info_.reason = "developer_contract_hook_failed";
            return false;
        }
        last_info_.success = true;
        last_info_.library = kLibrary;
        last_info_.symbol = kSymbol;
        last_info_.reason = "developer_contract_active";
        __android_log_print(ANDROID_LOG_INFO,
                            "echidna",
                            "raw audio-device read hook installed with explicit PCM contract");
        return true;
    }

    ssize_t LibcReadHookManager::Replacement(int fd, void *buffer, size_t bytes)
    {
        return ForwardRead(fd, buffer, bytes);
    }
} // namespace echidna::hooks
