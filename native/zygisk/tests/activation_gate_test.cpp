#include "runtime/activation_gate.h"
#include "runtime/reconnect_backoff.h"

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

namespace
{
    int g_failures = 0;

    void Check(bool condition, const char *expression, int line)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL line %d: %s\n", line, expression);
            ++g_failures;
        }
    }

#define CHECK(condition) Check((condition), #condition, __LINE__)

    void TestLatePublisherActivatesAfterColdStart()
    {
        echidna::runtime::ActivationGate gate;
        gate.start();
        CHECK(gate.running());
        CHECK(!gate.shouldAttemptInstall());
        CHECK(!gate.processingActive());

        gate.updatePolicy(true);
        CHECK(gate.shouldAttemptInstall());
        gate.markHooksInstalled();
        CHECK(!gate.shouldAttemptInstall());
        CHECK(gate.processingActive());
    }

    void TestRevokeAndReadmitKeepInstalledHooksInert()
    {
        echidna::runtime::ActivationGate gate;
        gate.start();
        gate.updatePolicy(true);
        gate.markHooksInstalled();

        gate.updatePolicy(false);
        CHECK(gate.hooksInstalled());
        CHECK(!gate.processingActive());
        CHECK(!gate.shouldAttemptInstall());

        gate.updatePolicy(true);
        CHECK(gate.processingActive());
        CHECK(!gate.shouldAttemptInstall());
    }

    void TestFailedInstallRemainsRetryable()
    {
        echidna::runtime::ActivationGate gate;
        gate.start();
        gate.updatePolicy(true);
        CHECK(gate.shouldAttemptInstall());
        CHECK(gate.shouldAttemptInstall());
        gate.markHooksInstalled();
        CHECK(!gate.shouldAttemptInstall());
    }

    void TestTeardownBlocksLateCallbacks()
    {
        echidna::runtime::ActivationGate gate;
        gate.start();
        gate.updatePolicy(true);
        gate.stop();
        CHECK(!gate.running());
        CHECK(!gate.shouldAttemptInstall());
        CHECK(!gate.processingActive());

        gate.updatePolicy(true);
        gate.markHooksInstalled();
        CHECK(!gate.shouldAttemptInstall());
        CHECK(!gate.processingActive());
    }

    void TestInFlightInstallCannotReactivateStoppedGate()
    {
        echidna::runtime::ActivationGate gate;
        std::mutex mutex;
        std::condition_variable changed;
        bool install_started = false;
        bool finish_install = false;
        gate.start();
        gate.updatePolicy(true);

        std::thread installer([&]()
                              {
                                  std::unique_lock lock(mutex);
                                  install_started = true;
                                  changed.notify_all();
                                  changed.wait(lock, [&]()
                                               { return finish_install; });
                                  lock.unlock();
                                  // Production takes the activation mutex before
                                  // this transition; the stopped gate suppresses it.
                                  gate.markHooksInstalled();
                              });
        {
            std::unique_lock lock(mutex);
            changed.wait(lock, [&]()
                         { return install_started; });
        }
        gate.stop();
        {
            std::scoped_lock lock(mutex);
            finish_install = true;
        }
        changed.notify_all();
        installer.join();
        CHECK(!gate.running());
        CHECK(!gate.hooksInstalled());
        CHECK(!gate.processingActive());
    }

    void TestDisableDuringActivationCannotPublishActiveState()
    {
        echidna::runtime::ActivationGate gate;
        gate.start();
        gate.updatePolicy(true);
        CHECK(gate.shouldAttemptInstall());

        // The policy callback drains and disables while installHooks is outside
        // the activation mutex. A successful late return may retain installed
        // trampolines, but must never make the revoked route active.
        gate.updatePolicy(false);
        gate.markHooksInstalled();
        CHECK(gate.hooksInstalled());
        CHECK(!gate.processingActive());
        CHECK(!gate.shouldAttemptInstall());
    }

    void TestReconnectBackoffIsJitteredAndBounded()
    {
        echidna::runtime::ReconnectBackoff backoff(0x12345678u);
        const auto first = backoff.nextDelay();
        CHECK(first >= std::chrono::milliseconds(250));
        CHECK(first <= std::chrono::milliseconds(313));
        for (int attempt = 0; attempt < 32; ++attempt)
        {
            backoff.recordFailure();
            const auto delay = backoff.nextDelay();
            CHECK(delay >= std::chrono::milliseconds(250));
            CHECK(delay <= std::chrono::milliseconds(30000));
        }
        backoff.reset();
        const auto reset = backoff.nextDelay();
        CHECK(reset >= std::chrono::milliseconds(250));
        CHECK(reset <= std::chrono::milliseconds(313));
    }

} // namespace

int main()
{
    TestLatePublisherActivatesAfterColdStart();
    TestRevokeAndReadmitKeepInstalledHooksInert();
    TestFailedInstallRemainsRetryable();
    TestTeardownBlocksLateCallbacks();
    TestInFlightInstallCannotReactivateStoppedGate();
    TestDisableDuringActivationCannotPublishActiveState();
    TestReconnectBackoffIsJitteredAndBounded();
    if (g_failures != 0)
    {
        std::fprintf(stderr, "activation_gate_test: %d failure(s)\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "activation_gate_test: all checks passed\n");
    return 0;
}
