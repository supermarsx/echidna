#include "hooks/libc_read_hook_manager.h"

/**
 * @file libc_read_hook_manager.cpp
 * @brief Intercept libc read() to detect audio device reads and log telemetry.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <algorithm>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/process_utils.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
    namespace hooks
    {

        namespace
        {
            using ReadFn = ssize_t (*)(int, void *, size_t);
            ReadFn gOriginalRead = nullptr;

            bool IsAudioFd(int fd)
            {
                struct stat st{};
                if (fstat(fd, &st) != 0)
                {
                    return false;
                }
                if (!S_ISCHR(st.st_mode))
                {
                    return false;
                }
                char path[PATH_MAX] = {0};
                const ssize_t len = readlink(("/proc/self/fd/" + std::to_string(fd)).c_str(),
                                             path,
                                             sizeof(path) - 1);
                if (len <= 0)
                {
                    return false;
                }
                path[len] = '\0';
                const std::string target(path);
                return target.find("/dev/snd/") != std::string::npos ||
                       target.find("/dev/audio") != std::string::npos;
            }

            uint32_t DefaultSampleRate()
            {
                if (const char *env = std::getenv("ECHIDNA_LIBC_SR"))
                {
                    const int sr = std::atoi(env);
                    if (sr > 8000 && sr < 192000)
                    {
                        return static_cast<uint32_t>(sr);
                    }
                }
                return 48000u;
            }

            uint32_t DefaultChannels()
            {
                if (const char *env = std::getenv("ECHIDNA_LIBC_CH"))
                {
                    const int ch = std::atoi(env);
                    if (ch >= 1 && ch <= 8)
                    {
                        return static_cast<uint32_t>(ch);
                    }
                }
                return 2u;
            }

            ssize_t ForwardRead(int fd, void *buffer, size_t bytes)
            {
                auto &state = state::SharedState::instance();
                const std::string &process = utils::CachedProcessName();
                if (!state.hooksEnabled() || !state.isProcessWhitelisted(process) || !IsAudioFd(fd))
                {
                    return gOriginalRead ? gOriginalRead(fd, buffer, bytes) : -1;
                }

                timespec wall_start{};
                timespec wall_end{};
                timespec cpu_start{};
                timespec cpu_end{};
                clock_gettime(CLOCK_MONOTONIC, &wall_start);
                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);

                const ssize_t result = gOriginalRead ? gOriginalRead(fd, buffer, bytes) : -1;

                clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
                clock_gettime(CLOCK_MONOTONIC, &wall_end);

                if (result > 0 && buffer)
                {
                    uint32_t sample_rate = DefaultSampleRate();
                    uint32_t channels = DefaultChannels();
                    size_t frame_bytes = channels * sizeof(int16_t);
                    const size_t total_samples = static_cast<size_t>(result) / sizeof(int16_t);
                    if (frame_bytes == 0 || (static_cast<size_t>(result) % frame_bytes) != 0)
                    {
                        for (uint32_t ch = 1; ch <= 8; ++ch)
                        {
                            if (total_samples % ch == 0)
                            {
                                channels = ch;
                                frame_bytes = channels * sizeof(int16_t);
                                break;
                            }
                        }
                    }
                    if (frame_bytes != 0 && (static_cast<size_t>(result) % frame_bytes) == 0)
                    {
                        const size_t frames = static_cast<size_t>(result) / frame_bytes;
                        const size_t samples = frames * channels;
                        std::vector<float> in(samples);
                        const int16_t *pcm = static_cast<const int16_t *>(buffer);
                        for (size_t i = 0; i < samples; ++i)
                        {
                            in[i] = static_cast<float>(pcm[i]) / 32768.0f;
                        }
                        std::vector<float> out(samples);
                        if (echidna_process_block(in.data(),
                                                  out.data(),
                                                  static_cast<uint32_t>(frames),
                                                  sample_rate,
                                                  channels) == ECHIDNA_RESULT_OK)
                        {
                            int16_t *pcm_out = static_cast<int16_t *>(buffer);
                            for (size_t i = 0; i < samples; ++i)
                            {
                                const float clamped = std::clamp(out[i], -1.0f, 1.0f);
                                pcm_out[i] = static_cast<int16_t>(clamped * 32767.0f);
                            }
                        }
                    }
                }

                const int64_t wall_ns_raw = (static_cast<int64_t>(wall_end.tv_sec) -
                                             static_cast<int64_t>(wall_start.tv_sec)) *
                                                1000000000ll +
                                            (static_cast<int64_t>(wall_end.tv_nsec) -
                                             static_cast<int64_t>(wall_start.tv_nsec));
                const int64_t cpu_ns_raw = (static_cast<int64_t>(cpu_end.tv_sec) -
                                            static_cast<int64_t>(cpu_start.tv_sec)) *
                                               1000000000ll +
                                           (static_cast<int64_t>(cpu_end.tv_nsec) -
                                            static_cast<int64_t>(cpu_start.tv_nsec));
                const uint32_t wall_us = static_cast<uint32_t>(
                    std::max<int64_t>(wall_ns_raw, 0ll) / 1000ll);
                const uint32_t cpu_us = static_cast<uint32_t>(
                    std::max<int64_t>(cpu_ns_raw, 0ll) / 1000ll);
                const uint64_t timestamp_ns =
                    static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                    static_cast<uint64_t>(wall_end.tv_nsec);

                state.telemetry().recordCallback(timestamp_ns,
                                                 wall_us,
                                                 cpu_us,
                                                 echidna::utils::kTelemetryFlagCallback,
                                                 0);
                state.setStatus(state::InternalStatus::kHooked);
                return result;
            }

        } // namespace

        LibcReadHookManager::LibcReadHookManager(utils::PltResolver &resolver)
            : resolver_(resolver) {}

        bool LibcReadHookManager::install()
        {
            void *target = resolver_.findSymbol("libc.so", "read");
            if (!target)
            {
                return false;
            }
            if (hook_.install(target,
                              reinterpret_cast<void *>(&ForwardRead),
                              reinterpret_cast<void **>(&gOriginalRead)))
            {
                __android_log_print(ANDROID_LOG_INFO, "echidna", "libc read hook installed");
                return true;
            }
            return false;
        }

        ssize_t LibcReadHookManager::Replacement(int fd, void *buffer, size_t bytes)
        {
            return ForwardRead(fd, buffer, bytes);
        }

    } // namespace hooks
} // namespace echidna
