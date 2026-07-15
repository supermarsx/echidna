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

    private:
        struct alignas(64) Context
        {
            std::atomic<bool> claimed{false};
            std::atomic<bool> active{false};
            std::atomic<uint32_t> invocations{0};
            std::atomic<void *> builder{nullptr};
            std::atomic<AAudioDataCallback> callback{nullptr};
            std::atomic<void *> user_data{nullptr};
            std::array<std::atomic<void *>, kMaxStreamsPerRegistration> streams{};
            std::atomic<bool> stream_overflow{false};
        };

        Context *contextFor(void *proxy_context);
        void attachStream(Context &context, void *stream);
        void tryRetire(Context &context);

        std::array<Context, kMaxRegistrations> contexts_{};
    };

} // namespace echidna::hooks
