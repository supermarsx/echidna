#include "utils/config_shared_memory.h"
#include "state/shared_state.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

namespace
{
    int g_failures = 0;

    void check(bool condition, const char *expr, const char *file, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [%s:%d] %s\n", expr, file, line, message);
            ++g_failures;
        }
    }

#define CHECK(cond, msg) check((cond), #cond, __FILE__, __LINE__, (msg))

    void TestSnapshotAndProfileRoundTrip()
    {
        echidna::utils::ConfigSharedMemory memory;

        echidna::utils::ConfigurationSnapshot snapshot;
        snapshot.hooks_enabled = true;
        snapshot.process_whitelist = {"com.echidna.interceptionprobe", "com.example.allowed"};
        snapshot.profile = "SmokeProfile";
        memory.updateSnapshot(snapshot);

        const auto restored = memory.snapshot();
        CHECK(restored.hooks_enabled, "hooks_enabled must round-trip");
        CHECK(restored.process_whitelist == snapshot.process_whitelist,
              "process whitelist must round-trip");
        CHECK(restored.profile == snapshot.profile, "profile must round-trip");
    }

    void TestProfileUpdateOverwritesWithoutDeadlock()
    {
        echidna::utils::ConfigSharedMemory memory;
        memory.updateProfile("ProfileA");
        CHECK(memory.snapshot().profile == "ProfileA", "profile update must be visible");

        memory.updateProfile("ProfileB");
        CHECK(memory.snapshot().profile == "ProfileB", "profile update must overwrite old value");
    }

    void TestLongProfileIsNulTerminated()
    {
        echidna::utils::ConfigSharedMemory memory;
        const std::string long_profile(512, 'x');
        memory.updateProfile(long_profile);

        const auto profile = memory.snapshot().profile;
        CHECK(!profile.empty(), "truncated profile must remain readable");
        CHECK(profile.size() < long_profile.size(), "profile must be bounded by shared layout");
        CHECK(std::all_of(profile.begin(), profile.end(), [](char value)
                          { return value == 'x'; }),
              "truncated profile must preserve prefix bytes");
    }

    void TestPackageWhitelistCoversColonProcess()
    {
        echidna::utils::ConfigSharedMemory memory;

        echidna::utils::ConfigurationSnapshot snapshot;
        snapshot.hooks_enabled = true;
        snapshot.process_whitelist = {"com.example.recorder"};
        snapshot.profile = "SmokeProfile";
        memory.updateSnapshot(snapshot);

        auto &state = echidna::state::SharedState::instance();
        state.refreshFromSharedMemory();

        CHECK(state.isProcessWhitelisted("com.example.recorder"),
              "exact process whitelist must still match");
        CHECK(state.isProcessWhitelisted("com.example.recorder:capture"),
              "package whitelist must include colon-suffixed app processes");
        CHECK(!state.isProcessWhitelisted("com.example.other:capture"),
              "unlisted packages must remain blocked");
    }
} // namespace

int main()
{
    TestSnapshotAndProfileRoundTrip();
    TestProfileUpdateOverwritesWithoutDeadlock();
    TestLongProfileIsNulTerminated();
    TestPackageWhitelistCoversColonProcess();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "config_shared_memory_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "config_shared_memory_test: all checks passed\n");
    return 0;
}
