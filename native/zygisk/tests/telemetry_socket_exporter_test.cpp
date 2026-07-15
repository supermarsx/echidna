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
}

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

    if (g_failures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "telemetry_socket_exporter_test: all checks passed\n");
    return 0;
}
