#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace echidna::hooks
{

    using AAudioDataCallback = int (*)(void *stream,
                                       void *user_data,
                                       void *audio_data,
                                       int32_t frames);

    struct AAudioCallbackTarget
    {
        AAudioDataCallback callback{nullptr};
        void *user_data{nullptr};
    };

    class AAudioCallbackRegistry
    {
    public:
        static constexpr size_t kMaxRegistrations = 256;
        static constexpr size_t kMaxStreamsPerRegistration = 8;
        static constexpr uint32_t kMaxGeneration = 0x00FFFFFFU;

        void *registerCallback(void *builder,
                               AAudioDataCallback callback,
                               void *user_data);
        bool attachOpenedStream(void *builder, void *stream);
        bool beginInvocation(void *proxy_context,
                             void *stream,
                             AAudioCallbackTarget *target);
        void endInvocation(void *proxy_context);
        void retireBuilder(void *builder);
        void closeStream(void *stream);

#if defined(ECHIDNA_AAUDIO_REGISTRY_TESTING)
        bool setGenerationForTesting(size_t slot, uint32_t generation);
#endif

    private:
        static constexpr uint32_t kActiveMask = 0x80000000U;
        static constexpr uint32_t kExhaustedMask = 0x40000000U;
        static constexpr uint32_t kInvocationMask = 0x3FFFFFFFU;
        static constexpr unsigned kTokenGenerationShift = 8;

        struct alignas(64) Context
        {
            // Active and invocation count share one word so retirement cannot
            // miss a callback delayed between observing active and acquiring a
            // reference. Callback threads touch only these 32-bit atomics.
            std::atomic<uint32_t> usage{0};
            std::atomic<uint32_t> generation{0};

            // The remaining fields are published before kActiveMask with a
            // release store and are not changed until usage reaches zero.
            AAudioDataCallback callback{nullptr};
            void *user_data{nullptr};
            void *builder{nullptr};
            std::array<std::atomic<void *>, kMaxStreamsPerRegistration> streams{};
            std::atomic<uint32_t> stream_tracking_started{0};
            bool allocated{false};
            bool stream_overflow{false};
        };

        class MaintenanceGuard
        {
        public:
            explicit MaintenanceGuard(AAudioCallbackRegistry &registry);
            ~MaintenanceGuard();

            MaintenanceGuard(const MaintenanceGuard &) = delete;
            MaintenanceGuard &operator=(const MaintenanceGuard &) = delete;

        private:
            AAudioCallbackRegistry &registry_;
        };

        Context *contextFor(void *proxy_context, uint32_t *generation);
        void *tokenFor(size_t slot, uint32_t generation) const;
        void attachStreamLocked(Context &context, void *stream);
        void retireBuilderLocked(void *builder);
        void tryRetireLocked(Context &context);
        void finishRetirementLocked(Context &context);
        void lockMaintenance();
        void unlockMaintenance();

        std::atomic<uint32_t> maintenance_gate_{0};
        std::array<Context, kMaxRegistrations> contexts_{};
    };

} // namespace echidna::hooks
