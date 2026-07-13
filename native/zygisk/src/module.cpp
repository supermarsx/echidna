#include "hooks/audio_hook_orchestrator.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

#include <zygisk.hpp>

#include "runtime/profile_sync_server.h"
#include "state/shared_state.h"
#include "utils/process_utils.h"

/**
 * @file module.cpp
 * @brief Zygisk module entrypoints. Registers Echidna as a genuine Zygisk
 * module (REGISTER_ZYGISK_MODULE) and drives the existing hook lifecycle
 * (echidna_module_attach) from the official specialization callbacks.
 */

namespace
{

    constexpr const char *kLogTag = "echidna_zygisk";

    enum class LifecyclePhase : int
    {
        kNotLoaded = 0,
        kLoaded,
        kPreAppSpecialize,
        kPostAppSpecialize,
        kPreServerSpecialize,
        kPostServerSpecialize,
        kAttached,
        kDisabled,
        kError,
        kReset,
    };

    enum class AttachResult : int
    {
        kHooked,
        kDisabled,
        kError,
    };

    std::unique_ptr<echidna::hooks::AudioHookOrchestrator> g_audio_orchestrator;
    std::unique_ptr<echidna::runtime::ProfileSyncServer> g_profile_server;
    std::atomic<int> g_lifecycle_phase{static_cast<int>(LifecyclePhase::kNotLoaded)};
    std::atomic<bool> g_hooks_installed{false};
    std::atomic<bool> g_process_on_denylist{false};

    const char *LifecyclePhaseName(LifecyclePhase phase)
    {
        switch (phase)
        {
        case LifecyclePhase::kNotLoaded:
            return "not_loaded";
        case LifecyclePhase::kLoaded:
            return "loaded";
        case LifecyclePhase::kPreAppSpecialize:
            return "pre_app_specialize";
        case LifecyclePhase::kPostAppSpecialize:
            return "post_app_specialize";
        case LifecyclePhase::kPreServerSpecialize:
            return "pre_server_specialize";
        case LifecyclePhase::kPostServerSpecialize:
            return "post_server_specialize";
        case LifecyclePhase::kAttached:
            return "attached";
        case LifecyclePhase::kDisabled:
            return "disabled";
        case LifecyclePhase::kError:
            return "error";
        case LifecyclePhase::kReset:
            return "reset";
        }
        return "unknown";
    }

    void RecordLifecycle(LifecyclePhase phase, const char *detail = nullptr)
    {
        g_lifecycle_phase.store(static_cast<int>(phase), std::memory_order_release);
        if (detail != nullptr && detail[0] != '\0')
        {
            __android_log_print(ANDROID_LOG_INFO,
                                kLogTag,
                                "lifecycle=%s detail=%s",
                                LifecyclePhaseName(phase),
                                detail);
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "lifecycle=%s", LifecyclePhaseName(phase));
    }

    uint32_t ReadZygiskFlags(zygisk::Api *api, const char *phase)
    {
        if (api == nullptr)
        {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s: zygisk API unavailable", phase);
            return 0;
        }

        const uint32_t flags = api->getFlags();
        if (flags != 0)
        {
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s: zygisk flags=0x%x", phase, flags);
        }
        return flags;
    }

    void RequestModuleDlclose(zygisk::Api *api, const char *reason)
    {
        if (api == nullptr)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "dlclose request skipped without API: %s",
                                reason);
            return;
        }
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        __android_log_print(ANDROID_LOG_INFO,
                            kLogTag,
                            "requested zygisk dlclose: %s",
                            reason);
    }

    void ResetRuntimeObjects(const char *reason)
    {
        g_hooks_installed.store(false, std::memory_order_release);
        g_audio_orchestrator.reset();
        if (g_profile_server)
        {
            g_profile_server->stop();
            g_profile_server.reset();
        }
        RecordLifecycle(LifecyclePhase::kReset, reason);
    }

    std::string JStringToString(JNIEnv *env, jstring value)
    {
        if (env == nullptr || value == nullptr)
        {
            return {};
        }

        const char *chars = env->GetStringUTFChars(value, nullptr);
        if (chars == nullptr)
        {
            return {};
        }

        std::string result(chars);
        env->ReleaseStringUTFChars(value, chars);
        return result;
    }

    void CacheSpecializedProcessName(JNIEnv *env,
                                     const zygisk::AppSpecializeArgs *args,
                                     const char *phase)
    {
        if (args == nullptr)
        {
            return;
        }

        std::string process_name = JStringToString(env, args->nice_name);
        if (process_name.empty())
        {
            return;
        }

        echidna::utils::SetCachedProcessName(process_name);
        __android_log_print(ANDROID_LOG_INFO,
                            kLogTag,
                            "%s: process=%s",
                            phase,
                            process_name.c_str());
    }

    AttachResult AttachEchidnaRuntime()
    {
        auto &state = echidna::state::SharedState::instance();
        state.refreshFromSharedMemory();
        state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);

        if (!g_profile_server)
        {
            g_profile_server = std::make_unique<echidna::runtime::ProfileSyncServer>();
            g_profile_server->start();
        }

        if (!g_audio_orchestrator)
        {
            g_audio_orchestrator = std::make_unique<echidna::hooks::AudioHookOrchestrator>();
        }

        if (!g_audio_orchestrator->installHooks())
        {
            if (state.status() == static_cast<int>(echidna::state::InternalStatus::kDisabled))
            {
                return AttachResult::kDisabled;
            }
            state.setStatus(echidna::state::InternalStatus::kError);
            return AttachResult::kError;
        }

        g_hooks_installed.store(true, std::memory_order_release);
        return AttachResult::kHooked;
    }

    void MarkSpecializationDisabled(const char *reason)
    {
        auto &state = echidna::state::SharedState::instance();
        state.refreshFromSharedMemory();
        state.setStatus(echidna::state::InternalStatus::kDisabled);
        RecordLifecycle(LifecyclePhase::kDisabled, reason);
    }

} // namespace

/**
 * @brief Attach entrypoint that initializes the hooking machinery inside the
 * target process. Idempotent: creates the profile-sync server and the audio
 * hook orchestrator once, then attempts hook installation. Target-selection is
 * enforced downstream by AudioHookOrchestrator::installHooks(), which consults
 * the shared configuration (global enable + per-process whitelist) and returns
 * false (status kDisabled) for processes that must not be hooked.
 */
extern "C" void echidna_module_attach()
{
    (void)AttachEchidnaRuntime();
}

namespace
{

    /**
     * @brief The Echidna Zygisk module.
     *
     * Zygote forks into every app process and passes the target name through
     * AppSpecializeArgs. Cache that value before hook installation; some Zygisk
     * runtimes still expose /proc/self/cmdline as zygote64 during the callback.
     * The per-process whitelist gate lives in installHooks(), so we do not hook
     * unconditionally.
     */
    class EchidnaModule : public zygisk::ModuleBase
    {
    public:
        void onLoad(zygisk::Api *api, JNIEnv *env) override
        {
            api_ = api;
            env_ = env;
            g_process_on_denylist.store(false, std::memory_order_release);
            g_hooks_installed.store(false, std::memory_order_release);
            RecordLifecycle(LifecyclePhase::kLoaded);
            // Deliberately do NOT set zygisk::DLCLOSE_MODULE_LIBRARY: this module
            // installs long-lived inline/PLT hooks, so its library must stay
            // mapped after post[XXX]Specialize returns. Leaving the option unset
            // by default keeps the code resident and the hooks live for hooked
            // processes; known no-hook paths request dlclose in their callbacks.
        }

        void preAppSpecialize(zygisk::AppSpecializeArgs *args) override
        {
            // Keep pre-specialization work limited to Zygisk state checks.
            // Hook installation waits until the app identity/process name exist.
            RecordLifecycle(LifecyclePhase::kPreAppSpecialize);
            CacheSpecializedProcessName(env_, args, "preAppSpecialize");

            const uint32_t flags = ReadZygiskFlags(api_, "preAppSpecialize");
            const bool on_denylist = (flags & zygisk::PROCESS_ON_DENYLIST) != 0;
            g_process_on_denylist.store(on_denylist, std::memory_order_release);
            if (on_denylist)
            {
                RequestModuleDlclose(api_, "zygisk denylist");
            }
        }

        void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override
        {
            RecordLifecycle(LifecyclePhase::kPostAppSpecialize);
            CacheSpecializedProcessName(env_, args, "postAppSpecialize");
            if (g_process_on_denylist.load(std::memory_order_acquire))
            {
                MarkSpecializationDisabled("zygisk denylist");
                ResetRuntimeObjects("zygisk denylist");
                resetZygiskHandles();
                return;
            }

            // Start the hook lifecycle now that the process has been specialized
            // into the target app. Whitelist/enable gating is enforced inside
            // echidna_module_attach() -> installHooks().
            const AttachResult result = AttachEchidnaRuntime();
            switch (result)
            {
            case AttachResult::kHooked:
                RecordLifecycle(LifecyclePhase::kAttached,
                                echidna::utils::CachedProcessName().c_str());
                break;
            case AttachResult::kDisabled:
                RequestModuleDlclose(api_, "hooks disabled or process not whitelisted");
                ResetRuntimeObjects("hooks disabled or process not whitelisted");
                break;
            case AttachResult::kError:
                RecordLifecycle(LifecyclePhase::kError,
                                echidna::utils::CachedProcessName().c_str());
                break;
            }
            resetZygiskHandles();
        }

        void preServerSpecialize(zygisk::ServerSpecializeArgs *args) override
        {
            (void)args;
            RecordLifecycle(LifecyclePhase::kPreServerSpecialize, "system_server");
            (void)ReadZygiskFlags(api_, "preServerSpecialize");
            RequestModuleDlclose(api_, "system_server unsupported");
        }

        void postServerSpecialize(const zygisk::ServerSpecializeArgs *args) override
        {
            (void)args;
            RecordLifecycle(LifecyclePhase::kPostServerSpecialize, "system_server");
            MarkSpecializationDisabled("system_server unsupported");
            ResetRuntimeObjects("system_server unsupported");
            resetZygiskHandles();
        }

    private:
        void resetZygiskHandles()
        {
            api_ = nullptr;
            env_ = nullptr;
        }

        zygisk::Api *api_{nullptr};
        JNIEnv *env_{nullptr};
    };

} // namespace

// Register EchidnaModule as the Zygisk module entrypoint. This emits the
// exported `zygisk_module_entry` symbol that Zygisk looks up when loading the
// library, making libechidna.so a genuine Zygisk module.
REGISTER_ZYGISK_MODULE(EchidnaModule)
