#pragma once

/**
 * @file profile_sync_server.h
 * @brief Local socket reader for profile snapshots published by the companion
 * service.
 */

#include <atomic>
#include <string>
#include <thread>
#include <vector>

namespace echidna
{
    namespace runtime
    {

        class ProfileSyncServer
        {
        public:
            ProfileSyncServer();
            ~ProfileSyncServer();

            /**
             * @brief Reads one snapshot synchronously when the publisher is reachable.
             */
            bool refreshOnce();

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

            void run();
            bool readAndApply(int client_fd);
            void handlePayload(const std::string &payload);
        };

    } // namespace runtime
} // namespace echidna
