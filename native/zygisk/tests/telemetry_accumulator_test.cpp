#include "utils/telemetry_accumulator.h"

#include <atomic>
#include <cstddef>
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
} // namespace

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

    // ------------------------------------------------------------------
    // Section A — the four block outcomes are independent and correct, and
    // "unchanged" is the exact derivable remainder (block count != mutation).
    // ------------------------------------------------------------------
    {
        const auto route = TelemetryRoute::kOpenSl;
        for (int i = 0; i < 5; ++i)
        {
            accumulator.recordBlock(route, 10, TelemetryBlockOutcome::kUnchanged);
        }
        for (int i = 0; i < 3; ++i)
        {
            accumulator.recordBlock(route, 10, TelemetryBlockOutcome::kMutated);
        }
        for (int i = 0; i < 2; ++i)
        {
            accumulator.recordBlock(route, 10, TelemetryBlockOutcome::kBypassed);
        }
        for (int i = 0; i < 4; ++i)
        {
            accumulator.recordBlock(route, 10, TelemetryBlockOutcome::kFailure);
        }
        const TelemetryDelta d = accumulator.take(route);
        Check(d.blocks == 14, "every processed block increments the block count");
        Check(d.frames == 140, "frames accumulate for every outcome, including bypass/failure");
        Check(d.mutations == 3, "only mutated blocks increment mutations");
        Check(d.bypasses == 2, "only bypassed blocks increment bypasses");
        Check(d.failures == 4, "only failed blocks increment failures");
        // Unchanged is not stored; it is the exact remainder.
        const uint32_t unchanged = d.blocks - d.mutations - d.bypasses - d.failures;
        Check(unchanged == 5, "unchanged == blocks - mutations - bypasses - failures");
        // Independence: each classifier counts only its own outcome.
        Check(d.mutations + d.bypasses + d.failures + unchanged == d.blocks,
              "the four outcome classes partition the block count exactly");
    }

    // Recording a single outcome must leave the sibling counters at zero.
    {
        const auto route = TelemetryRoute::kAudioRecord;
        accumulator.recordBlock(route, 7, TelemetryBlockOutcome::kMutated);
        const TelemetryDelta d = accumulator.take(route);
        Check(d.blocks == 1 && d.mutations == 1, "a mutated block counts as one mutation");
        Check(d.bypasses == 0 && d.failures == 0,
              "a mutated block must not touch bypass or failure counters");
        Check(!d.installed && d.install_events == 0,
              "processing a block must not fabricate an install/hook signal");
    }

    // ------------------------------------------------------------------
    // Section B — "hooked != mutated": an install signal implies no block
    // processing and no mutation, and vice-versa.
    // ------------------------------------------------------------------
    {
        const auto route = TelemetryRoute::kLsposed;
        accumulator.recordInstall(route, true);
        const TelemetryDelta d = accumulator.take(route);
        Check(d.installed, "a successful install latches the installed level");
        Check(d.install_events == 1, "install produces exactly one install event");
        Check(d.blocks == 0 && d.frames == 0,
              "install alone must not imply any processed block");
        Check(d.mutations == 0 && d.bypasses == 0 && d.failures == 0,
              "a hooked/installed signal must not imply a mutated/bypassed/failed signal");
    }
    {
        // The converse: mutation without any install signal.
        const auto route = TelemetryRoute::kPreprocessor;
        accumulator.recordBlock(route, 64, TelemetryBlockOutcome::kMutated);
        const TelemetryDelta d = accumulator.take(route);
        Check(d.mutations == 1 && d.blocks == 1, "mutation is recorded without any install");
        Check(!d.installed && d.install_events == 0,
              "a mutation must not imply the route was ever installed/hooked");
    }

    // ------------------------------------------------------------------
    // Section C — `installed` is a latched LEVEL (route-presence); the uint32
    // counters are drainable EDGES (route-use). take() clears edges but not
    // the level, and the level alone never marks the delta pending().
    // ------------------------------------------------------------------
    {
        const auto route = TelemetryRoute::kLibcRead;
        accumulator.recordInstall(route, true);
        accumulator.recordBlock(route, 1, TelemetryBlockOutcome::kUnchanged);
        const TelemetryDelta first = accumulator.take(route);
        Check(first.installed && first.install_events == 1 && first.blocks == 1,
              "first drain reports install level, install edge and use edge");
        const TelemetryDelta second = accumulator.take(route);
        Check(second.installed,
              "installed level persists across drains (route stays present)");
        Check(second.install_events == 0 && second.blocks == 0 && second.frames == 0,
              "all edges are drained by the previous take()");
        Check(!second.pending(),
              "an installed route with no new use must not be pending (presence != use)");
    }

    // ------------------------------------------------------------------
    // Section D — install FAILURE semantics (F1 fix). An install/attach failure
    // increments the dedicated `install_failures` counter and MUST NOT fold into
    // the block-processing `failures` counter, so the two remain independently
    // observable. See docs/hardening/evidence-state-model.md.
    // ------------------------------------------------------------------
    {
        const auto route = TelemetryRoute::kUnknown;
        accumulator.recordInstall(route, false);
        const TelemetryDelta d = accumulator.take(route);
        Check(!d.installed, "a failed install must not latch the installed level");
        Check(d.install_events == 1, "a failed install still records one install event");
        Check(d.blocks == 0 && d.mutations == 0 && d.bypasses == 0,
              "a failed install processed no audio blocks");
        Check(d.install_failures == 1,
              "F1: an install failure increments the dedicated install_failures counter");
        Check(d.failures == 0,
              "F1: an install failure must NOT fold into the block-processing failures counter");
        Check(d.pending(),
              "an install failure is unsent evidence and must mark the delta pending");
    }

    // A block-processing failure and an install failure on the same route must
    // land in their own counters without cross-contaminating.
    {
        const auto route = TelemetryRoute::kApi;
        accumulator.recordBlock(route, 8, TelemetryBlockOutcome::kFailure);
        accumulator.recordInstall(route, false);
        const TelemetryDelta d = accumulator.take(route);
        Check(d.failures == 1,
              "the block failure is counted in the block failures counter");
        Check(d.install_failures == 1,
              "the install failure is counted in the install_failures counter");
        Check(d.blocks == 1, "only the processed block increments the block count");
    }

    // ------------------------------------------------------------------
    // Section E — per-route isolation: counters never cross-contaminate.
    // ------------------------------------------------------------------
    {
        accumulator.recordBlock(TelemetryRoute::kTinyAlsa, 5, TelemetryBlockOutcome::kMutated);
        const TelemetryDelta other = accumulator.take(TelemetryRoute::kApi);
        Check(!other.pending(),
              "recording one route must not leak into a different route");
        const TelemetryDelta owner = accumulator.take(TelemetryRoute::kTinyAlsa);
        Check(owner.blocks == 1 && owner.mutations == 1,
              "the recorded route retains its own counters");
    }

    // ------------------------------------------------------------------
    // Section F — realtime/PCM safety: many mixed records + drains allocate
    // nothing, the counters are lock-free, and the accumulator's footprint is
    // fixed (it holds only per-route counters, never PCM).
    // ------------------------------------------------------------------
    {
        Check(std::atomic<uint32_t>::is_always_lock_free,
              "telemetry counters must be lock-free so the audio thread never blocks");
        const uint32_t alloc_before = g_allocations.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < 20000; ++i)
        {
            const auto outcome = static_cast<TelemetryBlockOutcome>(i % 4);
            accumulator.recordBlock(TelemetryRoute::kAAudio, 256, outcome);
            if ((i & 0x3FFu) == 0)
            {
                accumulator.recordInstall(TelemetryRoute::kAAudio, (i & 1u) != 0);
                (void)accumulator.take(TelemetryRoute::kAAudio);
            }
        }
        (void)accumulator.take(TelemetryRoute::kAAudio);
        const uint32_t alloc_after = g_allocations.load(std::memory_order_relaxed);
        Check(alloc_before == alloc_after,
              "sustained record/take traffic must not allocate on the audio path");
        // Footprint is a fixed array of per-route counters — it cannot grow
        // with traffic and structurally cannot retain sample data.
        const std::size_t routes = static_cast<std::size_t>(TelemetryRoute::kCount);
        Check(sizeof(TelemetryAccumulator) == 64u * routes,
              "accumulator is a fixed per-route counter array, never a PCM buffer");
    }

    // ------------------------------------------------------------------
    // Section G — merge() coalesces install level (last-wins) and event edges
    // (summed), matching how the exporter batches unsent deltas.
    // ------------------------------------------------------------------
    {
        TelemetryDelta base;
        base.route = TelemetryRoute::kOpenSl;
        base.install_events = 1;
        base.install_failures = 1;
        base.installed = false;
        TelemetryDelta next;
        next.route = TelemetryRoute::kOpenSl;
        next.install_events = 2;
        next.install_failures = 2;
        next.installed = true;
        next.mutations = 3;
        base.merge(next);
        Check(base.install_events == 3, "merge sums install-event edges");
        Check(base.install_failures == 3, "merge sums install-failure edges");
        Check(base.installed, "merge takes the latest install level (last-wins)");
        Check(base.mutations == 3, "merge accumulates mutation edges");

        TelemetryDelta uninstall;
        uninstall.route = TelemetryRoute::kOpenSl;
        uninstall.installed = false;
        base.merge(uninstall);
        Check(!base.installed,
              "a later uninstall level overrides the latched installed bit");
    }

    if (g_failures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "telemetry_accumulator_test: all checks passed\n");
    return 0;
}
