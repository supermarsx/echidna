#include "hooks/aaudio_hook_readiness.h"

namespace echidna::hooks
{
    void AAudioHookReadiness::clear()
    {
        ready_routes_.store(0, std::memory_order_release);
    }

    void AAudioHookReadiness::publish(const AAudioHookInstallation &installed)
    {
        uint32_t routes = 0;
        if (installed.readRouteComplete())
        {
            routes |= kReadRoute;
        }
        if (installed.callbackRouteComplete())
        {
            routes |= kCallbackRoute;
        }
        ready_routes_.store(routes, std::memory_order_release);
    }

    uint32_t AAudioHookReadiness::snapshot() const
    {
        return ready_routes_.load(std::memory_order_acquire);
    }

    bool AAudioHookReadiness::readReady() const
    {
        return (snapshot() & kReadRoute) != 0;
    }

    bool AAudioHookReadiness::callbackReady() const
    {
        return (snapshot() & kCallbackRoute) != 0;
    }

    bool AAudioHookReadiness::lifecycleReady() const
    {
        return snapshot() != 0;
    }
} // namespace echidna::hooks
