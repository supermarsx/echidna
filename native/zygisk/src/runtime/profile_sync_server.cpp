#include "runtime/profile_sync_server.h"

#include <arpa/inet.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstring>
#include <string_view>

#include "utils/config_shared_memory.h"

namespace {
constexpr const char *kSocketPath = "/data/local/tmp/echidna_profiles.sock";
constexpr const char *kLogTag = "echidna_profile_sync";

bool ReadBytes(int fd, void *buffer, size_t bytes) {
    uint8_t *out = static_cast<uint8_t *>(buffer);
    size_t read_total = 0;
    while (read_total < bytes) {
        const ssize_t r = ::recv(fd, out + read_total, bytes - read_total, 0);
        if (r <= 0) {
            return false;
        }
        read_total += static_cast<size_t>(r);
    }
    return true;
}

std::string ReceiveWithFd(int fd, int *out_fd) {
    std::array<char, 4096> control{};
    std::array<char, 4> header{};
    struct iovec iov {
        .iov_base = header.data(), .iov_len = header.size()
    };
    struct msghdr msg {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.data();
    msg.msg_controllen = control.size();

    if (::recvmsg(fd, &msg, 0) <= 0) {
        return {};
    }

    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
            const int *fds = reinterpret_cast<int *>(CMSG_DATA(cmsg));
            const size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            if (count > 0 && fds) {
                *out_fd = fds[0];
                break;
            }
        }
    }

    uint32_t be_length = 0;
    std::memcpy(&be_length, header.data(), header.size());
    const uint32_t length = ntohl(be_length);
    if (length == 0 || length > (10 * 1024 * 1024)) {
        return {};
    }

    std::string payload(length, '\0');
    if (!ReadBytes(fd, payload.data(), length)) {
        return {};
    }
    return payload;
}

std::string ReadFromSharedFd(int fd) {
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(uint32_t))) {
        return {};
    }
    void *mapping = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        return {};
    }
    const auto *bytes = static_cast<const uint8_t *>(mapping);
    uint32_t be_length = 0;
    std::memcpy(&be_length, bytes, sizeof(uint32_t));
    const uint32_t length = ntohl(be_length);
    if (length == 0 || length + sizeof(uint32_t) > static_cast<size_t>(st.st_size)) {
        munmap(mapping, static_cast<size_t>(st.st_size));
        return {};
    }
    std::string payload(reinterpret_cast<const char *>(bytes + sizeof(uint32_t)), length);
    munmap(mapping, static_cast<size_t>(st.st_size));
    return payload;
}

std::string ExtractObjectSegment(std::string_view json, std::string_view key) {
    const size_t key_pos = json.find(key);
    if (key_pos == std::string_view::npos) {
        return {};
    }
    size_t start = json.find('{', key_pos);
    if (start == std::string_view::npos) {
        return {};
    }
    int depth = 0;
    for (size_t i = start; i < json.size(); ++i) {
        if (json[i] == '{') {
            depth++;
        } else if (json[i] == '}') {
            depth--;
            if (depth == 0) {
                return std::string(json.substr(start, i - start + 1));
            }
        }
    }
    return {};
}

std::vector<std::string> ParseWhitelist(const std::string &json) {
    std::vector<std::string> whitelist;
    const std::string segment = ExtractObjectSegment(json, "\"whitelist\"");
    if (segment.empty()) {
        return whitelist;
    }
    size_t pos = 0;
    while (pos < segment.size()) {
        size_t key_start = segment.find('"', pos);
        if (key_start == std::string::npos) break;
        size_t key_end = segment.find('"', key_start + 1);
        if (key_end == std::string::npos) break;
        const std::string key = segment.substr(key_start + 1, key_end - key_start - 1);
        size_t colon = segment.find(':', key_end);
        if (colon == std::string::npos) break;
        size_t value_start = segment.find_first_not_of(" \t\n\r", colon + 1);
        if (value_start == std::string::npos) break;
        bool value_true = segment.compare(value_start, 4, "true") == 0;
        bool value_false = segment.compare(value_start, 5, "false") == 0;
        if (value_true) {
            whitelist.push_back(key);
        } else if (!value_false) {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Whitelist entry for %s has non-boolean value",
                                key.c_str());
        }
        pos = value_start + (value_true ? 4 : value_false ? 5 : 1);
    }
    return whitelist;
}

std::string ParseDefaultProfile(const std::string &json) {
    const std::string segment = ExtractObjectSegment(json, "\"profiles\"");
    if (segment.empty()) {
        return {};
    }
    size_t quote = segment.find('"');
    if (quote == std::string::npos) {
        return {};
    }
    size_t end = segment.find('"', quote + 1);
    if (end == std::string::npos) {
        return {};
    }
    return segment.substr(quote + 1, end - quote - 1);
}

}  // namespace

namespace echidna {
namespace runtime {

ProfileSyncServer::ProfileSyncServer() = default;

ProfileSyncServer::~ProfileSyncServer() { stop(); }

void ProfileSyncServer::start() {
    if (running_.exchange(true)) {
        return;
    }
    worker_ = std::thread([this]() { run(); });
}

void ProfileSyncServer::stop() {
    running_ = false;
    if (listener_fd_ >= 0) {
        ::close(listener_fd_);
        listener_fd_ = -1;
    }
    if (worker_.joinable()) {
        worker_.join();
    }
}

int ProfileSyncServer::createListener() {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    ::unlink(kSocketPath);
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", kSocketPath);
    if (::bind(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        ::close(fd);
        return -1;
    }
    if (::listen(fd, 4) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

void ProfileSyncServer::run() {
    listener_fd_ = createListener();
    if (listener_fd_ < 0) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Failed to create profile listener");
        running_ = false;
        return;
    }
    while (running_) {
        int client = ::accept4(listener_fd_, nullptr, nullptr, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) {
                continue;
            }
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Accept failed: %s", strerror(errno));
            break;
        }
        handleClient(client);
        ::close(client);
    }
}

void ProfileSyncServer::handleClient(int client_fd) {
    int shared_fd = -1;
    std::string payload = ReceiveWithFd(client_fd, &shared_fd);
    if (payload.empty() && shared_fd >= 0) {
        payload = ReadFromSharedFd(shared_fd);
    }
    if (shared_fd >= 0) {
        ::close(shared_fd);
    }
    if (!payload.empty()) {
        handlePayload(payload);
    } else {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Profile sync payload empty");
    }
}

void ProfileSyncServer::handlePayload(const std::string &payload) {
    echidna::utils::ConfigurationSnapshot snapshot;
    snapshot.hooks_enabled = true;
    snapshot.process_whitelist = ParseWhitelist(payload);
    snapshot.profile = ParseDefaultProfile(payload);
    echidna::utils::ConfigSharedMemory memory;
    memory.updateSnapshot(snapshot);
}

}  // namespace runtime
}  // namespace echidna
