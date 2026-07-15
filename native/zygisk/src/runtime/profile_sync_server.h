#pragma once

/**
 * @file profile_sync_server.h
 * @brief Local socket reader for profile snapshots published by the companion
 * service.
 */

#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>

#include "runtime/profile_sync_protocol.h"

namespace echidna
{
    namespace runtime
    {

        class ProfileSyncServer
        {
        public:
            using SnapshotCallback = std::function<void(const DecodedProfileSnapshot &)>;
            using PresetApplier = std::function<bool(std::string_view)>;

            ProfileSyncServer();
            ProfileSyncServer(std::string process_name,
                              SnapshotCallback callback,
                              PresetApplier preset_applier = {});
            ~ProfileSyncServer();

            /**
             * @brief Reads one snapshot synchronously when the publisher is reachable.
             */
            bool refreshOnce();

            /** Validates and atomically publishes one already-framed v2 payload. */
            bool applyPayload(std::string_view payload);

            /** True after this process has accepted at least one valid v2 snapshot. */
            bool hasSnapshot() const;

            /** Current process-local native admission decision. */
            bool nativeProcessAdmitted() const;

            /**
             * @brief Starts the profile sync reader thread (idempotent).
             */
            void start();

            /**
             * @brief Stops the listener and joins the worker thread.
             */
            void stop();

        private:
            std::atomic<bool> running_{false};
            std::thread worker_;
            std::atomic<int> client_fd_{-1};
            std::string process_name_;
            SnapshotCallback callback_;
            PresetApplier preset_applier_;
            mutable std::mutex state_mutex_;
            std::mutex wait_mutex_;
            std::condition_variable stop_requested_;
            uint64_t generation_{0};
            std::string generation_payload_;
            DecodedProfileSnapshot current_snapshot_;
            bool has_snapshot_{false};

            void run();
            bool readAndApply(int client_fd);
            bool waitBeforeReconnect();
        };

    } // namespace runtime
} // namespace echidna
