#include "hooks/aaudio_callback_registry.h"

namespace echidna::hooks
{

    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "AAudio callback registry requires lock-free 32-bit atomics");
    static_assert(sizeof(uintptr_t) >= sizeof(uint32_t),
                  "AAudio callback tokens require at least 32-bit uintptr_t");
    static_assert(AAudioCallbackRegistry::kMaxRegistrations == 256,
                  "AAudio callback token layout reserves exactly eight slot bits");

    AAudioCallbackRegistry::MaintenanceGuard::MaintenanceGuard(
        AAudioCallbackRegistry &registry)
        : registry_(registry)
    {
        registry_.lockMaintenance();
    }

    AAudioCallbackRegistry::MaintenanceGuard::~MaintenanceGuard()
    {
        registry_.unlockMaintenance();
    }

    void AAudioCallbackRegistry::lockMaintenance()
    {
        uint32_t expected = 0;
        while (!maintenance_gate_.compare_exchange_weak(expected,
                                                        1,
                                                        std::memory_order_acquire,
                                                        std::memory_order_relaxed))
        {
            expected = 0;
        }
    }

    void AAudioCallbackRegistry::unlockMaintenance()
    {
        maintenance_gate_.store(0, std::memory_order_release);
    }

    void *AAudioCallbackRegistry::tokenFor(size_t slot, uint32_t generation) const
    {
        if (slot >= kMaxRegistrations || generation == 0 ||
            generation > kMaxGeneration)
        {
            return nullptr;
        }
        const uintptr_t token =
            (static_cast<uintptr_t>(generation) << kTokenGenerationShift) |
            static_cast<uintptr_t>(slot);
        return reinterpret_cast<void *>(token);
    }

    AAudioCallbackRegistry::Context *AAudioCallbackRegistry::contextFor(
        void *proxy_context,
        uint32_t *generation)
    {
        if (!proxy_context || !generation)
        {
            return nullptr;
        }
        const uintptr_t token = reinterpret_cast<uintptr_t>(proxy_context);
        const uintptr_t token_generation = token >> kTokenGenerationShift;
        if (token_generation == 0 || token_generation > kMaxGeneration)
        {
            return nullptr;
        }
        const size_t slot = static_cast<size_t>(token & 0xFFU);
        if (slot >= contexts_.size())
        {
            return nullptr;
        }
        *generation = static_cast<uint32_t>(token_generation);
        return &contexts_[slot];
    }

    void AAudioCallbackRegistry::finishRetirementLocked(Context &context)
    {
        if (!context.allocated)
        {
            return;
        }
        const uint32_t usage = context.usage.load(std::memory_order_acquire);
        if ((usage & kActiveMask) != 0 || (usage & kInvocationMask) != 0)
        {
            return;
        }

        context.callback = nullptr;
        context.user_data = nullptr;
        context.builder = nullptr;
        context.streams.fill(nullptr);
        context.stream_overflow = false;
        context.allocated = false;

        if (context.generation.load(std::memory_order_relaxed) >= kMaxGeneration)
        {
            // Never publish generation zero or allow an old token to become
            // current again after the 24-bit portable generation space ends.
            context.usage.store(kExhaustedMask, std::memory_order_release);
        }
        else
        {
            context.usage.store(0, std::memory_order_release);
        }
    }

    void AAudioCallbackRegistry::tryRetireLocked(Context &context)
    {
        if (!context.allocated || context.builder || context.stream_overflow)
        {
            return;
        }
        for (void *stream : context.streams)
        {
            if (stream)
            {
                return;
            }
        }

        uint32_t usage = context.usage.load(std::memory_order_acquire);
        while ((usage & kActiveMask) != 0 &&
               !context.usage.compare_exchange_weak(
                   usage,
                   usage & ~kActiveMask,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire))
        {
        }

        // Clearing active and retaining the count in the same atomic word
        // prevents new entrants. Reuse is allowed only after an acquire
        // recheck observes every in-flight callback has released its count.
        if ((context.usage.load(std::memory_order_acquire) & kInvocationMask) == 0)
        {
            finishRetirementLocked(context);
        }
    }

    void AAudioCallbackRegistry::retireBuilderLocked(void *builder)
    {
        for (auto &context : contexts_)
        {
            if (!context.allocated || context.builder != builder)
            {
                continue;
            }
            context.builder = nullptr;
            tryRetireLocked(context);
        }
    }

    void *AAudioCallbackRegistry::registerCallback(void *builder,
                                                   AAudioDataCallback callback,
                                                   void *user_data)
    {
        if (!builder || !callback)
        {
            return nullptr;
        }

        MaintenanceGuard guard(*this);
        retireBuilderLocked(builder);
        for (auto &context : contexts_)
        {
            finishRetirementLocked(context);
        }

        for (size_t slot = 0; slot < contexts_.size(); ++slot)
        {
            Context &context = contexts_[slot];
            if (context.allocated)
            {
                continue;
            }
            const uint32_t usage = context.usage.load(std::memory_order_acquire);
            if (usage == kExhaustedMask)
            {
                continue;
            }
            if (usage != 0)
            {
                continue;
            }

            const uint32_t previous_generation =
                context.generation.load(std::memory_order_relaxed);
            if (previous_generation >= kMaxGeneration)
            {
                context.usage.store(kExhaustedMask, std::memory_order_release);
                continue;
            }
            const uint32_t generation = previous_generation + 1;
            context.allocated = true;
            context.callback = callback;
            context.user_data = user_data;
            context.builder = builder;
            context.streams.fill(nullptr);
            context.stream_overflow = false;
            context.generation.store(generation, std::memory_order_relaxed);
            context.usage.store(kActiveMask, std::memory_order_release);
            return tokenFor(slot, generation);
        }
        return nullptr;
    }

    void AAudioCallbackRegistry::attachStreamLocked(Context &context, void *stream)
    {
        if (!stream)
        {
            return;
        }
        for (void *entry : context.streams)
        {
            if (entry == stream)
            {
                return;
            }
        }
        for (void *&entry : context.streams)
        {
            if (!entry)
            {
                entry = stream;
                return;
            }
        }
        // An untracked stream can retain this token indefinitely, so fail
        // closed by never recycling this slot rather than risk stale dispatch.
        context.stream_overflow = true;
    }

    bool AAudioCallbackRegistry::attachOpenedStream(void *builder, void *stream)
    {
        if (!builder || !stream)
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        for (auto &context : contexts_)
        {
            if (context.allocated && context.builder == builder &&
                (context.usage.load(std::memory_order_acquire) & kActiveMask) != 0)
            {
                attachStreamLocked(context, stream);
                return true;
            }
        }
        return false;
    }

    bool AAudioCallbackRegistry::beginInvocation(void *proxy_context,
                                                 void *stream,
                                                 AAudioCallbackTarget *target)
    {
        (void)stream;
        if (!target)
        {
            return false;
        }
        target->callback = nullptr;
        target->user_data = nullptr;

        uint32_t token_generation = 0;
        Context *context = contextFor(proxy_context, &token_generation);
        if (!context)
        {
            return false;
        }

        uint32_t usage = context->usage.load(std::memory_order_acquire);
        while ((usage & kActiveMask) != 0)
        {
            if ((usage & kInvocationMask) == kInvocationMask)
            {
                return false;
            }
            if (context->usage.compare_exchange_weak(usage,
                                                     usage + 1,
                                                     std::memory_order_acq_rel,
                                                     std::memory_order_acquire))
            {
                if (context->generation.load(std::memory_order_acquire) !=
                    token_generation)
                {
                    context->usage.fetch_sub(1, std::memory_order_acq_rel);
                    return false;
                }
                target->callback = context->callback;
                target->user_data = context->user_data;
                if (!target->callback)
                {
                    context->usage.fetch_sub(1, std::memory_order_acq_rel);
                    target->user_data = nullptr;
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    void AAudioCallbackRegistry::endInvocation(void *proxy_context)
    {
        uint32_t token_generation = 0;
        Context *context = contextFor(proxy_context, &token_generation);
        if (!context ||
            context->generation.load(std::memory_order_acquire) != token_generation)
        {
            return;
        }

        uint32_t usage = context->usage.load(std::memory_order_acquire);
        while ((usage & kInvocationMask) != 0)
        {
            if (context->usage.compare_exchange_weak(usage,
                                                     usage - 1,
                                                     std::memory_order_acq_rel,
                                                     std::memory_order_acquire))
            {
                return;
            }
        }
    }

    void AAudioCallbackRegistry::retireBuilder(void *builder)
    {
        if (!builder)
        {
            return;
        }
        MaintenanceGuard guard(*this);
        retireBuilderLocked(builder);
    }

    void AAudioCallbackRegistry::closeStream(void *stream)
    {
        if (!stream)
        {
            return;
        }
        MaintenanceGuard guard(*this);
        for (auto &context : contexts_)
        {
            if (!context.allocated)
            {
                continue;
            }
            for (void *&entry : context.streams)
            {
                if (entry == stream)
                {
                    entry = nullptr;
                }
            }
            tryRetireLocked(context);
        }
    }

#if defined(ECHIDNA_AAUDIO_REGISTRY_TESTING)
    bool AAudioCallbackRegistry::setGenerationForTesting(size_t slot,
                                                         uint32_t generation)
    {
        if (slot >= contexts_.size() || generation > kMaxGeneration)
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        Context &context = contexts_[slot];
        finishRetirementLocked(context);
        if (context.allocated || context.usage.load(std::memory_order_acquire) != 0)
        {
            return false;
        }
        context.generation.store(generation, std::memory_order_relaxed);
        return true;
    }
#endif

} // namespace echidna::hooks
