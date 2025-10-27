#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace echidna {
namespace utils {

struct MemoryRegion {
    uintptr_t start;
    uintptr_t end;
    std::string permissions;
    std::string path;
};

class ProcMapsScanner {
  public:
    ProcMapsScanner() = default;
    std::vector<MemoryRegion> regions() const;
    std::optional<MemoryRegion> findRegion(const std::function<bool(const MemoryRegion &)> &predicate) const;
};

}  // namespace utils
}  // namespace echidna
