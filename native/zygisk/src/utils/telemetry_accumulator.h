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

    // Mutually exclusive per-block classifications. Every processed block is
    // exactly one of these; they never overlap. `kUnchanged` is the implicit
    // remainder (processed, but the DSP produced output bit-equal to input),
    // so it has no dedicated counter and is derived as
    // blocks - mutations - bypasses - failures. This distinction is deliberate:
    // "processed" (blocks) is not "mutated" (mutations).
    enum class TelemetryBlockOutcome : uint8_t
    {
        kUnchanged, // processed, output == input (no audible change)
        kMutated,   // processed, output != input (audio actually altered)
        kBypassed,  // admitted but intentionally not processed (bypass/policy)
        kFailure,   // block could not be processed; original audio preserved
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
        // Latched hook/install LEVEL (route-presence). Unlike the counters
        // above, this is a current-state bit, not a drainable edge: take()
        // reports it but does NOT clear it, and it does not, on its own, make
        // the delta pending() (route-presence != route-use). All the uint32_t
        // fields are edges (deltas since the last take()).
        bool installed{false};

        // True only when there is unsent USE evidence to export. Note the
        // latched `installed` level is intentionally excluded: an installed
        // route that has processed nothing must not spuriously emit.
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

        // Realtime-safe: lock-free relaxed atomics only, no allocation, no
        // logging, no PCM retained. `frames` is a COUNT; sample data is never
        // passed in or stored, so telemetry cannot leak audio content.
        void recordBlock(TelemetryRoute route,
                         uint32_t frames,
                         TelemetryBlockOutcome outcome) noexcept;
        // Records a hook install/attach LEVEL transition for a route. This is
        // orthogonal to block processing: it never touches blocks/frames/
        // mutations/bypasses, so an install ("hooked") signal never implies a
        // "mutated" signal. A failed install (success=false) increments the
        // dedicated `install_failures` counter, NOT the block `failures`
        // counter, so an attach failure is never mistaken for a block-processing
        // failure downstream.
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
