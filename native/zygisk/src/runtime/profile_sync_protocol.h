#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace echidna::runtime
{
    inline constexpr size_t kProfileSyncMaxEnvelopeBytes = 512 * 1024;
    inline constexpr size_t kProfileSyncMaxTransportFrameBytes =
        kProfileSyncMaxEnvelopeBytes + 128;
    inline constexpr size_t kProfileSyncMaxPresetBytes = 256 * 1024;
    inline constexpr size_t kProfileSyncMaxEntries = 256;
    inline constexpr size_t kProfileSyncMaxProfileIdBytes = 128;
    inline constexpr size_t kProfileSyncMaxProcessNameBytes = 255;

    inline constexpr std::string_view kProfileSyncV2ZygiskHello =
        "ECHIDNA_PROFILE_SYNC/2 zygisk\n";
    inline constexpr std::string_view kProfileSyncV2LsposedHello =
        "ECHIDNA_PROFILE_SYNC/2 lsposed\n";
    inline constexpr std::string_view kProfileSyncV3ZygiskHelloPrefix =
        "ECHIDNA_PROFILE_SYNC/3 zygisk ";

    enum class CaptureOwner
    {
        kNone,
        kZygisk,
        kLsposed,
    };

    struct DecodedProfileSnapshot
    {
        uint64_t generation{0};
        // Transport identity is populated only for authenticated v3 socket
        // frames. It is deliberately excluded from policy generation conflict
        // checks, but must accompany every route-state acknowledgement.
        uint64_t handoff_token{0};
        uint64_t connection_epoch{0};
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

    struct DecodedCapturePolicyFrame
    {
        uint64_t handoff_token{0};
        // View into the caller-owned frame. The exact nested bytes are retained
        // for collision-safe policy generation comparisons.
        std::string_view policy_payload;
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

    /** Strictly unwraps one v3 capture-policy transport frame. */
    bool DecodeCapturePolicyFrameV1(std::string_view payload,
                                    DecodedCapturePolicyFrame *frame,
                                    std::string *error);

    /** Compares exact prior payload bytes so generation equality is collision-safe. */
    GenerationDecision EvaluateGeneration(uint64_t generation,
                                          std::string_view payload,
                                          uint64_t previous_generation,
                                          std::string_view previous_payload);

} // namespace echidna::runtime
