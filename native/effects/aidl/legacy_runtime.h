#pragma once

#include <cstdint>
#include <span>
#include <vector>

#include <aidl/android/hardware/audio/effect/Descriptor.h>
#include <aidl/android/hardware/audio/effect/Parameter.h>
#include <aidl/android/media/audio/common/AudioUuid.h>

#include "effect_context.h"

namespace echidna::effects::aidl
{
    inline constexpr char kTypeUuid[] =
        "c83e3db3-d4f5-5f2c-a095-8775c1edfc6d";
    inline constexpr char kImplementationUuid[] =
        "3e66a36e-dee9-5d81-a0d6-49fc3b863530";
    inline constexpr char kEffectName[] = "Echidna Input Preprocessor";
    inline constexpr char kImplementor[] = "Echidna";

    const ::aidl::android::media::audio::common::AudioUuid &TypeUuid();
    const ::aidl::android::media::audio::common::AudioUuid &ImplementationUuid();
    ::aidl::android::hardware::audio::effect::Descriptor Descriptor();

    /**
     * Adapts the Stable AIDL float/FMQ contract to the existing, independently
     * instantiated legacy runtime. Cryptographic capability verification,
     * preset preparation, DSP, and authenticated telemetry therefore have one
     * implementation across the HIDL and AIDL loaders.
     */
    class LegacyRuntime
    {
    public:
        explicit LegacyRuntime(
            const ::aidl::android::hardware::audio::effect::Parameter::Common &common);

        [[nodiscard]] bool ready() const noexcept { return ready_; }
        int32_t Reconfigure(
            const ::aidl::android::hardware::audio::effect::Parameter::Common &common);
        int32_t Start();
        int32_t Stop();
        int32_t Reset();

        // Android 14's framework conversion sends SET value bytes, GET key
        // bytes, and expects GET value bytes.
        int32_t SetValueV1(std::span<const uint8_t> value);
        int32_t GetValueV1(std::span<const uint8_t> key,
                           std::vector<uint8_t> *value);

        // Android 15's conversion preserves the complete effect_param_t for
        // both SET and GET.
        int32_t SetPacketV2(std::span<const uint8_t> packet);
        int32_t GetPacketV2(std::span<const uint8_t> packet,
                            std::vector<uint8_t> *reply);

        // Always writes identity on a rejected block. A false return tells the
        // AIDL adapter that the FMQ block layout itself was invalid.
        [[nodiscard]] bool Process(float *input,
                                   float *output,
                                   int32_t samples) noexcept;

    private:
        int32_t ActivateIfRequested(int32_t mutation_status);
        static bool BuildConfig(
            const ::aidl::android::hardware::audio::effect::Parameter::Common &common,
            effect_config_t *config,
            uint32_t *channels) noexcept;

        ::echidna::effects::legacy::EffectContext context_;
        uint32_t channels_{0};
        bool start_requested_{false};
        bool legacy_enabled_{false};
        bool ready_{false};
    };

} // namespace echidna::effects::aidl
