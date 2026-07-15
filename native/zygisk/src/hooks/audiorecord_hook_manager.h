#pragma once

/**
 * @file audiorecord_hook_manager.h
 * @brief Exact-ABI native AudioRecord synchronous-read hook manager.
 */

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna::hooks
{
    class AudioRecordHookManager : public HookManager
    {
    public:
        explicit AudioRecordHookManager(utils::PltResolver &resolver);

        bool install() override;
        const char *name() const override { return "AudioRecord"; }
        const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

    private:
        utils::PltResolver &resolver_;
        runtime::InlineHook hook_;
        HookInstallInfo last_info_;
    };
} // namespace echidna::hooks
