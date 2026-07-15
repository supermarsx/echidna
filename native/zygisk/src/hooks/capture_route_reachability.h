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

    inline constexpr const char *kArmv7DirectHookUnavailableReason =
        "unsupported_armv7_late_symbol_hooking";

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

    constexpr CaptureRouteAvailability CaptureRouteAvailabilityForAbi(
        const CaptureRouteDescriptor &route,
        CaptureHookAbi abi)
    {
        if (abi == CaptureHookAbi::kArmv7 && IsDirectInlineSymbolRoute(route))
        {
            return {
                CaptureRouteSupport::kUnsupported,
                kArmv7DirectHookUnavailableReason,
            };
        }
        return {route.support, route.unavailable_reason};
    }

    constexpr bool IsCaptureRouteAbiEligible(const CaptureRouteDescriptor &route,
                                             CaptureHookAbi abi)
    {
        return !(abi == CaptureHookAbi::kArmv7 &&
                 IsDirectInlineSymbolRoute(route));
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
