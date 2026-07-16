#include "hooks/aaudio_callback_registry.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>
#include <thread>

namespace
{
    std::atomic<uint64_t> gAllocationCount{0};
    std::atomic<bool> gTrackAllocations{false};
} // namespace

void *operator new(std::size_t size)
{
    if (gTrackAllocations.load(std::memory_order_relaxed))
    {
        gAllocationCount.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *allocation = std::malloc(size))
    {
        return allocation;
    }
    throw std::bad_alloc();
}

void *operator new[](std::size_t size)
{
    return ::operator new(size);
}

void operator delete(void *allocation) noexcept
{
    std::free(allocation);
}

void operator delete[](void *allocation) noexcept
{
    std::free(allocation);
}

void operator delete(void *allocation, std::size_t) noexcept
{
    std::free(allocation);
}

void operator delete[](void *allocation, std::size_t) noexcept
{
    std::free(allocation);
}

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

    size_t TokenSlot(void *token)
    {
        return static_cast<size_t>(reinterpret_cast<uintptr_t>(token) & 0xFFU);
    }

    uint32_t TokenGeneration(void *token)
    {
        return static_cast<uint32_t>(reinterpret_cast<uintptr_t>(token) >> 8U);
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
              "each live registration needs a distinct token");
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

    void TestStaleTokenRejectedAfterSlotReuse()
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
        CHECK(new_proxy && TokenSlot(new_proxy) == TokenSlot(old_proxy),
              "a quiesced slot should be reclaimed");
        CHECK(TokenGeneration(new_proxy) == TokenGeneration(old_proxy) + 1,
              "slot reuse must advance its generation");
        CHECK(registry.attachOpenedStream(builder, stream),
              "reused stream address must associate with the new registration");

        echidna::hooks::AAudioCallbackTarget stale;
        echidna::hooks::AAudioCallbackTarget current;
        CHECK(!registry.beginInvocation(old_proxy, stream, &stale),
              "old generation must remain rejected after slot reuse");
        CHECK(registry.beginInvocation(new_proxy, stream, &current),
              "new generation must resolve after address reuse");
        CHECK(current.callback(nullptr, current.user_data, nullptr, 0) == 22,
              "new token must call only the new callback");
        registry.endInvocation(new_proxy);
        CHECK(old_calls == 0 && new_calls == 1,
              "slot reuse must not dispatch to stale user data");
    }

    void TestBoundStreamIdentityIsRequired()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        void *builder = reinterpret_cast<void *>(uintptr_t{0xA1000});
        void *stream = reinterpret_cast<void *>(uintptr_t{0xA2000});
        void *other_stream = reinterpret_cast<void *>(uintptr_t{0xA3000});
        void *proxy = registry.registerCallback(builder, CallbackA, &calls);
        CHECK(registry.attachOpenedStream(builder, stream), "stream identity attaches");

        echidna::hooks::AAudioCallbackTarget target;
        CHECK(!registry.beginInvocation(proxy, other_stream, &target),
              "proxy must reject a stream it never owned");
        CHECK(registry.beginInvocation(proxy, stream, &target),
              "proxy must accept its exact opened stream");
        registry.endInvocation(proxy);
        registry.closeStream(stream);
        CHECK(!registry.beginInvocation(proxy, stream, &target),
              "closed stream stays rejected even while its builder remains live");
    }

    void TestThousandsOfRegistrationsReuseSlots()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        void *first = nullptr;
        void *last = nullptr;
        constexpr size_t kCycles = 10000;

        for (size_t i = 0; i < kCycles; ++i)
        {
            void *builder = reinterpret_cast<void *>(uintptr_t{i + 0x10000});
            void *token = registry.registerCallback(builder, CallbackA, &calls);
            CHECK(token != nullptr,
                  "normal lifetime churn must not exhaust the fixed registry");
            if (!token)
            {
                break;
            }
            if (i == 0)
            {
                first = token;
            }
            last = token;
            registry.retireBuilder(builder);
        }

        CHECK(first && last && TokenSlot(first) == TokenSlot(last),
              "quiescent churn should repeatedly reclaim the same slot");
        CHECK(TokenGeneration(last) == kCycles,
              "each slot lifetime must have a distinct generation");
        echidna::hooks::AAudioCallbackTarget stale;
        CHECK(!registry.beginInvocation(first, nullptr, &stale),
              "the first token must stay stale after thousands of reuses");
    }

    void TestConcurrentRetirementWaitsForInvocationQuiescence()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int old_calls = 0;
        int other_calls = 0;
        int current_calls = 0;
        void *old_builder = reinterpret_cast<void *>(uintptr_t{0x21000});
        void *old_token = registry.registerCallback(old_builder, CallbackA, &old_calls);
        std::atomic<bool> ready{false};
        std::atomic<bool> began_invocation{false};
        std::atomic<bool> release{false};

        std::thread callback_thread([&]()
                                    {
            echidna::hooks::AAudioCallbackTarget target;
            const bool began = registry.beginInvocation(old_token, nullptr, &target);
            began_invocation.store(began, std::memory_order_relaxed);
            ready.store(true, std::memory_order_release);
            while (!release.load(std::memory_order_acquire))
            {
                std::this_thread::yield();
            }
            if (began)
            {
                registry.endInvocation(old_token);
            } });
        while (!ready.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        CHECK(began_invocation.load(std::memory_order_relaxed),
              "the callback thread must acquire the original generation");

        registry.retireBuilder(old_builder);
        void *other_builder = reinterpret_cast<void *>(uintptr_t{0x22000});
        void *other_token =
            registry.registerCallback(other_builder, CallbackB, &other_calls);
        CHECK(other_token && TokenSlot(other_token) != TokenSlot(old_token),
              "an in-flight retired slot must not be reused");
        echidna::hooks::AAudioCallbackTarget stale;
        CHECK(!registry.beginInvocation(old_token, nullptr, &stale),
              "retirement must reject new entrants while an old call drains");

        release.store(true, std::memory_order_release);
        callback_thread.join();
        registry.retireBuilder(other_builder);

        void *current_builder = reinterpret_cast<void *>(uintptr_t{0x23000});
        void *current_token =
            registry.registerCallback(current_builder, CallbackA, &current_calls);
        CHECK(current_token && TokenSlot(current_token) == TokenSlot(old_token),
              "the old slot may be reused only after its callback releases");
        CHECK(TokenGeneration(current_token) == TokenGeneration(old_token) + 1,
              "post-quiescence reuse must publish a new generation");
        CHECK(!registry.beginInvocation(old_token, nullptr, &stale),
              "the drained token must not alias the reused slot");
    }

    void TestGenerationWrapPermanentlyExhaustsSlots()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        for (size_t slot = 1;
             slot < echidna::hooks::AAudioCallbackRegistry::kMaxRegistrations;
             ++slot)
        {
            CHECK(registry.setGenerationForTesting(
                      slot, echidna::hooks::AAudioCallbackRegistry::kMaxGeneration),
                  "unused test slots must accept a terminal generation");
        }
        CHECK(registry.setGenerationForTesting(
                  0, echidna::hooks::AAudioCallbackRegistry::kMaxGeneration - 1),
              "slot zero must be positioned immediately before wrap");

        int calls = 0;
        void *builder = reinterpret_cast<void *>(uintptr_t{0x31000});
        void *last_token = registry.registerCallback(builder, CallbackA, &calls);
        CHECK(last_token &&
                  TokenGeneration(last_token) ==
                      echidna::hooks::AAudioCallbackRegistry::kMaxGeneration,
              "the terminal non-zero generation must remain usable once");
        registry.retireBuilder(builder);

        CHECK(registry.registerCallback(
                  reinterpret_cast<void *>(uintptr_t{0x32000}), CallbackB, &calls) ==
                  nullptr,
              "all slots must fail closed instead of wrapping a generation");
        echidna::hooks::AAudioCallbackTarget stale;
        CHECK(!registry.beginInvocation(last_token, nullptr, &stale),
              "a terminal generation must be permanently retired");
    }

    void TestTokenPortabilityAndValidation()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        void *token = registry.registerCallback(
            reinterpret_cast<void *>(uintptr_t{0x41000}), CallbackA, &calls);
        const uintptr_t encoded = reinterpret_cast<uintptr_t>(token);

        CHECK(token != nullptr && encoded <= std::numeric_limits<uint32_t>::max(),
              "tokens must fit the common 32-bit and 64-bit uintptr_t subset");
        CHECK(reinterpret_cast<void *>(encoded) == token,
              "opaque userData must round-trip through uintptr_t exactly");
        CHECK(TokenGeneration(token) != 0,
              "the null-reserved generation must never be emitted");

        echidna::hooks::AAudioCallbackTarget target;
        CHECK(!registry.beginInvocation(reinterpret_cast<void *>(uintptr_t{1}),
                                        nullptr,
                                        &target),
              "malformed tokens without a generation must fail closed");
#if UINTPTR_MAX > UINT32_MAX
        CHECK(!registry.beginInvocation(
                  reinterpret_cast<void *>(uintptr_t{1} << 40U), nullptr, &target),
              "tokens outside the portable 32-bit range must fail closed");
#endif
    }

    void TestCallbackPathHasNoAllocatorDependency()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        void *builder = reinterpret_cast<void *>(uintptr_t{0x51000});
        void *token = registry.registerCallback(builder, CallbackA, &calls);
        bool all_began = true;

        const uint64_t before = gAllocationCount.load(std::memory_order_relaxed);
        gTrackAllocations.store(true, std::memory_order_release);
        for (size_t i = 0; i < 10000; ++i)
        {
            echidna::hooks::AAudioCallbackTarget target;
            if (!registry.beginInvocation(token, nullptr, &target))
            {
                all_began = false;
                break;
            }
            registry.endInvocation(token);
        }
        gTrackAllocations.store(false, std::memory_order_release);
        const uint64_t after = gAllocationCount.load(std::memory_order_relaxed);

        CHECK(all_began, "the callback path must remain available under churn");
        CHECK(after == before,
              "begin/end callback dispatch must not allocate on the RT thread");
        CHECK(std::atomic<uint32_t>::is_always_lock_free,
              "registry synchronization requires lock-free 32-bit atomics");
    }

    void TestTrueLiveExhaustionIsBoundedAndExplicit()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        int calls = 0;
        CHECK(registry.registerCallback(nullptr, CallbackA, &calls) == nullptr,
              "null builder must fail registration");
        CHECK(registry.registerCallback(reinterpret_cast<void *>(uintptr_t{1}),
                                        nullptr,
                                        &calls) == nullptr,
              "null callback must fail registration");

        void *first_token = nullptr;
        for (size_t i = 0;
             i < echidna::hooks::AAudioCallbackRegistry::kMaxRegistrations;
             ++i)
        {
            void *builder = reinterpret_cast<void *>(uintptr_t{i + 0x60000});
            void *token = registry.registerCallback(builder, CallbackA, &calls);
            CHECK(token != nullptr, "every advertised registry slot must be usable");
            if (i == 0)
            {
                first_token = token;
            }
        }
        CHECK(registry.registerCallback(reinterpret_cast<void *>(uintptr_t{0x900000}),
                                        CallbackB,
                                        &calls) == nullptr,
              "true simultaneous exhaustion must fail without aliasing a token");

        echidna::hooks::AAudioCallbackTarget target;
        CHECK(registry.beginInvocation(first_token,
                                       reinterpret_cast<void *>(uintptr_t{0xA00000}),
                                       &target),
              "registry exhaustion must not corrupt existing registrations");
        registry.endInvocation(first_token);
    }
} // namespace

int main()
{
    TestConcurrentStreamsNeverCrossTalk();
    TestReentrantInvocationAndBuilderTeardown();
    TestStaleTokenRejectedAfterSlotReuse();
    TestBoundStreamIdentityIsRequired();
    TestThousandsOfRegistrationsReuseSlots();
    TestConcurrentRetirementWaitsForInvocationQuiescence();
    TestGenerationWrapPermanentlyExhaustsSlots();
    TestTokenPortabilityAndValidation();
    TestCallbackPathHasNoAllocatorDependency();
    TestTrueLiveExhaustionIsBoundedAndExplicit();

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
