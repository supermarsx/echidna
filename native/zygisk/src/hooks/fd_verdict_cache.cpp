#include "hooks/fd_verdict_cache.h"

/**
 * @file fd_verdict_cache.cpp
 * @brief Translation-unit anchor and compile-time invariant checks for the
 * header-only FdVerdictCache.
 *
 * The cache is deliberately header-only: every member is inline so the libc
 * read route keeps the hot-path lookup fully inlinable AND so the Zygisk module
 * needs no additional entry in its explicit source list (which is shared with
 * sibling native tracks). This unit exists to (a) satisfy the {h,cpp}
 * deliverable, (b) give the header an independent, standalone compile, and
 * (c) pin the encoding invariants the lock-free single-word scheme relies on.
 * It defines no out-of-line symbols, so it need not be linked into the module.
 */

namespace echidna
{
    namespace hooks
    {
        namespace
        {
            // The direct-mapped index math requires a power-of-two capacity.
            static_assert((FdVerdictCache::kCapacity & (FdVerdictCache::kCapacity - 1)) == 0,
                          "kCapacity must be a power of two for the bucket mask");

            // A stored verdict is never kUnknown, so an all-zero bucket is an
            // unambiguous "empty". If this ever changes the empty-slot detection
            // in lookup() breaks.
            static_assert(static_cast<uint8_t>(FdAudioVerdict::kUnknown) == 0,
                          "kUnknown must encode as zero so an empty bucket reads as a miss");
            static_assert(static_cast<uint8_t>(FdAudioVerdict::kNotAudio) != 0 &&
                              static_cast<uint8_t>(FdAudioVerdict::kAudioCapture) != 0,
                          "definite verdicts must be non-zero to be distinct from empty");
        } // namespace
    } // namespace hooks
} // namespace echidna
