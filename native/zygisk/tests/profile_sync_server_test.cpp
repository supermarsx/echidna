#include "runtime/profile_sync_server.h"

#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/config_shared_memory.h"

extern "C" echidna_result_t echidna_set_profile(const char *, size_t)
{
    return ECHIDNA_RESULT_NOT_INITIALISED;
}

namespace
{
    using namespace std::chrono_literals;

    constexpr std::string_view kProcess = "com.example.app:capture";
    constexpr std::string_view kSocketName = "echidna_profiles";
    int g_failures = 0;

    void Check(bool condition, const char *expression, int line, std::string_view message)
    {
        if (!condition)
        {
            std::fprintf(stderr,
                         "FAIL line %d: %s (%.*s)\n",
                         line,
                         expression,
                         static_cast<int>(message.size()),
                         message.data());
            ++g_failures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    std::string Envelope(uint64_t generation,
                         bool master_enabled = true,
                         bool whitelisted = true,
                         std::string_view owner = "zygisk")
    {
        return std::string(R"({"schemaVersion":2,"generation":)") +
               std::to_string(generation) +
               R"(,"profiles":{"default":{"id":"default","modules":[],"engine":{}},)"
               R"("selected":{"id":"selected","modules":[],"engine":{}}},)"
               R"("defaultProfileId":"default",)"
               R"("appBindings":{"com.example.app":"selected"},)"
               R"("whitelist":{"com.example.app":)" +
               (whitelisted ? "true" : "false") +
               R"(},"captureOwners":{"com.example.app":")" + std::string(owner) +
               R"("},"control":{"masterEnabled":)" +
               (master_enabled ? "true" : "false") +
               R"(,"bypass":false,"panicUntilEpochMs":0,)"
               R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
               R"("engineMode":"native_first"}})";
    }

    std::string CapturePolicyFrame(uint64_t handoff_token, std::string_view policy)
    {
        return std::string(
                   R"({"schemaVersion":1,"type":"capture_policy","handoffToken":)") +
               std::to_string(handoff_token) + R"(,"policy":)" + std::string(policy) + "}";
    }

    bool WaitUntil(const std::function<bool()> &predicate,
                   std::chrono::milliseconds timeout)
    {
        const auto deadline = std::chrono::steady_clock::now() + timeout;
        while (std::chrono::steady_clock::now() < deadline)
        {
            if (predicate())
            {
                return true;
            }
            std::this_thread::sleep_for(5ms);
        }
        return predicate();
    }

    void TestAtomicPolicyTransitions()
    {
        auto &shared_state = echidna::state::SharedState::instance();
        shared_state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        shared_state.prepareProcessAdmission(std::string(kProcess));

        int apply_count = 0;
        bool accept_preset = true;
        int callback_count = 0;
        std::vector<bool> callback_admission;
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &snapshot)
            {
                ++callback_count;
                callback_admission.push_back(shared_state.audioProcessingAllowed());
                CHECK(snapshot.nativeProcessAdmitted() ==
                          shared_state.audioProcessingAllowed(),
                      "callback must observe the atomically published admission state");
            },
            [&](std::string_view preset)
            {
                ++apply_count;
                CHECK(preset.find(R"("id":"selected")") != std::string_view::npos,
                      "base-package binding must apply the selected process profile");
                return accept_preset;
            });

        const std::string generation_one = Envelope(1);
        CHECK(server.applyPayload(generation_one), "first admitted generation must apply");
        CHECK(server.hasSnapshot(), "accepted generation must become visible");
        CHECK(server.nativeProcessAdmitted(), "zygisk owner must admit native processing");
        CHECK(shared_state.audioProcessingAllowed(), "shared state must admit audio processing");
        CHECK(shared_state.profile() == "selected", "selected profile must publish process-locally");
        CHECK(apply_count == 1 && callback_count == 1,
              "accepted generation must apply and notify exactly once");

        CHECK(server.applyPayload(generation_one), "exact duplicate must be idempotent");
        CHECK(apply_count == 1 && callback_count == 1,
              "duplicate generation must not reapply or renotify");

        CHECK(!server.applyPayload(Envelope(1, false)),
              "same generation with different bytes must reject");
        CHECK(server.nativeProcessAdmitted() && shared_state.audioProcessingAllowed(),
              "generation conflict must preserve prior admission");

        CHECK(server.applyPayload(Envelope(2, true, false)),
              "valid policy revoke must publish as an inert snapshot");
        CHECK(!server.nativeProcessAdmitted() && !shared_state.audioProcessingAllowed(),
              "revoke must synchronously disable audio processing");
        CHECK(apply_count == 1 && callback_count == 2,
              "denied policy must skip preset application but notify lifecycle");

        CHECK(!server.applyPayload(generation_one), "generation rollback must reject");
        CHECK(!server.nativeProcessAdmitted() && !shared_state.audioProcessingAllowed(),
              "rollback must not undo a revoke");

        CHECK(server.applyPayload(Envelope(3)), "later admitted generation must reactivate");
        CHECK(server.nativeProcessAdmitted() && shared_state.audioProcessingAllowed(),
              "readmission must restore audio processing");
        CHECK(apply_count == 2 && callback_count == 3,
              "readmission must apply preset and notify once");

        accept_preset = false;
        CHECK(!server.applyPayload(Envelope(4)),
              "failed preset application must reject the whole generation");
        CHECK(server.nativeProcessAdmitted() && shared_state.audioProcessingAllowed(),
              "preset failure must preserve last known-good policy");
        CHECK(apply_count == 3 && callback_count == 3,
              "failed preset must not publish a lifecycle callback");

        accept_preset = true;
        CHECK(server.applyPayload(Envelope(4, true, true, "lsposed")),
              "valid mismatched owner must publish an inert policy");
        CHECK(!server.nativeProcessAdmitted() && !shared_state.audioProcessingAllowed(),
              "LSPosed ownership must keep the Zygisk process inert");
        CHECK(apply_count == 3 && callback_count == 4,
              "owner-denied policy must skip native preset application and notify");
        CHECK(callback_admission == std::vector<bool>({true, false, true, false}),
              "callbacks must observe each admitted/revoked transition in order");
    }

    int CreatePublisherSocket()
    {
        const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0)
        {
            return -1;
        }

        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        address.sun_path[0] = '\0';
        std::memcpy(address.sun_path + 1, kSocketName.data(), kSocketName.size());
        const auto address_length = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + kSocketName.size());
        if (::bind(fd, reinterpret_cast<sockaddr *>(&address), address_length) != 0 ||
            ::listen(fd, 1) != 0)
        {
            ::close(fd);
            return -1;
        }
        return fd;
    }

    bool SendBytes(int fd, const void *data, size_t size)
    {
        const auto *bytes = static_cast<const uint8_t *>(data);
        size_t sent = 0;
        while (sent < size)
        {
            const ssize_t result = ::send(fd, bytes + sent, size - sent, MSG_NOSIGNAL);
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

    bool ReadBytes(int fd, void *data, size_t size)
    {
        auto *bytes = static_cast<uint8_t *>(data);
        size_t received = 0;
        while (received < size)
        {
            const ssize_t result = ::recv(fd, bytes + received, size - received, 0);
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

    ssize_t RejectCriticalSend(int, const void *, size_t, int)
    {
        errno = EAGAIN;
        return -1;
    }

    bool SendFrame(int fd, std::string_view payload)
    {
        const uint32_t network_length = htonl(static_cast<uint32_t>(payload.size()));
        return SendBytes(fd, &network_length, sizeof(network_length)) &&
               SendBytes(fd, payload.data(), payload.size());
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
            return false;
        }
        payload->assign(length, '\0');
        return ReadBytes(fd, payload->data(), payload->size());
    }

    void TestSocketNegotiationAndInterruptibleStop()
    {
        const int listener = CreatePublisherSocket();
        CHECK(listener >= 0, "abstract publisher socket must bind");
        if (listener < 0)
        {
            return;
        }

        std::atomic<int> callbacks{0};
        std::vector<echidna::runtime::DecodedProfileSnapshot> snapshots;
        echidna::runtime::ProfileSyncServer *server_pointer = nullptr;
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &snapshot)
            {
                snapshots.push_back(snapshot);
                if (server_pointer)
                {
                    (void)server_pointer->reportCaptureRouteState(
                        snapshot.generation,
                        snapshot.handoff_token,
                        snapshot.connection_epoch,
                        snapshot.nativeProcessAdmitted());
                }
                callbacks.fetch_add(1, std::memory_order_release);
            },
            [](std::string_view)
            {
                return true;
            },
            static_cast<int64_t>(::getuid()));
        server_pointer = &server;
        server.start();

        pollfd listener_poll{listener, POLLIN, 0};
        const int poll_result = ::poll(&listener_poll, 1, 2000);
        CHECK(poll_result == 1,
              "profile reader must connect to the publisher");
        if (poll_result != 1)
        {
            server.stop();
            ::close(listener);
            return;
        }
        int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0, "publisher must accept the profile reader");
        if (client < 0)
        {
            server.stop();
            ::close(listener);
            return;
        }

        const std::string expected_hello =
            std::string(echidna::runtime::kProfileSyncV3ZygiskHelloPrefix) +
            std::string(kProcess) + "\n";
        std::string hello(expected_hello.size(), '\0');
        CHECK(ReadBytes(client, hello.data(), hello.size()),
              "reader must send a complete negotiation hello");
        CHECK(hello == expected_hello,
              "reader must negotiate the exact process-bound Zygisk v3 token");
        timeval publisher_timeout{};
        publisher_timeout.tv_sec = 2;
        CHECK(::setsockopt(client,
                           SOL_SOCKET,
                           SO_RCVTIMEO,
                           &publisher_timeout,
                           sizeof(publisher_timeout)) == 0,
              "publisher must configure a bounded acknowledgement read");

        CHECK(SendFrame(client, CapturePolicyFrame(7001, Envelope(100))),
              "publisher must send first framed policy");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 1; },
                        2s),
              "reader must apply the initial policy frame");
        CHECK(echidna::state::SharedState::instance().audioProcessingAllowed(),
              "initial admitted policy must enable callback admission");
        std::string acknowledgement;
        CHECK(ReceiveFrame(client, &acknowledgement),
              "installed native route must acknowledge the active generation");
        CHECK(acknowledgement.find(R"("process":"com.example.app:capture")") !=
                  std::string::npos &&
                  acknowledgement.find(R"("generation":100)") != std::string::npos &&
                  acknowledgement.find(R"("handoffToken":7001)") != std::string::npos &&
                  acknowledgement.find(R"("active":true)") != std::string::npos,
              "active acknowledgement must bind process, raw generation, and token");
        const auto first_active = snapshots.back();
        CHECK(!server.reportCaptureRouteState(99, 7001, first_active.connection_epoch, true) &&
                  !server.reportCaptureRouteState(100, 7002, first_active.connection_epoch, true) &&
                  !server.reportCaptureRouteState(100, 7001, first_active.connection_epoch + 1, true) &&
                  !server.reportCaptureRouteState(100, 7001, first_active.connection_epoch, false) &&
                  !server.reportCaptureRouteState(100, 7001, first_active.connection_epoch, true),
              "wrong generation, token, epoch, state, and duplicate ACKs must fail closed");

        // EOF must revoke live admission but preserve the generation watermark.
        ::close(client);
        client = -1;
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 2; },
                        2s),
              "publisher disconnect must notify lifecycle revocation");
        CHECK(!echidna::state::SharedState::instance().audioProcessingAllowed(),
              "publisher disconnect must fail closed immediately");
        CHECK(server.hasSnapshot() && !server.nativeProcessAdmitted(),
              "disconnect must retain watermark but revoke live admission");

        listener_poll.revents = 0;
        CHECK(::poll(&listener_poll, 1, 3000) == 1,
              "reader must reconnect with bounded backoff");
        client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0, "publisher must accept the reconnecting reader");
        if (client < 0)
        {
            server.stop();
            ::close(listener);
            return;
        }
        hello.assign(hello.size(), '\0');
        CHECK(ReadBytes(client, hello.data(), hello.size()),
              "reconnecting reader must renegotiate");
        CHECK(hello == expected_hello,
              "reconnect hello must retain the exact process-bound v3 token");
        CHECK(::setsockopt(client,
                           SOL_SOCKET,
                           SO_RCVTIMEO,
                           &publisher_timeout,
                           sizeof(publisher_timeout)) == 0,
              "reconnected publisher must bound acknowledgement reads");
        CHECK(SendFrame(client, CapturePolicyFrame(7002, Envelope(100))),
              "publisher must resend the exact retained generation");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 3; },
                        2s),
              "exact duplicate after disconnect must restore admission");
        CHECK(server.nativeProcessAdmitted() &&
                  echidna::state::SharedState::instance().audioProcessingAllowed(),
              "exact duplicate must restore the retained active snapshot");
        CHECK(ReceiveFrame(client, &acknowledgement) &&
                  acknowledgement.find(R"("generation":100)") != std::string::npos &&
                  acknowledgement.find(R"("handoffToken":7002)") != std::string::npos,
              "new authenticated socket incarnation must re-acknowledge retained policy");
        CHECK(!server.reportCaptureRouteState(first_active.generation,
                                              first_active.handoff_token,
                                              first_active.connection_epoch,
                                              true),
              "a prior connection callback must not ACK a recycled descriptor incarnation");

        // The 750 ms timeout only bounds the cold initial frame. A healthy
        // connection must remain resident for later updates with no idle timeout.
        std::this_thread::sleep_for(900ms);
        CHECK(SendFrame(client, CapturePolicyFrame(7003, Envelope(101))),
              "publisher must send a post-idle policy update");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 4; },
                        2s),
              "reader must keep consuming after the initial timeout window");
        CHECK(echidna::state::SharedState::instance().audioProcessingAllowed(),
              "admitted socket policy must enable the real callback admission gate");
        CHECK(ReceiveFrame(client, &acknowledgement) &&
                  acknowledgement.find(R"("generation":101)") != std::string::npos,
              "later generation acknowledgement must preserve raw generation");

        CHECK(SendFrame(client,
                        CapturePolicyFrame(7004, Envelope(102, true, true, "lsposed"))),
              "publisher must send native-owner revoke");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 5; },
                        2s),
              "native owner revoke must drain and notify the route callback");
        CHECK(ReceiveFrame(client, &acknowledgement) &&
                  acknowledgement.find(R"("generation":102)") != std::string::npos &&
                  acknowledgement.find(R"("handoffToken":7004)") != std::string::npos &&
                  acknowledgement.find(R"("active":false)") != std::string::npos,
              "drained native route must acknowledge the exact inactive generation");

        const auto stop_started = std::chrono::steady_clock::now();
        server.stop();
        const auto stop_elapsed = std::chrono::steady_clock::now() - stop_started;
        CHECK(stop_elapsed < 500ms, "stop must interrupt a blocked frame read promptly");
        CHECK(!echidna::state::SharedState::instance().audioProcessingAllowed(),
              "reader teardown must revoke the real callback admission gate");
        CHECK(server.hasSnapshot() && !server.nativeProcessAdmitted(),
              "reader teardown must preserve watermark but revoke live admission");

        char trailing = '\0';
        CHECK(::recv(client, &trailing, sizeof(trailing), 0) == 0,
              "stopped reader must close its publisher connection");
        ::close(client);
        ::close(listener);
    }

    void TestColdDeniedPolicyIsTerminalWithoutReconnectChurn()
    {
        const int listener = CreatePublisherSocket();
        CHECK(listener >= 0, "cold-deny publisher socket must bind");
        if (listener < 0)
        {
            return;
        }

        std::atomic<int> callbacks{0};
        echidna::runtime::ProfileSyncServer *server_pointer = nullptr;
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &snapshot)
            {
                if (server_pointer)
                {
                    (void)server_pointer->reportCaptureRouteState(
                        snapshot.generation,
                        snapshot.handoff_token,
                        snapshot.connection_epoch,
                        snapshot.nativeProcessAdmitted());
                }
                callbacks.fetch_add(1, std::memory_order_release);
            },
            [](std::string_view)
            {
                return true;
            },
            static_cast<int64_t>(::getuid()));
        server_pointer = &server;
        server.start();

        pollfd listener_poll{listener, POLLIN, 0};
        CHECK(::poll(&listener_poll, 1, 2000) == 1,
              "cold-deny reader must connect once");
        int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0, "cold-deny publisher must accept reader");
        if (client < 0)
        {
            server.stop();
            ::close(listener);
            return;
        }

        const std::string expected_hello =
            std::string(echidna::runtime::kProfileSyncV3ZygiskHelloPrefix) +
            std::string(kProcess) + "\n";
        std::string hello(expected_hello.size(), '\0');
        CHECK(ReadBytes(client, hello.data(), hello.size()) && hello == expected_hello,
              "cold-deny connection must authenticate the exact v3 process claim");
        timeval timeout{};
        timeout.tv_sec = 2;
        CHECK(::setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0,
              "cold-deny ACK read must be bounded");

        CHECK(SendFrame(client, CapturePolicyFrame(9001, Envelope(500, true, false))),
              "publisher must send an explicit no-owner cold policy");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 1; },
                        2s),
              "cold denied policy must be applied as a terminal state");
        std::string acknowledgement;
        CHECK(ReceiveFrame(client, &acknowledgement) &&
                  acknowledgement.find(R"("generation":500)") != std::string::npos &&
                  acknowledgement.find(R"("handoffToken":9001)") != std::string::npos &&
                  acknowledgement.find(R"("active":false)") != std::string::npos,
              "cold denied policy must emit an authenticated inactive ACK");

        std::this_thread::sleep_for(900ms);
        listener_poll.revents = 0;
        CHECK(::poll(&listener_poll, 1, 100) == 0,
              "explicit cold denial must not hit the initial timeout and reconnect forever");
        CHECK(SendFrame(client, CapturePolicyFrame(9002, Envelope(501, true, false))),
              "cold-deny connection must remain resident for later policies");
        CHECK(WaitUntil([&]()
                        { return callbacks.load(std::memory_order_acquire) == 2; },
                        2s) &&
                  ReceiveFrame(client, &acknowledgement) &&
                  acknowledgement.find(R"("handoffToken":9002)") != std::string::npos,
              "resident denied connection must process and ACK later generations");

        server.stop();
        ::close(client);
        ::close(listener);
    }

    void TestCriticalAcknowledgementFailureDisconnects()
    {
        const int listener = CreatePublisherSocket();
        CHECK(listener >= 0, "critical-send publisher socket must bind");
        if (listener < 0)
        {
            return;
        }

        std::atomic<bool> callback_done{false};
        std::atomic<bool> acknowledgement_result{true};
        echidna::runtime::ProfileSyncServer *server_pointer = nullptr;
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &snapshot)
            {
                if (server_pointer)
                {
                    acknowledgement_result.store(
                        server_pointer->reportCaptureRouteState(
                            snapshot.generation,
                            snapshot.handoff_token,
                            snapshot.connection_epoch,
                            snapshot.nativeProcessAdmitted()),
                        std::memory_order_release);
                }
                callback_done.store(true, std::memory_order_release);
            },
            [](std::string_view)
            {
                return true;
            },
            static_cast<int64_t>(::getuid()),
            RejectCriticalSend);
        server_pointer = &server;
        server.start();

        pollfd listener_poll{listener, POLLIN, 0};
        CHECK(::poll(&listener_poll, 1, 2000) == 1,
              "critical-send reader must connect");
        const int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0, "critical-send publisher must accept reader");
        if (client >= 0)
        {
            const std::string expected_hello =
                std::string(echidna::runtime::kProfileSyncV3ZygiskHelloPrefix) +
                std::string(kProcess) + "\n";
            std::string hello(expected_hello.size(), '\0');
            CHECK(ReadBytes(client, hello.data(), hello.size()) && hello == expected_hello,
                  "critical-send connection must negotiate v3");
            CHECK(SendFrame(client, CapturePolicyFrame(9100, Envelope(600))),
                  "critical-send publisher must send active policy");
            CHECK(WaitUntil([&]()
                            { return callback_done.load(std::memory_order_acquire); },
                            2s) &&
                      !acknowledgement_result.load(std::memory_order_acquire),
                  "ACK backpressure must be reported as a critical failure");
            pollfd client_poll{client, POLLIN | POLLHUP, 0};
            CHECK(::poll(&client_poll, 1, 2000) == 1,
                  "critical ACK failure must interrupt the authenticated channel");
            char byte = '\0';
            CHECK(::recv(client, &byte, sizeof(byte), 0) == 0,
                  "critical ACK failure must force endpoint replacement");
            ::close(client);
        }
        server.stop();
        ::close(listener);
    }

    void TestFrameInFlightDuringStopCannotPublishCallback()
    {
        auto &shared_state = echidna::state::SharedState::instance();
        shared_state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        shared_state.prepareProcessAdmission(std::string(kProcess));

        std::mutex mutex;
        std::condition_variable changed;
        bool preset_entered = false;
        bool release_preset = false;
        std::atomic<int> callbacks{0};
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &)
            {
                callbacks.fetch_add(1, std::memory_order_release);
            },
            [&](std::string_view)
            {
                std::unique_lock lock(mutex);
                preset_entered = true;
                changed.notify_all();
                changed.wait(lock, [&]()
                             { return release_preset; });
                return true;
            });

        bool apply_result = false;
        std::thread publisher([&]()
                              { apply_result = server.applyPayload(Envelope(200)); });
        {
            std::unique_lock lock(mutex);
            changed.wait(lock, [&]()
                         { return preset_entered; });
        }

        std::atomic<bool> stop_entered{false};
        std::thread stopper([&]()
                            {
                                stop_entered.store(true, std::memory_order_release);
                                server.stop();
                            });
        CHECK(WaitUntil([&]()
                        { return stop_entered.load(std::memory_order_acquire); },
                        2s),
              "teardown thread must begin while the frame is in flight");
        std::this_thread::sleep_for(20ms);
        {
            std::scoped_lock lock(mutex);
            release_preset = true;
        }
        changed.notify_all();
        publisher.join();
        stopper.join();

        CHECK(apply_result, "already-entered preset application may finish atomically");
        CHECK(callbacks.load(std::memory_order_acquire) == 0,
              "stop must detach callback before in-flight frame publication");
        CHECK(!shared_state.audioProcessingAllowed(),
              "final stop revoke must win over an in-flight frame");
        CHECK(server.hasSnapshot() && !server.nativeProcessAdmitted(),
              "in-flight frame watermark may persist but admission must remain revoked");
        CHECK(!server.applyPayload(Envelope(201)),
              "stopped reader must reject all later payload publication");
    }

    void TestPublisherUidMismatchFailsClosed()
    {
        auto &shared_state = echidna::state::SharedState::instance();
        shared_state.updateConfiguration(echidna::utils::ConfigurationSnapshot{});
        shared_state.prepareProcessAdmission(std::string(kProcess));

        const int listener = CreatePublisherSocket();
        CHECK(listener >= 0, "spoof publisher socket must bind");
        if (listener < 0)
        {
            return;
        }

        const int64_t current_uid = static_cast<int64_t>(::getuid());
        const int64_t mismatched_uid = current_uid == 0 ? 1 : 0;
        std::atomic<int> callbacks{0};
        echidna::runtime::ProfileSyncServer server(
            std::string(kProcess),
            [&](const echidna::runtime::DecodedProfileSnapshot &)
            {
                callbacks.fetch_add(1, std::memory_order_release);
            },
            [](std::string_view)
            {
                return true;
            },
            mismatched_uid);
        server.start();

        pollfd listener_poll{listener, POLLIN, 0};
        const int poll_result = ::poll(&listener_poll, 1, 2000);
        CHECK(poll_result == 1, "reader must reach the spoof publisher for credential rejection");
        if (poll_result != 1)
        {
            server.stop();
            ::close(listener);
            return;
        }
        const int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        CHECK(client >= 0, "spoof publisher must accept the reader connection");
        if (client >= 0)
        {
            pollfd client_poll{client, POLLIN | POLLHUP, 0};
            CHECK(::poll(&client_poll, 1, 2000) == 1,
                  "credential mismatch must close the socket promptly");
            char unexpected = '\0';
            CHECK(::recv(client, &unexpected, sizeof(unexpected), 0) == 0,
                  "untrusted publisher must receive no negotiation or policy traffic");
            ::close(client);
        }

        server.stop();
        CHECK(callbacks.load(std::memory_order_acquire) == 0,
              "untrusted publisher must not publish callbacks");
        CHECK(!server.hasSnapshot() && !server.nativeProcessAdmitted(),
              "untrusted publisher must not retain snapshot state");
        CHECK(!shared_state.audioProcessingAllowed(),
              "untrusted publisher must leave callback admission fail-closed");
        ::close(listener);
    }

} // namespace

int main()
{
    TestAtomicPolicyTransitions();
    TestFrameInFlightDuringStopCannotPublishCallback();
    TestPublisherUidMismatchFailsClosed();
    TestSocketNegotiationAndInterruptibleStop();
    TestColdDeniedPolicyIsTerminalWithoutReconnectChurn();
    TestCriticalAcknowledgementFailureDisconnects();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "profile_sync_server_test: %d failure(s)\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "profile_sync_server_test: all checks passed\n");
    return 0;
}
