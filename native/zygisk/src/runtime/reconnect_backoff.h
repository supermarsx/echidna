#pragma once

#include <algorithm>
#include <chrono>
#include <cstdint>

namespace echidna::runtime
{
    /** Bounded exponential reconnect delay with deterministic per-process jitter. */
    class ReconnectBackoff
    {
    public:
        explicit ReconnectBackoff(uint32_t seed) : jitter_state_(seed == 0 ? 1 : seed) {}

        [[nodiscard]] std::chrono::milliseconds nextDelay()
        {
            // Xorshift32 is sufficient here: jitter only spreads reconnect wakeups;
            // it is not a security primitive.
            jitter_state_ ^= jitter_state_ << 13;
            jitter_state_ ^= jitter_state_ >> 17;
            jitter_state_ ^= jitter_state_ << 5;
            const int64_t jitter_span = std::max<int64_t>(1, current_.count() / 4);
            const auto jitter = std::chrono::milliseconds(
                static_cast<int64_t>(jitter_state_ % static_cast<uint32_t>(jitter_span + 1)));
            return std::min(current_ + jitter, kMaximumDelay);
        }

        void recordFailure()
        {
            current_ = std::min(current_ * 2, kMaximumDelay);
        }

        void reset()
        {
            current_ = kInitialDelay;
        }

        static constexpr auto kInitialDelay = std::chrono::milliseconds(250);
        static constexpr auto kMaximumDelay = std::chrono::milliseconds(30000);

    private:
        std::chrono::milliseconds current_{kInitialDelay};
        uint32_t jitter_state_;
    };

} // namespace echidna::runtime
