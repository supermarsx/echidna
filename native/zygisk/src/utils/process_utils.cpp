#include "utils/process_utils.h"

/**
 * @file process_utils.cpp
 * @brief Implementation of process name helpers.
 */

#include <fstream>

namespace echidna
{
    namespace utils
    {

        std::string CurrentProcessName()
        {
            std::ifstream cmdline("/proc/self/cmdline");
            std::string value;
            std::getline(cmdline, value, '\0');
            return value;
        }

        const std::string &CachedProcessName()
        {
            static const std::string process = CurrentProcessName();
            return process;
        }

    } // namespace utils
} // namespace echidna
