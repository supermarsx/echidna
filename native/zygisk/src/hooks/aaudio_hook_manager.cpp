#include "hooks/aaudio_hook_manager.h"

/**
 * @file aaudio_hook_manager.cpp
 * @brief Install lifecycle-safe AAudio capture hooks.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <time.h>

#include "echidna/api.h"
#include "hooks/aaudio_callback_registry.h"
#include "hooks/capture_buffer_router.h"
#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna::hooks
{
    namespace
    {
        using Callback = AAudioDataCallback;
        using SetDataCallbackFn = void (*)(void *, Callback, void *);
        using OpenStreamFn = int32_t (*)(void *, void **);
        using CloseStreamFn = int32_t (*)(void *);
        using DeleteBuilderFn = int32_t (*)(void *);
        using ReadFn = int32_t (*)(void *, void *, int32_t, int64_t);

        SetDataCallbackFn gOriginalSetDataCallback = nullptr;
        OpenStreamFn gOriginalOpenStream = nullptr;
        CloseStreamFn gOriginalCloseStream = nullptr;
        DeleteBuilderFn gOriginalDeleteBuilder = nullptr;
        ReadFn gOriginalRead = nullptr;
        AAudioCallbackRegistry gCallbackRegistry;

        enum : int32_t
        {
            kAAudioFormatI16 = 1,
            kAAudioFormatFloat = 2,
            kAAudioDirectionInput = 1,
            kAAudioResultOk = 0,
            kAAudioCallbackContinue = 0,
        };

        struct StreamConfig
        {
            uint32_t sample_rate{0};
            uint32_t channels{0};
            audio::PcmFormat format{audio::PcmFormat::kSigned16};
            bool valid{false};
        };

        struct StreamFns
        {
            using GetIntFn = int32_t (*)(void *);
            GetIntFn get_sample_rate{nullptr};
            GetIntFn get_channel_count{nullptr};
            GetIntFn get_format{nullptr};
            GetIntFn get_direction{nullptr};

            bool complete() const
            {
                return get_sample_rate && get_channel_count && get_format && get_direction;
            }
        };

        StreamFns gStreamFns;

        struct TimingStart
        {
            timespec wall{};
            timespec cpu{};
        };

        TimingStart StartTiming()
        {
            TimingStart start;
            clock_gettime(CLOCK_MONOTONIC, &start.wall);
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &start.cpu);
            return start;
        }

        void RecordHookCall(state::SharedState &shared_state,
                            const TimingStart &start,
                            uint32_t flags)
        {
            timespec wall_end{};
            timespec cpu_end{};
            clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
            clock_gettime(CLOCK_MONOTONIC, &wall_end);

            const int64_t wall_ns =
                (static_cast<int64_t>(wall_end.tv_sec) - start.wall.tv_sec) * 1000000000ll +
                (static_cast<int64_t>(wall_end.tv_nsec) - start.wall.tv_nsec);
            const int64_t cpu_ns =
                (static_cast<int64_t>(cpu_end.tv_sec) - start.cpu.tv_sec) * 1000000000ll +
                (static_cast<int64_t>(cpu_end.tv_nsec) - start.cpu.tv_nsec);
            const uint64_t timestamp_ns =
                static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                static_cast<uint64_t>(wall_end.tv_nsec);

            shared_state.telemetry().recordCallback(
                timestamp_ns,
                static_cast<uint32_t>(std::max<int64_t>(wall_ns, 0) / 1000),
                static_cast<uint32_t>(std::max<int64_t>(cpu_ns, 0) / 1000),
                flags,
                0);
            shared_state.setStatus(state::InternalStatus::kHooked);
        }

        bool ProcessingAllowed()
        {
            return state::SharedState::instance().audioProcessingAllowed();
        }

        StreamConfig QueryStreamConfig(void *stream)
        {
            StreamConfig config;
            if (!stream || !gStreamFns.complete())
            {
                return config;
            }

            const int32_t sample_rate = gStreamFns.get_sample_rate(stream);
            const int32_t channels = gStreamFns.get_channel_count(stream);
            const int32_t format = gStreamFns.get_format(stream);
            const int32_t direction = gStreamFns.get_direction(stream);
            if (sample_rate < 8000 || sample_rate > 384000 ||
                channels < 1 || channels > 8 || direction != kAAudioDirectionInput)
            {
                return config;
            }
            if (format == kAAudioFormatI16)
            {
                config.format = audio::PcmFormat::kSigned16;
            }
            else if (format == kAAudioFormatFloat)
            {
                config.format = audio::PcmFormat::kFloat32;
            }
            else
            {
                return config;
            }
            config.sample_rate = static_cast<uint32_t>(sample_rate);
            config.channels = static_cast<uint32_t>(channels);
            config.valid = true;
            return config;
        }

        bool ProcessPcmBuffer(const StreamConfig &config,
                              void *buffer,
                              uint32_t frames)
        {
            if (!config.valid || !buffer || frames == 0)
            {
                return false;
            }
            const uint64_t sample_count =
                static_cast<uint64_t>(frames) * config.channels;
            if (sample_count == 0 || sample_count > kMaxRealtimeCaptureSamples)
            {
                return false;
            }
            const size_t bytes_per_sample =
                config.format == audio::PcmFormat::kFloat32 ? sizeof(float) : sizeof(int16_t);
            const size_t byte_count = static_cast<size_t>(sample_count) * bytes_per_sample;
            return RouteCaptureBufferInPlace(buffer,
                                             byte_count,
                                             config.format,
                                             config.sample_rate,
                                             config.channels,
                                             echidna_process_block);
        }

        int32_t ForwardRead(void *stream,
                            void *buffer,
                            int32_t frames,
                            int64_t timeout_ns)
        {
            const int32_t read_frames =
                gOriginalRead ? gOriginalRead(stream, buffer, frames, timeout_ns) : -1;
            if (read_frames <= 0 || !buffer || !ProcessingAllowed())
            {
                return read_frames;
            }
            const StreamConfig config = QueryStreamConfig(stream);
            if (!config.valid)
            {
                return read_frames;
            }
            auto &shared_state = state::SharedState::instance();
            const TimingStart timing = StartTiming();
            const bool processed =
                ProcessPcmBuffer(config, buffer, static_cast<uint32_t>(read_frames));
            RecordHookCall(shared_state,
                           timing,
                           utils::kTelemetryFlagCallback |
                               (processed ? utils::kTelemetryFlagDsp
                                          : utils::kTelemetryFlagError));
            return read_frames;
        }

        class InvocationGuard
        {
        public:
            explicit InvocationGuard(void *proxy_context) : proxy_context_(proxy_context) {}
            ~InvocationGuard() { gCallbackRegistry.endInvocation(proxy_context_); }

            InvocationGuard(const InvocationGuard &) = delete;
            InvocationGuard &operator=(const InvocationGuard &) = delete;

        private:
            void *proxy_context_;
        };

        int ForwardCallback(void *stream,
                            void *proxy_context,
                            void *audio_data,
                            int32_t frames)
        {
            AAudioCallbackTarget target;
            if (!gCallbackRegistry.beginInvocation(proxy_context, stream, &target))
            {
                return kAAudioCallbackContinue;
            }
            InvocationGuard invocation(proxy_context);

            bool attempted = false;
            bool processed = false;
            TimingStart timing{};
            if (audio_data && frames > 0 && ProcessingAllowed())
            {
                const StreamConfig config = QueryStreamConfig(stream);
                if (config.valid)
                {
                    attempted = true;
                    timing = StartTiming();
                    processed =
                        ProcessPcmBuffer(config, audio_data, static_cast<uint32_t>(frames));
                }
            }

            const int result = target.callback(
                stream, target.user_data, audio_data, frames);
            if (attempted)
            {
                auto &shared_state = state::SharedState::instance();
                RecordHookCall(shared_state,
                               timing,
                               utils::kTelemetryFlagCallback |
                                   (processed ? utils::kTelemetryFlagDsp
                                              : utils::kTelemetryFlagError));
            }
            return result;
        }

        void ForwardSetDataCallback(void *builder, Callback callback, void *user_data)
        {
            if (!gOriginalSetDataCallback)
            {
                return;
            }
            if (!callback)
            {
                gCallbackRegistry.retireBuilder(builder);
                gOriginalSetDataCallback(builder, nullptr, user_data);
                return;
            }
            void *proxy_context =
                gCallbackRegistry.registerCallback(builder, callback, user_data);
            if (!proxy_context)
            {
                // Bounded registry exhaustion must not break application audio.
                gOriginalSetDataCallback(builder, callback, user_data);
                return;
            }
            gOriginalSetDataCallback(builder, &ForwardCallback, proxy_context);
        }

        int32_t ForwardOpenStream(void *builder, void **stream_out)
        {
            const int32_t result =
                gOriginalOpenStream ? gOriginalOpenStream(builder, stream_out) : -1;
            if (result == kAAudioResultOk && stream_out && *stream_out)
            {
                const StreamConfig config = QueryStreamConfig(*stream_out);
                if (config.valid)
                {
                    (void)echidna_prepare_stream(config.sample_rate, config.channels);
                }
                (void)gCallbackRegistry.attachOpenedStream(builder, *stream_out);
            }
            return result;
        }

        int32_t ForwardCloseStream(void *stream)
        {
            const int32_t result = gOriginalCloseStream ? gOriginalCloseStream(stream) : -1;
            if (result == kAAudioResultOk)
            {
                gCallbackRegistry.closeStream(stream);
            }
            return result;
        }

        int32_t ForwardDeleteBuilder(void *builder)
        {
            const int32_t result =
                gOriginalDeleteBuilder ? gOriginalDeleteBuilder(builder) : -1;
            if (result == kAAudioResultOk)
            {
                gCallbackRegistry.retireBuilder(builder);
            }
            return result;
        }
    } // namespace

    AAudioHookManager::AAudioHookManager(utils::PltResolver &resolver) : resolver_(resolver) {}

    bool AAudioHookManager::install()
    {
        last_info_ = {};
        constexpr const char *kLibrary = "libaaudio.so";

        gStreamFns.get_sample_rate = reinterpret_cast<StreamFns::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getSampleRate"));
        gStreamFns.get_channel_count = reinterpret_cast<StreamFns::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getChannelCount"));
        gStreamFns.get_format = reinterpret_cast<StreamFns::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getFormat"));
        gStreamFns.get_direction = reinterpret_cast<StreamFns::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getDirection"));
        if (!gStreamFns.complete())
        {
            last_info_.reason = "stream_metadata_unavailable";
            return false;
        }

        bool read_installed = false;
        bool callback_installed = false;
        bool hook_failed = false;

        if (void *read = resolver_.findSymbol(kLibrary, "AAudioStream_read"))
        {
            read_installed = hook_read_.install(
                read,
                reinterpret_cast<void *>(&ForwardRead),
                reinterpret_cast<void **>(&gOriginalRead));
            hook_failed = hook_failed || !read_installed;
        }

        void *open = resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_openStream");
        void *close = resolver_.findSymbol(kLibrary, "AAudioStream_close");
        void *delete_builder =
            resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_delete");
        void *set_callback =
            resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_setDataCallback");
        if (open && close && delete_builder && set_callback)
        {
            const bool open_installed = hook_open_stream_.install(
                open,
                reinterpret_cast<void *>(&ForwardOpenStream),
                reinterpret_cast<void **>(&gOriginalOpenStream));
            const bool close_installed = hook_close_stream_.install(
                close,
                reinterpret_cast<void *>(&ForwardCloseStream),
                reinterpret_cast<void **>(&gOriginalCloseStream));
            const bool delete_installed = hook_delete_builder_.install(
                delete_builder,
                reinterpret_cast<void *>(&ForwardDeleteBuilder),
                reinterpret_cast<void **>(&gOriginalDeleteBuilder));
            if (open_installed && close_installed && delete_installed)
            {
                callback_installed = hook_set_data_callback_.install(
                    set_callback,
                    reinterpret_cast<void *>(&ForwardSetDataCallback),
                    reinterpret_cast<void **>(&gOriginalSetDataCallback));
            }
            hook_failed = hook_failed || !open_installed || !close_installed ||
                          !delete_installed || !callback_installed;
        }

        last_info_.success = read_installed || callback_installed;
        if (last_info_.success)
        {
            last_info_.library = kLibrary;
            last_info_.symbol = callback_installed
                                    ? "AAudioStreamBuilder_setDataCallback"
                                    : "AAudioStream_read";
            __android_log_print(ANDROID_LOG_INFO,
                                "echidna",
                                "AAudio input transform hooks installed (read=%d callback=%d)",
                                read_installed,
                                callback_installed);
            return true;
        }

        last_info_.reason = hook_failed ? "hook_failed" : "transform_symbol_not_found";
        __android_log_print(ANDROID_LOG_WARN,
                            "echidna",
                            "AAudio transform hook not installed: %s",
                            last_info_.reason.c_str());
        return false;
    }

} // namespace echidna::hooks
