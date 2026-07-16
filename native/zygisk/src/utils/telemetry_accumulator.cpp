#include "utils/telemetry_accumulator.h"

namespace echidna::utils
{
    namespace
    {
        thread_local TelemetryRoute g_current_route = TelemetryRoute::kApi;

        size_t RouteIndex(TelemetryRoute route) noexcept
        {
            const size_t index = static_cast<size_t>(route);
            return index < static_cast<size_t>(TelemetryRoute::kCount)
                       ? index
                       : static_cast<size_t>(TelemetryRoute::kUnknown);
        }
    } // namespace

    void TelemetryDelta::merge(const TelemetryDelta &other) noexcept
    {
        blocks += other.blocks;
        frames += other.frames;
        failures += other.failures;
        mutations += other.mutations;
        bypasses += other.bypasses;
        install_events += other.install_events;
        install_failures += other.install_failures;
        installed = other.installed;
    }

    void TelemetryDelta::clear() noexcept
    {
        blocks = 0;
        frames = 0;
        failures = 0;
        mutations = 0;
        bypasses = 0;
        install_events = 0;
        install_failures = 0;
    }

    void TelemetryAccumulator::recordBlock(TelemetryRoute route,
                                           uint32_t frames,
                                           TelemetryBlockOutcome outcome) noexcept
    {
        Counters &counters = counters_[RouteIndex(route)];
        counters.blocks.fetch_add(1, std::memory_order_relaxed);
        counters.frames.fetch_add(frames, std::memory_order_relaxed);
        switch (outcome)
        {
        case TelemetryBlockOutcome::kMutated:
            counters.mutations.fetch_add(1, std::memory_order_relaxed);
            break;
        case TelemetryBlockOutcome::kBypassed:
            counters.bypasses.fetch_add(1, std::memory_order_relaxed);
            break;
        case TelemetryBlockOutcome::kFailure:
            counters.failures.fetch_add(1, std::memory_order_relaxed);
            break;
        case TelemetryBlockOutcome::kUnchanged:
            break;
        }
    }

    void TelemetryAccumulator::recordInstall(TelemetryRoute route, bool success) noexcept
    {
        Counters &counters = counters_[RouteIndex(route)];
        counters.installed.store(success ? 1u : 0u, std::memory_order_relaxed);
        counters.install_events.fetch_add(1, std::memory_order_relaxed);
        if (!success)
        {
            // An attach failure is NOT a block-processing failure: keep it in the
            // dedicated install_failures counter so the wire `failures` counter is
            // never inflated by a hook that never attached.
            counters.install_failures.fetch_add(1, std::memory_order_relaxed);
        }
    }

    TelemetryDelta TelemetryAccumulator::take(TelemetryRoute route) noexcept
    {
        const TelemetryRoute normalized =
            RouteIndex(route) == static_cast<size_t>(route) ? route : TelemetryRoute::kUnknown;
        Counters &counters = counters_[RouteIndex(normalized)];
        TelemetryDelta delta;
        delta.route = normalized;
        delta.blocks = counters.blocks.exchange(0, std::memory_order_acq_rel);
        delta.frames = counters.frames.exchange(0, std::memory_order_acq_rel);
        delta.failures = counters.failures.exchange(0, std::memory_order_acq_rel);
        delta.mutations = counters.mutations.exchange(0, std::memory_order_acq_rel);
        delta.bypasses = counters.bypasses.exchange(0, std::memory_order_acq_rel);
        delta.install_events = counters.install_events.exchange(0, std::memory_order_acq_rel);
        delta.install_failures = counters.install_failures.exchange(0, std::memory_order_acq_rel);
        delta.installed = counters.installed.load(std::memory_order_relaxed) != 0;
        return delta;
    }

    ScopedTelemetryRoute::ScopedTelemetryRoute(TelemetryRoute route) noexcept
        : previous_(g_current_route)
    {
        g_current_route = route;
    }

    ScopedTelemetryRoute::~ScopedTelemetryRoute() noexcept
    {
        g_current_route = previous_;
    }

    TelemetryRoute CurrentTelemetryRoute() noexcept
    {
        return g_current_route;
    }

    const char *TelemetryRouteName(TelemetryRoute route) noexcept
    {
        switch (route)
        {
        case TelemetryRoute::kAAudio:
            return "aaudio";
        case TelemetryRoute::kAudioRecord:
            return "audiorecord";
        case TelemetryRoute::kOpenSl:
            return "opensl";
        case TelemetryRoute::kTinyAlsa:
            return "tinyalsa";
        case TelemetryRoute::kLibcRead:
            return "libc_read";
        case TelemetryRoute::kApi:
            return "api";
        case TelemetryRoute::kLsposed:
            return "lsposed";
        case TelemetryRoute::kPreprocessor:
            return "preprocessor";
        case TelemetryRoute::kUnknown:
        case TelemetryRoute::kCount:
            return "unknown";
        }
        return "unknown";
    }

} // namespace echidna::utils
