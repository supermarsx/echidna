#include "utils/proc_maps_scanner.h"

/**
 * @file proc_maps_scanner.cpp
 * @brief Implementation for parsing /proc/self/maps and selecting memory regions
 * by predicate.
 */

#include <sys/types.h>
#include <unistd.h>

#include <fstream>
#include <sstream>
#include <string>

namespace echidna
{
    namespace utils
    {

        /** Return an array of parsed MemoryRegion entries read from /proc/self/maps. */
        std::vector<MemoryRegion> ProcMapsScanner::regions() const
        {
            std::vector<MemoryRegion> results;
            std::ifstream maps("/proc/self/maps");
            std::string line;
            while (std::getline(maps, line))
            {
                std::istringstream iss(line);
                std::string address_range;
                std::string permissions;
                std::string offset;
                std::string dev;
                std::string inode;
                std::string path;
                if (!(iss >> address_range >> permissions >> offset >> dev >> inode))
                {
                    continue;
                }
                std::getline(iss, path);
                if (!path.empty() && path.front() == ' ')
                {
                    path.erase(path.begin());
                }

                size_t separator = address_range.find('-');
                if (separator == std::string::npos)
                {
                    continue;
                }

                uintptr_t start = std::stoull(address_range.substr(0, separator), nullptr, 16);
                uintptr_t end = std::stoull(address_range.substr(separator + 1), nullptr, 16);
                results.push_back(MemoryRegion{start, end, permissions, path});
            }
            return results;
        }

        /** Find the first region where predicate(region) returns true. */
        std::optional<MemoryRegion> ProcMapsScanner::findRegion(const std::function<bool(const MemoryRegion &)> &predicate) const
        {
            for (const auto &region : regions())
            {
                if (predicate(region))
                {
                    return region;
                }
            }
            return std::nullopt;
        }

    } // namespace utils
} // namespace echidna
