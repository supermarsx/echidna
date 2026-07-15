#pragma once

/**
 * @file profile_sync_server.h
 * @brief Local socket reader for profile snapshots published by the companion
 * service.
 */

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
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
                              PresetApplier preset_applier = {},
                              int64_t expected_publisher_uid = -1);
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

            /** Quiesces publication/callbacks and interrupts I/O without joining. */
            void beginStop();

            /** Joins the quiesced worker and performs the final admission revoke. */
            void finishStop();

        private:
            std::atomic<bool> running_{false};
            std::atomic<bool> accepting_payloads_{true};
            std::thread worker_;
            std::thread telemetry_worker_;
            // The worker exclusively closes this descriptor. stop() only
            // shutdowns it while holding client_mutex_ so a recycled fd can
            // never be touched by a concurrent teardown.
            int client_fd_{-1};
            std::mutex client_mutex_;
            std::string process_name_;
            SnapshotCallback callback_;
            std::mutex callback_mutex_;
            PresetApplier preset_applier_;
            int64_t expected_publisher_uid_{-1};
            mutable std::mutex state_mutex_;
            std::mutex wait_mutex_;
            std::mutex telemetry_wait_mutex_;
            std::condition_variable stop_requested_;
            uint64_t generation_{0};
            std::string generation_payload_;
            DecodedProfileSnapshot current_snapshot_;
            bool has_snapshot_{false};
            bool snapshot_published_{false};

            void run();
            void runTelemetryExporter();
            bool readAndApply(int client_fd);
            bool waitBeforeReconnect(std::chrono::milliseconds delay);
            void revokeProcessAdmission(bool notify_callback);
            void dispatchSnapshot(const DecodedProfileSnapshot &snapshot);
        };

    } // namespace runtime
} // namespace echidna
