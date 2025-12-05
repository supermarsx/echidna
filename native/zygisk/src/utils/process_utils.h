#pragma once

/**
 * @file process_utils.h
 * @brief Helpers for querying the current process name and caching it.
 */

#include <string>

namespace echidna
{
    namespace utils
    {

        /** Return the current process name by reading /proc/self/cmdline. */
        std::string CurrentProcessName();
        /**
         * @brief Returns cached process name (computed once per process).
         */
        /** Return a cached process name (computed once per process). */
        const std::string &CachedProcessName();

    } // namespace utils
} // namespace echidna
