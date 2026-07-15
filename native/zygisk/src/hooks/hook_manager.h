#pragma once

/**
 * @file hook_manager.h
 * @brief Base interface for per-API hook manager classes which install and
 * maintain individual hook sets (AAudio, OpenSL, libc read, etc.).
 */

#include <string>

#include "hooks/capture_route_reachability.h"

namespace echidna
{
    namespace hooks
    {

        struct HookInstallInfo
        {
            bool success{false};
            std::string library;
            std::string symbol;
            std::string reason;
        };

        class HookManager
        {
        public:
            virtual ~HookManager() = default;
            /**
             * @brief Installs the hook(s) managed by this instance.
             * @return true on success.
             */
            virtual bool install() = 0;
            /**
             * @brief Returns a short human-readable hook name.
             */
            virtual const char *name() const = 0;
            /**
             * @brief Returns details from the most recent install attempt.
             */
            virtual const HookInstallInfo &lastInstallInfo() const = 0;
            /** Describes whether the route is normally reachable and its metadata source. */
            virtual const CaptureRouteDescriptor &routeDescriptor() const = 0;
        };

    } // namespace hooks
} // namespace echidna
