#include "runtime/telemetry_socket_exporter.h"

#include <arpa/inet.h>
#include <array>
#include <cerrno>
#include <cstring>
#include <limits>
#include <sys/socket.h>

namespace echidna::runtime
{
    namespace
    {
        ssize_t SystemSend(int fd, const void *data, size_t size, int flags)
        {
            return ::send(fd, data, size, flags);
        }

        void AppendJsonString(std::string *output, std::string_view value)
        {
            output->push_back('"');
            size_t accepted = 0;
            for (unsigned char byte : value)
            {
                if (accepted >= 255)
                {
                    break;
                }
                ++accepted;
                switch (byte)
                {
                case '"':
                    output->append("\\\"");
                    break;
                case '\\':
                    output->append("\\\\");
                    break;
                case '\b':
                    output->append("\\b");
                    break;
                case '\f':
                    output->append("\\f");
                    break;
                case '\n':
                    output->append("\\n");
                    break;
                case '\r':
                    output->append("\\r");
                    break;
                case '\t':
                    output->append("\\t");
                    break;
                default:
                    // Android process names are printable ASCII. Replace all other
                    // bytes so the framed document is always valid UTF-8 JSON.
                    output->push_back(byte >= 0x20 && byte <= 0x7e
                                          ? static_cast<char>(byte)
                                          : '?');
                    break;
                }
            }
            output->push_back('"');
        }

        const char *StateFor(const utils::TelemetryDelta &delta)
        {
            if (delta.mutations != 0)
            {
                return "processing";
            }
            if (delta.bypasses != 0)
            {
                return "bypassed";
            }
            if (delta.failures != 0)
            {
                return "error";
            }
            return "installed";
        }
    } // namespace

    std::string EncodeTelemetryV2(const utils::TelemetryDelta &delta,
                                  uint32_t sequence,
                                  uint64_t sender_monotonic_ms,
                                  std::string_view process,
                                  uint64_t generation)
    {
        if (!delta.pending() || sequence == 0 || process.empty() || generation == 0 ||
            sender_monotonic_ms > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
            generation > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()))
        {
            return {};
        }

        std::string payload;
        payload.reserve(512);
        payload.append(R"({"schemaVersion":2,"type":"telemetry","sequence":)");
        payload.append(std::to_string(sequence));
        payload.append(R"(,"senderMonotonicMs":)");
        payload.append(std::to_string(sender_monotonic_ms));
        payload.append(R"(,"process":)");
        AppendJsonString(&payload, process);
        payload.append(R"(,"route":")");
        payload.append(utils::TelemetryRouteName(delta.route));
        payload.append(R"(","generation":)");
        payload.append(std::to_string(generation));
        payload.append(R"(,"state":")");
        payload.append(StateFor(delta));
        payload.append(R"(","deltas":{"blocks":)");
        payload.append(std::to_string(delta.blocks));
        payload.append(R"(,"frames":)");
        payload.append(std::to_string(delta.frames));
        payload.append(R"(,"failures":)");
        payload.append(std::to_string(delta.failures));
        payload.append(R"(,"mutations":)");
        payload.append(std::to_string(delta.mutations));
        payload.append("}}");
        if (payload.size() > kTelemetryV2MaxFrameBytes)
        {
            return {};
        }
        return payload;
    }

    TelemetrySendResult SendTelemetryV2Frame(int fd,
                                             std::string_view payload,
                                             TelemetrySendFn send_fn) noexcept
    {
        if (fd < 0 || payload.empty() || payload.size() > kTelemetryV2MaxFrameBytes)
        {
            return TelemetrySendResult::kInvalidFrame;
        }
        std::array<uint8_t, kTelemetryV2MaxFrameBytes + sizeof(uint32_t)> frame{};
        const uint32_t network_length = htonl(static_cast<uint32_t>(payload.size()));
        std::memcpy(frame.data(), &network_length, sizeof(network_length));
        std::memcpy(frame.data() + sizeof(network_length), payload.data(), payload.size());
        const size_t frame_size = sizeof(network_length) + payload.size();

#ifdef MSG_NOSIGNAL
        constexpr int kFlags = MSG_DONTWAIT | MSG_NOSIGNAL;
#else
        constexpr int kFlags = MSG_DONTWAIT;
#endif
        TelemetrySendFn writer = send_fn ? send_fn : SystemSend;
        ssize_t result = -1;
        do
        {
            result = writer(fd, frame.data(), frame_size, kFlags);
        } while (result < 0 && errno == EINTR);

        if (result == static_cast<ssize_t>(frame_size))
        {
            return TelemetrySendResult::kComplete;
        }
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
        {
            return TelemetrySendResult::kWouldBlock;
        }
        if (result > 0)
        {
            // A length-prefixed stream cannot recover from a truncated payload.
            // shutdown() on a duplicated descriptor applies to the underlying
            // socket and forces ProfileSyncServer to reconnect the whole channel.
            (void)::shutdown(fd, SHUT_RDWR);
            return TelemetrySendResult::kPartialWrite;
        }
        (void)::shutdown(fd, SHUT_RDWR);
        return TelemetrySendResult::kConnectionLost;
    }

} // namespace echidna::runtime
