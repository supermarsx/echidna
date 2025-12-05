#pragma once

#include <optional>
#include <string>
#include <vector>

namespace echidna {
namespace utils {

class PltResolver {
  public:
    /**
     * @brief Resolves a symbol by name from a loaded library.
     */
    void *findSymbol(const std::string &library, const std::string &symbol) const;
    /**
     * @brief Resolves a symbol by byte signature from a loaded library.
     */
    void *findSymbolBySignature(const std::string &library, const std::vector<uint8_t> &signature) const;
};

}  // namespace utils
}  // namespace echidna
