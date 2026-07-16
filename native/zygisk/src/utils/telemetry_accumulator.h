#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace echidna::utils
{
    enum class TelemetryRoute : uint8_t
    {
        kAAudio = 0,
        kAudioRecord,
        kOpenSl,
        kTinyAlsa,
        kLibcRead,
        kApi,
        kUnknown,
        kLsposed,
        kPreprocessor,
        kCount,
    };

    enum class TelemetryBlockOutcome : uint8_t
    {
        kUnchanged,
        kMutated,
        kBypassed,
        kFailure,
    };

    struct TelemetryDelta
    {
        TelemetryRoute route{TelemetryRoute::kUnknown};
        uint32_t blocks{0};
        uint32_t frames{0};
        // Block-PROCESSING failures only (a block could not be processed and the
        // original audio was preserved). Install/attach failures are counted
        // separately in `install_failures`, so this counter is never inflated by
        // a hook that failed to install.
        uint32_t failures{0};
        uint32_t mutations{0};
        uint32_t bypasses{0};
        uint32_t install_events{0};
        // Hook/attach INSTALL failures (recordInstall(success=false)). Kept
        // distinct from the block-processing `failures` counter above so a
        // consumer can tell "the route never attached" from "a block failed to
        // process". This is an edge (drained by take()), like install_events.
        uint32_t install_failures{0};
        bool installed{false};

        [[nodiscard]] bool pending() const noexcept
        {
            return blocks != 0 || frames != 0 || failures != 0 || mutations != 0 ||
                   bypasses != 0 || install_events != 0 || install_failures != 0;
        }

        void merge(const TelemetryDelta &other) noexcept;
        void clear() noexcept;
    };

    class TelemetryAccumulator
    {
    public:
        TelemetryAccumulator() noexcept = default;

        void recordBlock(TelemetryRoute route,
                         uint32_t frames,
                         TelemetryBlockOutcome outcome) noexcept;
        void recordInstall(TelemetryRoute route, bool success) noexcept;
        [[nodiscard]] TelemetryDelta take(TelemetryRoute route) noexcept;

    private:
        struct alignas(64) Counters
        {
            std::atomic<uint32_t> blocks{0};
            std::atomic<uint32_t> frames{0};
            std::atomic<uint32_t> failures{0};
            std::atomic<uint32_t> mutations{0};
            std::atomic<uint32_t> bypasses{0};
            std::atomic<uint32_t> install_events{0};
            std::atomic<uint32_t> install_failures{0};
            std::atomic<uint32_t> installed{0};
        };

        static_assert(std::atomic<uint32_t>::is_always_lock_free,
                      "audio telemetry requires lock-free 32-bit atomics");
        std::array<Counters, static_cast<size_t>(TelemetryRoute::kCount)> counters_{};
    };

    class ScopedTelemetryRoute
    {
    public:
        explicit ScopedTelemetryRoute(TelemetryRoute route) noexcept;
        ~ScopedTelemetryRoute() noexcept;

        ScopedTelemetryRoute(const ScopedTelemetryRoute &) = delete;
        ScopedTelemetryRoute &operator=(const ScopedTelemetryRoute &) = delete;

    private:
        TelemetryRoute previous_;
    };

    [[nodiscard]] TelemetryRoute CurrentTelemetryRoute() noexcept;
    [[nodiscard]] const char *TelemetryRouteName(TelemetryRoute route) noexcept;

} // namespace echidna::utils
