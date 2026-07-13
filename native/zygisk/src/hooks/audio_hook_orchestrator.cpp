#include "hooks/audio_hook_orchestrator.h"

/**
 * @file audio_hook_orchestrator.cpp
 * @brief Orchestrator implementation deciding which hook managers to attempt
 * and recording telemetry about installation attempts.
 */

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <string>
#include <time.h>

#include "state/shared_state.h"
#include "utils/process_utils.h"

namespace echidna
{
    namespace hooks
    {

        AudioHookOrchestrator::AudioHookOrchestrator()
            : aaudio_manager_(resolver_),
              opensl_manager_(resolver_),
              audiorecord_manager_(resolver_),
              audioflinger_manager_(resolver_),
              libc_read_manager_(resolver_),
              tinyalsa_manager_(resolver_),
              audiohal_manager_(resolver_) {}

        bool AudioHookOrchestrator::attempt(HookManager &manager)
        {
            auto monotonic_now = []()
            {
                timespec ts{};
                clock_gettime(CLOCK_MONOTONIC, &ts);
                return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
                       static_cast<uint64_t>(ts.tv_nsec);
            };

            const bool success = manager.install();
            const auto &info = manager.lastInstallInfo();
            state::SharedState::instance().telemetry().registerHookResult(
                manager.name(),
                success,
                monotonic_now(),
                info.library,
                info.symbol,
                info.reason);
            __android_log_print(success ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                                "echidna",
                                "hook install result name='%s' success=%d library='%s' "
                                "symbol='%s' reason='%s'",
                                manager.name(),
                                success ? 1 : 0,
                                info.library.c_str(),
                                info.symbol.c_str(),
                                info.reason.c_str());
            return success;
        }

        bool AudioHookOrchestrator::installHooks()
        {
            auto &state = state::SharedState::instance();
            state.refreshFromSharedMemory();
            const std::string &process = utils::CachedProcessName();
            const bool hooks_enabled = state.hooksEnabled();
            const bool process_whitelisted = state.isProcessWhitelisted(process);

            if (!hooks_enabled || !process_whitelisted)
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    "echidna",
                                    "hook gate disabled process='%s' hooks=%d whitelisted=%d",
                                    process.c_str(),
                                    hooks_enabled ? 1 : 0,
                                    process_whitelisted ? 1 : 0);
                state.setStatus(state::InternalStatus::kDisabled);
                return false;
            }

            state.setStatus(state::InternalStatus::kWaitingForAttach);

            // Attempt every eligible manager. Some apps can touch multiple
            // audio APIs in one process, so the first successful hook must not
            // prevent lower-priority capture paths from being installed.
            HookManager *managers[] = {
                shouldAttemptAAudio() ? static_cast<HookManager *>(&aaudio_manager_) : nullptr,
                &opensl_manager_,
                &audioflinger_manager_,
                &audiorecord_manager_,
                &libc_read_manager_,
                &tinyalsa_manager_,
                &audiohal_manager_,
            };

            bool installed_any = false;
            for (HookManager *manager : managers)
            {
                if (manager != nullptr)
                {
                    installed_any = attempt(*manager) || installed_any;
                }
            }

            if (installed_any)
            {
                state.setStatus(state::InternalStatus::kHooked);
                return true;
            }

            state.setStatus(state::InternalStatus::kError);
            return false;
        }

        bool AudioHookOrchestrator::shouldAttemptAAudio() const
        {
            return api_probe_.apiLevel() >= 26;
        }

    } // namespace hooks
} // namespace echidna
