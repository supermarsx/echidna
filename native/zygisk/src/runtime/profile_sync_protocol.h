#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace echidna::runtime
{
    inline constexpr size_t kProfileSyncMaxEnvelopeBytes = 512 * 1024;
    inline constexpr size_t kProfileSyncMaxPresetBytes = 256 * 1024;
    inline constexpr size_t kProfileSyncMaxEntries = 256;
    inline constexpr size_t kProfileSyncMaxProfileIdBytes = 128;
    inline constexpr size_t kProfileSyncMaxProcessNameBytes = 255;

    inline constexpr std::string_view kProfileSyncV2ZygiskHello =
        "ECHIDNA_PROFILE_SYNC/2 zygisk\n";
    inline constexpr std::string_view kProfileSyncV2LsposedHello =
        "ECHIDNA_PROFILE_SYNC/2 lsposed\n";

    enum class CaptureOwner
    {
        kNone,
        kZygisk,
        kLsposed,
    };

    struct DecodedProfileSnapshot
    {
        uint64_t generation{0};
        bool global_hooks_enabled{false};
        bool process_whitelisted{false};
        CaptureOwner capture_owner{CaptureOwner::kNone};
        std::string profile_id;
        std::string preset_json;

        [[nodiscard]] bool nativeProcessAdmitted() const
        {
            return global_hooks_enabled && process_whitelisted &&
                   capture_owner == CaptureOwner::kZygisk;
        }
    };

    enum class GenerationDecision
    {
        kAccept,
        kDuplicate,
        kRejectRollback,
        kRejectConflict,
    };

    /**
     * Strictly decodes and validates a version-2 profile-sync envelope.
     *
     * The function never mutates runtime state. A valid policy that denies the
     * process still succeeds and returns an inert snapshot.
     */
    bool DecodeProfileSyncV2(std::string_view payload,
                             std::string_view process_name,
                             uint64_t now_epoch_ms,
                             DecodedProfileSnapshot *snapshot,
                             std::string *error);

    /** Compares exact prior payload bytes so generation equality is collision-safe. */
    GenerationDecision EvaluateGeneration(uint64_t generation,
                                          std::string_view payload,
                                          uint64_t previous_generation,
                                          std::string_view previous_payload);

} // namespace echidna::runtime
