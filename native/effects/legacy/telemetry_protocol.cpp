#include "telemetry_protocol.h"

#include <algorithm>
#include <cstring>

#include "capability_protocol.h"

namespace echidna::effects::legacy
{
    namespace
    {
        void WriteUint16(std::array<uint8_t, kTelemetrySnapshotValueBytes> &encoded,
                         size_t offset,
                         uint16_t value) noexcept
        {
            encoded[offset] = static_cast<uint8_t>(value >> 8U);
            encoded[offset + 1] = static_cast<uint8_t>(value);
        }

        void WriteUint32(std::array<uint8_t, kTelemetrySnapshotValueBytes> &encoded,
                         size_t offset,
                         uint32_t value) noexcept
        {
            encoded[offset] = static_cast<uint8_t>(value >> 24U);
            encoded[offset + 1] = static_cast<uint8_t>(value >> 16U);
            encoded[offset + 2] = static_cast<uint8_t>(value >> 8U);
            encoded[offset + 3] = static_cast<uint8_t>(value);
        }

        void WriteUint64(std::array<uint8_t, kTelemetrySnapshotValueBytes> &encoded,
                         size_t offset,
                         uint64_t value) noexcept
        {
            WriteUint32(encoded, offset, static_cast<uint32_t>(value >> 32U));
            WriteUint32(encoded, offset + 4, static_cast<uint32_t>(value));
        }
    } // namespace

    bool ValidTelemetryQueryLayout(const effect_param_t &parameter,
                                   size_t command_size) noexcept
    {
        constexpr size_t kCommandBytes =
            sizeof(effect_param_t) + kTelemetrySnapshotParameter.size();
        return parameter.psize == kTelemetrySnapshotParameter.size() &&
               command_size == kCommandBytes &&
               parameter.vsize <= kEffectParamMaxBytes - kCommandBytes;
    }

    bool IsTelemetrySnapshotParameter(const effect_param_t &parameter,
                                      size_t command_size) noexcept
    {
        return ValidTelemetryQueryLayout(parameter, command_size) &&
               std::equal(kTelemetrySnapshotParameter.begin(),
                          kTelemetrySnapshotParameter.end(),
                          reinterpret_cast<const uint8_t *>(parameter.data));
    }

    std::array<uint8_t, kTelemetrySnapshotValueBytes>
    EncodeTelemetrySnapshot(const TelemetryWireSnapshot &snapshot) noexcept
    {
        std::array<uint8_t, kTelemetrySnapshotValueBytes> encoded{};
        std::copy_n(kTelemetrySnapshotParameter.begin(), 4, encoded.begin());
        WriteUint16(encoded, 4, kTelemetrySchema);
        WriteUint16(encoded, 6, kTelemetrySnapshotParameterId);
        WriteUint16(encoded, 8,
                    static_cast<uint16_t>(kTelemetrySnapshotValueBytes));
        WriteUint16(encoded, 10, snapshot.flags);
        WriteUint32(encoded, 12, static_cast<uint32_t>(snapshot.session_id));
        WriteUint64(encoded, 16, snapshot.generation);
        WriteUint32(encoded, 24, snapshot.sequence);
        WriteUint32(encoded, 28, snapshot.blocks);
        WriteUint32(encoded, 32, snapshot.frames);
        WriteUint32(encoded, 36, snapshot.failures);
        WriteUint32(encoded, 40, snapshot.mutations);
        WriteUint32(encoded, 44, 0);
        return encoded;
    }

} // namespace echidna::effects::legacy
