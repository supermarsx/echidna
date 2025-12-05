#pragma once

#include <stddef.h>

#include <mutex>
#include <string>
#include <vector>

namespace echidna {
namespace utils {

/**
 * @brief Process whitelist and profile snapshot shared between controller and hooks.
 */
struct ConfigurationSnapshot {
    bool hooks_enabled{false};
    std::vector<std::string> process_whitelist;
    std::string profile;
};

class ConfigSharedMemory {
  public:
    ConfigSharedMemory();
    ~ConfigSharedMemory();

    /**
     * @brief Reads current configuration from shared memory.
     */
    ConfigurationSnapshot snapshot() const;
    /**
     * @brief Updates only the active profile string in shared memory.
     */
    void updateProfile(const std::string &profile);
    /**
     * @brief Writes a full snapshot (hooks flag, whitelist, profile).
     */
    void updateSnapshot(const ConfigurationSnapshot &snapshot);

  private:
    struct SharedLayout;

    SharedLayout *layout_;
    size_t layout_size_;
    int fd_;
    mutable std::mutex mutex_;

    void ensureInitialized();
};

}  // namespace utils
}  // namespace echidna
