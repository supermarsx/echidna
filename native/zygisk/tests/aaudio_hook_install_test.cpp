#include "hooks/aaudio_callback_registry.h"
#include "hooks/aaudio_hook_readiness.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <thread>
#include <vector>

namespace
{
    int gFailures = 0;

    void Check(bool condition, const char *expression, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [line %d] %s\n", expression, line, message);
            ++gFailures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    uint32_t RoleBit(echidna::hooks::AAudioHookRole role)
    {
        return 1U << static_cast<uint32_t>(role);
    }

    void TestEveryHookResultPermutation()
    {
        using namespace echidna::hooks;
        const AAudioHookAvailability all_available{true, true, true, true, true};

        for (uint32_t outcomes = 0; outcomes < 32U; ++outcomes)
        {
            std::vector<AAudioHookRole> attempts;
            const AAudioHookInstallation installed = InstallAAudioHookSet(
                all_available,
                [&](AAudioHookRole role)
                {
                    attempts.push_back(role);
                    return (outcomes & RoleBit(role)) != 0;
                });

            const bool close_ok = (outcomes & RoleBit(AAudioHookRole::kClose)) != 0;
            const bool delete_ok =
                (outcomes & RoleBit(AAudioHookRole::kDeleteBuilder)) != 0;
            const bool callback_ok =
                (outcomes & RoleBit(AAudioHookRole::kSetDataCallback)) != 0;
            const bool read_ok = (outcomes & RoleBit(AAudioHookRole::kRead)) != 0;
            const bool support_complete = close_ok &&
                (read_ok || (delete_ok && callback_ok));
            const bool open_ok = support_complete &&
                (outcomes & RoleBit(AAudioHookRole::kOpen)) != 0;

            CHECK(installed.anyRouteComplete() == (support_complete && open_ok),
                  "only an open plus a complete lifecycle route may activate");
            if (!attempts.empty() && attempts.back() == AAudioHookRole::kOpen)
            {
                CHECK(support_complete, "open is attempted only after complete support");
            }
            for (size_t index = 0; index + 1 < attempts.size(); ++index)
            {
                CHECK(attempts[index] != AAudioHookRole::kOpen,
                      "open must be the final irreversible hook attempt");
            }

            AAudioHookReadiness readiness;
            readiness.publish(installed);
            CHECK(readiness.readReady() ==
                      (open_ok && close_ok && read_ok),
                  "read readiness requires open, close, and read");
            CHECK(readiness.callbackReady() ==
                      (open_ok && close_ok && delete_ok && callback_ok),
                  "callback readiness requires complete builder lifecycle");
        }
    }

    void TestReadOnlyCallbackOnlyAndMissingClose()
    {
        using namespace echidna::hooks;
        std::vector<AAudioHookRole> attempts;
        const auto succeed = [&](AAudioHookRole role)
        {
            attempts.push_back(role);
            return true;
        };

        AAudioHookInstallation read_only = InstallAAudioHookSet(
            AAudioHookAvailability{true, true, false, false, true}, succeed);
        CHECK(read_only.readRouteComplete() && !read_only.callbackRouteComplete(),
              "a read-only lifecycle is transform-capable");
        CHECK(!attempts.empty() && attempts.back() == AAudioHookRole::kOpen,
              "read-only route still installs open last");

        attempts.clear();
        AAudioHookInstallation callback_only = InstallAAudioHookSet(
            AAudioHookAvailability{true, true, true, true, false}, succeed);
        CHECK(!callback_only.readRouteComplete() &&
                  callback_only.callbackRouteComplete(),
              "a callback-only lifecycle is transform-capable");
        CHECK(!attempts.empty() && attempts.back() == AAudioHookRole::kOpen,
              "callback-only route still installs open last");

        attempts.clear();
        AAudioHookInstallation no_close = InstallAAudioHookSet(
            AAudioHookAvailability{true, false, true, true, true}, succeed);
        CHECK(!no_close.anyRouteComplete() && attempts.empty(),
              "missing close leaves every irreversible hook untouched");
    }

    void TestPartialHooksStayPassThrough()
    {
        using namespace echidna::hooks;
        AAudioHookReadiness readiness;
        AAudioHookInstallation partial;
        partial.close = true;
        partial.read = true;
        partial.delete_builder = true;
        partial.set_data_callback = true;
        readiness.publish(partial);

        int platform_reads = 0;
        int processed_reads = 0;
        int platform_callbacks = 0;
        int proxied_callbacks = 0;
        auto forward_read = [&]()
        {
            ++platform_reads;
            if (readiness.readReady())
            {
                ++processed_reads;
            }
        };
        auto forward_callback = [&]()
        {
            ++platform_callbacks;
            if (readiness.callbackReady())
            {
                ++proxied_callbacks;
            }
        };
        forward_read();
        forward_callback();
        CHECK(platform_reads == 1 && processed_reads == 0,
              "partial read hook forwards without processing");
        CHECK(platform_callbacks == 1 && proxied_callbacks == 0,
              "partial callback hook forwards without proxy state");
        CHECK(!readiness.lifecycleReady(),
              "partial close/delete forwarders may not touch lifecycle state");

        partial.open = true;
        readiness.publish(partial);
        forward_read();
        forward_callback();
        CHECK(processed_reads == 1 && proxied_callbacks == 1,
              "one release publication activates both complete routes");
        readiness.clear();
        CHECK(!readiness.lifecycleReady(),
              "reinstall or test teardown resets every route to pass-through");
    }

    int Callback(void *, void *, void *, int32_t)
    {
        return 7;
    }

    void TestStaleCloseBeforePointerReuse()
    {
        echidna::hooks::AAudioCallbackRegistry registry;
        void *builder = reinterpret_cast<void *>(uintptr_t{0x1000});
        void *stream = reinterpret_cast<void *>(uintptr_t{0x2000});
        void *old_token = registry.registerCallback(builder, Callback, nullptr);
        CHECK(old_token != nullptr && registry.attachOpenedStream(builder, stream),
              "old callback lifecycle attaches");
        registry.closeStream(stream);
        registry.closeStream(stream);
        registry.retireBuilder(builder);

        void *new_token = registry.registerCallback(builder, Callback, nullptr);
        CHECK(new_token != nullptr && new_token != old_token,
              "pointer reuse receives a fresh callback generation");
        CHECK(registry.attachOpenedStream(builder, stream),
              "stream address can be reused after a duplicate stale close");

        echidna::hooks::AAudioCallbackTarget target;
        CHECK(!registry.beginInvocation(old_token, stream, &target),
              "stale token cannot alias the reused stream pointer");
        CHECK(registry.beginInvocation(new_token, stream, &target),
              "current generation remains callable after pointer reuse");
        registry.endInvocation(new_token);
    }

    void TestReadinessPublicationRace()
    {
        using namespace echidna::hooks;
        AAudioHookReadiness readiness;
        AAudioHookInstallation partial;
        partial.open = true;
        partial.read = true;
        AAudioHookInstallation complete_read = partial;
        complete_read.close = true;
        AAudioHookInstallation complete_callback;
        complete_callback.open = true;
        complete_callback.close = true;
        complete_callback.delete_builder = true;
        complete_callback.set_data_callback = true;

        std::atomic<bool> start{false};
        std::atomic<bool> done{false};
        std::atomic<bool> invalid{false};
        std::thread reader([&]()
        {
            while (!start.load(std::memory_order_acquire))
            {
                std::this_thread::yield();
            }
            while (!done.load(std::memory_order_acquire))
            {
                const uint32_t routes = readiness.snapshot();
                if (routes != 0 && routes != AAudioHookReadiness::kReadRoute &&
                    routes != AAudioHookReadiness::kCallbackRoute)
                {
                    invalid.store(true, std::memory_order_release);
                }
            }
        });
        start.store(true, std::memory_order_release);
        for (size_t iteration = 0; iteration < 100000; ++iteration)
        {
            readiness.publish(partial);
            readiness.publish(complete_read);
            readiness.publish(complete_callback);
        }
        done.store(true, std::memory_order_release);
        reader.join();
        CHECK(!invalid.load(std::memory_order_acquire),
              "acquire readers observe only whole release-published route masks");
    }
} // namespace

int main()
{
    TestEveryHookResultPermutation();
    TestReadOnlyCallbackOnlyAndMissingClose();
    TestPartialHooksStayPassThrough();
    TestStaleCloseBeforePointerReuse();
    TestReadinessPublicationRace();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "aaudio_hook_install_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "aaudio_hook_install_test: all checks passed\n");
    return 0;
}
