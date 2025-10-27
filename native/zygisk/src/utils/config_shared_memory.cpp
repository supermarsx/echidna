#include "utils/config_shared_memory.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <cstdio>

namespace {
constexpr const char *kSharedMemoryName = "/echidna_config";
constexpr size_t kMaxWhitelistEntries = 32;
constexpr size_t kMaxProcessName = 128;
constexpr size_t kMaxProfile = 96;
constexpr uint32_t kLayoutMagic = 0xEDC1DA00u;
}  // namespace

namespace echidna {
namespace utils {

struct ConfigSharedMemory::SharedLayout {
    uint32_t magic;
    uint32_t hooks_enabled;
    uint32_t whitelist_size;
    char whitelist[kMaxWhitelistEntries][kMaxProcessName];
    char profile[kMaxProfile];
};

ConfigSharedMemory::ConfigSharedMemory()
    : layout_(nullptr), layout_size_(sizeof(SharedLayout)), fd_(-1) {
    ensureInitialized();
}

ConfigSharedMemory::~ConfigSharedMemory() {
    if (layout_) {
        munmap(layout_, layout_size_);
    }
    if (fd_ >= 0) {
        close(fd_);
    }
}

void ConfigSharedMemory::ensureInitialized() {
    std::scoped_lock lock(mutex_);
    if (layout_) {
        return;
    }

    fd_ = shm_open(kSharedMemoryName, O_RDWR | O_CREAT, 0666);
    if (fd_ < 0) {
        perror("shm_open");
        return;
    }

    if (ftruncate(fd_, static_cast<off_t>(layout_size_)) != 0) {
        perror("ftruncate");
        close(fd_);
        fd_ = -1;
        return;
    }

    void *mapped = mmap(nullptr, layout_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (mapped == MAP_FAILED) {
        perror("mmap");
        close(fd_);
        fd_ = -1;
        return;
    }

    layout_ = reinterpret_cast<SharedLayout *>(mapped);
    if (layout_->magic != kLayoutMagic) {
        std::memset(layout_, 0, sizeof(SharedLayout));
        layout_->magic = kLayoutMagic;
    }
}

ConfigurationSnapshot ConfigSharedMemory::snapshot() const {
    std::scoped_lock lock(mutex_);
    ConfigurationSnapshot snapshot;
    if (!layout_) {
        return snapshot;
    }

    snapshot.hooks_enabled = layout_->hooks_enabled != 0;
    snapshot.profile = layout_->profile;
    const uint32_t count = std::min(layout_->whitelist_size, static_cast<uint32_t>(kMaxWhitelistEntries));
    snapshot.process_whitelist.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        snapshot.process_whitelist.emplace_back(layout_->whitelist[i]);
    }
    return snapshot;
}

void ConfigSharedMemory::updateProfile(const std::string &profile) {
    std::scoped_lock lock(mutex_);
    if (!layout_) {
        return;
    }

    std::array<char, kMaxProfile> buffer{};
    std::strncpy(buffer.data(), profile.c_str(), buffer.size() - 1);
    std::memcpy(layout_->profile, buffer.data(), buffer.size());
}

}  // namespace utils
}  // namespace echidna
