#include "utils/process_utils.h"

/**
 * @file process_utils.cpp
 * @brief Implementation of process name helpers.
 */

#include <fstream>
#include <mutex>
#include <utility>

namespace echidna
{
    namespace utils
    {
        namespace
        {
            std::string &ProcessNameCache()
            {
                static std::string process = CurrentProcessName();
                return process;
            }

            std::mutex &ProcessNameMutex()
            {
                static std::mutex mutex;
                return mutex;
            }
        } // namespace

        std::string CurrentProcessName()
        {
            std::ifstream cmdline("/proc/self/cmdline");
            std::string value;
            std::getline(cmdline, value, '\0');
            return value;
        }

        void SetCachedProcessName(std::string process_name)
        {
            if (process_name.empty())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(ProcessNameMutex());
            ProcessNameCache() = std::move(process_name);
        }

        const std::string &CachedProcessName()
        {
            std::lock_guard<std::mutex> lock(ProcessNameMutex());
            return ProcessNameCache();
        }

    } // namespace utils
} // namespace echidna
