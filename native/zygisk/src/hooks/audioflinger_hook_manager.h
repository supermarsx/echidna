#pragma once

/**
 * @file audioflinger_hook_manager.h
 * @brief Reports the currently unsupported AudioFlinger service boundary.
 */

#include "hooks/hook_manager.h"
#include "utils/plt_resolver.h"

namespace echidna::hooks
{
    class AudioFlingerHookManager : public HookManager
    {
    public:
        static constexpr const CaptureRouteDescriptor &kReachability = kAudioFlingerRoute;
        explicit AudioFlingerHookManager(utils::PltResolver &resolver);

        bool install() override;
        const char *name() const override { return "AudioFlinger_RecordThread"; }
        const HookInstallInfo &lastInstallInfo() const override { return last_info_; }
        const CaptureRouteDescriptor &routeDescriptor() const override
        {
            return kReachability;
        }

    private:
        utils::PltResolver &resolver_;
        HookInstallInfo last_info_;
    };
} // namespace echidna::hooks
