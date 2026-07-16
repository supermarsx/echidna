#include "hooks/opensl_hook_manager.h"

/**
 * @file opensl_hook_manager.cpp
 * @brief Wrap OpenSL recorder buffer-queue vtables from slCreateEngine.
 */

#if defined(__ANDROID__) || defined(ECHIDNA_OPENSL_TESTING)
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#endif
#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <thread>

#include "echidna/api.h"
#include "hooks/opensl_buffer_fifo.h"
#include "hooks/opensl_pcm_contract.h"
#include "hooks/opensl_stream_registry.h"
#include "runtime/profile_sync_protocol.h"
#include "state/shared_state.h"
#include "utils/telemetry_accumulator.h"

namespace echidna::hooks
{
    namespace
    {
        OpenSlStreamRegistry gOpenSlStreams;
#ifdef ECHIDNA_OPENSL_TESTING
        OpenSlDspApi gOpenSlDspApi;
#else
        const OpenSlDspApi gOpenSlDspApi{
            echidna_stream_create,
            echidna_stream_process,
            echidna_stream_update,
            echidna_stream_destroy,
        };
#endif
    } // namespace

#if defined(__ANDROID__) || defined(ECHIDNA_OPENSL_TESTING)
    namespace
    {
        constexpr size_t kMaxObjects = 128;
        constexpr size_t kMaxEngines = 64;
        constexpr size_t kMaxQueues = 128;
        constexpr size_t kMaxCallbackTokens = 512;
        constexpr size_t kMaxQueuedBuffers = OpenSlBufferFifo::kCapacity;

        using CreateEngineFn = SLresult (*)(SLObjectItf *,
                                            SLuint32,
                                            const SLEngineOption *,
                                            SLuint32,
                                            const SLInterfaceID *,
                                            const SLboolean *);
        using QueueCallback = slAndroidSimpleBufferQueueCallback;

        CreateEngineFn gOriginalCreateEngine = nullptr;
        SLInterfaceID gIidEngine = nullptr;
        SLInterfaceID gIidAndroidSimpleBufferQueue = nullptr;
        SLInterfaceID gIidBufferQueue = nullptr;

        enum class ObjectKind : uint8_t
        {
            kEngine,
            kRecorder,
        };

        struct ObjectContext
        {
            std::atomic<bool> claimed{false};
            std::atomic<bool> active{false};
            SLObjectItf self{nullptr};
            const SLObjectItf_ *original{nullptr};
            SLObjectItf_ replacement{};
            ObjectKind kind{ObjectKind::kEngine};
            OpenSlPcmContract contract{};
        };

        struct EngineContext
        {
            std::atomic<bool> claimed{false};
            std::atomic<bool> active{false};
            SLEngineItf self{nullptr};
            SLObjectItf owner{nullptr};
            const SLEngineItf_ *original{nullptr};
            SLEngineItf_ replacement{};
        };

        struct QueueContext
        {
            std::atomic<bool> claimed{false};
            std::atomic<bool> active{false};
            SLAndroidSimpleBufferQueueItf self{nullptr};
            SLObjectItf owner{nullptr};
            const SLAndroidSimpleBufferQueueItf_ *original{nullptr};
            SLAndroidSimpleBufferQueueItf_ replacement{};
            OpenSlPcmContract contract{};
            OpenSlBufferFifo buffers;
        };

        struct CallbackToken
        {
            std::atomic<bool> claimed{false};
            std::atomic<uint32_t> usage{0};
            std::atomic<QueueContext *> queue{nullptr};
            QueueCallback callback{nullptr};
            void *user_data{nullptr};
        };

        std::array<ObjectContext, kMaxObjects> gObjects;
        std::array<EngineContext, kMaxEngines> gEngines;
        std::array<QueueContext, kMaxQueues> gQueues;
        std::array<CallbackToken, kMaxCallbackTokens> gCallbackTokens;

        static_assert(std::atomic<void *>::is_always_lock_free,
                      "OpenSL capture requires lock-free pointer atomics");
        static_assert(std::atomic<uint32_t>::is_always_lock_free,
                      "OpenSL callback admission requires lock-free 32-bit atomics");

        constexpr uint32_t kCallbackActiveMask = 0x80000000U;
        constexpr uint32_t kCallbackInFlightMask = 0x7FFFFFFFU;
        thread_local CallbackToken *gCurrentCallbackToken = nullptr;

        uintptr_t RecorderIdentity(SLObjectItf recorder)
        {
            return reinterpret_cast<uintptr_t>(recorder);
        }

        ObjectContext *FindObject(SLObjectItf self)
        {
            for (auto &context : gObjects)
            {
                if (context.active.load(std::memory_order_acquire) && context.self == self)
                {
                    return &context;
                }
            }
            return nullptr;
        }

        EngineContext *FindEngine(SLEngineItf self)
        {
            for (auto &context : gEngines)
            {
                if (context.active.load(std::memory_order_acquire) && context.self == self)
                {
                    return &context;
                }
            }
            return nullptr;
        }

        QueueContext *FindQueue(SLAndroidSimpleBufferQueueItf self)
        {
            for (auto &context : gQueues)
            {
                if (context.active.load(std::memory_order_acquire) && context.self == self)
                {
                    return &context;
                }
            }
            return nullptr;
        }

        CallbackToken *AcquireCallbackToken(void *opaque)
        {
            if (!opaque)
            {
                return nullptr;
            }
            const uintptr_t address = reinterpret_cast<uintptr_t>(opaque);
            const uintptr_t begin = reinterpret_cast<uintptr_t>(gCallbackTokens.data());
            const uintptr_t end = begin + sizeof(gCallbackTokens);
            if (address < begin || address >= end ||
                ((address - begin) % sizeof(CallbackToken)) != 0)
            {
                return nullptr;
            }
            auto *token = reinterpret_cast<CallbackToken *>(opaque);
            uint32_t usage = token->usage.load(std::memory_order_acquire);
            while ((usage & kCallbackActiveMask) != 0)
            {
                if ((usage & kCallbackInFlightMask) == kCallbackInFlightMask)
                {
                    return nullptr;
                }
                if (token->usage.compare_exchange_weak(usage,
                                                       usage + 1,
                                                       std::memory_order_acq_rel,
                                                       std::memory_order_acquire))
                {
                    return token;
                }
            }
            return nullptr;
        }

        void ReleaseCallbackToken(CallbackToken *token)
        {
            if (token)
            {
                token->usage.fetch_sub(1, std::memory_order_acq_rel);
            }
        }

        class CallbackInvocationGuard
        {
        public:
            explicit CallbackInvocationGuard(CallbackToken *token)
                : token_(token), previous_(gCurrentCallbackToken)
            {
                gCurrentCallbackToken = token;
            }

            ~CallbackInvocationGuard()
            {
                gCurrentCallbackToken = previous_;
                ReleaseCallbackToken(token_);
            }

            CallbackInvocationGuard(const CallbackInvocationGuard &) = delete;
            CallbackInvocationGuard &operator=(const CallbackInvocationGuard &) = delete;

        private:
            CallbackToken *token_;
            CallbackToken *previous_;
        };

        CallbackToken *CreateCallbackToken(QueueContext *queue,
                                           QueueCallback callback,
                                           void *user_data)
        {
            if (!queue || !callback)
            {
                return nullptr;
            }
            for (auto &token : gCallbackTokens)
            {
                bool expected = false;
                if (!token.claimed.compare_exchange_strong(expected,
                                                           true,
                                                           std::memory_order_acq_rel,
                                                           std::memory_order_relaxed))
                {
                    continue;
                }
                token.queue.store(queue, std::memory_order_relaxed);
                token.callback = callback;
                token.user_data = user_data;
                token.usage.store(kCallbackActiveMask, std::memory_order_release);
                return &token;
            }
            return nullptr;
        }

        void RetireCallbackTokens(const QueueContext *queue,
                                  const CallbackToken *keep = nullptr,
                                  bool wait = false)
        {
            if (!queue)
            {
                return;
            }
            for (auto &token : gCallbackTokens)
            {
                if (&token != keep &&
                    token.queue.load(std::memory_order_acquire) == queue)
                {
                    token.usage.fetch_and(~kCallbackActiveMask,
                                          std::memory_order_acq_rel);
                }
            }
            if (!wait)
            {
                return;
            }
            for (auto &token : gCallbackTokens)
            {
                if (&token == keep ||
                    token.queue.load(std::memory_order_acquire) != queue)
                {
                    continue;
                }
                const uint32_t self_allowance =
                    gCurrentCallbackToken == &token ? 1U : 0U;
                while ((token.usage.load(std::memory_order_acquire) &
                        kCallbackInFlightMask) > self_allowance)
                {
                    std::this_thread::yield();
                }
            }
        }

        size_t BytesPerSample(audio::PcmFormat format)
        {
            switch (format)
            {
            case audio::PcmFormat::kUnsigned8:
                return 1;
            case audio::PcmFormat::kSigned16:
                return 2;
            case audio::PcmFormat::kSigned24Packed:
                return 3;
            case audio::PcmFormat::kSigned32:
            case audio::PcmFormat::kFloat32:
                return 4;
            }
            return 0;
        }

        std::optional<echidna_stream_config_t> StreamConfig(
            const OpenSlPcmContract &contract)
        {
            uint32_t format = 0;
            if (contract.format == audio::PcmFormat::kSigned16)
            {
                format = ECHIDNA_PCM_FORMAT_SIGNED_16;
            }
            else if (contract.format == audio::PcmFormat::kFloat32)
            {
                format = ECHIDNA_PCM_FORMAT_FLOAT_32;
            }
            else
            {
                return std::nullopt;
            }
            if (contract.channels == 0 || contract.channels > 8)
            {
                return std::nullopt;
            }
            echidna_stream_config_t config{};
            config.struct_size = sizeof(config);
            config.sample_rate = contract.sample_rate;
            config.channel_count = contract.channels;
            config.max_frames = 32768U / contract.channels;
            config.format = format;
            return config;
        }

        void RecordUnroutedBlock(uint32_t frames,
                                 utils::TelemetryBlockOutcome outcome)
        {
#ifndef ECHIDNA_OPENSL_TESTING
            state::SharedState::instance().telemetry().recordBlock(
                utils::TelemetryRoute::kOpenSl, frames, outcome);
#else
            (void)frames;
            (void)outcome;
#endif
        }

        bool ProcessBuffer(const QueueContext &queue,
                           const OpenSlQueuedBuffer &buffer)
        {
            const size_t bytes_per_sample = BytesPerSample(queue.contract.format);
            const size_t frame_bytes = bytes_per_sample * queue.contract.channels;
            if (!buffer.data || buffer.bytes == 0 || frame_bytes == 0 ||
                buffer.bytes % frame_bytes != 0)
            {
                RecordUnroutedBlock(0, utils::TelemetryBlockOutcome::kFailure);
                return false;
            }
            const size_t frames = buffer.bytes / frame_bytes;
            if (frames == 0 || frames > std::numeric_limits<uint32_t>::max())
            {
                RecordUnroutedBlock(0, utils::TelemetryBlockOutcome::kFailure);
                return false;
            }
#ifndef ECHIDNA_OPENSL_TESTING
            utils::ScopedTelemetryRoute telemetry_route(utils::TelemetryRoute::kOpenSl);
#endif
            const uint32_t frame_count = static_cast<uint32_t>(frames);
            const OpenSlProcessResult result =
                gOpenSlStreams.process(RecorderIdentity(queue.owner),
                                       buffer.data,
                                       frame_count);
            if (result == OpenSlProcessResult::kBypassed)
            {
                RecordUnroutedBlock(frame_count,
                                    utils::TelemetryBlockOutcome::kBypassed);
            }
            else if (result == OpenSlProcessResult::kUnavailable)
            {
                RecordUnroutedBlock(frame_count,
                                    utils::TelemetryBlockOutcome::kFailure);
            }
            return result == OpenSlProcessResult::kProcessed;
        }

        void QueueCallbackProxy(SLAndroidSimpleBufferQueueItf caller, void *opaque)
        {
            CallbackToken *token = AcquireCallbackToken(opaque);
            if (!token)
            {
                return;
            }
            if (!token->callback)
            {
                ReleaseCallbackToken(token);
                return;
            }
            CallbackInvocationGuard invocation(token);
            QueueContext *queue = token->queue.load(std::memory_order_acquire);
            if (queue && queue->active.load(std::memory_order_acquire))
            {
                const auto buffer = queue->buffers.pop();
                if (buffer)
                {
                    (void)ProcessBuffer(*queue, *buffer);
                }
            }
            token->callback(caller, token->user_data);
        }

        SLresult ForwardQueueEnqueue(SLAndroidSimpleBufferQueueItf self,
                                     const void *buffer,
                                     SLuint32 bytes)
        {
            QueueContext *queue = FindQueue(self);
            if (!queue || !queue->original || !queue->original->Enqueue)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            const OpenSlBufferReservation reservation =
                queue->buffers.reserve(buffer, bytes);
            const SLresult result = queue->original->Enqueue(self, buffer, bytes);
            if (result != SL_RESULT_SUCCESS)
            {
                queue->buffers.rollback(reservation);
            }
            return result;
        }

        SLresult ForwardQueueClear(SLAndroidSimpleBufferQueueItf self)
        {
            QueueContext *queue = FindQueue(self);
            if (!queue || !queue->original || !queue->original->Clear)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            const SLresult result = queue->original->Clear(self);
            if (result == SL_RESULT_SUCCESS)
            {
                queue->buffers.clear();
            }
            return result;
        }

        SLresult ForwardQueueRegisterCallback(SLAndroidSimpleBufferQueueItf self,
                                              QueueCallback callback,
                                              void *user_data)
        {
            QueueContext *queue = FindQueue(self);
            if (!queue || !queue->original || !queue->original->RegisterCallback)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            if (!callback)
            {
                const SLresult result =
                    queue->original->RegisterCallback(self, nullptr, user_data);
                if (result == SL_RESULT_SUCCESS)
                {
                    RetireCallbackTokens(queue, nullptr, true);
                }
                return result;
            }
            CallbackToken *token = CreateCallbackToken(queue, callback, user_data);
            if (!token)
            {
                // Exhaustion preserves application behavior and disables DSP
                // only for this subsequent callback registration.
                const SLresult result =
                    queue->original->RegisterCallback(self, callback, user_data);
                if (result == SL_RESULT_SUCCESS)
                {
                    queue->buffers.disable();
                    RetireCallbackTokens(queue, nullptr, true);
                }
                return result;
            }
            const SLresult result = queue->original->RegisterCallback(
                self, &QueueCallbackProxy, token);
            if (result != SL_RESULT_SUCCESS)
            {
                token->usage.fetch_and(~kCallbackActiveMask,
                                       std::memory_order_acq_rel);
            }
            else
            {
                RetireCallbackTokens(queue, token, true);
            }
            return result;
        }

        bool WrapQueue(SLAndroidSimpleBufferQueueItf queue,
                       SLObjectItf owner,
                       const OpenSlPcmContract &contract)
        {
            if (!queue || !*queue)
            {
                return false;
            }
            if (FindQueue(queue))
            {
                return true;
            }
            for (auto &context : gQueues)
            {
                bool expected = false;
                if (!context.claimed.compare_exchange_strong(expected,
                                                             true,
                                                             std::memory_order_acq_rel,
                                                             std::memory_order_relaxed))
                {
                    continue;
                }
                context.self = queue;
                context.owner = owner;
                context.original = *queue;
                context.replacement = **queue;
                context.contract = contract;
                context.replacement.Enqueue = &ForwardQueueEnqueue;
                context.replacement.Clear = &ForwardQueueClear;
                context.replacement.RegisterCallback = &ForwardQueueRegisterCallback;
                context.active.store(true, std::memory_order_release);
                *const_cast<const SLAndroidSimpleBufferQueueItf_ **>(queue) =
                    &context.replacement;
                return true;
            }
            return false;
        }

        std::optional<OpenSlPcmContract> ParseRecorderSink(const SLDataSink *sink)
        {
            if (!sink || !sink->pLocator || !sink->pFormat)
            {
                return std::nullopt;
            }
            const auto locator_type = *static_cast<const SLuint32 *>(sink->pLocator);
            uint32_t buffer_count = 0;
            if (locator_type == SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE)
            {
                buffer_count = static_cast<const SLDataLocator_AndroidSimpleBufferQueue *>(
                                   sink->pLocator)
                                   ->numBuffers;
            }
            else if (locator_type == SL_DATALOCATOR_BUFFERQUEUE)
            {
                buffer_count =
                    static_cast<const SLDataLocator_BufferQueue *>(sink->pLocator)->numBuffers;
            }
            else
            {
                return std::nullopt;
            }
            if (buffer_count == 0 || buffer_count > kMaxQueuedBuffers)
            {
                return std::nullopt;
            }

            const auto format_type = *static_cast<const SLuint32 *>(sink->pFormat);
            OpenSlPcmDescriptor descriptor;
            descriptor.format_type = format_type;
            if (format_type == SL_DATAFORMAT_PCM)
            {
                const auto *format = static_cast<const SLDataFormat_PCM *>(sink->pFormat);
                descriptor.channels = format->numChannels;
                descriptor.sample_rate_millihz = format->samplesPerSec;
                descriptor.bits_per_sample = format->bitsPerSample;
                descriptor.container_bits = format->containerSize;
                descriptor.channel_mask = format->channelMask;
                descriptor.byte_order = format->endianness;
            }
            else if (format_type == SL_ANDROID_DATAFORMAT_PCM_EX)
            {
                const auto *format =
                    static_cast<const SLAndroidDataFormat_PCM_EX *>(sink->pFormat);
                descriptor.channels = format->numChannels;
                descriptor.sample_rate_millihz = format->sampleRate;
                descriptor.bits_per_sample = format->bitsPerSample;
                descriptor.container_bits = format->containerSize;
                descriptor.channel_mask = format->channelMask;
                descriptor.byte_order = format->endianness;
                descriptor.representation = format->representation;
            }
            return ParseOpenSlPcmContract(descriptor);
        }

        SLresult ForwardCreateAudioRecorder(SLEngineItf self,
                                            SLObjectItf *recorder,
                                            SLDataSource *source,
                                            SLDataSink *sink,
                                            SLuint32 interface_count,
                                            const SLInterfaceID *interface_ids,
                                            const SLboolean *required);
        SLresult ForwardObjectGetInterface(SLObjectItf self,
                                           const SLInterfaceID iid,
                                           void *out_interface);
        void ForwardObjectDestroy(SLObjectItf self);

        bool WrapEngine(SLEngineItf engine, SLObjectItf owner)
        {
            if (!engine || !*engine)
            {
                return false;
            }
            if (FindEngine(engine))
            {
                return true;
            }
            for (auto &context : gEngines)
            {
                bool expected = false;
                if (!context.claimed.compare_exchange_strong(expected,
                                                             true,
                                                             std::memory_order_acq_rel,
                                                             std::memory_order_relaxed))
                {
                    continue;
                }
                context.self = engine;
                context.owner = owner;
                context.original = *engine;
                context.replacement = **engine;
                context.replacement.CreateAudioRecorder = &ForwardCreateAudioRecorder;
                context.active.store(true, std::memory_order_release);
                *const_cast<const SLEngineItf_ **>(engine) = &context.replacement;
                return true;
            }
            return false;
        }

        bool WrapObject(SLObjectItf object,
                        ObjectKind kind,
                        const OpenSlPcmContract &contract = {})
        {
            if (!object || !*object)
            {
                return false;
            }
            if (FindObject(object))
            {
                return true;
            }
            for (auto &context : gObjects)
            {
                bool expected = false;
                if (!context.claimed.compare_exchange_strong(expected,
                                                             true,
                                                             std::memory_order_acq_rel,
                                                             std::memory_order_relaxed))
                {
                    continue;
                }
                context.self = object;
                context.original = *object;
                context.replacement = **object;
                context.kind = kind;
                context.contract = contract;
                context.replacement.GetInterface = &ForwardObjectGetInterface;
                context.replacement.Destroy = &ForwardObjectDestroy;
                context.active.store(true, std::memory_order_release);
                *const_cast<const SLObjectItf_ **>(object) = &context.replacement;
                return true;
            }
            return false;
        }

        SLresult ForwardCreateAudioRecorder(SLEngineItf self,
                                            SLObjectItf *recorder,
                                            SLDataSource *source,
                                            SLDataSink *sink,
                                            SLuint32 interface_count,
                                            const SLInterfaceID *interface_ids,
                                            const SLboolean *required)
        {
            EngineContext *engine = FindEngine(self);
            if (!engine || !engine->original || !engine->original->CreateAudioRecorder)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            const auto contract = ParseRecorderSink(sink);
            const auto config = contract ? StreamConfig(*contract) : std::nullopt;
            const SLresult result = engine->original->CreateAudioRecorder(self,
                                                                          recorder,
                                                                          source,
                                                                          sink,
                                                                          interface_count,
                                                                          interface_ids,
                                                                          required);
            if (result == SL_RESULT_SUCCESS && recorder && *recorder &&
                contract && config)
            {
                const bool ready =
                    gOpenSlStreams.open(RecorderIdentity(*recorder),
                                        *config,
                                        gOpenSlDspApi);
                if (ready)
                {
                    if (!WrapObject(*recorder, ObjectKind::kRecorder, *contract))
                    {
                        gOpenSlStreams.close(RecorderIdentity(*recorder));
                    }
                }
            }
            return result;
        }

        SLresult ForwardObjectGetInterface(SLObjectItf self,
                                           const SLInterfaceID iid,
                                           void *out_interface)
        {
            ObjectContext *object = FindObject(self);
            if (!object || !object->original || !object->original->GetInterface)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            const SLresult result = object->original->GetInterface(self, iid, out_interface);
            if (result != SL_RESULT_SUCCESS || !out_interface)
            {
                return result;
            }
            if (object->kind == ObjectKind::kEngine && iid == gIidEngine)
            {
                auto engine = *static_cast<SLEngineItf *>(out_interface);
                (void)WrapEngine(engine, self);
            }
            else if (object->kind == ObjectKind::kRecorder &&
                     (iid == gIidAndroidSimpleBufferQueue || iid == gIidBufferQueue))
            {
                auto queue =
                    *static_cast<SLAndroidSimpleBufferQueueItf *>(out_interface);
                (void)WrapQueue(queue, self, object->contract);
            }
            return result;
        }

        void ForwardObjectDestroy(SLObjectItf self)
        {
            ObjectContext *object = FindObject(self);
            if (!object || !object->original || !object->original->Destroy)
            {
                return;
            }
            const SLObjectItf_ *original = object->original;
            for (auto &queue : gQueues)
            {
                if (queue.active.load(std::memory_order_acquire) && queue.owner == self)
                {
                    queue.active.store(false, std::memory_order_release);
                    queue.buffers.disable();
                    RetireCallbackTokens(&queue, nullptr, true);
                    *const_cast<const SLAndroidSimpleBufferQueueItf_ **>(queue.self) =
                        queue.original;
                }
            }
            gOpenSlStreams.close(RecorderIdentity(self));
            for (auto &engine : gEngines)
            {
                if (engine.active.load(std::memory_order_acquire) && engine.owner == self)
                {
                    *const_cast<const SLEngineItf_ **>(engine.self) = engine.original;
                    engine.active.store(false, std::memory_order_release);
                }
            }
            *const_cast<const SLObjectItf_ **>(self) = original;
            object->active.store(false, std::memory_order_release);
            original->Destroy(self);
        }

        SLresult ForwardCreateEngine(SLObjectItf *engine,
                                     SLuint32 option_count,
                                     const SLEngineOption *options,
                                     SLuint32 interface_count,
                                     const SLInterfaceID *interface_ids,
                                     const SLboolean *required)
        {
            if (!gOriginalCreateEngine)
            {
                return SL_RESULT_INTERNAL_ERROR;
            }
            const SLresult result = gOriginalCreateEngine(engine,
                                                          option_count,
                                                          options,
                                                          interface_count,
                                                          interface_ids,
                                                          required);
            if (result == SL_RESULT_SUCCESS && engine && *engine)
            {
                (void)WrapObject(*engine, ObjectKind::kEngine);
            }
            return result;
        }
    } // namespace
#endif

    bool PublishOpenSLProfile(const runtime::DecodedProfileSnapshot &snapshot)
    {
        const bool published = gOpenSlStreams.publishProfile(snapshot.generation,
                                                             snapshot.nativeProcessAdmitted(),
                                                             snapshot.preset_json,
                                                             gOpenSlDspApi);
        if (!published)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                "echidna",
                                "OpenSL profile publication failed generation=%llu",
                                static_cast<unsigned long long>(snapshot.generation));
        }
        return published;
    }

#ifndef ECHIDNA_OPENSL_TESTING
    OpenSLHookManager::OpenSLHookManager(utils::PltResolver &resolver)
        : resolver_(resolver) {}

    bool OpenSLHookManager::install()
    {
        last_info_ = {};
#ifndef __ANDROID__
        (void)resolver_;
        last_info_.reason = "android_only";
        return false;
#else
        constexpr const char *kLibrary = "libOpenSLES.so";
        constexpr const char *kSymbol = "slCreateEngine";
        auto resolve_iid = [&](const char *symbol) -> SLInterfaceID
        {
            void *address = resolver_.findSymbol(kLibrary, symbol);
            return address ? *static_cast<const SLInterfaceID *>(address) : nullptr;
        };
        gIidEngine = resolve_iid("SL_IID_ENGINE");
        gIidAndroidSimpleBufferQueue =
            resolve_iid("SL_IID_ANDROIDSIMPLEBUFFERQUEUE");
        gIidBufferQueue = resolve_iid("SL_IID_BUFFERQUEUE");
        if (!gIidEngine || !gIidAndroidSimpleBufferQueue || !gIidBufferQueue)
        {
            last_info_.reason = "interface_ids_unavailable";
            return false;
        }
        void *target = resolver_.findSymbol(kLibrary, kSymbol);
        if (!target)
        {
            last_info_.reason = "symbol_not_found";
            return false;
        }
        if (!hook_create_engine_.install(
                target,
                reinterpret_cast<void *>(&ForwardCreateEngine),
                reinterpret_cast<void **>(&gOriginalCreateEngine)))
        {
            last_info_.library = kLibrary;
            last_info_.symbol = kSymbol;
            last_info_.reason = "hook_failed";
            return false;
        }
        last_info_.success = true;
        last_info_.library = kLibrary;
        last_info_.symbol = kSymbol;
        __android_log_print(ANDROID_LOG_INFO,
                            "echidna",
                            "OpenSL recorder interface wrapping installed via slCreateEngine");
        return true;
#endif
    }
#endif
} // namespace echidna::hooks
