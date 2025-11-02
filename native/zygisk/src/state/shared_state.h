#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "utils/config_shared_memory.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna {
namespace state {

enum class InternalStatus : int {
    kDisabled = 0,
    kWaitingForAttach = 1,
    kHooked = 2,
    kError = 3,
};

class SharedState {
  public:
    static SharedState &instance();

    int status() const;
    void setStatus(InternalStatus status);

    std::string profile() const;
    void setProfile(const std::string &profile);

    bool isProcessWhitelisted(const std::string &process) const;
    bool hooksEnabled() const;

    void updateConfiguration(const utils::ConfigurationSnapshot &snapshot);
    void refreshFromSharedMemory();

    utils::TelemetrySharedMemory &telemetry();
    const utils::TelemetrySharedMemory &telemetry() const;

  private:
    SharedState();

    mutable std::mutex mutex_;
    InternalStatus status_;
    std::string profile_;
    utils::ConfigSharedMemory shared_memory_;
    utils::ConfigurationSnapshot cached_snapshot_;
    utils::TelemetrySharedMemory telemetry_memory_;
};

}  // namespace state
}  // namespace echidna
