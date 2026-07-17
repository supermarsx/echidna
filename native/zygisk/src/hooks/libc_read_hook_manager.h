#pragma once

/**
 * @file libc_read_hook_manager.h
 * @brief Hook manager to intercept the libc read() function for detecting
 * audio reads from device files when other hook points are not available.
 */

#include <cstddef>
#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna
{
    namespace hooks
    {

        /**
         * @brief Fallback hook for libc read() to observe audio device reads.
         */
        class LibcReadHookManager : public HookManager
        {
        public:
            static constexpr const CaptureRouteDescriptor &kReachability = kLibcReadRoute;
            explicit LibcReadHookManager(utils::PltResolver &resolver);

            bool install() override;
            const char *name() const override { return "libc_read"; }
            const HookInstallInfo &lastInstallInfo() const override { return last_info_; }
            const CaptureRouteDescriptor &routeDescriptor() const override { return kReachability; }

        private:
            static ssize_t Replacement(int fd, void *buffer, size_t bytes);
            // Resolve @p symbol from libc.so and inline-hook it into @p hook,
            // publishing the trampoline via @p original. Returns false (leaving
            // the route usable) when the symbol is absent or the patch fails.
            bool InstallLifecycleHook(const char *symbol,
                                      void *replacement,
                                      void **original,
                                      runtime::InlineHook &hook);
            utils::PltResolver &resolver_;
            runtime::InlineHook hook_;
            // fd-lifecycle hooks that keep the per-fd verdict cache honest under
            // fd reuse: close evicts, dup/dup2/dup3 alias. Best-effort — read
            // still installs if these are unavailable, but the cache then falls
            // back to per-read classification (see the .cpp) rather than trusting
            // a verdict that a missed close/dup2 could have made stale.
            runtime::InlineHook close_hook_;
            runtime::InlineHook dup_hook_;
            runtime::InlineHook dup2_hook_;
            runtime::InlineHook dup3_hook_;
            HookInstallInfo last_info_;
        };

    } // namespace hooks
} // namespace echidna
