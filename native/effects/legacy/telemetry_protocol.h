#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

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

    inline constexpr std::array<uint8_t, 8> kTelemetryProofParameter = {
        'E', 'C', 'H', 'T', 0x00, 0x02, 0x00, 0x02};
    inline constexpr uint16_t kTelemetryProofSchema = 2;
    inline constexpr uint16_t kTelemetryProofParameterId = 2;
    inline constexpr size_t kTelemetryProofNonceBytes = 16;
    inline constexpr size_t kTelemetryProofQueryBytes =
        kTelemetryProofParameter.size() + kTelemetryProofNonceBytes;
    inline constexpr size_t kTelemetryProofAuthenticatedBodyBytes = 80;
    inline constexpr size_t kTelemetryProofKeyIdBytes = 16;
    inline constexpr size_t kTelemetryProofTagBytes = 32;
    inline constexpr size_t kTelemetryProofValueBytes =
        kTelemetryProofAuthenticatedBodyBytes + kTelemetryProofTagBytes;
    inline constexpr size_t kTelemetryProofReplyBytes =
        sizeof(effect_param_t) + kTelemetryProofQueryBytes +
        kTelemetryProofValueBytes;
    inline constexpr const char *kTelemetryProofKeyPath =
        "/system/etc/echidna/preprocessor_telemetry_hmac.key";
    inline constexpr uint32_t kTelemetryProofKeyOwnerUid = 0;
    inline constexpr uint32_t kTelemetryProofKeyGroupGid = 1005;
    inline constexpr uint32_t kTelemetryProofKeyMode = 0440;
    inline constexpr int32_t kTelemetryProofKeyUnavailableStatus = -126;
    inline constexpr int32_t kTelemetryProofNoCapabilityStatus = -61;
    inline constexpr int32_t kTelemetryProofStaleNonceStatus = -116;

    using TelemetryProofNonce = std::array<uint8_t, kTelemetryProofNonceBytes>;
    using TelemetryProofKey = std::array<uint8_t, 32>;
    using TelemetryProofKeyId =
        std::array<uint8_t, kTelemetryProofKeyIdBytes>;
    using TelemetryProofTag = std::array<uint8_t, kTelemetryProofTagBytes>;

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

    struct TelemetryProofWireSnapshot
    {
        int32_t session_id{0};
        uint64_t generation{0};
        TelemetryProofNonce nonce{};
        uint32_t sequence{0};
        uint16_t flags{0};
        uint32_t blocks{0};
        uint32_t frames{0};
        uint32_t failures{0};
        uint32_t mutations{0};
    };

    struct TelemetryProofKeyOptions
    {
        std::string key_path{kTelemetryProofKeyPath};
        // Production requires root:AID_AUDIO and mode 0440. Host tests inject
        // the effective UID/GID while retaining all other checks.
        uint32_t required_owner_uid{kTelemetryProofKeyOwnerUid};
        uint32_t required_group_gid{kTelemetryProofKeyGroupGid};
        uint32_t required_mode{kTelemetryProofKeyMode};
    };

    /** Fixed-key HMAC signer. Load() is control-thread-only; Sign() performs no I/O. */
    class TelemetryProofSigner
    {
    public:
        explicit TelemetryProofSigner(TelemetryProofKeyOptions options = {});
        ~TelemetryProofSigner();

        TelemetryProofSigner(const TelemetryProofSigner &) = delete;
        TelemetryProofSigner &operator=(const TelemetryProofSigner &) = delete;

        [[nodiscard]] bool Load() noexcept;
        [[nodiscard]] bool available() const noexcept { return available_; }
        [[nodiscard]] const TelemetryProofKeyId &key_id() const noexcept
        {
            return key_id_;
        }
        [[nodiscard]] bool Sign(
            const std::array<uint8_t, kTelemetryProofAuthenticatedBodyBytes> &body,
            TelemetryProofTag *tag) const noexcept;
        [[nodiscard]] bool Verify(
            const std::array<uint8_t, kTelemetryProofValueBytes> &value) const noexcept;

    private:
        void Clear() noexcept;

        TelemetryProofKeyOptions options_;
        TelemetryProofKey key_{};
        TelemetryProofKeyId key_id_{};
        bool available_{false};
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
    [[nodiscard]] bool ValidTelemetryProofQueryLayout(
        const effect_param_t &parameter,
        size_t command_size) noexcept;
    [[nodiscard]] bool IsTelemetryProofParameter(
        const effect_param_t &parameter,
        size_t command_size) noexcept;
    [[nodiscard]] TelemetryProofNonce TelemetryProofQueryNonce(
        const effect_param_t &parameter,
        size_t command_size) noexcept;
    [[nodiscard]] std::array<uint8_t, kTelemetrySnapshotValueBytes>
    EncodeTelemetrySnapshot(const TelemetryWireSnapshot &snapshot) noexcept;
    [[nodiscard]] bool EncodeTelemetryProof(
        const TelemetryProofWireSnapshot &snapshot,
        const TelemetryProofSigner &signer,
        std::array<uint8_t, kTelemetryProofValueBytes> *encoded) noexcept;

} // namespace echidna::effects::legacy
