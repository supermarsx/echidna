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
