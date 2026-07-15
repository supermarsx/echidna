#include "hooks/aaudio_callback_registry.h"

#include <cstdint>
#include <cstdio>

namespace
{
    int gFailures = 0;

    void Check(bool condition, const char *expression, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr,
                         "FAIL: %s [line %d] %s\n",
                         expression,
                         line,
                         message);
            ++gFailures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    int CallbackA(void *, void *user_data, void *, int32_t)
    {
        ++*static_cast<int *>(user_data);
        return 11;
    }

    int CallbackB(void *, void *user_data, void *, int32_t)
    {
        ++*static_cast<int *>(user_data);
        return 22;
    }

    void TestConcurrentStreamsNeverCrossTalk()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls_a = 0;
        int calls_b = 0;
        void *builder_a = reinterpret_cast<void *>(uintptr_t{0x1000});
        void *builder_b = reinterpret_cast<void *>(uintptr_t{0x2000});
        void *stream_a = reinterpret_cast<void *>(uintptr_t{0x3000});
        void *stream_b = reinterpret_cast<void *>(uintptr_t{0x4000});
        void *proxy_a = registry.registerCallback(builder_a, CallbackA, &calls_a);
        void *proxy_b = registry.registerCallback(builder_b, CallbackB, &calls_b);

        CHECK(proxy_a && proxy_b && proxy_a != proxy_b,
              "each registration needs a distinct immutable proxy");
        CHECK(registry.attachOpenedStream(builder_a, stream_a),
              "first builder must bind its stream");
        CHECK(registry.attachOpenedStream(builder_b, stream_b),
              "second builder must bind its stream");

        echidna::hooks::AAudioCallbackTarget target_a;
        echidna::hooks::AAudioCallbackTarget target_b;
        CHECK(registry.beginInvocation(proxy_a, stream_a, &target_a),
              "first callback must resolve");
        CHECK(registry.beginInvocation(proxy_b, stream_b, &target_b),
              "second callback must resolve concurrently");
        CHECK(target_a.callback(nullptr, target_a.user_data, nullptr, 0) == 11,
              "first proxy must call callback A");
        CHECK(target_b.callback(nullptr, target_b.user_data, nullptr, 0) == 22,
              "second proxy must call callback B");
        registry.endInvocation(proxy_b);
        registry.endInvocation(proxy_a);
        CHECK(calls_a == 1 && calls_b == 1,
              "per-stream user data must not cross-talk");
    }

    void TestReentrantInvocationAndBuilderTeardown()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        void *builder = reinterpret_cast<void *>(uintptr_t{0x5000});
        void *stream = reinterpret_cast<void *>(uintptr_t{0x6000});
        void *proxy = registry.registerCallback(builder, CallbackA, &calls);
        CHECK(proxy != nullptr, "callback registration must succeed");
        CHECK(registry.attachOpenedStream(builder, stream),
              "open-stream hook must associate the stream before builder deletion");

        registry.retireBuilder(builder);
        echidna::hooks::AAudioCallbackTarget outer;
        echidna::hooks::AAudioCallbackTarget inner;
        CHECK(registry.beginInvocation(proxy, stream, &outer),
              "deleting a builder must not retire an open stream callback");
        CHECK(registry.beginInvocation(proxy, stream, &inner),
              "recursive callbacks must resolve without locks or global state");
        CHECK(outer.callback == inner.callback && outer.user_data == inner.user_data,
              "nested invocation must preserve the original target");
        registry.endInvocation(proxy);
        registry.endInvocation(proxy);

        registry.closeStream(stream);
        echidna::hooks::AAudioCallbackTarget stale;
        CHECK(!registry.beginInvocation(proxy, stream, &stale),
              "closed stream plus deleted builder must reject stale proxy calls");
    }

    void TestPointerReuseCannotAliasStaleProxy()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int old_calls = 0;
        int new_calls = 0;
        void *builder = reinterpret_cast<void *>(uintptr_t{0x7000});
        void *stream = reinterpret_cast<void *>(uintptr_t{0x8000});

        void *old_proxy = registry.registerCallback(builder, CallbackA, &old_calls);
        CHECK(registry.attachOpenedStream(builder, stream),
              "old stream must associate");
        registry.retireBuilder(builder);
        registry.closeStream(stream);

        void *new_proxy = registry.registerCallback(builder, CallbackB, &new_calls);
        CHECK(new_proxy && new_proxy != old_proxy,
              "reused builder address must receive a never-before-used proxy");
        CHECK(registry.attachOpenedStream(builder, stream),
              "reused stream address must associate with the new registration");

        echidna::hooks::AAudioCallbackTarget stale;
        echidna::hooks::AAudioCallbackTarget current;
        CHECK(!registry.beginInvocation(old_proxy, stream, &stale),
              "old proxy must remain permanently retired");
        CHECK(registry.beginInvocation(new_proxy, stream, &current),
              "new proxy must resolve after address reuse");
        CHECK(current.callback(nullptr, current.user_data, nullptr, 0) == 22,
              "new proxy must call only the new callback");
        registry.endInvocation(new_proxy);
        CHECK(old_calls == 0 && new_calls == 1,
              "address reuse must not dispatch to stale user data");
    }

    void TestRegistrationFailureIsBoundedAndExplicit()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        CHECK(registry.registerCallback(nullptr, CallbackA, &calls) == nullptr,
              "null builder must fail registration");
        CHECK(registry.registerCallback(reinterpret_cast<void *>(uintptr_t{1}),
                                        nullptr,
                                        &calls) == nullptr,
              "null callback must fail registration");

        void *first_proxy = nullptr;
        for (size_t i = 0;
             i < echidna::hooks::AAudioCallbackRegistry::kMaxRegistrations;
             ++i)
        {
            void *builder = reinterpret_cast<void *>(uintptr_t{i + 0x10000});
            void *proxy = registry.registerCallback(builder, CallbackA, &calls);
            CHECK(proxy != nullptr, "every advertised registry slot must be usable");
            if (i == 0)
            {
                first_proxy = proxy;
            }
        }
        CHECK(registry.registerCallback(reinterpret_cast<void *>(uintptr_t{0x900000}),
                                        CallbackB,
                                        &calls) == nullptr,
              "exhaustion must fail instead of reusing a stale proxy address");

        echidna::hooks::AAudioCallbackTarget target;
        CHECK(registry.beginInvocation(first_proxy,
                                       reinterpret_cast<void *>(uintptr_t{0xA00000}),
                                       &target),
              "registry exhaustion must not corrupt existing registrations");
        registry.endInvocation(first_proxy);
    }
} // namespace

int main()
{
    TestConcurrentStreamsNeverCrossTalk();
    TestReentrantInvocationAndBuilderTeardown();
    TestPointerReuseCannotAliasStaleProxy();
    TestRegistrationFailureIsBoundedAndExplicit();

    if (gFailures != 0)
    {
        std::fprintf(stderr,
                     "aaudio_callback_registry_test: %d check(s) failed\n",
                     gFailures);
        return 1;
    }
    std::fprintf(stderr, "aaudio_callback_registry_test: all checks passed\n");
    return 0;
}
