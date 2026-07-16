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

#include <cstddef>
#include <cstdint>

#include "echidna/api.h"
#include "hooks/aaudio_callback_registry.h"
#include "hooks/aaudio_hook_readiness.h"
#include "hooks/aaudio_stream_contract.h"
#include "hooks/aaudio_stream_registry.h"
#include "runtime/profile_sync_protocol.h"
#include "state/shared_state.h"
#include "utils/telemetry_accumulator.h"

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
        AAudioHookReadiness gHookReadiness;
        AAudioStreamRegistry gStreamRegistry;
        const AAudioDspApi gDspApi{
            echidna_stream_create,
            echidna_stream_process,
            echidna_stream_update,
            echidna_stream_destroy,
        };

        enum : int32_t
        {
            kAAudioResultOk = 0,
            kAAudioCallbackContinue = 0,
        };

        AAudioStreamQueryApi gStreamQueries;

        echidna_stream_config_t QueryStreamConfig(void *stream)
        {
            echidna_stream_config_t config{};
            (void)QueryAAudioStreamConfig(stream, gStreamQueries, &config);
            return config;
        }

        void RecordUnroutedBlock(uint32_t frames,
                                 utils::TelemetryBlockOutcome outcome)
        {
            state::SharedState::instance().telemetry().recordBlock(
                utils::TelemetryRoute::kAAudio, frames, outcome);
        }

        void ProcessPcmBuffer(void *stream,
                              AAudioProcessOwner owner,
                              void *buffer,
                              uint32_t frames)
        {
            utils::ScopedTelemetryRoute telemetry_route(utils::TelemetryRoute::kAAudio);
            const AAudioProcessResult result =
                gStreamRegistry.process(stream, owner, buffer, frames);
            if (result == AAudioProcessResult::kBypassed)
            {
                RecordUnroutedBlock(frames, utils::TelemetryBlockOutcome::kBypassed);
            }
            else if (result == AAudioProcessResult::kUnavailable)
            {
                RecordUnroutedBlock(frames, utils::TelemetryBlockOutcome::kFailure);
            }
        }

        int32_t ForwardRead(void *stream,
                            void *buffer,
                            int32_t frames,
                            int64_t timeout_ns)
        {
            const int32_t read_frames =
                gOriginalRead ? gOriginalRead(stream, buffer, frames, timeout_ns) : -1;
            if (read_frames <= 0 || !buffer || !gHookReadiness.readReady())
            {
                return read_frames;
            }
            ProcessPcmBuffer(stream,
                             AAudioProcessOwner::kRead,
                             buffer,
                             static_cast<uint32_t>(read_frames));
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

            if (gHookReadiness.callbackReady() && audio_data && frames > 0)
            {
                // The dispatcher transforms into per-stream scratch and invokes
                // the application callback itself, never writing the platform
                // input buffer. Fail-open paths hand the untouched input through.
                utils::ScopedTelemetryRoute telemetry_route(utils::TelemetryRoute::kAAudio);
                AAudioProcessResult result = AAudioProcessResult::kUnavailable;
                const int callback_result =
                    gStreamRegistry.dispatchCallback(stream,
                                                     audio_data,
                                                     frames,
                                                     target.callback,
                                                     target.user_data,
                                                     &result);
                if (result == AAudioProcessResult::kBypassed)
                {
                    RecordUnroutedBlock(static_cast<uint32_t>(frames),
                                        utils::TelemetryBlockOutcome::kBypassed);
                }
                else if (result == AAudioProcessResult::kUnavailable)
                {
                    RecordUnroutedBlock(static_cast<uint32_t>(frames),
                                        utils::TelemetryBlockOutcome::kFailure);
                }
                return callback_result;
            }

            return target.callback(stream, target.user_data, audio_data, frames);
        }

        void ForwardSetDataCallback(void *builder, Callback callback, void *user_data)
        {
            if (!gOriginalSetDataCallback)
            {
                return;
            }
            if (!gHookReadiness.callbackReady())
            {
                gOriginalSetDataCallback(builder, callback, user_data);
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
                const uint32_t ready_routes = gHookReadiness.snapshot();
                if (ready_routes == 0)
                {
                    return result;
                }
                const bool callback_ready =
                    (ready_routes & AAudioHookReadiness::kCallbackRoute) != 0;
                const bool read_ready =
                    (ready_routes & AAudioHookReadiness::kReadRoute) != 0;
                const bool callback_owned = callback_ready &&
                                            gCallbackRegistry.attachOpenedStream(builder, *stream_out);
                const echidna_stream_config_t config = QueryStreamConfig(*stream_out);
                if (config.struct_size == sizeof(config) &&
                    (callback_owned || read_ready))
                {
                    const bool ready = gStreamRegistry.open(
                        *stream_out,
                        config,
                        callback_owned ? AAudioProcessOwner::kCallback
                                       : AAudioProcessOwner::kRead,
                        gDspApi);
                    if (!ready)
                    {
                        __android_log_print(ANDROID_LOG_WARN,
                                            "echidna",
                                            "AAudio stream opened without DSP handle");
                    }
                }
            }
            return result;
        }

        int32_t ForwardCloseStream(void *stream)
        {
            // Stop proxy admission before quiescing and destroying the DSP
            // handle. A failed platform close remains permanently bypassed.
            if (gHookReadiness.lifecycleReady())
            {
                gCallbackRegistry.closeStream(stream);
                gStreamRegistry.close(stream);
            }
            return gOriginalCloseStream ? gOriginalCloseStream(stream) : -1;
        }

        int32_t ForwardDeleteBuilder(void *builder)
        {
            const int32_t result =
                gOriginalDeleteBuilder ? gOriginalDeleteBuilder(builder) : -1;
            if (result == kAAudioResultOk && gHookReadiness.callbackReady())
            {
                gCallbackRegistry.retireBuilder(builder);
            }
            return result;
        }
    } // namespace

    AAudioHookManager::AAudioHookManager(utils::PltResolver &resolver) : resolver_(resolver) {}

    bool PublishAAudioProfile(const runtime::DecodedProfileSnapshot &snapshot)
    {
        const bool published = gStreamRegistry.publishProfile(snapshot.generation,
                                                              snapshot.nativeProcessAdmitted(),
                                                              snapshot.preset_json,
                                                              gDspApi);
        if (!published)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                "echidna",
                                "AAudio profile publication failed generation=%llu",
                                static_cast<unsigned long long>(snapshot.generation));
        }
        return published;
    }

    bool AAudioHookManager::install()
    {
        last_info_ = {};
        gHookReadiness.clear();
        constexpr const char *kLibrary = "libaaudio.so";

        gStreamQueries.get_sample_rate = reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getSampleRate"));
        gStreamQueries.get_channel_count = reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getChannelCount"));
        gStreamQueries.get_format = reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getFormat"));
        gStreamQueries.get_direction = reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(
            resolver_.findSymbol(kLibrary, "AAudioStream_getDirection"));
        gStreamQueries.get_buffer_capacity_frames =
            reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(resolver_.findSymbol(
                kLibrary, "AAudioStream_getBufferCapacityInFrames"));
        gStreamQueries.get_frames_per_burst =
            reinterpret_cast<AAudioStreamQueryApi::GetIntFn>(resolver_.findSymbol(
                kLibrary, "AAudioStream_getFramesPerBurst"));
        if (!gStreamQueries.complete())
        {
            last_info_.reason = "stream_metadata_unavailable";
            return false;
        }

        void *open = resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_openStream");
        void *close = resolver_.findSymbol(kLibrary, "AAudioStream_close");
        void *delete_builder =
            resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_delete");
        void *set_callback =
            resolver_.findSymbol(kLibrary, "AAudioStreamBuilder_setDataCallback");
        void *read = resolver_.findSymbol(kLibrary, "AAudioStream_read");

        const AAudioHookAvailability available{
            .open = open != nullptr,
            .close = close != nullptr,
            .delete_builder = delete_builder != nullptr,
            .set_data_callback = set_callback != nullptr,
            .read = read != nullptr,
        };
        const AAudioHookInstallation installed = InstallAAudioHookSet(
            available,
            [this, open, close, delete_builder, set_callback, read](AAudioHookRole role)
            {
                switch (role)
                {
                case AAudioHookRole::kClose:
                    return hook_close_stream_.install(
                        close,
                        reinterpret_cast<void *>(&ForwardCloseStream),
                        reinterpret_cast<void **>(&gOriginalCloseStream));
                case AAudioHookRole::kDeleteBuilder:
                    return hook_delete_builder_.install(
                        delete_builder,
                        reinterpret_cast<void *>(&ForwardDeleteBuilder),
                        reinterpret_cast<void **>(&gOriginalDeleteBuilder));
                case AAudioHookRole::kSetDataCallback:
                    return hook_set_data_callback_.install(
                        set_callback,
                        reinterpret_cast<void *>(&ForwardSetDataCallback),
                        reinterpret_cast<void **>(&gOriginalSetDataCallback));
                case AAudioHookRole::kRead:
                    return hook_read_.install(
                        read,
                        reinterpret_cast<void *>(&ForwardRead),
                        reinterpret_cast<void **>(&gOriginalRead));
                case AAudioHookRole::kOpen:
                    return hook_open_stream_.install(
                        open,
                        reinterpret_cast<void *>(&ForwardOpenStream),
                        reinterpret_cast<void **>(&gOriginalOpenStream));
                }
                return false;
            });
        gHookReadiness.publish(installed);

        last_info_.success = installed.anyRouteComplete();
        if (last_info_.success)
        {
            last_info_.library = kLibrary;
            last_info_.symbol = installed.callbackRouteComplete()
                                    ? "AAudioStreamBuilder_setDataCallback"
                                    : "AAudioStream_read";
            __android_log_print(ANDROID_LOG_INFO,
                                "echidna",
                                "AAudio input transform hooks installed (read=%d callback=%d)",
                                installed.readRouteComplete(),
                                installed.callbackRouteComplete());
            return true;
        }

        const bool transform_symbols_present = available.open && available.close &&
                                               (available.read ||
                                                (available.delete_builder && available.set_data_callback));
        last_info_.reason = transform_symbols_present
                                ? "hook_failed"
                                : "transform_symbol_not_found";
        __android_log_print(ANDROID_LOG_WARN,
                            "echidna",
                            "AAudio transform hook not installed: %s",
                            last_info_.reason.c_str());
        return false;
    }

} // namespace echidna::hooks
