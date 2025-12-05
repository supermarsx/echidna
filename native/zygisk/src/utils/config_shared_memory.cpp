#include "utils/config_shared_memory.h"

/**
 * @file config_shared_memory.cpp
 * @brief Shared memory helpers for reading/writing runtime configuration
 * (whitelist and active profile) between the controller service and hooks.
 */

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <cstdio>

namespace
{
    constexpr const char *kSharedMemoryName = "/echidna_config";
    constexpr size_t kMaxWhitelistEntries = 32;
    constexpr size_t kMaxProcessName = 128;
    constexpr size_t kMaxProfile = 96;
    constexpr uint32_t kLayoutMagic = 0xEDC1DA00u;
} // namespace

namespace echidna
{
    namespace utils
    {

        /**
         * @brief On-disk/shared memory packed layout for configuration.
         */
        struct ConfigSharedMemory::SharedLayout
        {
            uint32_t magic;
            uint32_t hooks_enabled;
            uint32_t whitelist_size;
            char whitelist[kMaxWhitelistEntries][kMaxProcessName];
            char profile[kMaxProfile];
        };

        /** Construct and map the config shared-memory segment. */
        ConfigSharedMemory::ConfigSharedMemory()
            : layout_(nullptr), layout_size_(sizeof(SharedLayout)), fd_(-1)
        {
            ensureInitialized();
        }

        /** Unmap and close the shared-memory segment. */
        ConfigSharedMemory::~ConfigSharedMemory()
        {
            if (layout_)
            {
                munmap(layout_, layout_size_);
            }
            if (fd_ >= 0)
            {
                close(fd_);
            }
        }

        /** Ensure the shared memory mapping is visible in-memory; initialize if new. */
        void ConfigSharedMemory::ensureInitialized()
        {
            std::scoped_lock lock(mutex_);
            if (layout_)
            {
                return;
            }

            fd_ = shm_open(kSharedMemoryName, O_RDWR | O_CREAT, 0666);
            if (fd_ < 0)
            {
                perror("shm_open");
                return;
            }

            if (ftruncate(fd_, static_cast<off_t>(layout_size_)) != 0)
            {
                perror("ftruncate");
                close(fd_);
                fd_ = -1;
                return;
            }

            void *mapped = mmap(nullptr, layout_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
            if (mapped == MAP_FAILED)
            {
                perror("mmap");
                close(fd_);
                fd_ = -1;
                return;
            }

            layout_ = reinterpret_cast<SharedLayout *>(mapped);
            if (layout_->magic != kLayoutMagic)
            {
                std::memset(layout_, 0, sizeof(SharedLayout));
                layout_->magic = kLayoutMagic;
            }
        }

        /** Read a snapshot copy of configuration visible to users of this class. */
        ConfigurationSnapshot ConfigSharedMemory::snapshot() const
        {
            std::scoped_lock lock(mutex_);
            ConfigurationSnapshot snapshot;
            if (!layout_)
            {
                return snapshot;
            }

            snapshot.hooks_enabled = layout_->hooks_enabled != 0;
            snapshot.profile = layout_->profile;
            const uint32_t count = std::min(layout_->whitelist_size, static_cast<uint32_t>(kMaxWhitelistEntries));
            snapshot.process_whitelist.reserve(count);
            for (uint32_t i = 0; i < count; ++i)
            {
                snapshot.process_whitelist.emplace_back(layout_->whitelist[i]);
            }
            return snapshot;
        }

        /** Update only the profile string part of the shared layout. */
        void ConfigSharedMemory::updateProfile(const std::string &profile)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }

            std::array<char, kMaxProfile> buffer{};
            std::strncpy(buffer.data(), profile.c_str(), buffer.size() - 1);
            std::memcpy(layout_->profile, buffer.data(), buffer.size());
        }

        /** Apply a full snapshot of configuration (hooks_enabled, whitelist and profile). */
        void ConfigSharedMemory::updateSnapshot(const ConfigurationSnapshot &snapshot)
        {
            std::scoped_lock lock(mutex_);
            if (!layout_)
            {
                return;
            }

            layout_->hooks_enabled = snapshot.hooks_enabled ? 1u : 0u;
            const uint32_t count =
                std::min<uint32_t>(snapshot.process_whitelist.size(), kMaxWhitelistEntries);
            layout_->whitelist_size = count;
            for (uint32_t i = 0; i < count; ++i)
            {
                std::array<char, kMaxProcessName> buffer{};
                std::strncpy(buffer.data(),
                             snapshot.process_whitelist[i].c_str(),
                             buffer.size() - 1);
                std::memcpy(layout_->whitelist[i], buffer.data(), buffer.size());
            }
            if (!snapshot.profile.empty())
            {
                updateProfile(snapshot.profile);
            }
        }

    } // namespace utils
} // namespace echidna
