#pragma once

/**
 * @file aaudio_hook_manager.h
 * @brief Hook manager for synchronous and callback-driven AAudio capture.
 */

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna::runtime
{
    struct DecodedProfileSnapshot;
}

namespace echidna::hooks
{
    class AAudioHookManager : public HookManager
    {
    public:
        static constexpr const CaptureRouteDescriptor &kReachability = kAAudioRoute;
        explicit AAudioHookManager(utils::PltResolver &resolver);

        bool install() override;
        const char *name() const override { return "AAudio"; }
        const HookInstallInfo &lastInstallInfo() const override { return last_info_; }
        const CaptureRouteDescriptor &routeDescriptor() const override { return kReachability; }

    private:
        utils::PltResolver &resolver_;
        runtime::InlineHook hook_set_data_callback_;
        runtime::InlineHook hook_open_stream_;
        runtime::InlineHook hook_close_stream_;
        runtime::InlineHook hook_delete_builder_;
        runtime::InlineHook hook_read_;
        HookInstallInfo last_info_;
    };

    /** Publishes one already-authenticated process snapshot to live AAudio streams. */
    bool PublishAAudioProfile(const runtime::DecodedProfileSnapshot &snapshot);
} // namespace echidna::hooks
