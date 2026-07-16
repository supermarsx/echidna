#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "effect_abi.h"

namespace echidna::effects::legacy
{
    inline constexpr std::array<uint8_t, 8> kCapabilityAuthorizeParameter = {
        'E', 'C', 'H', 'P', 0x00, 0x02, 0x00, 0x01};
    inline constexpr std::array<uint8_t, 8> kCapabilityRevokeParameter = {
        'E', 'C', 'H', 'P', 0x00, 0x02, 0x00, 0x02};
    inline constexpr std::array<uint8_t, 16> kEffectImplementationUuidBytes = {
        0x3e, 0x66, 0xa3, 0x6e, 0xde, 0xe9, 0x5d, 0x81,
        0xa0, 0xd6, 0x49, 0xfc, 0x3b, 0x86, 0x35, 0x30};
    inline constexpr std::string_view kCapabilityMagic = "ECHC";
    inline constexpr uint16_t kCapabilitySchema = 1;
    inline constexpr size_t kCapabilityFixedBodyBytes = 110;
    inline constexpr size_t kCapabilityMaxPresetBytes = 60 * 1024;
    inline constexpr size_t kCapabilityMaxProcessBytes = 255;
    inline constexpr size_t kEffectParamMaxBytes = 64 * 1024;
    inline constexpr uint64_t kCapabilityMaxLifetimeMs = 5000;
    inline constexpr uint64_t kCapabilityFutureSkewMs = 250;
    // Linux/Android errno values are part of the control-plane wire contract.
    inline constexpr int32_t kCapabilityReplayStatus = -114;   // EALREADY
    inline constexpr int32_t kCapabilityRollbackStatus = -116; // ESTALE
    inline constexpr int32_t kCapabilityConflictStatus = -17;  // EEXIST
    inline constexpr int32_t kCapabilityBusyStatus = -16;      // EBUSY
    inline constexpr const char *kControllerSpkiPath =
        "/system/etc/echidna/preprocessor_controller_p256.spki";

    using CapabilityNonce = std::array<uint8_t, 16>;
    using CapabilityHash = std::array<uint8_t, 32>;

    struct CapabilityClaims
    {
        int32_t session_id{0};
        uint32_t target_uid{0};
        uint64_t generation{0};
        uint64_t issued_boottime_ms{0};
        uint64_t expires_boottime_ms{0};
        CapabilityNonce nonce{};
        CapabilityHash preset_hash{};
        std::string process;
        std::string preset_json;
    };

    enum class CapabilityStatus : int32_t
    {
        kOk = 0,
        kMalformed = -22,         // EINVAL
        kKeyUnavailable = -126,   // ENOKEY
        kSignatureInvalid = -129, // EKEYREJECTED
        kExpired = -127,          // EKEYEXPIRED
        kWrongSession = -1,       // EPERM
    };

    using CapabilityClock = uint64_t (*)() noexcept;

    struct CapabilityVerifierOptions
    {
        std::string spki_path{kControllerSpkiPath};
        CapabilityClock clock{nullptr};
        // Production uses root (0). Host tests may inject their own effective UID.
        uint32_t required_spki_owner_uid{0};
    };

    class CapabilityVerifier
    {
    public:
        explicit CapabilityVerifier(CapabilityVerifierOptions options = {});

        CapabilityStatus verify(std::string_view value,
                                int32_t expected_session,
                                CapabilityClaims *claims) const;
        [[nodiscard]] uint64_t nowBoottimeMs() const noexcept;

        static bool IsActiveDeadline(uint32_t now_ms, uint32_t deadline_ms) noexcept;

    private:
        CapabilityVerifierOptions options_;
    };

    [[nodiscard]] bool IsAuthorizeParameter(const effect_param_t &parameter,
                                            size_t command_size) noexcept;
    [[nodiscard]] bool IsRevokeParameter(const effect_param_t &parameter,
                                         size_t command_size) noexcept;
    [[nodiscard]] std::string_view EffectParameterValue(const effect_param_t &parameter,
                                                        size_t command_size) noexcept;

} // namespace echidna::effects::legacy
