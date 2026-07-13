#include "runtime/profile_sync_server.h"

/**
 * @file profile_sync_server.cpp
 * @brief Socket-based profile sync reader used by hooked processes to consume
 * snapshots from the companion service.
 */

#include <arpa/inet.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_WARN 0
#endif
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstring>
#include <cstddef>
#include <optional>
#include <string_view>
#include <thread>

#include "echidna/api.h"
#include "state/shared_state.h"
#include "utils/config_shared_memory.h"

namespace
{
    constexpr const char *kSocketName = "echidna_profiles";
    constexpr const char *kLogTag = "echidna_profile_sync";
    constexpr size_t kMaxPresetSize = 512 * 1024;
    constexpr size_t kMaxWhitelistEntries = 256;
    constexpr size_t kMaxProcessNameLength = 128;
    constexpr size_t kMaxProfileIdLength = 64;
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

    int ConnectAbstractSocket()
    {
        const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0)
        {
            return -1;
        }

        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        const size_t name_len = std::strlen(kSocketName);
        if (name_len + 1 >= sizeof(addr.sun_path))
        {
            ::close(fd);
            errno = ENAMETOOLONG;
            return -1;
        }
        addr.sun_path[0] = '\0';
        std::memcpy(addr.sun_path + 1, kSocketName, name_len);
        const auto addr_len =
            static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_len);

        if (::connect(fd, reinterpret_cast<sockaddr *>(&addr), addr_len) != 0)
        {
            ::close(fd);
            return -1;
        }
        return fd;
    }

    int ConnectPublisher()
    {
        return ConnectAbstractSocket();
    }

    bool ReadBytes(int fd, void *buffer, size_t bytes)
    {
        uint8_t *out = static_cast<uint8_t *>(buffer);
        size_t read_total = 0;
        while (read_total < bytes)
        {
            const ssize_t r = ::recv(fd, out + read_total, bytes - read_total, 0);
            if (r <= 0)
            {
                return false;
            }
            read_total += static_cast<size_t>(r);
        }
        return true;
    }

    std::string ReceiveWithFd(int fd, int *out_fd)
    {
        std::array<char, 4096> control{};
        std::array<char, 4> header{};
        struct iovec iov{
            .iov_base = header.data(), .iov_len = header.size()};
        struct msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control.data();
        msg.msg_controllen = control.size();

        if (::recvmsg(fd, &msg, 0) <= 0)
        {
            return {};
        }

        for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg))
        {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
            {
                const int *fds = reinterpret_cast<int *>(CMSG_DATA(cmsg));
                const size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                if (count > 0 && fds)
                {
                    *out_fd = fds[0];
                    break;
                }
            }
        }

        uint32_t be_length = 0;
        std::memcpy(&be_length, header.data(), header.size());
        const uint32_t length = ntohl(be_length);
        if (length == 0 || length > (10 * 1024 * 1024))
        {
            return {};
        }

        std::string payload(length, '\0');
        if (!ReadBytes(fd, payload.data(), length))
        {
            return {};
        }
        return payload;
    }

    std::string ReadFromSharedFd(int fd)
    {
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(uint32_t)))
        {
            return {};
        }
        void *mapping = mmap(nullptr,
                             static_cast<size_t>(st.st_size),
                             PROT_READ,
                             MAP_SHARED,
                             fd,
                             0);
        if (mapping == MAP_FAILED)
        {
            return {};
        }
        const auto *bytes = static_cast<const uint8_t *>(mapping);
        uint32_t be_length = 0;
        std::memcpy(&be_length, bytes, sizeof(uint32_t));
        const uint32_t length = ntohl(be_length);
        if (length == 0 || length + sizeof(uint32_t) > static_cast<size_t>(st.st_size))
        {
            munmap(mapping, static_cast<size_t>(st.st_size));
            return {};
        }
        std::string payload(reinterpret_cast<const char *>(bytes + sizeof(uint32_t)), length);
        munmap(mapping, static_cast<size_t>(st.st_size));
        return payload;
    }

    std::string ExtractObjectSegment(std::string_view json, std::string_view key)
    {
        const size_t key_pos = json.find(key);
        if (key_pos == std::string_view::npos)
        {
            return {};
        }
        size_t start = json.find('{', key_pos);
        if (start == std::string_view::npos)
        {
            return {};
        }
        int depth = 0;
        for (size_t i = start; i < json.size(); ++i)
        {
            if (json[i] == '{')
            {
                depth++;
            }
            else if (json[i] == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return std::string(json.substr(start, i - start + 1));
                }
            }
        }
        return {};
    }

    std::vector<std::string> ParseWhitelist(const std::string &json)
    {
        std::vector<std::string> whitelist;
        const std::string segment = ExtractObjectSegment(json, "\"whitelist\"");
        if (segment.empty())
        {
            return whitelist;
        }
        size_t pos = 0;
        while (pos < segment.size())
        {
            size_t key_start = segment.find('"', pos);
            if (key_start == std::string::npos)
                break;
            size_t key_end = segment.find('"', key_start + 1);
            if (key_end == std::string::npos)
                break;
            const std::string key = segment.substr(key_start + 1, key_end - key_start - 1);
            size_t colon = segment.find(':', key_end);
            if (colon == std::string::npos)
                break;
            size_t value_start = segment.find_first_not_of(" \t\n\r", colon + 1);
            if (value_start == std::string::npos)
                break;
            if (segment.compare(value_start, 4, "true") == 0)
            {
                if (key.size() > kMaxProcessNameLength)
                {
                    __android_log_print(ANDROID_LOG_WARN,
                                        kLogTag,
                                        "Whitelist entry too long: %s",
                                        key.c_str());
                }
                else
                {
                    whitelist.push_back(key);
                }
                pos = value_start + 4;
            }
            else if (segment.compare(value_start, 5, "false") == 0)
            {
                pos = value_start + 5;
            }
            else
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Whitelist entry for %s has non-boolean value",
                                    key.c_str());
                pos = value_start + 1;
            }
            if (whitelist.size() >= kMaxWhitelistEntries)
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Whitelist truncated at %zu entries",
                                    whitelist.size());
                break;
            }
        }
        return whitelist;
    }

    bool IsValidProcessName(std::string_view name)
    {
        if (name.empty() || name.size() > kMaxProcessNameLength)
        {
            return false;
        }
        for (char c : name)
        {
            if (std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == ':')
            {
                continue;
            }
            return false;
        }
        return true;
    }

    bool IsValidProfileId(std::string_view id)
    {
        if (id.empty() || id.size() > kMaxProfileIdLength)
        {
            return false;
        }
        for (char c : id)
        {
            if (std::isalnum(static_cast<unsigned char>(c)) || c == ' ' || c == '-' ||
                c == '_' || c == '.')
            {
                continue;
            }
            return false;
        }
        return true;
    }

    std::string ParseDefaultProfile(const std::string &json)
    {
        const std::string segment = ExtractObjectSegment(json, "\"profiles\"");
        if (segment.empty())
        {
            return {};
        }
        size_t quote = segment.find('"');
        if (quote == std::string::npos)
        {
            return {};
        }
        size_t end = segment.find('"', quote + 1);
        if (end == std::string::npos)
        {
            return {};
        }
        const std::string id = segment.substr(quote + 1, end - quote - 1);
        return IsValidProfileId(id) ? id : std::string{};
    }

    std::optional<bool> ParseBoolField(std::string_view json, std::string_view key)
    {
        const std::string needle = std::string("\"") + std::string(key) + "\"";
        size_t pos = json.find(needle);
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        pos = json.find(':', pos + needle.size());
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        pos = json.find_first_not_of(" \t\n\r", pos + 1);
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        if (json.compare(pos, 4, "true") == 0)
        {
            return true;
        }
        if (json.compare(pos, 5, "false") == 0)
        {
            return false;
        }
        return std::nullopt;
    }

    std::optional<std::string> ParseStringField(std::string_view json, std::string_view key)
    {
        const std::string needle = std::string("\"") + std::string(key) + "\"";
        size_t pos = json.find(needle);
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        pos = json.find(':', pos + needle.size());
        if (pos == std::string_view::npos)
        {
            return std::nullopt;
        }
        pos = json.find_first_not_of(" \t\n\r", pos + 1);
        if (pos == std::string_view::npos || json[pos] != '"')
        {
            return std::nullopt;
        }
        const size_t start = pos + 1;
        const size_t end = json.find('"', start);
        if (end == std::string_view::npos)
        {
            return std::nullopt;
        }
        return std::string(json.substr(start, end - start));
    }

    bool EngineModeAllowsNative(const std::optional<std::string> &mode)
    {
        if (!mode.has_value() || mode->empty() || *mode == "native_first" || *mode == "low_latency")
        {
            return true;
        }
        if (*mode == "compatibility")
        {
            return false;
        }
        __android_log_print(ANDROID_LOG_WARN,
                            kLogTag,
                            "Unknown engine mode '%s'; disabling native hooks",
                            mode->c_str());
        return false;
    }

    std::vector<std::string> FilterWhitelist(std::vector<std::string> whitelist)
    {
        std::vector<std::string> filtered;
        filtered.reserve(whitelist.size());
        for (const auto &entry : whitelist)
        {
            if (IsValidProcessName(entry))
            {
                filtered.push_back(entry);
            }
            else
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Rejected invalid process name: %s",
                                    entry.c_str());
            }
            if (filtered.size() >= kMaxWhitelistEntries)
            {
                break;
            }
        }
        return filtered;
    }

    std::string ExtractFirstProfilePayload(const std::string &json)
    {
        // Minimal JSON traversal: expect {"profiles":{...}, "whitelist": {...}}.
        const std::string segment = ExtractObjectSegment(json, "\"profiles\"");
        if (segment.empty())
        {
            return {};
        }
        size_t pos = segment.find('{');
        if (pos == std::string::npos)
        {
            return {};
        }
        pos += 1; // inside profiles map
        while (pos < segment.size())
        {
            // skip whitespace
            while (pos < segment.size() && isspace(static_cast<unsigned char>(segment[pos])))
            {
                ++pos;
            }
            if (pos >= segment.size() || segment[pos] != '"')
            {
                break;
            }
            size_t key_end = segment.find('"', pos + 1);
            if (key_end == std::string::npos)
                break;
            size_t colon = segment.find(':', key_end);
            if (colon == std::string::npos)
                break;
            size_t value_start = segment.find_first_not_of(" \t\n\r", colon + 1);
            if (value_start == std::string::npos)
                break;
            if (segment[value_start] != '{')
            {
                pos = value_start + 1;
                continue;
            }
            int depth = 0;
            size_t start = value_start;
            for (size_t i = value_start; i < segment.size(); ++i)
            {
                if (segment[i] == '{')
                    depth++;
                else if (segment[i] == '}')
                    depth--;
                if (depth == 0)
                {
                    return segment.substr(start, i - start + 1);
                }
            }
            break;
        }
        return {};
    }

    bool LooksLikePreset(const std::string &payload)
    {
        if (payload.size() > kMaxPresetSize)
        {
            return false;
        }
        // Simple structural checks; full validation happens in DSP loader.
        return payload.find("\"modules\"") != std::string::npos &&
               payload.find("\"engine\"") != std::string::npos &&
               payload.find("\"id\"") != std::string::npos;
    }

} // namespace

namespace echidna
{
    namespace runtime
    {

        ProfileSyncServer::ProfileSyncServer() = default;

        ProfileSyncServer::~ProfileSyncServer() { stop(); }

        bool ProfileSyncServer::refreshOnce()
        {
            const int fd = ConnectPublisher();
            if (fd < 0)
            {
                return false;
            }
            (void)SetReadTimeout(fd, kInitialReadTimeoutMs);
            const bool applied = readAndApply(fd);
            ::close(fd);
            return applied;
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
            running_ = false;
            const int fd = client_fd_.exchange(-1);
            if (fd >= 0)
            {
                ::shutdown(fd, SHUT_RDWR);
                ::close(fd);
            }
            if (worker_.joinable())
            {
                worker_.join();
            }
        }

        void ProfileSyncServer::run()
        {
            while (running_)
            {
                const int client = ConnectPublisher();
                if (client < 0)
                {
                    std::this_thread::sleep_for(std::chrono::milliseconds(kReconnectDelayMs));
                    continue;
                }
                client_fd_.store(client);
                __android_log_print(ANDROID_LOG_INFO, kLogTag, "Connected to profile publisher");
                while (running_ && readAndApply(client))
                {
                    // Keep consuming update frames on the long-lived connection.
                }
                const int fd = client_fd_.exchange(-1);
                if (fd >= 0)
                {
                    ::close(fd);
                }
                if (running_)
                {
                    std::this_thread::sleep_for(std::chrono::milliseconds(kReconnectDelayMs));
                }
            }
        }

        bool ProfileSyncServer::readAndApply(int client_fd)
        {
            int shared_fd = -1;
            std::string payload = ReceiveWithFd(client_fd, &shared_fd);
            if (payload.empty() && shared_fd >= 0)
            {
                payload = ReadFromSharedFd(shared_fd);
            }
            if (shared_fd >= 0)
            {
                ::close(shared_fd);
            }
            if (!payload.empty())
            {
                handlePayload(payload);
                return true;
            }
            return false;
        }

        void ProfileSyncServer::handlePayload(const std::string &payload)
        {
            // Guard against unreasonably large payloads.
            if (payload.size() > 512 * 1024)
            {
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "Profile payload too large, skipping");
                return;
            }

            echidna::utils::ConfigurationSnapshot snapshot;
            const std::string control = ExtractObjectSegment(payload, "\"control\"");
            const bool hooks_enabled = ParseBoolField(payload, "hooksEnabled").value_or(true);
            const bool master_enabled =
                control.empty() ? true : ParseBoolField(control, "masterEnabled").value_or(true);
            const bool bypass =
                control.empty() ? false : ParseBoolField(control, "bypass").value_or(false);
            const std::optional<std::string> engine_mode =
                control.empty() ? std::nullopt : ParseStringField(control, "engineMode");
            snapshot.hooks_enabled =
                hooks_enabled && master_enabled && !bypass && EngineModeAllowsNative(engine_mode);
            snapshot.process_whitelist = FilterWhitelist(ParseWhitelist(payload));
            snapshot.profile = ParseDefaultProfile(payload);
            echidna::utils::ConfigSharedMemory memory;
            memory.updateSnapshot(snapshot);
            echidna::state::SharedState::instance().updateConfiguration(snapshot);

            // Apply preset JSON if the payload looks like a preset definition.
            const std::string preset_payload = ExtractFirstProfilePayload(payload);
            if (!preset_payload.empty() &&
                preset_payload.size() < kMaxPresetSize &&
                LooksLikePreset(preset_payload))
            {
                const echidna_result_t result =
                    echidna_set_profile(preset_payload.c_str(), preset_payload.size());
                if (result != ECHIDNA_RESULT_OK)
                {
                    __android_log_print(ANDROID_LOG_WARN,
                                        kLogTag,
                                        "Failed to apply pushed preset: %d",
                                        static_cast<int>(result));
                }
            }
        }

    } // namespace runtime
} // namespace echidna
