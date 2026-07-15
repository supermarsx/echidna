#include "hooks/aaudio_hook_manager.h"
#include "hooks/audioflinger_hook_manager.h"
#include "hooks/audiohal_hook_manager.h"
#include "hooks/audiorecord_hook_manager.h"
#include "hooks/capture_route_reachability.h"
#include "hooks/libc_read_hook_manager.h"
#include "hooks/opensl_hook_manager.h"
#include "hooks/tinyalsa_hook_manager.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string_view>

namespace
{
    using namespace echidna::hooks;

    int gFailures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++gFailures;
        }
    }

    void SetAudioHalDeveloperEnvironment()
    {
#ifdef _WIN32
        _putenv_s("ECHIDNA_HAL_SR", "48000");
        _putenv_s("ECHIDNA_HAL_CH", "1");
        _putenv_s("ECHIDNA_HAL_FORMAT", "pcm16");
#else
        setenv("ECHIDNA_HAL_SR", "48000", 1);
        setenv("ECHIDNA_HAL_CH", "1", 1);
        setenv("ECHIDNA_HAL_FORMAT", "pcm16", 1);
#endif
    }

    void CheckOperational(const CaptureRouteDescriptor &route)
    {
        Check(route.support == CaptureRouteSupport::kOperational,
              "normal route must be classified operational");
        Check(route.metadata_source && std::strcmp(route.metadata_source, "none") != 0,
              "operational route must name a stable runtime metadata source");
        Check(route.unavailable_reason && route.unavailable_reason[0] == '\0',
              "operational route must not carry an unsupported reason");
    }
} // namespace

int main()
{
    using namespace echidna::hooks;

    Check(kCaptureRouteMatrix.size() == 8,
          "capture route matrix must enumerate every supported tier and boundary");
    for (size_t i = 0; i < kCaptureRouteMatrix.size(); ++i)
    {
        const CaptureRouteDescriptor *route = kCaptureRouteMatrix[i];
        Check(route && route->id && route->id[0] != '\0',
              "every capture route must have a stable identifier");
        Check(route && route->metadata_source && route->metadata_source[0] != '\0',
              "every capture route must describe its metadata source");
        if (!route || !route->id || !route->metadata_source)
        {
            continue;
        }
        for (size_t j = i + 1; j < kCaptureRouteMatrix.size(); ++j)
        {
            const CaptureRouteDescriptor *other = kCaptureRouteMatrix[j];
            Check(other && other->id &&
                      std::string_view(route->id) != other->id,
                  "capture route identifiers must be unique");
        }
    }

    Check(&AAudioHookManager::kReachability == &kAAudioRoute,
          "AAudio manager must map to AAudio reachability descriptor");
    Check(&OpenSLHookManager::kReachability == &kOpenSlRoute,
          "OpenSL manager must map to OpenSL reachability descriptor");
    Check(&TinyAlsaHookManager::kReachability == &kTinyAlsaRoute,
          "tinyalsa manager must map to tinyalsa reachability descriptor");
    CheckOperational(kAAudioRoute);
    CheckOperational(kOpenSlRoute);
    Check(kTinyAlsaRoute.support == CaptureRouteSupport::kDeviceTargetGated &&
              std::strcmp(kTinyAlsaRoute.unavailable_reason,
                          "requires_mapped_compatible_tinyalsa_in_target_process") == 0,
          "tinyalsa must report its device and target-process gate honestly");
    CheckOperational(kLsposedJavaAudioRecordRoute);

    Check(&AudioRecordHookManager::kReachability == &kNativeAudioRecordRoute,
          "native AudioRecord manager must map to developer-contract descriptor");
    Check(kNativeAudioRecordRoute.support ==
                  CaptureRouteSupport::kDeveloperContractOnly &&
              std::strcmp(kNativeAudioRecordRoute.metadata_source,
                          "ECHIDNA_AR_SR,ECHIDNA_AR_CH,ECHIDNA_AR_FORMAT") == 0 &&
              std::strcmp(kNativeAudioRecordRoute.unavailable_reason,
                          "developer_contract_only_unconfigured") == 0,
          "native AudioRecord must not claim normal reachability without its contract");
    Check(&LibcReadHookManager::kReachability == &kLibcReadRoute,
          "libc-read manager must map to developer-contract descriptor");
    Check(kLibcReadRoute.support == CaptureRouteSupport::kDeveloperContractOnly &&
              std::strcmp(kLibcReadRoute.metadata_source,
                          "ECHIDNA_LIBC_SR,ECHIDNA_LIBC_CH,ECHIDNA_LIBC_FORMAT") == 0 &&
              std::strcmp(kLibcReadRoute.unavailable_reason,
                          "developer_contract_only_unconfigured") == 0,
          "libc-read must not claim normal reachability without its contract");

    Check(&AudioHalHookManager::kReachability == &kAudioHalRoute &&
              kAudioHalRoute.support == CaptureRouteSupport::kUnsupported,
          "Audio HAL manager must remain unsupported without audioserver injection");
    Check(&AudioFlingerHookManager::kReachability == &kAudioFlingerRoute &&
              kAudioFlingerRoute.support == CaptureRouteSupport::kUnsupported,
          "AudioFlinger manager must remain unsupported without audioserver injection");
    Check(std::strcmp(CaptureRouteSupportName(CaptureRouteSupport::kOperational),
                      "operational") == 0 &&
              std::strcmp(CaptureRouteSupportName(
                              CaptureRouteSupport::kDeviceTargetGated),
                          "device_target_process_gated") == 0 &&
              std::strcmp(CaptureRouteSupportName(
                              CaptureRouteSupport::kDeveloperContractOnly),
                          "developer_contract_only") == 0 &&
              std::strcmp(CaptureRouteSupportName(CaptureRouteSupport::kUnsupported),
                          "unsupported") == 0,
          "diagnostic support labels must be stable");

    echidna::utils::PltResolver resolver;
    SetAudioHalDeveloperEnvironment();
    AudioHalHookManager audio_hal(resolver);
    Check(!audio_hal.install() && !audio_hal.lastInstallInfo().success &&
              audio_hal.lastInstallInfo().reason ==
                  "unsupported_injection_boundary",
          "legacy Audio HAL environment values must not reactivate unreachable coverage");
    AudioFlingerHookManager audio_flinger(resolver);
    Check(!audio_flinger.install() && !audio_flinger.lastInstallInfo().success &&
              audio_flinger.lastInstallInfo().reason ==
                  "unsupported_injection_boundary",
          "AudioFlinger diagnostics must remain explicit unsupported status");

    if (gFailures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "capture_route_reachability_test: all checks passed\n");
    return 0;
}
