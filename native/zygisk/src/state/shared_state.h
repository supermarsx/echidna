#pragma once

/**
 * @file shared_state.h
 * @brief Process-local singleton representing configuration and telemetry
 * state synchronized with shared-memory segments.
 */

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "utils/config_shared_memory.h"
#include "utils/telemetry_shared_memory.h"

namespace echidna
{
  namespace state
  {

    enum class InternalStatus : int
    {
      kDisabled = 0,
      kWaitingForAttach = 1,
      kHooked = 2,
      kError = 3,
    };

    class SharedState
    {
    public:
      /**
       * @brief Singleton accessor for process-local shared state.
       */
      static SharedState &instance();

      /**
       * @brief Returns current internal status.
       */
      int status() const;
      /**
       * @brief Sets internal status (thread-safe).
       */
      void setStatus(InternalStatus status);

      /**
       * @brief Returns active profile label.
       */
      std::string profile() const;
      /**
       * @brief Updates active profile label and broadcasts to shared memory.
       */
      void setProfile(const std::string &profile);

      /**
       * @brief Checks if process is whitelisted for hooks.
       */
      bool isProcessWhitelisted(const std::string &process) const;
      /**
       * @brief Returns whether hooks are globally enabled.
       */
      bool hooksEnabled() const;
      /**
       * @brief Enables or disables local bypass mode (no DSP processing).
       */
      void setBypass(bool enabled);
      /**
       * @brief Enables bypass until the provided monotonic timestamp (ns).
       */
      void setBypassUntil(uint64_t until_ns);
      /**
       * @brief Returns true when bypass is active; clears timer when expired.
       */
      bool isBypassed(uint64_t now_ns);

      /**
       * @brief Applies configuration snapshot from controller.
       */
      void updateConfiguration(const utils::ConfigurationSnapshot &snapshot);
      /**
       * @brief Refreshes configuration from shared memory segment.
       */
      void refreshFromSharedMemory();

      /**
       * @brief Accessor for telemetry shared memory.
       */
      utils::TelemetrySharedMemory &telemetry();
      const utils::TelemetrySharedMemory &telemetry() const;

    private:
      SharedState();

      mutable std::mutex mutex_;
      InternalStatus status_;
      std::string profile_;
      bool bypass_enabled_;
      uint64_t bypass_until_ns_;
      utils::ConfigSharedMemory shared_memory_;
      utils::ConfigurationSnapshot cached_snapshot_;
      utils::TelemetrySharedMemory telemetry_memory_;
    };

  } // namespace state
} // namespace echidna
