#pragma once

#include <stddef.h>

#include <mutex>
#include <string>
#include <vector>

namespace echidna {
namespace utils {

struct ConfigurationSnapshot {
    bool hooks_enabled{false};
    std::vector<std::string> process_whitelist;
    std::string profile;
};

class ConfigSharedMemory {
  public:
    ConfigSharedMemory();
    ~ConfigSharedMemory();

    ConfigurationSnapshot snapshot() const;
    void updateProfile(const std::string &profile);

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
