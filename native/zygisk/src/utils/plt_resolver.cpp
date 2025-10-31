#include "utils/plt_resolver.h"

#include <dlfcn.h>
#include <sys/mman.h>

#include <algorithm>
#include <cstring>
#include <fstream>

#include "utils/proc_maps_scanner.h"

namespace echidna {
namespace utils {

void *PltResolver::findSymbol(const std::string &library, const std::string &symbol) const {
    void *handle = dlopen(library.c_str(), RTLD_LAZY | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen(library.c_str(), RTLD_LAZY);
    }
    if (!handle) {
        return nullptr;
    }
    return dlsym(handle, symbol.c_str());
}

void *PltResolver::findSymbolBySignature(const std::string &library, const std::vector<uint8_t> &signature) const {
    if (signature.empty()) {
        return nullptr;
    }

    ProcMapsScanner scanner;
    auto region = scanner.findRegion([&library](const MemoryRegion &region) {
        return region.path.find(library) != std::string::npos && region.permissions.find("x") != std::string::npos;
    });
    if (!region) {
        return nullptr;
    }

    size_t size = region->end - region->start;
    const uint8_t *base = reinterpret_cast<const uint8_t *>(region->start);
    for (size_t i = 0; i + signature.size() <= size; ++i) {
        if (std::memcmp(base + i, signature.data(), signature.size()) == 0) {
            return const_cast<uint8_t *>(base + i);
        }
    }
    return nullptr;
}

}  // namespace utils
}  // namespace echidna
