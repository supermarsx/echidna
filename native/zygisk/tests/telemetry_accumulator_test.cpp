#include "utils/telemetry_accumulator.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>

namespace
{
    std::atomic<uint32_t> g_allocations{0};
    int g_failures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++g_failures;
        }
    }
}

void *operator new(std::size_t size)
{
    g_allocations.fetch_add(1, std::memory_order_relaxed);
    if (void *memory = std::malloc(size))
    {
        return memory;
    }
    throw std::bad_alloc();
}

void operator delete(void *memory) noexcept
{
    std::free(memory);
}

void operator delete(void *memory, std::size_t) noexcept
{
    std::free(memory);
}

int main()
{
    using namespace echidna::utils;
    TelemetryAccumulator accumulator;
    const uint32_t before = g_allocations.load(std::memory_order_relaxed);
    accumulator.recordBlock(TelemetryRoute::kAAudio,
                            std::numeric_limits<uint32_t>::max(),
                            TelemetryBlockOutcome::kMutated);
    accumulator.recordBlock(TelemetryRoute::kAAudio,
                            2,
                            TelemetryBlockOutcome::kFailure);
    accumulator.recordInstall(TelemetryRoute::kAAudio, true);
    const uint32_t after = g_allocations.load(std::memory_order_relaxed);
    Check(before == after, "realtime record operations must not allocate");

    const TelemetryDelta delta = accumulator.take(TelemetryRoute::kAAudio);
    Check(delta.blocks == 2, "block deltas must accumulate");
    Check(delta.frames == 1, "uint32 frame counters must wrap modulo 2^32");
    Check(delta.failures == 1, "failures must be counted");
    Check(delta.mutations == 1, "mutations must be counted separately");
    Check(delta.install_events == 1 && delta.installed,
          "install state must be retained with a bounded event delta");
    Check(!accumulator.take(TelemetryRoute::kAAudio).pending(),
          "take must atomically drain deltas");

    {
        ScopedTelemetryRoute outer(TelemetryRoute::kTinyAlsa);
        Check(CurrentTelemetryRoute() == TelemetryRoute::kTinyAlsa,
              "route scope must select the capture route");
        {
            ScopedTelemetryRoute inner(TelemetryRoute::kOpenSl);
            Check(CurrentTelemetryRoute() == TelemetryRoute::kOpenSl,
                  "nested route scope must override locally");
        }
        Check(CurrentTelemetryRoute() == TelemetryRoute::kTinyAlsa,
              "nested route scope must restore its parent");
    }
    Check(CurrentTelemetryRoute() == TelemetryRoute::kApi,
          "route scope must restore the API default");

    TelemetryDelta pending;
    pending.route = TelemetryRoute::kLibcRead;
    pending.blocks = std::numeric_limits<uint32_t>::max();
    TelemetryDelta newer;
    newer.route = TelemetryRoute::kLibcRead;
    newer.blocks = 2;
    newer.mutations = 1;
    pending.merge(newer);
    Check(pending.blocks == 1 && pending.mutations == 1,
          "coalesced unsent deltas must use documented modulo arithmetic");

    if (g_failures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "telemetry_accumulator_test: all checks passed\n");
    return 0;
}
