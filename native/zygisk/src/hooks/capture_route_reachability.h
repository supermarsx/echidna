#pragma once

#include <array>

namespace echidna::hooks
{
    enum class CaptureRouteSupport
    {
        kOperational,
        kDeviceTargetGated,
        kDeveloperContractOnly,
        kUnsupported,
    };

    enum class CaptureHookAbi
    {
        kArm64,
        kArmv7,
        kX86_64,
        kOther,
    };

    struct CaptureRouteDescriptor
    {
        const char *id;
        CaptureRouteSupport support;
        const char *metadata_source;
        const char *unavailable_reason;
    };

    inline constexpr CaptureRouteDescriptor kAAudioRoute{
        "aaudio",
        CaptureRouteSupport::kOperational,
        "aaudio_stream_getters",
        "",
    };
    inline constexpr CaptureRouteDescriptor kOpenSlRoute{
        "opensl",
        CaptureRouteSupport::kOperational,
        "opensl_recorder_sink_pcm_descriptor",
        "",
    };
    inline constexpr CaptureRouteDescriptor kTinyAlsaRoute{
        "tinyalsa",
        CaptureRouteSupport::kDeviceTargetGated,
        "tinyalsa_pcm_open_config",
        "requires_mapped_compatible_tinyalsa_in_target_process",
    };
    inline constexpr CaptureRouteDescriptor kLsposedJavaAudioRecordRoute{
        "lsposed_java_audiorecord",
        CaptureRouteSupport::kOperational,
        "AudioRecord.getSampleRate,getChannelCount,getAudioFormat",
        "",
    };
    inline constexpr CaptureRouteDescriptor kNativeAudioRecordRoute{
        "native_audiorecord",
        CaptureRouteSupport::kDeveloperContractOnly,
        "ECHIDNA_AR_SR,ECHIDNA_AR_CH,ECHIDNA_AR_FORMAT",
        "developer_contract_only_unconfigured",
    };
    inline constexpr CaptureRouteDescriptor kLibcReadRoute{
        "libc_read",
        CaptureRouteSupport::kDeveloperContractOnly,
        "ECHIDNA_LIBC_SR,ECHIDNA_LIBC_CH,ECHIDNA_LIBC_FORMAT",
        "developer_contract_only_unconfigured",
    };
    inline constexpr CaptureRouteDescriptor kAudioHalRoute{
        "audio_hal",
        CaptureRouteSupport::kUnsupported,
        "none",
        "unsupported_injection_boundary",
    };

    inline constexpr CaptureRouteDescriptor kAudioFlingerRoute{
        "audioflinger",
        CaptureRouteSupport::kUnsupported,
        "none",
        "unsupported_injection_boundary",
    };

    inline constexpr std::array<const CaptureRouteDescriptor *, 8> kCaptureRouteMatrix{
        &kAAudioRoute,
        &kOpenSlRoute,
        &kTinyAlsaRoute,
        &kLsposedJavaAudioRecordRoute,
        &kNativeAudioRecordRoute,
        &kLibcReadRoute,
        &kAudioHalRoute,
        &kAudioFlingerRoute,
    };

    // armv7 (ARM/Thumb-2) direct inline-symbol hooking is now backed by a real
    // ARM32/Thumb-2 prologue relocator (runtime/armv7_instruction.h), host-proven
    // by tests/armv7_instruction_test.cpp. Direct routes are therefore no longer
    // categorically unsupported on armv7 — the orchestrator attempts installation,
    // and the relocator either relocates the prologue or fails closed per-function
    // (never a half-written patch). Live install/execution on real armv7 hardware
    // has not been validated here (highest crash-risk path), so the ABI view stays
    // device-gated rather than claiming parity with the arm64 primary target.
    inline constexpr const char *kArmv7DirectHookDeviceGatedReason =
        "armv7_inline_relocation_host_proven_on_device_gated";

    struct CaptureRouteAvailability
    {
        CaptureRouteSupport support;
        const char *unavailable_reason;
    };

    constexpr bool IsDirectInlineSymbolRoute(const CaptureRouteDescriptor &route)
    {
        return &route == &kAAudioRoute ||
               &route == &kOpenSlRoute ||
               &route == &kTinyAlsaRoute ||
               &route == &kNativeAudioRecordRoute ||
               &route == &kLibcReadRoute;
    }

    // Ordinal used to keep the armv7 view never *less* gated than the route's own
    // native tier (kOperational < kDeviceTargetGated < kDeveloperContractOnly <
    // kUnsupported).
    constexpr int CaptureRouteSupportRank(CaptureRouteSupport support)
    {
        switch (support)
        {
        case CaptureRouteSupport::kOperational:
            return 0;
        case CaptureRouteSupport::kDeviceTargetGated:
            return 1;
        case CaptureRouteSupport::kDeveloperContractOnly:
            return 2;
        case CaptureRouteSupport::kUnsupported:
            return 3;
        }
        return 3;
    }

    constexpr CaptureRouteAvailability CaptureRouteAvailabilityForAbi(
        const CaptureRouteDescriptor &route,
        CaptureHookAbi abi)
    {
        if (abi == CaptureHookAbi::kArmv7 && IsDirectInlineSymbolRoute(route))
        {
            // Relocation backend exists + is host-proven; on-hardware install is
            // device-gated. Report the more restrictive of the route's own tier
            // and device-gated so armv7 never looks *less* gated than arm64.
            const CaptureRouteSupport gated =
                (CaptureRouteSupportRank(route.support) >
                 CaptureRouteSupportRank(CaptureRouteSupport::kDeviceTargetGated))
                    ? route.support
                    : CaptureRouteSupport::kDeviceTargetGated;
            return {gated, kArmv7DirectHookDeviceGatedReason};
        }
        return {route.support, route.unavailable_reason};
    }

    // Every route is now ABI-eligible on armv7: the orchestrator attempts the
    // install and the relocator fails closed per-function on any prologue it
    // cannot provably relocate.
    constexpr bool IsCaptureRouteAbiEligible(const CaptureRouteDescriptor &route,
                                             CaptureHookAbi abi)
    {
        (void)route;
        (void)abi;
        return true;
    }

    constexpr CaptureHookAbi CurrentCaptureHookAbi()
    {
#if defined(__aarch64__)
        return CaptureHookAbi::kArm64;
#elif defined(__arm__)
        return CaptureHookAbi::kArmv7;
#elif defined(__x86_64__) || defined(_M_X64)
        return CaptureHookAbi::kX86_64;
#else
        return CaptureHookAbi::kOther;
#endif
    }

    constexpr const char *CaptureRouteSupportName(CaptureRouteSupport support)
    {
        switch (support)
        {
        case CaptureRouteSupport::kOperational:
            return "operational";
        case CaptureRouteSupport::kDeviceTargetGated:
            return "device_target_process_gated";
        case CaptureRouteSupport::kDeveloperContractOnly:
            return "developer_contract_only";
        case CaptureRouteSupport::kUnsupported:
            return "unsupported";
        }
        return "unsupported";
    }
} // namespace echidna::hooks
