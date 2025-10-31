#include "utils/api_level_probe.h"

#include <cstdlib>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

namespace echidna {
namespace utils {

namespace {
#ifdef __ANDROID__
int ReadApiLevelFromSystemProperty() {
    char sdk[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.build.version.sdk", sdk);
    return std::atoi(sdk);
}
#endif
}  // namespace

int ApiLevelProbe::apiLevel() const {
#ifdef __ANDROID__
    return ReadApiLevelFromSystemProperty();
#else
    const char *sdk = std::getenv("ECHIDNA_ANDROID_API");
    if (!sdk) {
        return 33;  // Assume a modern Android version when running locally.
    }
    return std::atoi(sdk);
#endif
}

}  // namespace utils
}  // namespace echidna
