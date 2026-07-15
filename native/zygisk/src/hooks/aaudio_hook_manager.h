#pragma once

/**
 * @file aaudio_hook_manager.h
 * @brief Hook manager for AAudio stream read/write and data callback
 * registration. Uses inline hooks to route capture buffers through Echidna.
 */

#include <cstdint>

#include "hooks/hook_manager.h"
#include "runtime/inline_hook.h"
#include "utils/plt_resolver.h"

namespace echidna
{
    namespace hooks
    {

        class AAudioHookManager : public HookManager
        {
        public:
            explicit AAudioHookManager(utils::PltResolver &resolver);

            /** Attempt to install AAudio inline hooks. */
            bool install() override;
            const char *name() const override { return "AAudio"; }
            const HookInstallInfo &lastInstallInfo() const override { return last_info_; }

        private:
            /** Replacement callback invoked instead of the original AAudio dataCallback. */
            static int Replacement(void *stream, void *user, void *audio_data, int32_t num_frames);
            static void ReplacementSetDataCallback(void *builder,
                                                   void *callback,
                                                   void *user_data);
            static int32_t ReplacementRead(void *stream,
                                           void *buffer,
                                           int32_t frames,
                                           int64_t timeout_ns);
            static int32_t ReplacementWrite(void *stream,
                                            const void *buffer,
                                            int32_t frames,
                                            int64_t timeout_ns);

            utils::PltResolver &resolver_;
            runtime::InlineHook hook_set_data_callback_;
            runtime::InlineHook hook_read_;
            runtime::InlineHook hook_write_;
            HookInstallInfo last_info_;
        };

    } // namespace hooks
} // namespace echidna
