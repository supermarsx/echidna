#pragma once

/**
 * @file proc_maps_scanner.h
 * @brief Helpers to parse /proc/self/maps and produce MemoryRegion structures.
 */

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace echidna
{
  namespace utils
  {

    struct MemoryRegion
    {
      uintptr_t start;
      uintptr_t end;
      std::string permissions;
      std::string path;
    };

    class ProcMapsScanner
    {
    public:
      ProcMapsScanner() = default;
      /**
       * @brief Returns parsed /proc/self/maps regions.
       */
      std::vector<MemoryRegion> regions() const;
      /**
       * @brief Finds first region matching predicate.
       */
      std::optional<MemoryRegion> findRegion(const std::function<bool(const MemoryRegion &)> &predicate) const;
    };

  } // namespace utils
} // namespace echidna
