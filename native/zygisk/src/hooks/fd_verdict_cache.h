#pragma once

/**
 * @file fd_verdict_cache.h
 * @brief Per-fd audio-device classification cache for the libc read hot path.
 *
 * The libc @c read() interception must decide, for the file descriptor being
 * read, whether it is a raw audio-capture device (see
 * @c libc_read_hook_manager.cpp). That classification is inherently expensive:
 * it requires an @c fstat() plus a @c readlink("/proc/self/fd/N") — two
 * blocking syscalls, one of which traverses @c /proc. Running it on *every*
 * intercepted read is a real-time-safety violation on the audio hot path
 * (t6-e3 FINDING-1).
 *
 * This cache moves the classification off the hot path: it happens once, on the
 * first sighting of an fd (a cache MISS), and the verdict is memoised. Every
 * subsequent read is a single lock-free, allocation-free, syscall-free atomic
 * load. The classification LOGIC is unchanged — only *when* it runs moves.
 *
 * ## Structure & lock-freedom
 * A fixed-capacity, direct-mapped table indexed by @c fd&(kCapacity-1). Each
 * bucket is a single @c std::atomic<uint64_t> that packs the owning fd in the
 * high 32 bits and the verdict in the low 8 bits, so a reader observes a
 * consistent (fd, verdict) pair from one atomic load — there is no seqlock and
 * no torn read. A zero word is the natural "empty" state: verdicts stored are
 * always @c kNotAudio or @c kAudioCapture (never @c kUnknown), so a zero bucket
 * can only mean "never populated" and reads back as @c kUnknown (a MISS).
 *
 * The hot-path lookup is a single wait-free atomic load. Populate/invalidate
 * (off the hot path) are lock-free stores / bounded CAS loops. Nothing here
 * allocates after construction and nothing here performs a syscall.
 *
 * ## fd-lifecycle invalidation (the classic fd-reuse bug)
 * A cached verdict for fd @c N becomes stale the moment @c N is closed and the
 * kernel hands the same number back for an unrelated file. Because fd numbers
 * are only recycled *after* a close (or a dup2/dup3 that retires the target),
 * observing those retirements is sufficient to prevent a stale verdict:
 *   - @c invalidate(fd) on @c close — clears the bucket so a recycled number
 *     re-classifies from scratch.
 *   - @c alias(source,target) on @c dup / @c dup2 / @c dup3 — the new fd refers
 *     to the same open file description, so it inherits @c source's verdict
 *     (and dup2/dup3 first @c invalidate the target they retire).
 * If a lifecycle event cannot be observed the safe fallback is to treat the fd
 * as @c kUnknown and re-classify, never to trust stale state — every method
 * here degrades toward "MISS → reclassify", never toward a wrong positive.
 */

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace echidna
{
    namespace hooks
    {

        /** Cached classification of a file descriptor for the read route. */
        enum class FdAudioVerdict : uint8_t
        {
            kUnknown = 0,      ///< Not classified yet / evicted — a cache MISS.
            kNotAudio = 1,     ///< Classified: not a raw audio-capture device.
            kAudioCapture = 2, ///< Classified: a raw audio-capture device.
        };

        /**
         * Bounded, direct-mapped, lock-free fd -> verdict cache.
         *
         * All reads are wait-free single atomic loads; all mutations are
         * lock-free. No method allocates or performs a syscall. Safe for
         * concurrent use from the audio read thread (lookup / memoise) and
         * arbitrary threads driving fd lifecycle (invalidate / alias).
         */
        class FdVerdictCache
        {
        public:
            /// Bucket count. Power of two; covers the common low fd range with a
            /// direct map. Colliding fds (fd and fd+kCapacity) evict each other,
            /// which only costs a re-classification — never a wrong verdict.
            static constexpr size_t kCapacity = 4096;
            static_assert((kCapacity & (kCapacity - 1)) == 0, "kCapacity must be a power of two");

            FdVerdictCache() = default;
            FdVerdictCache(const FdVerdictCache &) = delete;
            FdVerdictCache &operator=(const FdVerdictCache &) = delete;

            /**
             * Hot-path lookup. Single wait-free atomic load; no syscall, no
             * allocation, no lock. Returns @c kUnknown for a miss (never
             * classified, evicted, or an fd that does not own its bucket).
             */
            [[nodiscard]] FdAudioVerdict lookup(int fd) const noexcept
            {
                if (fd < 0)
                {
                    return FdAudioVerdict::kUnknown;
                }
                const uint64_t packed = slots_[index(fd)].load(std::memory_order_acquire);
                if (ownerOf(packed) != static_cast<uint32_t>(fd))
                {
                    return FdAudioVerdict::kUnknown;
                }
                return verdictOf(packed);
            }

            /**
             * Memoise a definite verdict for @c fd, evicting whatever else maps
             * to the bucket. @c kUnknown and negative fds are ignored — the
             * cache never stores an ambiguous verdict.
             */
            void memoize(int fd, FdAudioVerdict verdict) noexcept
            {
                if (fd < 0 || verdict == FdAudioVerdict::kUnknown)
                {
                    return;
                }
                slots_[index(fd)].store(pack(fd, verdict), std::memory_order_release);
            }

            /**
             * Lookup, and on a MISS classify exactly once and memoise the
             * result. @p classify is invoked only on a miss and must return a
             * definite verdict (@c kNotAudio / @c kAudioCapture). This is the
             * one place classification (and thus its syscalls) may run.
             */
            template <typename Classifier>
            FdAudioVerdict resolve(int fd, Classifier &&classify) noexcept
            {
                const FdAudioVerdict cached = lookup(fd);
                if (cached != FdAudioVerdict::kUnknown)
                {
                    return cached;
                }
                const FdAudioVerdict computed = classify(fd);
                memoize(fd, computed);
                return computed;
            }

            /**
             * Evict @c fd's verdict (call on @c close). Clears the bucket only
             * if it still belongs to @c fd, so it never clobbers a different fd
             * that has since taken the bucket over. Off the hot path.
             *
             * Kept inline (header-only) so the libc read hook manager needs no
             * extra translation unit in the Zygisk module's source list.
             */
            void invalidate(int fd) noexcept
            {
                if (fd < 0)
                {
                    return;
                }
                std::atomic<uint64_t> &slot = slots_[index(fd)];
                uint64_t packed = slot.load(std::memory_order_acquire);
                while (ownerOf(packed) == static_cast<uint32_t>(fd))
                {
                    if (slot.compare_exchange_weak(packed,
                                                   0,
                                                   std::memory_order_acq_rel,
                                                   std::memory_order_acquire))
                    {
                        return;
                    }
                }
            }

            /**
             * Propagate @c source_fd's verdict to @c target_fd (call on
             * @c dup / @c dup2 / @c dup3): the descriptors share one open file
             * description, so the classification is identical. If @c source_fd
             * is not classified, @c target_fd is invalidated so it re-classifies
             * rather than inheriting a stale value. Off the hot path.
             */
            void alias(int source_fd, int target_fd) noexcept
            {
                if (target_fd < 0 || source_fd == target_fd)
                {
                    return;
                }
                const FdAudioVerdict verdict = lookup(source_fd);
                if (verdict == FdAudioVerdict::kUnknown)
                {
                    invalidate(target_fd);
                }
                else
                {
                    memoize(target_fd, verdict);
                }
            }

            /** Drop every cached verdict (test / reset helper). */
            void clear() noexcept
            {
                for (std::atomic<uint64_t> &slot : slots_)
                {
                    slot.store(0, std::memory_order_relaxed);
                }
            }

        private:
            static constexpr size_t index(int fd) noexcept
            {
                return static_cast<size_t>(static_cast<uint32_t>(fd)) & (kCapacity - 1);
            }

            static constexpr uint64_t pack(int fd, FdAudioVerdict verdict) noexcept
            {
                return (static_cast<uint64_t>(static_cast<uint32_t>(fd)) << 32) |
                       static_cast<uint64_t>(static_cast<uint8_t>(verdict));
            }

            static constexpr uint32_t ownerOf(uint64_t packed) noexcept
            {
                return static_cast<uint32_t>(packed >> 32);
            }

            static constexpr FdAudioVerdict verdictOf(uint64_t packed) noexcept
            {
                return static_cast<FdAudioVerdict>(static_cast<uint8_t>(packed & 0xFFU));
            }

            // A zero word is the empty bucket. Because a stored verdict is never
            // kUnknown (0), a real entry for fd 0 always has a non-zero low byte,
            // so it is distinguishable from an untouched slot.
            std::array<std::atomic<uint64_t>, kCapacity> slots_{};
        };

    } // namespace hooks
} // namespace echidna
