#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "effect_abi.h"

namespace echidna::effects::legacy
{
    inline constexpr std::array<uint8_t, 8> kTelemetrySnapshotParameter = {
        'E', 'C', 'H', 'T', 0x00, 0x01, 0x00, 0x01};
    inline constexpr uint16_t kTelemetrySchema = 1;
    inline constexpr uint16_t kTelemetrySnapshotParameterId = 1;
    inline constexpr size_t kTelemetrySnapshotValueBytes = 48;
    inline constexpr size_t kTelemetrySnapshotReplyBytes =
        sizeof(effect_param_t) + kTelemetrySnapshotParameter.size() +
        kTelemetrySnapshotValueBytes;

    enum TelemetryFlags : uint16_t
    {
        kTelemetryEnabled = 1U << 0,
        kTelemetryAuthorized = 1U << 1,
        kTelemetryExpired = 1U << 2,
    };

    struct TelemetryWireSnapshot
    {
        int32_t session_id{0};
        uint64_t generation{0};
        uint32_t sequence{0};
        uint16_t flags{0};
        uint32_t blocks{0};
        uint32_t frames{0};
        uint32_t failures{0};
        uint32_t mutations{0};
    };

    [[nodiscard]] constexpr uint32_t ModularAdd(uint32_t value,
                                                uint32_t amount) noexcept
    {
        return value + amount;
    }

    [[nodiscard]] bool ValidTelemetryQueryLayout(
        const effect_param_t &parameter,
        size_t command_size) noexcept;
    [[nodiscard]] bool IsTelemetrySnapshotParameter(
        const effect_param_t &parameter,
        size_t command_size) noexcept;
    [[nodiscard]] std::array<uint8_t, kTelemetrySnapshotValueBytes>
    EncodeTelemetrySnapshot(const TelemetryWireSnapshot &snapshot) noexcept;

} // namespace echidna::effects::legacy
