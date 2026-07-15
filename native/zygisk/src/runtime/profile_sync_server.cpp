#include "runtime/profile_sync_server.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif

#include <arpa/inet.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <utility>

#include "echidna/api.h"
#include "runtime/reconnect_backoff.h"
#include "runtime/telemetry_socket_exporter.h"
#include "state/shared_state.h"
#include "utils/config_shared_memory.h"
#include "utils/process_utils.h"

namespace
{
    constexpr const char *kSocketName = "echidna_profiles";
    constexpr const char *kLogTag = "echidna_profile_sync";
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

    bool IsTrustedPublisher(int fd, int64_t expected_uid)
    {
        if (expected_uid < 0)
        {
            return false;
        }
        ucred credentials{};
        socklen_t credentials_size = sizeof(credentials);
        if (::getsockopt(fd,
                         SOL_SOCKET,
                         SO_PEERCRED,
                         &credentials,
                         &credentials_size) != 0 ||
            credentials_size != sizeof(credentials))
        {
            return false;
        }
        return static_cast<int64_t>(credentials.uid) == expected_uid;
    }

    int ConnectPublisher(int64_t expected_uid)
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
            !IsTrustedPublisher(fd, expected_uid) ||
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

    echidna::utils::ConfigurationSnapshot ConfigurationFor(
        const echidna::runtime::DecodedProfileSnapshot &snapshot,
        std::string_view process_name)
    {
        echidna::utils::ConfigurationSnapshot configuration;
        configuration.hooks_enabled = snapshot.global_hooks_enabled;
        if (snapshot.process_whitelisted &&
            snapshot.capture_owner == echidna::runtime::CaptureOwner::kZygisk)
        {
            configuration.process_whitelist.emplace_back(process_name);
        }
        configuration.profile = snapshot.profile_id;
        return configuration;
    }

} // namespace

namespace echidna::runtime
{
    ProfileSyncServer::ProfileSyncServer()
        : ProfileSyncServer(utils::CachedProcessName(), {}, {}, -1)
    {
    }

    ProfileSyncServer::ProfileSyncServer(std::string process_name,
                                         SnapshotCallback callback,
                                         PresetApplier preset_applier,
                                         int64_t expected_publisher_uid)
        : process_name_(std::move(process_name)),
          callback_(std::move(callback)),
          preset_applier_(std::move(preset_applier)),
          expected_publisher_uid_(expected_publisher_uid)
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
        const int fd = ConnectPublisher(expected_publisher_uid_);
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

        bool notify_callback = false;
        DecodedProfileSnapshot published_snapshot;
        {
            std::scoped_lock lock(state_mutex_);
            if (!accepting_payloads_.load(std::memory_order_acquire))
            {
                return false;
            }
            const GenerationDecision decision = EvaluateGeneration(candidate.generation,
                                                                   payload,
                                                                   generation_,
                                                                   generation_payload_);
            if (decision == GenerationDecision::kDuplicate)
            {
                if (snapshot_published_)
                {
                    return true;
                }

                // Disconnect revocation deliberately preserves the watermark.
                // Re-publish an exact duplicate so the legitimate service can
                // restore admission without inventing a new generation.
                state::SharedState::instance().updateConfiguration(
                    ConfigurationFor(current_snapshot_, process_name_));
                snapshot_published_ = true;
                published_snapshot = current_snapshot_;
                notify_callback = true;
            }
            else if (decision == GenerationDecision::kRejectRollback ||
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
            else
            {
                if (candidate.nativeProcessAdmitted() &&
                    !preset_applier_(candidate.preset_json))
                {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        kLogTag,
                        "Rejected generation=%llu: selected preset was not accepted",
                        static_cast<unsigned long long>(candidate.generation));
                    return false;
                }

                // The socket snapshot is process-specific. Publish directly to the
                // process-local singleton only after every validation and preset step
                // succeeds; do not leak one process's selected profile into a global map.
                state::SharedState::instance().updateConfiguration(
                    ConfigurationFor(candidate, process_name_));

                generation_ = candidate.generation;
                generation_payload_ = std::move(retained_payload);
                current_snapshot_ = std::move(retained_snapshot);
                has_snapshot_ = true;
                snapshot_published_ = true;
                published_snapshot = candidate;
                notify_callback = true;
            }
        }

        if (notify_callback)
        {
            dispatchSnapshot(published_snapshot);
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
        return has_snapshot_ && snapshot_published_ &&
               current_snapshot_.nativeProcessAdmitted();
    }

    void ProfileSyncServer::start()
    {
        if (running_.exchange(true))
        {
            return;
        }
        worker_ = std::thread([this]()
                              { run(); });
        telemetry_worker_ = std::thread([this]()
                                        { runTelemetryExporter(); });
    }

    void ProfileSyncServer::stop()
    {
        beginStop();
        finishStop();
    }

    void ProfileSyncServer::beginStop()
    {
        running_.store(false, std::memory_order_release);
        accepting_payloads_.store(false, std::memory_order_release);
        {
            // Wait out any callback that already began, then detach permanently.
            // applyPayload rechecks accepting_payloads_ before dispatch, so no
            // callback can begin after this section completes.
            std::scoped_lock lock(callback_mutex_);
            callback_ = {};
        }
        // Existing hook trampolines consult SharedState directly. Revoke before
        // waiting on the socket worker so teardown is fail-closed immediately.
        revokeProcessAdmission(false);
        {
            std::scoped_lock lock(client_mutex_);
            if (client_fd_ >= 0)
            {
                // Interrupt recv(), but leave close() to the worker that owns
                // the raw descriptor. This prevents a subsequent setsockopt or
                // close from acting on an fd number already recycled elsewhere.
                (void)::shutdown(client_fd_, SHUT_RDWR);
            }
        }
        stop_requested_.notify_all();
    }

    void ProfileSyncServer::finishStop()
    {
        if (worker_.joinable())
        {
            worker_.join();
        }
        if (telemetry_worker_.joinable())
        {
            telemetry_worker_.join();
        }
        // A frame may already have passed recv() before shutdown and then waited
        // behind the first revoke. Clear again after join so it cannot leave the
        // process admitted once teardown returns.
        revokeProcessAdmission(false);
    }

    void ProfileSyncServer::run()
    {
        ReconnectBackoff reconnect_backoff(static_cast<uint32_t>(::getpid()));
        while (running_.load(std::memory_order_acquire))
        {
            const int client = ConnectPublisher(expected_publisher_uid_);
            if (client < 0)
            {
                if (!waitBeforeReconnect(reconnect_backoff.nextDelay()))
                {
                    break;
                }
                reconnect_backoff.recordFailure();
                continue;
            }
            {
                std::scoped_lock lock(client_mutex_);
                if (!running_.load(std::memory_order_acquire))
                {
                    ::close(client);
                    break;
                }
                client_fd_ = client;
            }
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Connected to v2 profile publisher");
            (void)SetReadTimeout(client, kInitialReadTimeoutMs);
            const bool received_initial_frame = readAndApply(client);
            (void)SetReadTimeout(client, 0);
            if (received_initial_frame)
            {
                reconnect_backoff.reset();
            }
            while (received_initial_frame && running_.load(std::memory_order_acquire) &&
                   readAndApply(client))
            {
                // Keep consuming framed updates. Invalid policy frames are rejected
                // by applyPayload without discarding the healthy stream.
            }
            {
                std::scoped_lock lock(client_mutex_);
                if (client_fd_ == client)
                {
                    client_fd_ = -1;
                }
            }
            if (running_.load(std::memory_order_acquire))
            {
                // A disconnected publisher is no longer authoritative. Revoke
                // live admission and wake the lifecycle gate, while retaining
                // generation bytes for rollback/conflict protection.
                revokeProcessAdmission(true);
            }
            ::close(client);
            if (running_.load(std::memory_order_acquire))
            {
                if (!waitBeforeReconnect(reconnect_backoff.nextDelay()))
                {
                    break;
                }
                reconnect_backoff.recordFailure();
            }
        }
    }

    void ProfileSyncServer::runTelemetryExporter()
    {
        constexpr auto kExportInterval = std::chrono::milliseconds(250);
        constexpr size_t kRouteCount =
            static_cast<size_t>(utils::TelemetryRoute::kCount);
        std::array<utils::TelemetryDelta, kRouteCount> pending{};
        for (size_t index = 0; index < kRouteCount; ++index)
        {
            pending[index].route = static_cast<utils::TelemetryRoute>(index);
        }
        size_t next_route = 0;
        uint32_t sequence = 0;

        while (running_.load(std::memory_order_acquire))
        {
            {
                std::unique_lock lock(telemetry_wait_mutex_);
                stop_requested_.wait_for(lock,
                                         kExportInterval,
                                         [this]()
                                         {
                                             return !running_.load(std::memory_order_acquire);
                                         });
            }
            if (!running_.load(std::memory_order_acquire))
            {
                break;
            }

            auto &accumulator = state::SharedState::instance().telemetry();
            for (size_t index = 0; index < kRouteCount; ++index)
            {
                pending[index].merge(
                    accumulator.take(static_cast<utils::TelemetryRoute>(index)));
            }

            uint64_t generation = 0;
            {
                std::scoped_lock lock(state_mutex_);
                if (snapshot_published_)
                {
                    generation = generation_;
                }
            }
            if (generation == 0)
            {
                continue;
            }

            size_t selected = kRouteCount;
            for (size_t offset = 0; offset < kRouteCount; ++offset)
            {
                const size_t candidate = (next_route + offset) % kRouteCount;
                if (pending[candidate].pending())
                {
                    selected = candidate;
                    break;
                }
            }
            if (selected == kRouteCount)
            {
                continue;
            }

            int export_fd = -1;
            {
                std::scoped_lock lock(client_mutex_);
                if (client_fd_ >= 0)
                {
#ifdef F_DUPFD_CLOEXEC
                    export_fd = ::fcntl(client_fd_, F_DUPFD_CLOEXEC, 0);
#else
                    export_fd = ::dup(client_fd_);
#endif
                }
            }
            if (export_fd < 0)
            {
                continue;
            }

            uint32_t candidate_sequence = sequence + 1;
            if (candidate_sequence == 0)
            {
                candidate_sequence = 1;
            }
            const auto monotonic_ms_raw =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch())
                    .count();
            const uint64_t monotonic_ms = monotonic_ms_raw > 0
                                              ? static_cast<uint64_t>(monotonic_ms_raw)
                                              : 0;
            const std::string payload = EncodeTelemetryV2(pending[selected],
                                                          candidate_sequence,
                                                          monotonic_ms,
                                                          process_name_,
                                                          generation);
            const TelemetrySendResult send_result =
                SendTelemetryV2Frame(export_fd, payload);
            ::close(export_fd);
            if (send_result == TelemetrySendResult::kComplete)
            {
                sequence = candidate_sequence;
                pending[selected].clear();
                next_route = (selected + 1) % kRouteCount;
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

    bool ProfileSyncServer::waitBeforeReconnect(std::chrono::milliseconds delay)
    {
        std::unique_lock lock(wait_mutex_);
        stop_requested_.wait_for(lock,
                                 delay,
                                 [this]()
                                 {
                                     return !running_.load(std::memory_order_acquire);
                                 });
        return running_.load(std::memory_order_acquire);
    }

    void ProfileSyncServer::revokeProcessAdmission(bool notify_callback)
    {
        bool notify = false;
        DecodedProfileSnapshot revoked_snapshot;
        {
            std::scoped_lock lock(state_mutex_);
            notify = notify_callback && has_snapshot_ && snapshot_published_ &&
                     current_snapshot_.nativeProcessAdmitted();
            snapshot_published_ = false;
            state::SharedState::instance().updateConfiguration(utils::ConfigurationSnapshot{});
            if (notify)
            {
                revoked_snapshot = current_snapshot_;
                revoked_snapshot.global_hooks_enabled = false;
            }
        }
        if (notify)
        {
            dispatchSnapshot(revoked_snapshot);
        }
    }

    void ProfileSyncServer::dispatchSnapshot(const DecodedProfileSnapshot &snapshot)
    {
        std::scoped_lock lock(callback_mutex_);
        if (accepting_payloads_.load(std::memory_order_acquire) && callback_)
        {
            callback_(snapshot);
        }
    }

} // namespace echidna::runtime
