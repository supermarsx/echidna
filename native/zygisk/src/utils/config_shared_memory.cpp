#include "utils/config_shared_memory.h"

/**
 * @file config_shared_memory.cpp
 * @brief Shared memory helpers for reading/writing runtime configuration
 * (whitelist and active profile) between the controller service and hooks.
 */

#include "utils/android_shared_memory.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>

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
            : layout_(nullptr), layout_size_(sizeof(SharedLayout)), fd_(-1), writable_(false)
        {
            ensureInitialized();
        }

        /** Release this reference to the shared-memory segment. */
        ConfigSharedMemory::~ConfigSharedMemory()
        {
            if (layout_)
            {
                ReleaseSharedRegion(kSharedMemoryName);
                layout_ = nullptr;
            }
            fd_ = -1;
            writable_ = false;
        }

        /** Ensure the shared memory mapping is visible in-memory; initialize if new. */
        void ConfigSharedMemory::ensureInitialized()
        {
            std::scoped_lock lock(mutex_);
            if (layout_)
            {
                return;
            }

            // bionic has no shm_open; back the region with an anonymous memfd kept
            // in a process-global name registry so separate ConfigSharedMemory
            // instances (ProfileSyncServer writer, SharedState reader) share it.
            AndroidSharedRegion region = AcquireSharedRegion(kSharedMemoryName, layout_size_);
            if (!region.addr)
            {
                return;
            }

            fd_ = region.fd;
            layout_ = reinterpret_cast<SharedLayout *>(region.addr);
            writable_ = !region.read_only;
            if (layout_->magic != kLayoutMagic && writable_)
            {
                // Only the writer (root/controller, RW mapping) initializes the
                // region. A hooked app maps it read-only under enforcing SELinux;
                // writing here would fault. If the writer has not published a valid
                // config yet, the magic stays invalid and snapshot() fails closed.
                std::memset(layout_, 0, sizeof(SharedLayout));
                layout_->magic = kLayoutMagic;
            }
        }

        /** Read a snapshot copy of configuration visible to users of this class. */
        ConfigurationSnapshot ConfigSharedMemory::snapshot() const
        {
            std::scoped_lock lock(mutex_);
            ConfigurationSnapshot snapshot;
            if (!layout_ || layout_->magic != kLayoutMagic)
            {
                // Fail closed: an unmapped region, or one the writer has not yet
                // published a valid config into, yields an empty snapshot (hooks
                // disabled, empty whitelist) so no process is hooked by default.
                return snapshot;
            }

            snapshot.hooks_enabled = layout_->hooks_enabled != 0;
            snapshot.profile = layout_->profile;
            const uint32_t count = std::min(
                layout_->whitelist_size,
                static_cast<uint32_t>(kMaxWhitelistEntries));
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
            updateProfileLocked(profile);
        }

        void ConfigSharedMemory::updateProfileLocked(const std::string &profile)
        {
            if (!layout_ || !writable_)
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
            if (!layout_ || !writable_)
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
                updateProfileLocked(snapshot.profile);
            }
        }

    } // namespace utils
} // namespace echidna
