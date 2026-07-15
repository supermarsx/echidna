#pragma once

/**
 * @file process_utils.h
 * @brief Helpers for querying the current process name and caching it.
 */

#include <cstdint>
#include <string>
#include <string_view>

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
        std::string CachedProcessName();

        /** Parses one package UID from Android's root-owned packages.list data. */
        int64_t ParsePackageUid(std::string_view packages_list,
                                std::string_view package_name);

        /** Reads and parses one package UID, returning -1 on any unsafe input. */
        int64_t ResolvePackageUid(
            std::string_view package_name,
            const std::string &packages_list_path = "/data/system/packages.list");

        /** Maps a system-user package UID to the target Android user profile. */
        int64_t PackageUidForTargetUser(int64_t package_uid, int64_t target_uid);

    } // namespace utils
} // namespace echidna
