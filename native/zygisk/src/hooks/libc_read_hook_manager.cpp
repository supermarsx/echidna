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
#include <atomic>
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
#include "hooks/fd_verdict_cache.h"
#include "state/shared_state.h"
#include "utils/telemetry_accumulator.h"

namespace echidna::hooks
{
    namespace
    {
        using ReadFn = ssize_t (*)(int, void *, size_t);
        using CloseFn = int (*)(int);
        using DupFn = int (*)(int);
        using Dup2Fn = int (*)(int, int);
        using Dup3Fn = int (*)(int, int, int);

        ReadFn gOriginalRead = nullptr;
        CloseFn gOriginalClose = nullptr;
        DupFn gOriginalDup = nullptr;
        Dup2Fn gOriginalDup2 = nullptr;
        Dup3Fn gOriginalDup3 = nullptr;
        std::optional<AudioHalPcmContract> gPcmContract;

        // Per-fd audio-device verdict cache. The hot read path does an O(1)
        // lock-free lookup here; the blocking fstat()+readlink() classification
        // runs only on a cache MISS (once per fd). Static-storage, zero-init,
        // never allocates.
        FdVerdictCache gVerdictCache;

        // Set once install() has hooked the fd-lifecycle calls that are needed
        // to keep the cache correct under fd reuse (close + dup2 + dup3, each of
        // which can retire an fd number without a close() call). Until then the
        // read path re-classifies every time rather than trusting a verdict that
        // an unobserved retirement could have made stale.
        std::atomic<bool> gLifecycleObserved{false};

        std::optional<AudioHalPcmContract> ReadExplicitContract()
        {
            return ParseAudioHalPcmContract(std::getenv("ECHIDNA_LIBC_SR"),
                                            std::getenv("ECHIDNA_LIBC_CH"),
                                            std::getenv("ECHIDNA_LIBC_FORMAT"));
        }

        // Classification LOGIC (unchanged from the original hot-path version):
        // an fd is a raw audio-capture device iff it is a character device whose
        // /proc/self/fd link resolves under /dev/snd/ or to /dev/audio. This is
        // the two-syscall step that the cache now runs at most once per fd.
        bool IsRawAudioDevice(int fd)
        {
            struct stat status
            {
            };
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

        FdAudioVerdict ClassifyFd(int fd)
        {
            return IsRawAudioDevice(fd) ? FdAudioVerdict::kAudioCapture
                                        : FdAudioVerdict::kNotAudio;
        }

        // Decide whether @p fd is an audio-capture device. On the steady-state
        // hot path this is a single lock-free cache load with no syscall; the
        // blocking classification runs at most once per fd (on a cache miss) and
        // only while fd-lifecycle observation guarantees the memoised verdict
        // cannot go stale. Without that guarantee we re-classify every time
        // (correct, just not RT-optimised) rather than trust a stale positive.
        bool IsAudioCaptureFd(int fd)
        {
            if (gLifecycleObserved.load(std::memory_order_relaxed))
            {
                return gVerdictCache.resolve(fd, &ClassifyFd) == FdAudioVerdict::kAudioCapture;
            }
            return ClassifyFd(fd) == FdAudioVerdict::kAudioCapture;
        }

        // fd-lifecycle forwarders. Each keeps the verdict cache honest, then
        // tail-calls the original libc implementation. They run off the audio
        // hot path (an app's read loop does not close/dup per frame).
        int ForwardClose(int fd)
        {
            // Evict while the fd is still valid so the number cannot be recycled
            // (which only happens after close returns) carrying a stale verdict.
            gVerdictCache.invalidate(fd);
            return gOriginalClose ? gOriginalClose(fd) : -1;
        }

        int ForwardDup(int fd)
        {
            const int new_fd = gOriginalDup ? gOriginalDup(fd) : -1;
            if (new_fd >= 0)
            {
                gVerdictCache.alias(fd, new_fd);
            }
            return new_fd;
        }

        int ForwardDup2(int old_fd, int new_fd)
        {
            // dup2 retires new_fd (closing it without a close() call) and rebinds
            // it to old_fd's description: clear any stale verdict first, then
            // alias the successful result to old_fd's verdict.
            gVerdictCache.invalidate(new_fd);
            const int result = gOriginalDup2 ? gOriginalDup2(old_fd, new_fd) : -1;
            if (result >= 0)
            {
                gVerdictCache.alias(old_fd, result);
            }
            return result;
        }

        int ForwardDup3(int old_fd, int new_fd, int flags)
        {
            gVerdictCache.invalidate(new_fd);
            const int result = gOriginalDup3 ? gOriginalDup3(old_fd, new_fd, flags) : -1;
            if (result >= 0)
            {
                gVerdictCache.alias(old_fd, result);
            }
            return result;
        }

        ssize_t ForwardRead(int fd, void *buffer, size_t bytes)
        {
            const ssize_t result = gOriginalRead ? gOriginalRead(fd, buffer, bytes) : -1;
            if (result <= 0 || !buffer || !gPcmContract || !IsAudioCaptureFd(fd))
            {
                return result;
            }
            auto processing = state::SharedState::instance().acquireAudioProcessing();
            if (!processing)
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

    bool LibcReadHookManager::InstallLifecycleHook(const char *symbol,
                                                   void *replacement,
                                                   void **original,
                                                   runtime::InlineHook &hook)
    {
        void *target = resolver_.findSymbol("libc.so", symbol);
        if (!target)
        {
            return false;
        }
        return hook.install(target, replacement, original);
    }

    bool LibcReadHookManager::install()
    {
        last_info_ = {};
        // A fresh specialization must never inherit verdicts from a previous
        // install: reset the cache and drop back to per-read classification
        // until the fd-lifecycle hooks below are proven installed.
        gLifecycleObserved.store(false, std::memory_order_relaxed);
        gVerdictCache.clear();
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

        // Install the fd-lifecycle observers that keep the verdict cache honest.
        // close, dup2 and dup3 can each retire an fd number and are therefore
        // correctness-critical: only when all three are hooked do we trust a
        // memoised verdict (gLifecycleObserved). dup merely creates a fresh fd
        // number (whose prior owner was already close-evicted), so its hook is a
        // pure aliasing optimisation and never gates correctness.
        const bool close_hooked =
            InstallLifecycleHook("close",
                                 reinterpret_cast<void *>(&ForwardClose),
                                 reinterpret_cast<void **>(&gOriginalClose),
                                 close_hook_);
        const bool dup2_hooked =
            InstallLifecycleHook("dup2",
                                 reinterpret_cast<void *>(&ForwardDup2),
                                 reinterpret_cast<void **>(&gOriginalDup2),
                                 dup2_hook_);
        const bool dup3_hooked =
            InstallLifecycleHook("dup3",
                                 reinterpret_cast<void *>(&ForwardDup3),
                                 reinterpret_cast<void **>(&gOriginalDup3),
                                 dup3_hook_);
        (void)InstallLifecycleHook("dup",
                                   reinterpret_cast<void *>(&ForwardDup),
                                   reinterpret_cast<void **>(&gOriginalDup),
                                   dup_hook_);

        if (close_hooked && dup2_hooked && dup3_hooked)
        {
            gLifecycleObserved.store(true, std::memory_order_release);
            __android_log_print(ANDROID_LOG_INFO,
                                "echidna",
                                "raw audio-device read hook installed with explicit PCM "
                                "contract; per-fd verdict cache active");
        }
        else
        {
            // Read is still hooked and correct; the cache just stays disabled so
            // a missed close/dup2/dup3 can never surface a stale verdict.
            __android_log_print(ANDROID_LOG_INFO,
                                "echidna",
                                "raw audio-device read hook installed with explicit PCM "
                                "contract; fd-lifecycle hooks incomplete -> verdict cache "
                                "disabled (per-read classification)");
        }
        return true;
    }

    ssize_t LibcReadHookManager::Replacement(int fd, void *buffer, size_t bytes)
    {
        return ForwardRead(fd, buffer, bytes);
    }
} // namespace echidna::hooks
