#include "runtime/telemetry_socket_exporter.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace
{
    int g_failures = 0;
    int g_send_count = 0;
    std::vector<uint8_t> g_frame;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++g_failures;
        }
    }

    ssize_t CaptureSend(int, const void *data, size_t size, int flags)
    {
        ++g_send_count;
        Check((flags & MSG_DONTWAIT) != 0, "telemetry send must be nonblocking");
        g_frame.assign(static_cast<const uint8_t *>(data),
                       static_cast<const uint8_t *>(data) + size);
        return static_cast<ssize_t>(size);
    }

    ssize_t BackpressureSend(int, const void *, size_t, int)
    {
        errno = EAGAIN;
        return -1;
    }

    ssize_t PartialSend(int, const void *, size_t size, int)
    {
        return static_cast<ssize_t>(size / 2);
    }
} // namespace

int main()
{
    using namespace echidna;
    utils::TelemetryDelta delta;
    delta.route = utils::TelemetryRoute::kAAudio;
    delta.blocks = 4;
    delta.frames = 960;
    delta.mutations = 3;
    delta.installed = true;
    const std::string payload =
        runtime::EncodeTelemetryV2(delta, 7, 1234, "com.example:capture", 42);
    Check(payload.find(R"("state":"processing")") != std::string::npos,
          "only mutation deltas may encode processing state");
    Check(payload.find(R"("mutations":3)") != std::string::npos,
          "mutation delta must be serialized");
    Check(payload.find(R"("route":"aaudio")") != std::string::npos,
          "route must use the stable schema enum");

    utils::TelemetryDelta pending[2]{};
    pending[0].route = utils::TelemetryRoute::kAAudio;
    pending[1].route = utils::TelemetryRoute::kOpenSl;
    pending[0].blocks = 3;
    pending[1].mutations = 1;
    uint64_t pending_epoch = 0;
    Check(runtime::RebindTelemetryPendingEpoch(10, &pending_epoch, pending, 2) &&
              pending_epoch == 10 && !pending[0].pending() && !pending[1].pending(),
          "first authenticated evidence epoch must drop pre-ACK queued counters");
    pending[0].blocks = 2;
    Check(!runtime::RebindTelemetryPendingEpoch(10, &pending_epoch, pending, 2) &&
              pending[0].blocks == 2,
          "same evidence epoch must preserve unsent telemetry for retry");
    Check(runtime::RebindTelemetryPendingEpoch(11, &pending_epoch, pending, 2) &&
              !pending[0].pending(),
          "cross-generation or reconnect evidence must drop queued old deltas");

    const std::string active_ack =
        runtime::EncodeCaptureOwnerAckV1("com.example:capture", 42, 73, true);
    Check(active_ack ==
              R"({"schemaVersion":1,"type":"capture_owner_ack","process":"com.example:capture","generation":42,"handoffToken":73,"active":true})",
          "active handoff acknowledgement must use the strict process-bound schema");
    Check(runtime::EncodeCaptureOwnerAckV1("", 42, 73, false).empty() &&
              runtime::EncodeCaptureOwnerAckV1("bad/process", 42, 73, false).empty() &&
              runtime::EncodeCaptureOwnerAckV1("com.example", 0, 73, false).empty() &&
              runtime::EncodeCaptureOwnerAckV1("com.example", 42, 0, false).empty(),
          "invalid acknowledgement process, generation, or token must fail closed");

    g_send_count = 0;
    for (int index = 0; index < 8; ++index)
    {
        Check(runtime::SendTelemetryV2Frame(1, payload, CaptureSend) ==
                  runtime::TelemetrySendResult::kComplete,
              "loaded telemetry frame must use the independent best-effort send path");
    }
    Check(runtime::SendTelemetryV2Frame(1, active_ack, CaptureSend) ==
                  runtime::TelemetrySendResult::kComplete &&
              g_send_count == 9,
          "critical ACK must remain sendable after eight loaded telemetry frames");

    Check(runtime::SendTelemetryV2Frame(1, payload, CaptureSend) ==
              runtime::TelemetrySendResult::kComplete,
          "complete single write must succeed");
    uint32_t network_length = 0;
    std::memcpy(&network_length, g_frame.data(), sizeof(network_length));
    Check(ntohl(network_length) == payload.size(),
          "wire frame must prefix a big-endian payload length");
    Check(std::string(g_frame.begin() + sizeof(network_length), g_frame.end()) == payload,
          "wire frame payload must be exact");

    int pair[2] = {-1, -1};
    Check(::socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0,
          "socketpair must be available for shutdown tests");
    if (pair[0] >= 0)
    {
        Check(runtime::SendTelemetryV2Frame(pair[0], payload, BackpressureSend) ==
                  runtime::TelemetrySendResult::kWouldBlock,
              "EAGAIN must retain the connection and coalesce for a later tick");
        const char marker = 'x';
        Check(::send(pair[1], &marker, 1, MSG_NOSIGNAL) == 1,
              "backpressure must not shut down the config channel");
        char received = 0;
        Check(::recv(pair[0], &received, 1, 0) == 1 && received == marker,
              "connection must remain usable after a zero-byte EAGAIN");

        Check(runtime::SendTelemetryV2Frame(pair[0], payload, PartialSend) ==
                  runtime::TelemetrySendResult::kPartialWrite,
              "partial framed write must be distinguished");
        Check(::recv(pair[1], &received, 1, 0) == 0,
              "partial write must shut down the entire duplicated socket channel");
        ::close(pair[0]);
        ::close(pair[1]);
    }

    utils::TelemetryDelta unchanged = delta;
    unchanged.mutations = 0;
    const std::string installed =
        runtime::EncodeTelemetryV2(unchanged, 8, 1235, "com.example", 42);
    Check(installed.find(R"("state":"processing")") == std::string::npos,
          "successful unchanged blocks must never claim processing");

    // --- Schema v3: the additive evidence frame ---------------------------------
    // A v3 frame is a strict superset of v2 that also carries the bypasses/
    // installEvents/installFailures deltas and the latched `installed` level, all
    // inside the same length-prefixed, peer-authenticated envelope.
    utils::TelemetryDelta v3_delta;
    v3_delta.route = utils::TelemetryRoute::kAAudio;
    v3_delta.blocks = 9;
    v3_delta.frames = 1728;
    v3_delta.failures = 1;
    v3_delta.mutations = 3;
    v3_delta.bypasses = 2;
    v3_delta.install_events = 4;
    v3_delta.install_failures = 5;
    v3_delta.installed = true;
    const std::string v3 =
        runtime::EncodeTelemetryV3(v3_delta, 11, 1236, "com.example:capture", 42);
    Check(v3.find(R"("schemaVersion":3)") != std::string::npos,
          "v3 frame must advertise schemaVersion 3");
    // Every v2 field is still present, in the same order, ahead of the new fields.
    const char *v2_order[] = {"\"blocks\":9", "\"frames\":1728",
                              "\"failures\":1", "\"mutations\":3"};
    size_t cursor = 0;
    for (const char *needle : v2_order)
    {
        const size_t at = v3.find(needle, cursor);
        Check(at != std::string::npos, "v3 must preserve every v2 delta field");
        if (at != std::string::npos)
        {
            cursor = at;
        }
    }
    Check(v3.find(R"("bypasses":2)") != std::string::npos &&
              v3.find(R"("installEvents":4)") != std::string::npos &&
              v3.find(R"("installFailures":5)") != std::string::npos,
          "v3 deltas must add bypasses/installEvents/installFailures edges");
    Check(v3.find(R"(,"installed":true})") != std::string::npos,
          "v3 must append the latched installed level at the root");
    Check(v3.find(R"("mutations":3)") < v3.find(R"("bypasses":2)"),
          "v3 must append the new deltas after the v2 deltas (no reorder)");

    // The new fields ride inside the framed, best-effort send path just like v2 —
    // they are authenticated by the same length-prefixed peer-credential channel,
    // not appended outside the frame.
    g_frame.clear();
    Check(runtime::SendTelemetryV2Frame(1, v3, CaptureSend) ==
              runtime::TelemetrySendResult::kComplete,
          "v3 frame must transit the framed authenticated send path");
    std::memcpy(&network_length, g_frame.data(), sizeof(network_length));
    Check(ntohl(network_length) == v3.size() &&
              std::string(g_frame.begin() + sizeof(network_length), g_frame.end()) == v3,
          "v3 wire frame must length-prefix and carry the exact v3 payload");

    // F1: an install/attach failure is counted in install_failures, NOT the block
    // `failures` counter. It must still read as the "error" state, the v3 frame
    // must expose it, and the v2 frame of the same delta must NOT leak new keys.
    utils::TelemetryDelta install_failure;
    install_failure.route = utils::TelemetryRoute::kAAudio;
    install_failure.install_events = 1;
    install_failure.install_failures = 1;
    install_failure.installed = false;
    Check(install_failure.pending(),
          "an install failure is unsent evidence that must be exportable");
    const std::string v3_install_fail =
        runtime::EncodeTelemetryV3(install_failure, 12, 1237, "com.example", 42);
    Check(v3_install_fail.find(R"("state":"error")") != std::string::npos &&
              v3_install_fail.find(R"("installFailures":1)") != std::string::npos &&
              v3_install_fail.find(R"("failures":0)") != std::string::npos,
          "v3 install failure must read error, expose installFailures, keep block "
          "failures at 0");
    const std::string v2_install_fail =
        runtime::EncodeTelemetryV2(install_failure, 12, 1237, "com.example", 42);
    Check(v2_install_fail.find(R"("state":"error")") != std::string::npos &&
              v2_install_fail.find(R"("failures":0)") != std::string::npos &&
              v2_install_fail.find("installFailures") == std::string::npos &&
              v2_install_fail.find("bypasses") == std::string::npos &&
              v2_install_fail.find("installEvents") == std::string::npos &&
              v2_install_fail.find(R"(,"installed":)") == std::string::npos,
          "v2 frame must stay the exact legacy key-set (no v3 keys leak)");

    Check(runtime::EncodeTelemetryV3({}, 11, 1236, "com.example", 42).empty(),
          "an empty (non-pending) v3 delta must fail closed like v2");

    if (g_failures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "telemetry_socket_exporter_test: all checks passed\n");
    return 0;
}
