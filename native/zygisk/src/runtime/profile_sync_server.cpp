#include "runtime/profile_sync_server.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <exception>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <utility>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/config_shared_memory.h"
#include "utils/process_utils.h"

namespace
{
    constexpr const char *kSocketName = "echidna_profiles";
    constexpr const char *kLogTag = "echidna_profile_sync";
    constexpr int kReconnectDelayMs = 1000;
    constexpr int kInitialReadTimeoutMs = 750;

    bool SetReadTimeout(int fd, int timeout_ms)
    {
        timeval timeout{};
        timeout.tv_sec = timeout_ms / 1000;
        timeout.tv_usec = (timeout_ms % 1000) * 1000;
        return ::setsockopt(fd,
                            SOL_SOCKET,
                            SO_RCVTIMEO,
                            &timeout,
                            sizeof(timeout)) == 0;
    }

    bool SendBytes(int fd, std::string_view bytes)
    {
        size_t sent = 0;
        while (sent < bytes.size())
        {
#ifdef MSG_NOSIGNAL
            constexpr int kSendFlags = MSG_NOSIGNAL;
#else
            constexpr int kSendFlags = 0;
#endif
            const ssize_t result =
                ::send(fd, bytes.data() + sent, bytes.size() - sent, kSendFlags);
            if (result < 0 && errno == EINTR)
            {
                continue;
            }
            if (result <= 0)
            {
                return false;
            }
            sent += static_cast<size_t>(result);
        }
        return true;
    }

    bool ReadBytes(int fd, void *buffer, size_t bytes)
    {
        auto *output = static_cast<uint8_t *>(buffer);
        size_t received = 0;
        while (received < bytes)
        {
            const ssize_t result = ::recv(fd, output + received, bytes - received, 0);
            if (result < 0 && errno == EINTR)
            {
                continue;
            }
            if (result <= 0)
            {
                return false;
            }
            received += static_cast<size_t>(result);
        }
        return true;
    }

    int ConnectPublisher()
    {
        const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0)
        {
            return -1;
        }

        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        const size_t name_length = std::strlen(kSocketName);
        if (name_length + 1 >= sizeof(address.sun_path))
        {
            ::close(fd);
            errno = ENAMETOOLONG;
            return -1;
        }
        address.sun_path[0] = '\0';
        std::memcpy(address.sun_path + 1, kSocketName, name_length);
        const auto address_length = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + name_length);
        if (::connect(fd, reinterpret_cast<sockaddr *>(&address), address_length) != 0 ||
            !SendBytes(fd, echidna::runtime::kProfileSyncV2ZygiskHello))
        {
            ::close(fd);
            return -1;
        }
        return fd;
    }

    bool ReceiveFrame(int fd, std::string *payload)
    {
        uint32_t network_length = 0;
        if (!ReadBytes(fd, &network_length, sizeof(network_length)))
        {
            return false;
        }
        const uint32_t length = ntohl(network_length);
        if (length == 0 || length > echidna::runtime::kProfileSyncMaxEnvelopeBytes)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Rejected profile frame length: %u",
                                length);
            return false;
        }
        payload->assign(length, '\0');
        return ReadBytes(fd, payload->data(), payload->size());
    }

    uint64_t NowEpochMs()
    {
        return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                         std::chrono::system_clock::now().time_since_epoch())
                                         .count());
    }

    bool ApplyPreset(std::string_view preset)
    {
        const echidna_result_t result = echidna_set_profile(preset.data(), preset.size());
        return result == ECHIDNA_RESULT_OK || result == ECHIDNA_RESULT_NOT_INITIALISED;
    }

} // namespace

namespace echidna::runtime
{
    ProfileSyncServer::ProfileSyncServer()
        : ProfileSyncServer(utils::CachedProcessName(), {}, {})
    {
    }

    ProfileSyncServer::ProfileSyncServer(std::string process_name,
                                         SnapshotCallback callback,
                                         PresetApplier preset_applier)
        : process_name_(std::move(process_name)),
          callback_(std::move(callback)),
          preset_applier_(std::move(preset_applier))
    {
        if (!preset_applier_)
        {
            preset_applier_ = ApplyPreset;
        }
    }

    ProfileSyncServer::~ProfileSyncServer()
    {
        stop();
    }

    bool ProfileSyncServer::refreshOnce()
    {
        const int fd = ConnectPublisher();
        if (fd < 0)
        {
            return false;
        }
        (void)SetReadTimeout(fd, kInitialReadTimeoutMs);
        const bool received = readAndApply(fd);
        ::close(fd);
        return received && hasSnapshot();
    }

    bool ProfileSyncServer::applyPayload(std::string_view payload)
    {
        DecodedProfileSnapshot candidate;
        std::string error;
        if (!DecodeProfileSyncV2(payload,
                                 process_name_,
                                 NowEpochMs(),
                                 &candidate,
                                 &error))
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Rejected v2 profile snapshot: %s",
                                error.c_str());
            return false;
        }

        // Prepare every allocation needed for the retained generation before
        // applying the preset or publishing admission state.
        std::string retained_payload(payload);
        DecodedProfileSnapshot retained_snapshot(candidate);

        SnapshotCallback callback;
        {
            std::scoped_lock lock(state_mutex_);
            const GenerationDecision decision = EvaluateGeneration(candidate.generation,
                                                                   payload,
                                                                   generation_,
                                                                   generation_payload_);
            if (decision == GenerationDecision::kDuplicate)
            {
                return true;
            }
            if (decision == GenerationDecision::kRejectRollback ||
                decision == GenerationDecision::kRejectConflict)
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Rejected profile generation=%llu current=%llu reason=%s",
                                    static_cast<unsigned long long>(candidate.generation),
                                    static_cast<unsigned long long>(generation_),
                                    decision == GenerationDecision::kRejectRollback
                                        ? "rollback"
                                        : "same_generation_conflict");
                return false;
            }

            if (candidate.nativeProcessAdmitted() && !preset_applier_(candidate.preset_json))
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Rejected generation=%llu: selected preset was not accepted",
                                    static_cast<unsigned long long>(candidate.generation));
                return false;
            }

            utils::ConfigurationSnapshot configuration;
            configuration.hooks_enabled = candidate.global_hooks_enabled;
            if (candidate.process_whitelisted &&
                candidate.capture_owner == CaptureOwner::kZygisk)
            {
                configuration.process_whitelist.push_back(process_name_);
            }
            configuration.profile = candidate.profile_id;

            // The socket snapshot is process-specific. Publish directly to the
            // process-local singleton only after every validation and preset step
            // succeeds; do not leak one process's selected profile into a global map.
            state::SharedState::instance().updateConfiguration(configuration);

            generation_ = candidate.generation;
            generation_payload_ = std::move(retained_payload);
            current_snapshot_ = std::move(retained_snapshot);
            has_snapshot_ = true;
            callback = callback_;
        }

        if (callback)
        {
            callback(candidate);
        }
        return true;
    }

    bool ProfileSyncServer::hasSnapshot() const
    {
        std::scoped_lock lock(state_mutex_);
        return has_snapshot_;
    }

    bool ProfileSyncServer::nativeProcessAdmitted() const
    {
        std::scoped_lock lock(state_mutex_);
        return has_snapshot_ && current_snapshot_.nativeProcessAdmitted();
    }

    void ProfileSyncServer::start()
    {
        if (running_.exchange(true))
        {
            return;
        }
        worker_ = std::thread([this]()
                              { run(); });
    }

    void ProfileSyncServer::stop()
    {
        running_.store(false, std::memory_order_release);
        const int fd = client_fd_.exchange(-1);
        if (fd >= 0)
        {
            ::shutdown(fd, SHUT_RDWR);
            ::close(fd);
        }
        stop_requested_.notify_all();
        if (worker_.joinable())
        {
            worker_.join();
        }
    }

    void ProfileSyncServer::run()
    {
        while (running_.load(std::memory_order_acquire))
        {
            const int client = ConnectPublisher();
            if (client < 0)
            {
                if (!waitBeforeReconnect())
                {
                    break;
                }
                continue;
            }
            client_fd_.store(client, std::memory_order_release);
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Connected to v2 profile publisher");
            while (running_.load(std::memory_order_acquire) && readAndApply(client))
            {
                // Keep consuming framed updates. Invalid policy frames are rejected
                // by applyPayload without discarding the healthy stream.
            }
            const int fd = client_fd_.exchange(-1);
            if (fd >= 0)
            {
                ::close(fd);
            }
            if (running_.load(std::memory_order_acquire))
            {
                if (!waitBeforeReconnect())
                {
                    break;
                }
            }
        }
    }

    bool ProfileSyncServer::readAndApply(int client_fd)
    {
        std::string payload;
        if (!ReceiveFrame(client_fd, &payload))
        {
            return false;
        }
        try
        {
            (void)applyPayload(payload);
        }
        catch (const std::exception &error)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Profile snapshot application failed: %s",
                                error.what());
        }
        catch (...)
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Profile snapshot application failed unexpectedly");
        }
        return true;
    }

    bool ProfileSyncServer::waitBeforeReconnect()
    {
        std::unique_lock lock(wait_mutex_);
        stop_requested_.wait_for(lock,
                                 std::chrono::milliseconds(kReconnectDelayMs),
                                 [this]()
                                 {
                                     return !running_.load(std::memory_order_acquire);
                                 });
        return running_.load(std::memory_order_acquire);
    }

} // namespace echidna::runtime
