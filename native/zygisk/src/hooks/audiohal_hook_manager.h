#pragma once

/**
 * @file audiohal_hook_manager.h
 * @brief Hook into platform Audio HAL read entrypoints.
 */

#include "hooks/hook_manager.h"
#include "utils/plt_resolver.h"

namespace echidna
{
    namespace hooks
    {

        /**
         * @brief Fallback hook for audio HAL stream reads (audio_stream_in_read).
         */
        class AudioHalHookManager : public HookManager
        {
        public:
            static constexpr const CaptureRouteDescriptor &kReachability = kAudioHalRoute;
            explicit AudioHalHookManager(utils::PltResolver &resolver);

            bool install() override;
            const char *name() const override { return "audiohal_stream_read"; }
            const HookInstallInfo &lastInstallInfo() const override { return last_info_; }
            const CaptureRouteDescriptor &routeDescriptor() const override { return kReachability; }

        private:
            utils::PltResolver &resolver_;
            HookInstallInfo last_info_;
        };

    } // namespace hooks
} // namespace echidna
