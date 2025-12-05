#pragma once

#include <atomic>
#include <string>
#include <thread>
#include <vector>

namespace echidna {
namespace runtime {

class ProfileSyncServer {
  public:
    ProfileSyncServer();
    ~ProfileSyncServer();

    /**
     * @brief Starts the profile sync listener thread (idempotent).
     */
    void start();

    /**
     * @brief Stops the listener and joins the worker thread.
     */
    void stop();

  private:
    std::atomic<bool> running_{false};
    std::thread worker_;
    int listener_fd_{-1};

    void run();
    int createListener();
    void handleClient(int client_fd);
    void handlePayload(const std::string &payload);
};

}  // namespace runtime
}  // namespace echidna
