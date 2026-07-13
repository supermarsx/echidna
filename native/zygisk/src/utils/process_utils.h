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

        /** Override the cached process name with a Zygisk-specialization value. */
        void SetCachedProcessName(std::string process_name);

        /**
         * @brief Returns cached process name.
         */
        const std::string &CachedProcessName();

    } // namespace utils
} // namespace echidna
