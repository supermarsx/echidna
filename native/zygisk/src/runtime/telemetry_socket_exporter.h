#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include <sys/types.h>

#include "utils/telemetry_accumulator.h"

namespace echidna::runtime
{
    inline constexpr size_t kTelemetryV2MaxFrameBytes = 16 * 1024;

    enum class TelemetrySendResult : uint8_t
    {
        kComplete,
        kWouldBlock,
        kConnectionLost,
        kPartialWrite,
        kInvalidFrame,
    };

    using TelemetrySendFn = ssize_t (*)(int, const void *, size_t, int);

    /** Drops queued deltas whenever authenticated evidence changes incarnation. */
    bool RebindTelemetryPendingEpoch(uint64_t evidence_epoch,
                                     uint64_t *pending_epoch,
                                     utils::TelemetryDelta *pending,
                                     size_t pending_count) noexcept;

    [[nodiscard]] std::string EncodeTelemetryV2(const utils::TelemetryDelta &delta,
                                                uint32_t sequence,
                                                uint64_t sender_monotonic_ms,
                                                std::string_view process,
                                                uint64_t generation);

    /** Encodes the process-bound acknowledgement used by capture-owner handoffs. */
    [[nodiscard]] std::string EncodeCaptureOwnerAckV1(std::string_view process,
                                                      uint64_t generation,
                                                      uint64_t handoff_token,
                                                      bool active);

    /** Sends one whole frame nonblocking; a partial write shuts down the socket. */
    [[nodiscard]] TelemetrySendResult SendTelemetryV2Frame(
        int fd,
        std::string_view payload,
        TelemetrySendFn send_fn = nullptr) noexcept;

} // namespace echidna::runtime
