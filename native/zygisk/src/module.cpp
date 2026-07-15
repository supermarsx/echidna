#include "hooks/audio_hook_orchestrator.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <zygisk.hpp>

#include "runtime/activation_gate.h"
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
    constexpr const char *kCompanionPackage = "com.echidna.app";
    constexpr auto kInitialHookRetryDelay = std::chrono::milliseconds(250);
    constexpr auto kMaximumHookRetryDelay = std::chrono::milliseconds(30000);

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

    std::unique_ptr<echidna::hooks::AudioHookOrchestrator> g_audio_orchestrator;
    std::unique_ptr<echidna::runtime::ProfileSyncServer> g_profile_server;
    std::thread g_activation_worker;
    std::mutex g_runtime_mutex;
    std::mutex g_activation_mutex;
    std::condition_variable g_activation_changed;
    echidna::runtime::ActivationGate g_activation_gate;
    uint64_t g_policy_revision{0};
    std::atomic<int> g_lifecycle_phase{static_cast<int>(LifecyclePhase::kNotLoaded)};
    std::atomic<bool> g_hooks_installed{false};
    std::atomic<bool> g_process_on_denylist{false};
    std::atomic<int64_t> g_trusted_publisher_uid{-1};

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

    void ActivationWorker()
    {
        auto retry_delay = kInitialHookRetryDelay;
        std::unique_lock lock(g_activation_mutex);
        while (g_activation_gate.running())
        {
            g_activation_changed.wait(lock, []()
                                      {
                                          return !g_activation_gate.running() ||
                                                 g_activation_gate.shouldAttemptInstall();
                                      });
            if (!g_activation_gate.running())
            {
                break;
            }

            const uint64_t attempt_revision = g_policy_revision;
            lock.unlock();
            const bool installed =
                g_audio_orchestrator != nullptr && g_audio_orchestrator->installHooks();
            lock.lock();

            if (!g_activation_gate.running())
            {
                break;
            }
            auto &state = echidna::state::SharedState::instance();
            if (installed)
            {
                g_activation_gate.markHooksInstalled();
                g_hooks_installed.store(true, std::memory_order_release);
                retry_delay = kInitialHookRetryDelay;
                if (g_activation_gate.admitted())
                {
                    state.setStatus(echidna::state::InternalStatus::kHooked);
                    const std::string process = echidna::utils::CachedProcessName();
                    RecordLifecycle(LifecyclePhase::kAttached, process.c_str());
                }
                else
                {
                    state.setStatus(echidna::state::InternalStatus::kDisabled);
                }
                continue;
            }

            if (g_activation_gate.admitted())
            {
                state.setStatus(echidna::state::InternalStatus::kError);
            }
            const bool policy_changed = g_activation_changed.wait_for(
                lock,
                retry_delay,
                [attempt_revision]()
                {
                    return !g_activation_gate.running() ||
                           !g_activation_gate.admitted() ||
                           g_activation_gate.hooksInstalled() ||
                           g_policy_revision != attempt_revision;
                });
            if (policy_changed)
            {
                retry_delay = kInitialHookRetryDelay;
            }
            else
            {
                retry_delay = std::min(retry_delay * 2, kMaximumHookRetryDelay);
            }
        }
    }

    void StartActivationWorker()
    {
        std::scoped_lock lock(g_activation_mutex);
        if (g_activation_gate.running())
        {
            return;
        }
        g_activation_gate = echidna::runtime::ActivationGate{};
        g_activation_gate.start();
        ++g_policy_revision;
        g_activation_worker = std::thread(ActivationWorker);
    }

    void RequestActivationStop()
    {
        {
            std::scoped_lock lock(g_activation_mutex);
            g_activation_gate.stop();
            ++g_policy_revision;
        }
        g_activation_changed.notify_all();
    }

    void JoinActivationWorker()
    {
        if (g_activation_worker.joinable())
        {
            g_activation_worker.join();
        }
    }

    void ResetRuntimeObjects(const char *reason)
    {
        std::scoped_lock runtime_lock(g_runtime_mutex);
        auto &state = echidna::state::SharedState::instance();
        if (g_profile_server)
        {
            // Stop accepting frames, wait out/detach callbacks, revoke the real
            // audio gate, and interrupt I/O before either worker is joined.
            g_profile_server->beginStop();
        }
        // The reader is now unable to republish. Close the lifecycle gate without
        // joining so no in-flight callback/install can schedule another attempt.
        RequestActivationStop();
        state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        state.setStatus(echidna::state::InternalStatus::kDisabled);
        if (g_profile_server)
        {
            g_profile_server->finishStop();
            g_profile_server.reset();
        }
        // The reader publishes process state before invoking its callback. Clear
        // once more after join so an in-flight final frame cannot leave already-
        // installed trampolines admitted after teardown completes.
        state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        state.setStatus(echidna::state::InternalStatus::kDisabled);
        // An install already in flight may finish, but all real callback gates
        // have remained revoked throughout. Join before destroying its owner.
        JoinActivationWorker();
        g_audio_orchestrator.reset();
        g_hooks_installed.store(false, std::memory_order_release);
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

    void OnProfileSnapshot(const echidna::runtime::DecodedProfileSnapshot &snapshot)
    {
        // Profile callbacks run on the control thread. Gate and replace every
        // live route engine before native capture admission changes.
        (void)echidna::hooks::PublishAAudioProfile(snapshot);
        (void)echidna::hooks::PublishOpenSLProfile(snapshot);
        (void)echidna::hooks::PublishTinyAlsaProfile(snapshot);
        auto &state = echidna::state::SharedState::instance();
        {
            std::scoped_lock lock(g_activation_mutex);
            if (!g_activation_gate.running())
            {
                return;
            }
            g_activation_gate.updatePolicy(snapshot.nativeProcessAdmitted());
            ++g_policy_revision;
            if (!g_activation_gate.admitted())
            {
                state.setStatus(echidna::state::InternalStatus::kDisabled);
                RecordLifecycle(LifecyclePhase::kDisabled, "profile policy denied native owner");
            }
            else if (g_activation_gate.hooksInstalled())
            {
                state.setStatus(echidna::state::InternalStatus::kHooked);
            }
            else
            {
                state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);
            }
        }
        g_activation_changed.notify_all();
    }

    void StartEchidnaRuntime()
    {
        std::scoped_lock runtime_lock(g_runtime_mutex);
        if (g_profile_server)
        {
            return;
        }

        auto &state = echidna::state::SharedState::instance();
        // V2 socket policy is authoritative. Start from an explicit empty
        // process-local snapshot rather than reviving legacy file state; this
        // also makes repeated public attach calls unable to undo a revoke.
        state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);

        if (!g_audio_orchestrator)
        {
            g_audio_orchestrator = std::make_unique<echidna::hooks::AudioHookOrchestrator>();
        }
        StartActivationWorker();

        g_profile_server = std::make_unique<echidna::runtime::ProfileSyncServer>(
            echidna::utils::CachedProcessName(),
            OnProfileSnapshot,
            echidna::runtime::ProfileSyncServer::PresetApplier{},
            g_trusted_publisher_uid.load(std::memory_order_acquire));
        g_profile_server->start();
        // Cold publisher startup is not a terminal policy decision. Keep
        // app specialization non-blocking and the reconnect reader resident,
        // with audio admission fail-closed until a valid frame arrives.
        // This currently costs one sleeping reader thread per specialized app;
        // bounded exponential backoff limits wakeups, but a future privileged
        // broker should fan policy out without per-app reconnect workers.
        RecordLifecycle(LifecyclePhase::kDisabled, "awaiting profile publisher");
    }

    void MarkSpecializationDisabled(const char *reason)
    {
        auto &state = echidna::state::SharedState::instance();
        // Denylisted/system-server paths must not revive the deprecated shared-
        // file policy now that negotiated v2 snapshots are authoritative.
        state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        state.setStatus(echidna::state::InternalStatus::kDisabled);
        RecordLifecycle(LifecyclePhase::kDisabled, reason);
    }

} // namespace

/**
 * @brief Attach entrypoint that initializes the hooking machinery inside the
 * target process. Idempotent: creates the process-local profile reader,
 * activation worker, and hook orchestrator once. Hook installation is deferred
 * until a valid v2 snapshot admits this process and the Zygisk owner.
 */
extern "C" void echidna_module_attach()
{
    StartEchidnaRuntime();
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
            g_trusted_publisher_uid.store(-1, std::memory_order_release);
            RecordLifecycle(LifecyclePhase::kLoaded);
            // Deliberately do NOT set zygisk::DLCLOSE_MODULE_LIBRARY: this module
            // installs long-lived inline/PLT hooks, so its library must stay
            // mapped after post[XXX]Specialize returns. Leaving the option unset
            // by default keeps the code resident and the hooks live for hooked
            // processes. Denylisted and system-server paths request dlclose;
            // ordinary apps retain only a fail-closed reconnect reader so a cold
            // publisher can safely activate them later.
        }

        void preAppSpecialize(zygisk::AppSpecializeArgs *args) override
        {
            // Keep pre-specialization work limited to Zygisk state checks.
            // Hook installation waits until the app identity/process name exist.
            RecordLifecycle(LifecyclePhase::kPreAppSpecialize);
            CacheSpecializedProcessName(env_, args, "preAppSpecialize");

            // This callback still has zygote privileges. Cache a kernel UID
            // expectation from PackageManager's root-owned package registry,
            // projected into the target Android user, before sandboxing removes
            // access. An unresolved identity keeps socket sync fail-closed.
            const int64_t package_uid = echidna::utils::ResolvePackageUid(kCompanionPackage);
            const int64_t publisher_uid = args == nullptr
                                              ? -1
                                              : echidna::utils::PackageUidForTargetUser(
                                                    package_uid,
                                                    static_cast<int64_t>(args->uid));
            g_trusted_publisher_uid.store(publisher_uid, std::memory_order_release);
            if (publisher_uid < 0)
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "trusted publisher UID unresolved; profile sync disabled");
            }

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

            // No process runtime/thread is created before specialization. A cold
            // 750 ms publisher miss now leaves this app inert and reconnecting;
            // valid later policy wakes the serialized activation worker.
            StartEchidnaRuntime();
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
