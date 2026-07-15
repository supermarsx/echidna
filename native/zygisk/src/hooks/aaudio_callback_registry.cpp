#include "hooks/aaudio_callback_registry.h"

namespace echidna::hooks
{

    static_assert(std::atomic<void *>::is_always_lock_free,
                  "AAudio callback registry requires lock-free pointer atomics");
    static_assert(std::atomic<AAudioDataCallback>::is_always_lock_free,
                  "AAudio callback registry requires lock-free callback atomics");

    AAudioCallbackRegistry::Context *AAudioCallbackRegistry::contextFor(void *proxy_context)
    {
        if (!proxy_context)
        {
            return nullptr;
        }
        const uintptr_t address = reinterpret_cast<uintptr_t>(proxy_context);
        const uintptr_t begin = reinterpret_cast<uintptr_t>(contexts_.data());
        const uintptr_t end = begin + sizeof(contexts_);
        if (address < begin || address >= end || ((address - begin) % sizeof(Context)) != 0)
        {
            return nullptr;
        }
        return reinterpret_cast<Context *>(proxy_context);
    }

    void *AAudioCallbackRegistry::registerCallback(void *builder,
                                                   AAudioDataCallback callback,
                                                   void *user_data)
    {
        if (!builder || !callback)
        {
            return nullptr;
        }
        retireBuilder(builder);
        for (auto &context : contexts_)
        {
            bool expected = false;
            if (!context.claimed.compare_exchange_strong(expected,
                                                         true,
                                                         std::memory_order_acq_rel,
                                                         std::memory_order_relaxed))
            {
                continue;
            }
            context.invocations.store(0, std::memory_order_relaxed);
            context.builder.store(builder, std::memory_order_relaxed);
            context.callback.store(callback, std::memory_order_relaxed);
            context.user_data.store(user_data, std::memory_order_relaxed);
            context.stream_overflow.store(false, std::memory_order_relaxed);
            for (auto &stream : context.streams)
            {
                stream.store(nullptr, std::memory_order_relaxed);
            }
            context.active.store(true, std::memory_order_release);
            return &context;
        }
        return nullptr;
    }

    void AAudioCallbackRegistry::attachStream(Context &context, void *stream)
    {
        if (!stream)
        {
            return;
        }
        for (auto &entry : context.streams)
        {
            if (entry.load(std::memory_order_acquire) == stream)
            {
                return;
            }
        }
        for (auto &entry : context.streams)
        {
            void *expected = nullptr;
            if (entry.compare_exchange_strong(expected,
                                              stream,
                                              std::memory_order_acq_rel,
                                              std::memory_order_relaxed) ||
                expected == stream)
            {
                return;
            }
        }
        // Keep this context permanently retired after its known streams close;
        // an untracked stream may still legally reference the proxy context.
        context.stream_overflow.store(true, std::memory_order_release);
    }

    bool AAudioCallbackRegistry::attachOpenedStream(void *builder, void *stream)
    {
        if (!builder || !stream)
        {
            return false;
        }
        for (auto &context : contexts_)
        {
            if (context.active.load(std::memory_order_acquire) &&
                context.builder.load(std::memory_order_acquire) == builder)
            {
                attachStream(context, stream);
                return true;
            }
        }
        return false;
    }

    bool AAudioCallbackRegistry::beginInvocation(void *proxy_context,
                                                 void *stream,
                                                 AAudioCallbackTarget *target)
    {
        Context *context = contextFor(proxy_context);
        if (!context || !target || !context->active.load(std::memory_order_acquire))
        {
            return false;
        }
        context->invocations.fetch_add(1, std::memory_order_acq_rel);
        if (!context->active.load(std::memory_order_acquire))
        {
            context->invocations.fetch_sub(1, std::memory_order_acq_rel);
            return false;
        }
        attachStream(*context, stream);
        target->callback = context->callback.load(std::memory_order_acquire);
        target->user_data = context->user_data.load(std::memory_order_acquire);
        if (!target->callback)
        {
            endInvocation(proxy_context);
            return false;
        }
        return true;
    }

    void AAudioCallbackRegistry::endInvocation(void *proxy_context)
    {
        Context *context = contextFor(proxy_context);
        if (!context)
        {
            return;
        }
        const uint32_t previous =
            context->invocations.fetch_sub(1, std::memory_order_acq_rel);
        (void)previous;
    }

    void AAudioCallbackRegistry::tryRetire(Context &context)
    {
        if (!context.active.load(std::memory_order_acquire) ||
            context.builder.load(std::memory_order_acquire) != nullptr ||
            context.stream_overflow.load(std::memory_order_acquire))
        {
            return;
        }
        for (const auto &stream : context.streams)
        {
            if (stream.load(std::memory_order_acquire) != nullptr)
            {
                return;
            }
        }
        bool expected = true;
        if (!context.active.compare_exchange_strong(expected,
                                                    false,
                                                    std::memory_order_acq_rel,
                                                    std::memory_order_relaxed))
        {
            return;
        }
        // Context addresses are never reused, and callback/user_data remain
        // immutable after publication. An invocation which won the race with
        // retirement can therefore finish safely without waiting here. New
        // invocations fail the active check above.
    }

    void AAudioCallbackRegistry::retireBuilder(void *builder)
    {
        if (!builder)
        {
            return;
        }
        for (auto &context : contexts_)
        {
            if (!context.active.load(std::memory_order_acquire) ||
                context.builder.load(std::memory_order_acquire) != builder)
            {
                continue;
            }
            context.builder.store(nullptr, std::memory_order_release);
            tryRetire(context);
        }
    }

    void AAudioCallbackRegistry::closeStream(void *stream)
    {
        if (!stream)
        {
            return;
        }
        for (auto &context : contexts_)
        {
            if (!context.active.load(std::memory_order_acquire))
            {
                continue;
            }
            for (auto &entry : context.streams)
            {
                void *expected = stream;
                (void)entry.compare_exchange_strong(expected,
                                                    nullptr,
                                                    std::memory_order_acq_rel,
                                                    std::memory_order_relaxed);
            }
            tryRetire(context);
        }
    }

} // namespace echidna::hooks
