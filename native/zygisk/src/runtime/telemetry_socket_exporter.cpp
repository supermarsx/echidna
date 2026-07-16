#include "runtime/telemetry_socket_exporter.h"

#include <algorithm>
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

        bool IsValidProcessName(std::string_view process)
        {
            if (process.empty() || process.size() > 255)
            {
                return false;
            }
            const auto alpha_numeric = [](unsigned char byte)
            {
                return (byte >= 'A' && byte <= 'Z') ||
                       (byte >= 'a' && byte <= 'z') ||
                       (byte >= '0' && byte <= '9');
            };
            const unsigned char first = static_cast<unsigned char>(process.front());
            if (!alpha_numeric(first) && first != '_')
            {
                return false;
            }
            return std::all_of(process.begin(), process.end(), [&](unsigned char byte)
                               { return alpha_numeric(byte) || byte == '_' ||
                                        byte == '.' || byte == ':' || byte == '-'; });
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
            // Block-processing failures and install/attach failures both surface
            // as the wire "error" state. They are counted separately in the
            // accumulator (delta.failures vs delta.install_failures); the v2 wire
            // exposes only the former, while v3 exposes both, but either kind of
            // failure must still read as "error".
            if (delta.failures != 0 || delta.install_failures != 0)
            {
                return "error";
            }
            return "installed";
        }
    } // namespace

    bool RebindTelemetryPendingEpoch(uint64_t evidence_epoch,
                                     uint64_t *pending_epoch,
                                     utils::TelemetryDelta *pending,
                                     size_t pending_count) noexcept
    {
        if (evidence_epoch == 0 || pending_epoch == nullptr || pending == nullptr ||
            pending_count == 0 || *pending_epoch == evidence_epoch)
        {
            return false;
        }
        for (size_t index = 0; index < pending_count; ++index)
        {
            pending[index].clear();
        }
        *pending_epoch = evidence_epoch;
        return true;
    }

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

    std::string EncodeTelemetryV3(const utils::TelemetryDelta &delta,
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

        // v3 is a strict superset of the v2 frame: every v2 field is emitted in the
        // same order, then the additional evidence fields are appended so they ride
        // INSIDE the same authenticated envelope (peer-credential socket + strict
        // exact-key-set validation + replay/generation checks) as the v2 fields.
        // The new deltas carry the drainable edges the accumulator already tracks
        // (bypasses/installEvents/installFailures); the latched route-presence level
        // is a root boolean (installed). No v2 field is removed or reordered.
        std::string payload;
        payload.reserve(640);
        payload.append(R"({"schemaVersion":3,"type":"telemetry","sequence":)");
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
        payload.append(R"(,"bypasses":)");
        payload.append(std::to_string(delta.bypasses));
        payload.append(R"(,"installEvents":)");
        payload.append(std::to_string(delta.install_events));
        payload.append(R"(,"installFailures":)");
        payload.append(std::to_string(delta.install_failures));
        payload.append(R"(},"installed":)");
        payload.append(delta.installed ? "true}" : "false}");
        if (payload.size() > kTelemetryV2MaxFrameBytes)
        {
            return {};
        }
        return payload;
    }

    std::string EncodeCaptureOwnerAckV1(std::string_view process,
                                        uint64_t generation,
                                        uint64_t handoff_token,
                                        bool active)
    {
        if (!IsValidProcessName(process) || generation == 0 || handoff_token == 0 ||
            generation > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
            handoff_token > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()))
        {
            return {};
        }

        std::string payload;
        payload.reserve(384);
        payload.append(R"({"schemaVersion":1,"type":"capture_owner_ack","process":)");
        AppendJsonString(&payload, process);
        payload.append(R"(,"generation":)");
        payload.append(std::to_string(generation));
        payload.append(R"(,"handoffToken":)");
        payload.append(std::to_string(handoff_token));
        payload.append(R"(,"active":)");
        payload.append(active ? "true}" : "false}");
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
