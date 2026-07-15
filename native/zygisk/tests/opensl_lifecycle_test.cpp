#define ECHIDNA_OPENSL_TESTING 1
#include "../src/hooks/opensl_hook_manager.cpp"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace
{
    using namespace echidna::hooks;

    constexpr SLresult kMockFailure = SL_RESULT_PRECONDITIONS_VIOLATED;

    int gFailures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++gFailures;
        }
    }

    struct MockEngine;
    struct MockQueue;

    enum class MockObjectKind
    {
        kEngine,
        kRecorder,
    };

    struct MockObject
    {
        const SLObjectItf_ *vtable{nullptr};
        MockObjectKind kind{MockObjectKind::kRecorder};
        MockEngine *engine{nullptr};
        MockQueue *queue{nullptr};
        SLresult realize_result{SL_RESULT_SUCCESS};
        SLresult get_interface_result{SL_RESULT_SUCCESS};
        int realize_calls{0};
        int get_interface_calls{0};
        int destroy_calls{0};

        SLObjectItf handle() { return &vtable; }
    };

    struct MockEngine
    {
        const SLEngineItf_ *vtable{nullptr};
        MockObject *recorder{nullptr};
        SLresult create_result{SL_RESULT_SUCCESS};
        int create_calls{0};

        SLEngineItf handle() { return &vtable; }
    };

    struct EnqueueRecord
    {
        const void *data{nullptr};
        uint32_t bytes{0};
    };

    struct MockQueue
    {
        const SLAndroidSimpleBufferQueueItf_ *vtable{nullptr};
        SLresult enqueue_result{SL_RESULT_SUCCESS};
        SLresult clear_result{SL_RESULT_SUCCESS};
        SLresult register_result{SL_RESULT_SUCCESS};
        bool callback_during_enqueue{false};
        int clear_calls{0};
        int register_calls{0};
        std::vector<EnqueueRecord> enqueues;
        slAndroidSimpleBufferQueueCallback callback{nullptr};
        void *callback_context{nullptr};

        SLAndroidSimpleBufferQueueItf handle() { return &vtable; }

        void trigger()
        {
            if (callback)
            {
                callback(handle(), callback_context);
            }
        }
    };

    MockObject *AsObject(SLObjectItf self)
    {
        return reinterpret_cast<MockObject *>(
            const_cast<const SLObjectItf_ **>(self));
    }

    MockEngine *AsEngine(SLEngineItf self)
    {
        return reinterpret_cast<MockEngine *>(
            const_cast<const SLEngineItf_ **>(self));
    }

    MockQueue *AsQueue(SLAndroidSimpleBufferQueueItf self)
    {
        return reinterpret_cast<MockQueue *>(
            const_cast<const SLAndroidSimpleBufferQueueItf_ **>(self));
    }

    SLresult MockRealize(SLObjectItf self, SLboolean)
    {
        MockObject *object = AsObject(self);
        ++object->realize_calls;
        return object->realize_result;
    }

    SLresult MockGetInterface(SLObjectItf self,
                              SLInterfaceID iid,
                              void *out_interface)
    {
        MockObject *object = AsObject(self);
        ++object->get_interface_calls;
        if (object->get_interface_result != SL_RESULT_SUCCESS || !out_interface)
        {
            return object->get_interface_result;
        }
        if (object->kind == MockObjectKind::kEngine && iid == gIidEngine &&
            object->engine)
        {
            *static_cast<SLEngineItf *>(out_interface) = object->engine->handle();
            return SL_RESULT_SUCCESS;
        }
        if (object->kind == MockObjectKind::kRecorder && object->queue &&
            (iid == gIidAndroidSimpleBufferQueue || iid == gIidBufferQueue))
        {
            *static_cast<SLAndroidSimpleBufferQueueItf *>(out_interface) =
                object->queue->handle();
            return SL_RESULT_SUCCESS;
        }
        return kMockFailure;
    }

    void MockDestroy(SLObjectItf self)
    {
        ++AsObject(self)->destroy_calls;
    }

    SLresult MockCreateAudioRecorder(SLEngineItf self,
                                     SLObjectItf *recorder,
                                     SLDataSource *,
                                     SLDataSink *,
                                     SLuint32,
                                     const SLInterfaceID *,
                                     const SLboolean *)
    {
        MockEngine *engine = AsEngine(self);
        ++engine->create_calls;
        if (engine->create_result != SL_RESULT_SUCCESS)
        {
            return engine->create_result;
        }
        if (!recorder || !engine->recorder)
        {
            return kMockFailure;
        }
        *recorder = engine->recorder->handle();
        return SL_RESULT_SUCCESS;
    }

    SLresult MockEnqueue(SLAndroidSimpleBufferQueueItf self,
                         const void *buffer,
                         SLuint32 bytes)
    {
        MockQueue *queue = AsQueue(self);
        queue->enqueues.push_back({buffer, bytes});
        if (queue->enqueue_result == SL_RESULT_SUCCESS &&
            queue->callback_during_enqueue && queue->callback)
        {
            queue->callback(self, queue->callback_context);
        }
        return queue->enqueue_result;
    }

    SLresult MockClear(SLAndroidSimpleBufferQueueItf self)
    {
        MockQueue *queue = AsQueue(self);
        ++queue->clear_calls;
        return queue->clear_result;
    }

    SLresult MockGetState(SLAndroidSimpleBufferQueueItf,
                          SLAndroidSimpleBufferQueueState *)
    {
        return SL_RESULT_SUCCESS;
    }

    SLresult MockRegisterCallback(SLAndroidSimpleBufferQueueItf self,
                                  slAndroidSimpleBufferQueueCallback callback,
                                  void *context)
    {
        MockQueue *queue = AsQueue(self);
        ++queue->register_calls;
        if (queue->register_result == SL_RESULT_SUCCESS)
        {
            queue->callback = callback;
            queue->callback_context = context;
        }
        return queue->register_result;
    }

    const SLObjectItf_ kObjectVtable{
        &MockRealize,
        &MockGetInterface,
        &MockDestroy,
    };
    const SLEngineItf_ kEngineVtable{&MockCreateAudioRecorder};
    const SLAndroidSimpleBufferQueueItf_ kQueueVtable{
        &MockEnqueue,
        &MockClear,
        &MockGetState,
        &MockRegisterCallback,
    };

    struct MockFlow
    {
        MockObject engine_object;
        MockEngine engine;
        MockObject recorder;
        MockQueue queue;
        SLDataLocator_AndroidSimpleBufferQueue locator{
            SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 8};
        SLDataFormat_PCM format{
            SL_DATAFORMAT_PCM,
            1,
            48000000,
            16,
            16,
            0,
            SL_BYTEORDER_LITTLEENDIAN,
        };
        SLDataSource source{};
        SLDataSink sink{&locator, &format};

        MockFlow()
        {
            engine_object.vtable = &kObjectVtable;
            engine_object.kind = MockObjectKind::kEngine;
            engine_object.engine = &engine;
            engine.vtable = &kEngineVtable;
            engine.recorder = &recorder;
            recorder.vtable = &kObjectVtable;
            recorder.kind = MockObjectKind::kRecorder;
            recorder.queue = &queue;
            queue.vtable = &kQueueVtable;
        }
    };

    MockObject *gCreateEngineObject = nullptr;
    SLresult gCreateEngineResult = SL_RESULT_SUCCESS;

    SLresult MockCreateEngine(SLObjectItf *engine,
                              SLuint32,
                              const SLEngineOption *,
                              SLuint32,
                              const SLInterfaceID *,
                              const SLboolean *)
    {
        if (gCreateEngineResult != SL_RESULT_SUCCESS)
        {
            return gCreateEngineResult;
        }
        if (!engine || !gCreateEngineObject)
        {
            return kMockFailure;
        }
        *engine = gCreateEngineObject->handle();
        return SL_RESULT_SUCCESS;
    }

    int gPrepareCalls = 0;
    uint32_t gPreparedRate = 0;
    uint32_t gPreparedChannels = 0;
    echidna_result_t gPrepareResult = ECHIDNA_RESULT_OK;

    echidna_result_t MockPrepareStream(uint32_t sample_rate, uint32_t channels)
    {
        ++gPrepareCalls;
        gPreparedRate = sample_rate;
        gPreparedChannels = channels;
        return gPrepareResult;
    }

    std::mutex gProcessMutex;
    std::condition_variable gProcessCondition;
    bool gBlockProcess = false;
    bool gProcessStarted = false;
    bool gReleaseProcess = false;
    int gProcessCalls = 0;
    std::vector<std::string> gEvents;

    echidna_result_t MockProcessBlock(const float *input,
                                      float *output,
                                      uint32_t frames,
                                      uint32_t,
                                      uint32_t channels)
    {
        {
            std::unique_lock lock(gProcessMutex);
            ++gProcessCalls;
            gEvents.emplace_back("process");
            if (gBlockProcess)
            {
                gProcessStarted = true;
                gProcessCondition.notify_all();
                gProcessCondition.wait(lock, []
                                       { return gReleaseProcess; });
            }
        }
        const size_t samples = static_cast<size_t>(frames) * channels;
        for (size_t i = 0; i < samples; ++i)
        {
            output[i] = input[i] * 2.0f;
        }
        return ECHIDNA_RESULT_OK;
    }

    struct CallbackState
    {
        int calls{0};
        std::vector<int16_t *> expected_buffers;
        std::vector<int16_t> expected_first_samples;
        SLAndroidSimpleBufferQueueItf recursive_queue{nullptr};
        const void *recursive_buffer{nullptr};
        uint32_t recursive_bytes{0};
        bool recurse_once{false};
    };

    void AppCallback(SLAndroidSimpleBufferQueueItf, void *opaque)
    {
        auto *state = static_cast<CallbackState *>(opaque);
        {
            std::lock_guard lock(gProcessMutex);
            gEvents.emplace_back("app");
        }
        if (state->calls < static_cast<int>(state->expected_buffers.size()))
        {
            if (state->expected_buffers[state->calls][0] !=
                state->expected_first_samples[state->calls])
            {
                std::fprintf(stderr,
                             "callback mismatch call=%d actual=%d expected=%d\n",
                             state->calls,
                             state->expected_buffers[state->calls][0],
                             state->expected_first_samples[state->calls]);
            }
            Check(state->expected_buffers[state->calls][0] ==
                      state->expected_first_samples[state->calls],
                  "application callback must observe transformed PCM");
        }
        ++state->calls;
        if (state->recurse_once && state->calls == 1 && state->recursive_queue)
        {
            const SLresult result = (*state->recursive_queue)
                                        ->Enqueue(state->recursive_queue,
                                                  state->recursive_buffer,
                                                  state->recursive_bytes);
            Check(result == SL_RESULT_SUCCESS,
                  "recursive Enqueue must preserve application result");
        }
    }

    struct GuardedPcm
    {
        static constexpr int16_t kGuard = 0x5a5a;
        std::array<int16_t, 6> samples{kGuard, 1000, -2000, 3000, -4000, kGuard};

        int16_t *data() { return samples.data() + 1; }
        uint32_t bytes() const { return 4u * sizeof(int16_t); }
        bool guardsIntact() const
        {
            return samples.front() == kGuard && samples.back() == kGuard;
        }
    };

    bool WrapFlow(MockFlow &flow, SLInterfaceID queue_iid = nullptr)
    {
        gCreateEngineObject = &flow.engine_object;
        gCreateEngineResult = SL_RESULT_SUCCESS;
        SLObjectItf engine_object = nullptr;
        const SLresult create_result =
            ForwardCreateEngine(&engine_object, 0, nullptr, 0, nullptr, nullptr);
        gCreateEngineObject = nullptr;
        if (create_result != SL_RESULT_SUCCESS)
        {
            return false;
        }
        SLEngineItf engine = nullptr;
        if ((*engine_object)->GetInterface(engine_object, gIidEngine, &engine) !=
            SL_RESULT_SUCCESS)
        {
            return false;
        }
        SLObjectItf recorder = nullptr;
        if ((*engine)->CreateAudioRecorder(engine,
                                           &recorder,
                                           &flow.source,
                                           &flow.sink,
                                           0,
                                           nullptr,
                                           nullptr) != SL_RESULT_SUCCESS)
        {
            return false;
        }
        SLAndroidSimpleBufferQueueItf queue = nullptr;
        if ((*recorder)->GetInterface(recorder,
                                      queue_iid ? queue_iid
                                                : gIidAndroidSimpleBufferQueue,
                                      &queue) != SL_RESULT_SUCCESS)
        {
            return false;
        }
        return engine_object == flow.engine_object.handle() &&
               engine == flow.engine.handle() && recorder == flow.recorder.handle() &&
               queue == flow.queue.handle();
    }

    void DestroyFlow(MockFlow &flow)
    {
        if (flow.recorder.vtable != &kObjectVtable)
        {
            auto recorder = flow.recorder.handle();
            (*recorder)->Destroy(recorder);
        }
        if (flow.engine_object.vtable != &kObjectVtable)
        {
            auto engine_object = flow.engine_object.handle();
            (*engine_object)->Destroy(engine_object);
        }
    }

    void ResetProcessState()
    {
        std::lock_guard lock(gProcessMutex);
        gProcessCalls = 0;
        gEvents.clear();
        gBlockProcess = false;
        gProcessStarted = false;
        gReleaseProcess = false;
    }

    void TestCreationAndInterfaceFailures()
    {
        MockFlow flow;
        gCreateEngineResult = kMockFailure;
        gCreateEngineObject = &flow.engine_object;
        SLObjectItf output = nullptr;
        const SLresult failed_create =
            ForwardCreateEngine(&output, 0, nullptr, 0, nullptr, nullptr);
        gCreateEngineObject = nullptr;
        Check(failed_create == kMockFailure,
              "slCreateEngine failure must be forwarded");
        Check(output == nullptr && flow.engine_object.vtable == &kObjectVtable,
              "failed engine creation must not wrap an object");

        gCreateEngineResult = SL_RESULT_SUCCESS;
        gCreateEngineObject = &flow.engine_object;
        const SLresult successful_create =
            ForwardCreateEngine(&output, 0, nullptr, 0, nullptr, nullptr);
        gCreateEngineObject = nullptr;
        Check(successful_create == SL_RESULT_SUCCESS,
              "slCreateEngine success must be forwarded");
        Check(flow.engine_object.vtable != &kObjectVtable,
              "successful engine object must be wrapped");

        flow.engine_object.realize_result = kMockFailure;
        Check(flow.engine_object.realize_result == kMockFailure,
              "Realize failure mock must be configured before invocation");
        Check((*output)->Realize(output, 0) == kMockFailure,
              "Realize failure must be forwarded exactly");
        flow.engine_object.realize_result = SL_RESULT_SUCCESS;
        Check((*output)->Realize(output, 0) == SL_RESULT_SUCCESS &&
                  flow.engine_object.realize_calls == 2,
              "Realize success must remain reachable through copied vtable");

        flow.engine_object.get_interface_result = kMockFailure;
        SLEngineItf engine = nullptr;
        Check((*output)->GetInterface(output, gIidEngine, &engine) == kMockFailure &&
                  engine == nullptr && flow.engine.vtable == &kEngineVtable,
              "failed engine GetInterface must not wrap output");
        flow.engine_object.get_interface_result = SL_RESULT_SUCCESS;
        Check((*output)->GetInterface(output, gIidEngine, &engine) ==
                      SL_RESULT_SUCCESS &&
                  flow.engine.vtable != &kEngineVtable,
              "successful engine GetInterface must wrap engine vtable");

        flow.engine.create_result = kMockFailure;
        SLObjectItf recorder = nullptr;
        Check((*engine)->CreateAudioRecorder(engine,
                                             &recorder,
                                             &flow.source,
                                             &flow.sink,
                                             0,
                                             nullptr,
                                             nullptr) == kMockFailure &&
                  recorder == nullptr && flow.recorder.vtable == &kObjectVtable,
              "failed CreateAudioRecorder must not wrap recorder");

        flow.engine.create_result = SL_RESULT_SUCCESS;
        flow.locator.numBuffers = 0;
        Check((*engine)->CreateAudioRecorder(engine,
                                             &recorder,
                                             &flow.source,
                                             &flow.sink,
                                             0,
                                             nullptr,
                                             nullptr) == SL_RESULT_SUCCESS &&
                  flow.recorder.vtable == &kObjectVtable,
              "ambiguous recorder contract must remain untouched");
        flow.locator.numBuffers = 8;
        gPrepareResult = ECHIDNA_RESULT_NOT_AVAILABLE;
        Check((*engine)->CreateAudioRecorder(engine,
                                             &recorder,
                                             &flow.source,
                                             &flow.sink,
                                             0,
                                             nullptr,
                                             nullptr) == SL_RESULT_SUCCESS &&
                  flow.recorder.vtable == &kObjectVtable,
              "DSP prepare failure must preserve unwrapped recorder behavior");
        gPrepareResult = ECHIDNA_RESULT_OK;
        Check((*engine)->CreateAudioRecorder(engine,
                                             &recorder,
                                             &flow.source,
                                             &flow.sink,
                                             0,
                                             nullptr,
                                             nullptr) == SL_RESULT_SUCCESS &&
                  flow.recorder.vtable != &kObjectVtable &&
                  gPreparedRate == 48000 && gPreparedChannels == 1,
              "valid recorder creation must prepare exact stream metadata");

        flow.recorder.get_interface_result = kMockFailure;
        SLAndroidSimpleBufferQueueItf queue = nullptr;
        Check((*recorder)->GetInterface(recorder,
                                        gIidAndroidSimpleBufferQueue,
                                        &queue) == kMockFailure &&
                  queue == nullptr && flow.queue.vtable == &kQueueVtable,
              "failed recorder GetInterface must not wrap queue");
        flow.recorder.get_interface_result = SL_RESULT_SUCCESS;
        Check((*recorder)->GetInterface(recorder,
                                        gIidAndroidSimpleBufferQueue,
                                        &queue) == SL_RESULT_SUCCESS &&
                  flow.queue.vtable != &kQueueVtable,
              "successful recorder GetInterface must wrap queue");

        (*recorder)->Destroy(recorder);
        (*output)->Destroy(output);
        Check(flow.engine_object.destroy_calls == 1 &&
                  flow.engine_object.vtable == &kObjectVtable &&
                  flow.engine.vtable == &kEngineVtable,
              "engine Destroy must restore owned object and engine vtables");
    }

    void TestFifoOrderingReplacementAndClear()
    {
        MockFlow flow;
        Check(WrapFlow(flow), "primary OpenSL flow must wrap end to end");
        auto queue = flow.queue.handle();
        CallbackState first;
        GuardedPcm a;
        GuardedPcm b;
        GuardedPcm c;
        first.expected_buffers = {a.data(), b.data(), c.data()};
        first.expected_first_samples = {2000, 2000, 2000};
        Check((*queue)->RegisterCallback(queue, &AppCallback, &first) ==
                  SL_RESULT_SUCCESS,
              "callback registration must succeed");
        Check((*queue)->Enqueue(queue, a.data(), a.bytes()) == SL_RESULT_SUCCESS &&
                  (*queue)->Enqueue(queue, b.data(), b.bytes()) == SL_RESULT_SUCCESS &&
                  (*queue)->Enqueue(queue, c.data(), c.bytes()) == SL_RESULT_SUCCESS,
              "N buffers must enqueue through wrapped queue");
        flow.queue.trigger();
        flow.queue.trigger();
        flow.queue.trigger();
        Check(first.calls == 3 && gProcessCalls == 3,
              "N callbacks must transform exactly N FIFO buffers");
        Check(a.guardsIntact() && b.guardsIntact() && c.guardsIntact(),
              "processing must not write outside exact OpenSL byte counts");
        Check(gEvents.size() == 6,
              "each transformed callback must emit process and app ordering events");
        for (size_t i = 0; i + 1 < gEvents.size(); i += 2)
        {
            Check(gEvents[i] == "process" && gEvents[i + 1] == "app",
                  "DSP must complete before original application callback");
        }

        const auto stale_callback = flow.queue.callback;
        void *stale_context = flow.queue.callback_context;
        CallbackState second;
        GuardedPcm replacement;
        second.expected_buffers = {replacement.data()};
        second.expected_first_samples = {2000};
        flow.queue.register_result = kMockFailure;
        Check((*queue)->RegisterCallback(queue, &AppCallback, &second) == kMockFailure,
              "failed callback replacement must preserve original result");
        GuardedPcm after_failed_replacement;
        first.expected_buffers.push_back(after_failed_replacement.data());
        first.expected_first_samples.push_back(2000);
        Check((*queue)->Enqueue(queue,
                                after_failed_replacement.data(),
                                after_failed_replacement.bytes()) == SL_RESULT_SUCCESS,
              "old callback must remain usable after failed replacement");
        flow.queue.trigger();
        Check(first.calls == 4 && second.calls == 0,
              "failed replacement must keep previous callback token active");
        flow.queue.register_result = SL_RESULT_SUCCESS;
        Check((*queue)->RegisterCallback(queue, &AppCallback, &second) ==
                  SL_RESULT_SUCCESS,
              "callback replacement must succeed");
        stale_callback(queue, stale_context);
        Check(first.calls == 4,
              "replaced callback token must reject stale framework invocation");
        Check((*queue)->Enqueue(queue, replacement.data(), replacement.bytes()) ==
                  SL_RESULT_SUCCESS,
              "replacement callback buffer must enqueue");
        flow.queue.trigger();
        Check(second.calls == 1,
              "replacement callback must receive subsequent transformed buffer");

        const auto replaced_proxy = flow.queue.callback;
        void *replaced_context = flow.queue.callback_context;
        Check((*queue)->RegisterCallback(queue, nullptr, nullptr) == SL_RESULT_SUCCESS,
              "null callback must unregister successfully");
        replaced_proxy(queue, replaced_context);
        Check(second.calls == 1,
              "unregistered callback token must reject stale invocation");

        CallbackState clear_state;
        GuardedPcm cleared;
        Check((*queue)->RegisterCallback(queue, &AppCallback, &clear_state) ==
                  SL_RESULT_SUCCESS,
              "callback must register after null unregister");
        const int process_before_clear = gProcessCalls;
        Check((*queue)->Enqueue(queue, cleared.data(), cleared.bytes()) ==
                      SL_RESULT_SUCCESS &&
                  (*queue)->Clear(queue) == SL_RESULT_SUCCESS,
              "successful Clear must forward and discard pending buffers");
        flow.queue.trigger();
        Check(gProcessCalls == process_before_clear && clear_state.calls == 1 &&
                  cleared.data()[0] == 1000,
              "Clear callback must not transform a discarded buffer");

        flow.queue.clear_result = kMockFailure;
        GuardedPcm retained;
        clear_state.expected_buffers = {retained.data()};
        clear_state.expected_first_samples = {2000};
        clear_state.calls = 0;
        Check((*queue)->Enqueue(queue, retained.data(), retained.bytes()) ==
                      SL_RESULT_SUCCESS &&
                  (*queue)->Clear(queue) == kMockFailure,
              "failed Clear must preserve original result");
        flow.queue.trigger();
        Check(clear_state.calls == 1 && retained.data()[0] == 2000,
              "failed Clear must retain pending FIFO association");
        DestroyFlow(flow);
    }

    void TestEnqueueRollbackRecursionAndOverflow()
    {
        MockFlow flow;
        Check(WrapFlow(flow, gIidBufferQueue),
              "standard buffer-queue IID must use compatible wrapper");
        auto queue = flow.queue.handle();
        CallbackState state;
        Check((*queue)->RegisterCallback(queue, &AppCallback, &state) ==
                  SL_RESULT_SUCCESS,
              "rollback test callback must register");
        GuardedPcm failed;
        flow.queue.enqueue_result = kMockFailure;
        Check(flow.queue.enqueue_result == kMockFailure,
              "Enqueue failure mock must be configured before invocation");
        Check((*queue)->Enqueue(queue, failed.data(), failed.bytes()) == kMockFailure,
              "Enqueue failure must be returned exactly");
        flow.queue.enqueue_result = SL_RESULT_SUCCESS;
        GuardedPcm good;
        state.expected_buffers = {good.data()};
        state.expected_first_samples = {2000};
        Check((*queue)->Enqueue(queue, good.data(), good.bytes()) ==
                  SL_RESULT_SUCCESS,
              "queue must recover after unconsumed failed Enqueue rollback");
        flow.queue.trigger();
        Check(state.calls == 1 && good.data()[0] == 2000 &&
                  failed.data()[0] == 1000,
              "failed Enqueue reservation must never consume or transform stale PCM");

        MockFlow recursive_flow;
        Check(WrapFlow(recursive_flow), "recursive flow must wrap");
        auto recursive_queue = recursive_flow.queue.handle();
        recursive_flow.queue.callback_during_enqueue = true;
        GuardedPcm outer;
        GuardedPcm inner;
        CallbackState recursive;
        recursive.expected_buffers = {outer.data(), inner.data()};
        recursive.expected_first_samples = {2000, 2000};
        recursive.recursive_queue = recursive_queue;
        recursive.recursive_buffer = inner.data();
        recursive.recursive_bytes = inner.bytes();
        recursive.recurse_once = true;
        Check((*recursive_queue)
                          ->RegisterCallback(recursive_queue, &AppCallback, &recursive) ==
                      SL_RESULT_SUCCESS &&
                  (*recursive_queue)
                          ->Enqueue(recursive_queue, outer.data(), outer.bytes()) ==
                      SL_RESULT_SUCCESS,
              "callback-during-Enqueue recursion must succeed");
        Check(recursive.calls == 2 && outer.data()[0] == 2000 &&
                  inner.data()[0] == 2000,
              "recursive callbacks must preserve FIFO order and transform both buffers");

        MockFlow overflow_flow;
        Check(WrapFlow(overflow_flow), "overflow flow must wrap");
        auto overflow_queue = overflow_flow.queue.handle();
        CallbackState overflow_state;
        Check((*overflow_queue)
                      ->RegisterCallback(overflow_queue,
                                         &AppCallback,
                                         &overflow_state) == SL_RESULT_SUCCESS,
              "overflow callback must register");
        std::array<std::array<int16_t, 1>, OpenSlBufferFifo::kCapacity + 1> buffers{};
        for (size_t i = 0; i < buffers.size(); ++i)
        {
            buffers[i][0] = static_cast<int16_t>(100 + i);
            Check((*overflow_queue)
                          ->Enqueue(overflow_queue,
                                    buffers[i].data(),
                                    sizeof(buffers[i])) == SL_RESULT_SUCCESS,
                  "native Enqueue result must survive tracking overflow");
        }
        const int before_overflow_callbacks = gProcessCalls;
        for (size_t i = 0; i < buffers.size(); ++i)
        {
            overflow_flow.queue.trigger();
        }
        Check(gProcessCalls == before_overflow_callbacks &&
                  overflow_state.calls == static_cast<int>(buffers.size()),
              "bounded overflow must fail closed to app-callback passthrough");
        for (size_t i = 0; i < buffers.size(); ++i)
        {
            Check(buffers[i][0] == static_cast<int16_t>(100 + i),
                  "overflow bypass must leave every buffer unchanged");
        }
        DestroyFlow(flow);
        DestroyFlow(recursive_flow);
        DestroyFlow(overflow_flow);
    }

    void TestDestroyConcurrencyAndPointerReuse()
    {
        MockFlow flow;
        Check(WrapFlow(flow), "destroy flow must wrap");
        auto queue = flow.queue.handle();
        GuardedPcm pending;
        CallbackState state;
        Check((*queue)->RegisterCallback(queue, &AppCallback, &state) ==
                      SL_RESULT_SUCCESS &&
                  (*queue)->Enqueue(queue, pending.data(), pending.bytes()) ==
                      SL_RESULT_SUCCESS,
              "destroy-before-callback setup must succeed");
        const auto stale_proxy = flow.queue.callback;
        void *stale_context = flow.queue.callback_context;
        auto recorder = flow.recorder.handle();
        (*recorder)->Destroy(recorder);
        Check(flow.recorder.destroy_calls == 1 &&
                  flow.recorder.vtable == &kObjectVtable &&
                  flow.queue.vtable == &kQueueVtable,
              "recorder Destroy must restore recorder and queue vtables");
        stale_proxy(queue, stale_context);
        Check(state.calls == 0 && pending.data()[0] == 1000,
              "callback after Destroy must reject retired token and leave PCM unchanged");

        // Reuse the exact recorder and queue addresses. Never-reused wrapper
        // contexts must keep the old token stale while allowing a fresh lifetime.
        flow.recorder.vtable = &kObjectVtable;
        flow.recorder.get_interface_result = SL_RESULT_SUCCESS;
        flow.recorder.destroy_calls = 0;
        flow.queue.vtable = &kQueueVtable;
        flow.queue.callback = nullptr;
        flow.queue.callback_context = nullptr;
        flow.queue.enqueue_result = SL_RESULT_SUCCESS;
        flow.queue.clear_result = SL_RESULT_SUCCESS;
        SLObjectItf reused_recorder = nullptr;
        auto engine = flow.engine.handle();
        Check((*engine)->CreateAudioRecorder(engine,
                                             &reused_recorder,
                                             &flow.source,
                                             &flow.sink,
                                             0,
                                             nullptr,
                                             nullptr) == SL_RESULT_SUCCESS,
              "same recorder pointer must support a fresh wrapped lifetime");
        SLAndroidSimpleBufferQueueItf reused_queue = nullptr;
        Check((*reused_recorder)
                      ->GetInterface(reused_recorder,
                                     gIidAndroidSimpleBufferQueue,
                                     &reused_queue) == SL_RESULT_SUCCESS,
              "same queue pointer must support a fresh wrapped lifetime");
        CallbackState reused_state;
        GuardedPcm reused_buffer;
        reused_state.expected_buffers = {reused_buffer.data()};
        reused_state.expected_first_samples = {2000};
        Check((*reused_queue)
                          ->RegisterCallback(reused_queue,
                                             &AppCallback,
                                             &reused_state) == SL_RESULT_SUCCESS &&
                  (*reused_queue)
                          ->Enqueue(reused_queue,
                                    reused_buffer.data(),
                                    reused_buffer.bytes()) == SL_RESULT_SUCCESS,
              "fresh pointer-reuse callback and buffer must register");
        stale_proxy(queue, stale_context);
        Check(state.calls == 0,
              "old callback token must remain stale after pointer reuse");
        flow.queue.trigger();
        Check(reused_state.calls == 1 && reused_buffer.data()[0] == 2000,
              "fresh pointer lifetime must transform normally");

        MockFlow concurrent_flow;
        Check(WrapFlow(concurrent_flow), "concurrent teardown flow must wrap");
        auto concurrent_queue = concurrent_flow.queue.handle();
        GuardedPcm concurrent_buffer;
        CallbackState concurrent_state;
        concurrent_state.expected_buffers = {concurrent_buffer.data()};
        concurrent_state.expected_first_samples = {2000};
        Check((*concurrent_queue)
                          ->RegisterCallback(concurrent_queue,
                                             &AppCallback,
                                             &concurrent_state) == SL_RESULT_SUCCESS &&
                  (*concurrent_queue)
                          ->Enqueue(concurrent_queue,
                                    concurrent_buffer.data(),
                                    concurrent_buffer.bytes()) == SL_RESULT_SUCCESS,
              "concurrent teardown setup must succeed");
        const auto concurrent_proxy = concurrent_flow.queue.callback;
        void *concurrent_context = concurrent_flow.queue.callback_context;
        {
            std::lock_guard lock(gProcessMutex);
            gBlockProcess = true;
            gProcessStarted = false;
            gReleaseProcess = false;
        }
        std::thread callback_thread([&]
                                    { concurrent_proxy(concurrent_queue, concurrent_context); });
        {
            std::unique_lock lock(gProcessMutex);
            gProcessCondition.wait(lock, []
                                   { return gProcessStarted; });
        }
        auto concurrent_recorder = concurrent_flow.recorder.handle();
        (*concurrent_recorder)->Destroy(concurrent_recorder);
        {
            std::lock_guard lock(gProcessMutex);
            gReleaseProcess = true;
        }
        gProcessCondition.notify_all();
        callback_thread.join();
        concurrent_proxy(concurrent_queue, concurrent_context);
        Check(concurrent_state.calls == 1 && concurrent_buffer.guardsIntact(),
              "callback begun before teardown may finish once; later stale calls must stop");
        DestroyFlow(flow);
        DestroyFlow(concurrent_flow);
    }

    void TestMultipleFlowIsolation()
    {
        MockFlow first_flow;
        MockFlow second_flow;
        Check(WrapFlow(first_flow) && WrapFlow(second_flow),
              "multiple engines, recorders, and queues must wrap independently");
        GuardedPcm first_buffer;
        GuardedPcm second_buffer;
        CallbackState first;
        CallbackState second;
        first.expected_buffers = {first_buffer.data()};
        first.expected_first_samples = {2000};
        second.expected_buffers = {second_buffer.data()};
        second.expected_first_samples = {2000};
        auto first_queue = first_flow.queue.handle();
        auto second_queue = second_flow.queue.handle();
        Check((*first_queue)->RegisterCallback(first_queue, &AppCallback, &first) ==
                      SL_RESULT_SUCCESS &&
                  (*second_queue)->RegisterCallback(second_queue, &AppCallback, &second) ==
                      SL_RESULT_SUCCESS,
              "multiple callbacks must register independently");
        Check((*first_queue)
                          ->Enqueue(first_queue, first_buffer.data(), first_buffer.bytes()) ==
                      SL_RESULT_SUCCESS &&
                  (*second_queue)
                          ->Enqueue(second_queue,
                                    second_buffer.data(),
                                    second_buffer.bytes()) == SL_RESULT_SUCCESS,
              "multiple queues must enqueue independently");
        second_flow.queue.trigger();
        first_flow.queue.trigger();
        Check(first.calls == 1 && second.calls == 1 && first_buffer.data()[0] == 2000 &&
                  second_buffer.data()[0] == 2000,
              "callbacks must transform only their owning queue buffer");
        DestroyFlow(first_flow);
        DestroyFlow(second_flow);
    }
} // namespace

int main()
{
    static const SLInterfaceID_ kEngineId{1};
    static const SLInterfaceID_ kAndroidQueueId{2};
    static const SLInterfaceID_ kQueueId{3};
    gIidEngine = &kEngineId;
    gIidAndroidSimpleBufferQueue = &kAndroidQueueId;
    gIidBufferQueue = &kQueueId;
    gOriginalCreateEngine = &MockCreateEngine;
    gTestPrepareStream = &MockPrepareStream;
    gTestProcessBlock = &MockProcessBlock;

    ResetProcessState();
    TestCreationAndInterfaceFailures();
    ResetProcessState();
    TestFifoOrderingReplacementAndClear();
    ResetProcessState();
    TestEnqueueRollbackRecursionAndOverflow();
    ResetProcessState();
    TestDestroyConcurrencyAndPointerReuse();
    ResetProcessState();
    TestMultipleFlowIsolation();

    if (gFailures != 0)
    {
        std::fprintf(stderr, "opensl_lifecycle_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "opensl_lifecycle_test: all checks passed\n");
    return 0;
}
