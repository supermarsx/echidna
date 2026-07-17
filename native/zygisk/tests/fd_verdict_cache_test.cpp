#include "hooks/fd_verdict_cache.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <thread>
#include <vector>

// Host harness for the per-fd audio-device verdict cache. It proves the
// behaviour the libc read hot path depends on: a MISS classifies exactly once
// and memoises; a HIT is a lock-free lookup that runs neither the classifier
// (the sole syscall site) nor any allocation; fd-lifecycle events (close, dup,
// dup2/dup3, fd reuse) never leave a stale verdict; the fixed-capacity table
// evicts colliding fds without ever returning a foreign verdict; and concurrent
// readers/mutators stay consistent. Mirrors aaudio_stream_registry_test.cpp:
// standalone main()/CHECK harness + a global operator new allocation tracker.

namespace
{
    using echidna::hooks::FdAudioVerdict;
    using echidna::hooks::FdVerdictCache;

    std::atomic<uint64_t> gAllocationCount{0};
    std::atomic<bool> gTrackAllocations{false};

    int gFailures = 0;
    void Check(bool condition, const char *expression, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [line %d] %s\n", expression, line, message);
            ++gFailures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    // A stand-in for the real fstat()+readlink() classifier. It performs no
    // syscall so the harness runs on a host, but it counts every invocation:
    // because it is the ONLY place the production path would touch the kernel,
    // "classifier not called" is exactly "no syscall on this path".
    std::atomic<uint64_t> gClassifyCalls{0};

    FdAudioVerdict CountingClassify(int fd, FdAudioVerdict answer)
    {
        gClassifyCalls.fetch_add(1, std::memory_order_relaxed);
        (void)fd;
        return answer;
    }

    void TestMissClassifiesOnceAndMemoizes()
    {
        FdVerdictCache cache;
        CHECK(cache.lookup(7) == FdAudioVerdict::kUnknown,
              "an unseen fd is a miss before classification");

        gClassifyCalls.store(0, std::memory_order_relaxed);
        const FdAudioVerdict first = cache.resolve(
            7, [](int fd)
            { return CountingClassify(fd, FdAudioVerdict::kAudioCapture); });
        CHECK(first == FdAudioVerdict::kAudioCapture, "miss returns the classified verdict");
        CHECK(gClassifyCalls.load(std::memory_order_relaxed) == 1,
              "miss classifies exactly once");
        CHECK(cache.lookup(7) == FdAudioVerdict::kAudioCapture,
              "the verdict is memoised for subsequent lookups");

        // A poisoned classifier proves memoisation: it would flip the verdict if
        // it ever ran again, but a hit must not call it.
        const FdAudioVerdict second = cache.resolve(
            7, [](int fd)
            { return CountingClassify(fd, FdAudioVerdict::kNotAudio); });
        CHECK(second == FdAudioVerdict::kAudioCapture, "a hit returns the memoised verdict");
        CHECK(gClassifyCalls.load(std::memory_order_relaxed) == 1,
              "a hit never re-runs the classifier");

        // A negative fd is always a miss and is never memoised.
        CHECK(cache.lookup(-1) == FdAudioVerdict::kUnknown, "negative fd is never cached");
        cache.memoize(-1, FdAudioVerdict::kAudioCapture);
        CHECK(cache.lookup(-1) == FdAudioVerdict::kUnknown, "negative fd stays uncached");
    }

    void TestCloseInvalidates()
    {
        FdVerdictCache cache;
        cache.memoize(11, FdAudioVerdict::kAudioCapture);
        CHECK(cache.lookup(11) == FdAudioVerdict::kAudioCapture, "fd classified before close");
        cache.invalidate(11);
        CHECK(cache.lookup(11) == FdAudioVerdict::kUnknown, "close evicts the verdict");

        // invalidate only clears its own fd, never a different owner of the same
        // bucket.
        cache.memoize(11, FdAudioVerdict::kNotAudio);
        const int colliding = 11 + static_cast<int>(FdVerdictCache::kCapacity);
        cache.memoize(colliding, FdAudioVerdict::kAudioCapture); // evicts fd 11
        cache.invalidate(11);                                    // fd 11 no longer owns the bucket
        CHECK(cache.lookup(colliding) == FdAudioVerdict::kAudioCapture,
              "invalidating an evicted fd does not disturb the current bucket owner");
    }

    void TestDupAliases()
    {
        FdVerdictCache cache;
        cache.memoize(20, FdAudioVerdict::kAudioCapture);
        cache.alias(20, 21); // dup: 21 shares 20's description
        CHECK(cache.lookup(21) == FdAudioVerdict::kAudioCapture,
              "dup target inherits the source verdict without reclassifying");
        CHECK(cache.lookup(20) == FdAudioVerdict::kAudioCapture, "dup leaves the source intact");

        // Aliasing from an unclassified source must clear the target rather than
        // leave a stale verdict behind (the dup2 retire-and-rebind case).
        cache.memoize(22, FdAudioVerdict::kAudioCapture);
        cache.alias(99 /* unknown */, 22);
        CHECK(cache.lookup(22) == FdAudioVerdict::kUnknown,
              "aliasing from an unknown source invalidates the target");

        // A non-audio source aliases faithfully too.
        cache.memoize(30, FdAudioVerdict::kNotAudio);
        cache.alias(30, 31);
        CHECK(cache.lookup(31) == FdAudioVerdict::kNotAudio, "dup preserves a NotAudio verdict");
    }

    void TestFdReuseAfterCloseReclassifies()
    {
        // The classic fd-reuse bug: fd N is an audio device, is closed, and the
        // same number is handed back for an unrelated file. Without eviction the
        // read path would transform the new file's bytes.
        FdVerdictCache cache;
        const int fd = 40;
        FdAudioVerdict resolved = cache.resolve(
            fd, [](int f)
            { return CountingClassify(f, FdAudioVerdict::kAudioCapture); });
        CHECK(resolved == FdAudioVerdict::kAudioCapture, "fd starts life as an audio device");

        cache.invalidate(fd); // close()

        gClassifyCalls.store(0, std::memory_order_relaxed);
        resolved = cache.resolve(
            fd, [](int f)
            { return CountingClassify(f, FdAudioVerdict::kNotAudio); });
        CHECK(resolved == FdAudioVerdict::kNotAudio,
              "a reused fd number reclassifies to the new file's verdict, not the stale one");
        CHECK(gClassifyCalls.load(std::memory_order_relaxed) == 1,
              "the reused fd triggers exactly one fresh classification");
    }

    void TestBoundedCapacityEviction()
    {
        // Two fds that map to the same bucket (fd and fd + kCapacity) cannot both
        // be cached; the later insert evicts the earlier. Eviction only costs a
        // reclassification and never yields a foreign verdict.
        FdVerdictCache cache;
        const int base = 3;
        const int collide = base + static_cast<int>(FdVerdictCache::kCapacity);

        cache.memoize(base, FdAudioVerdict::kAudioCapture);
        CHECK(cache.lookup(base) == FdAudioVerdict::kAudioCapture, "first fd occupies the bucket");
        cache.memoize(collide, FdAudioVerdict::kNotAudio);
        CHECK(cache.lookup(collide) == FdAudioVerdict::kNotAudio,
              "colliding fd takes the bucket over");
        CHECK(cache.lookup(base) == FdAudioVerdict::kUnknown,
              "evicted fd reads back as a miss, never as the colliding fd's verdict");

        // The whole table stays bounded: filling far more distinct fds than
        // kCapacity never grows storage and never returns a wrong positive.
        for (int fd = 0; fd < static_cast<int>(FdVerdictCache::kCapacity) * 4; ++fd)
        {
            cache.memoize(fd, (fd & 1) ? FdAudioVerdict::kAudioCapture : FdAudioVerdict::kNotAudio);
        }
        for (int fd = 0; fd < static_cast<int>(FdVerdictCache::kCapacity) * 4; ++fd)
        {
            const FdAudioVerdict expected =
                (fd & 1) ? FdAudioVerdict::kAudioCapture : FdAudioVerdict::kNotAudio;
            const FdAudioVerdict got = cache.lookup(fd);
            CHECK(got == expected || got == FdAudioVerdict::kUnknown,
                  "a bounded lookup is either the fd's own verdict or a miss, never foreign");
        }
    }

    void TestHotPathHitAllocatesAndSyscallsNothing()
    {
        FdVerdictCache cache;
        const int fd = 55;
        // Warm the cache OUTSIDE tracking so the one-time classification is not
        // counted against the hot path.
        (void)cache.resolve(
            fd, [](int f)
            { return CountingClassify(f, FdAudioVerdict::kAudioCapture); });
        CHECK(cache.lookup(fd) == FdAudioVerdict::kAudioCapture, "warmup memoises the verdict");

        const uint64_t alloc_before = gAllocationCount.load(std::memory_order_relaxed);
        gClassifyCalls.store(0, std::memory_order_relaxed);
        gTrackAllocations.store(true, std::memory_order_release);
        bool all_hit = true;
        for (int i = 0; i < 100000; ++i)
        {
            all_hit = all_hit &&
                      cache.resolve(fd, [](int f)
                                    { return CountingClassify(f, FdAudioVerdict::kNotAudio); }) == FdAudioVerdict::kAudioCapture;
        }
        gTrackAllocations.store(false, std::memory_order_release);

        CHECK(all_hit, "every steady-state read is a cache hit");
        CHECK(gClassifyCalls.load(std::memory_order_relaxed) == 0,
              "a cache hit never invokes the classifier (the sole syscall site)");
        CHECK(gAllocationCount.load(std::memory_order_relaxed) == alloc_before,
              "the cache-hit read path allocates nothing");

        // A bare lookup is likewise allocation-free.
        const uint64_t before_lookup = gAllocationCount.load(std::memory_order_relaxed);
        gTrackAllocations.store(true, std::memory_order_release);
        volatile FdAudioVerdict sink = FdAudioVerdict::kUnknown;
        for (int i = 0; i < 100000; ++i)
        {
            sink = cache.lookup(fd);
        }
        gTrackAllocations.store(false, std::memory_order_release);
        (void)sink;
        CHECK(gAllocationCount.load(std::memory_order_relaxed) == before_lookup,
              "lookup allocates nothing");
    }

    void TestConcurrentAccessStaysConsistent()
    {
        // Each fd has a fixed canonical verdict; writers only ever memoise that
        // verdict (or evict). Therefore a reader may see kUnknown or the fd's own
        // canonical verdict, but never the other verdict and never a neighbour's.
        // fds are packed so many collide into shared buckets to stress eviction.
        FdVerdictCache cache;
        constexpr int kFdCount = 512;
        auto canonical = [](int fd)
        {
            return (fd & 1) ? FdAudioVerdict::kAudioCapture : FdAudioVerdict::kNotAudio;
        };

        std::atomic<bool> stop{false};
        std::atomic<uint64_t> violations{0};
        std::atomic<uint64_t> reads{0};

        std::vector<std::thread> workers;
        for (int t = 0; t < 4; ++t)
        {
            workers.emplace_back([&, t]()
                                 {
                uint32_t rng = 0x9E3779B9u ^ static_cast<uint32_t>(t * 2654435761u);
                while (!stop.load(std::memory_order_acquire))
                {
                    rng ^= rng << 13;
                    rng ^= rng >> 17;
                    rng ^= rng << 5;
                    const int fd = static_cast<int>(rng % kFdCount);
                    switch (rng & 3u)
                    {
                    case 0:
                        cache.memoize(fd, canonical(fd));
                        break;
                    case 1:
                        cache.invalidate(fd);
                        break;
                    case 2:
                        cache.alias(fd, (fd + 1) % kFdCount);
                        break;
                    default:
                        (void)cache.resolve(fd, [&](int f) { return canonical(f); });
                        break;
                    }
                } });
        }

        std::vector<std::thread> readers;
        for (int t = 0; t < 4; ++t)
        {
            readers.emplace_back([&]()
                                 {
                while (!stop.load(std::memory_order_acquire))
                {
                    for (int fd = 0; fd < kFdCount; ++fd)
                    {
                        const FdAudioVerdict v = cache.lookup(fd);
                        reads.fetch_add(1, std::memory_order_relaxed);
                        if (v != FdAudioVerdict::kUnknown && v != canonical(fd))
                        {
                            violations.fetch_add(1, std::memory_order_relaxed);
                        }
                    }
                } });
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(150));
        stop.store(true, std::memory_order_release);
        for (auto &worker : workers)
        {
            worker.join();
        }
        for (auto &reader : readers)
        {
            reader.join();
        }

        CHECK(reads.load(std::memory_order_relaxed) > 0, "the concurrency test actually ran");
        CHECK(violations.load(std::memory_order_relaxed) == 0,
              "a lookup never returns a verdict belonging to a different fd (no torn reads)");
    }
} // namespace

void *operator new(std::size_t size)
{
    if (gTrackAllocations.load(std::memory_order_relaxed))
    {
        gAllocationCount.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *memory = std::malloc(size))
    {
        return memory;
    }
    throw std::bad_alloc();
}

void *operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void *memory) noexcept { std::free(memory); }
void operator delete[](void *memory) noexcept { std::free(memory); }
void operator delete(void *memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void *memory, std::size_t) noexcept { std::free(memory); }

int main()
{
    TestMissClassifiesOnceAndMemoizes();
    TestCloseInvalidates();
    TestDupAliases();
    TestFdReuseAfterCloseReclassifies();
    TestBoundedCapacityEviction();
    TestHotPathHitAllocatesAndSyscallsNothing();
    TestConcurrentAccessStaysConsistent();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "fd_verdict_cache_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "fd_verdict_cache_test: all checks passed\n");
    return 0;
}
