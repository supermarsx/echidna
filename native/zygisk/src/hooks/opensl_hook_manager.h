#pragma once

/**
 * @file opensl_hook_manager.h
 * @brief Hook the stable OpenSL engine entrypoint and wrap recorder interfaces.
 */

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna::hooks
{
    class OpenSLHookManager : public HookManager
    {
    public:
        explicit OpenSLHookManager(utils::PltResolver &resolver);

        bool install() override;
        const char *name() const override { return "OpenSL"; }
        const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

    private:
        utils::PltResolver &resolver_;
        runtime::InlineHook hook_create_engine_;
        HookInstallInfo last_info_;
    };
} // namespace echidna::hooks
