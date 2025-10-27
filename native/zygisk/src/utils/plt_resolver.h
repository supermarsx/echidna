#pragma once

#include <optional>
#include <string>
#include <vector>

namespace echidna {
namespace utils {

class PltResolver {
  public:
    void *findSymbol(const std::string &library, const std::string &symbol) const;
    void *findSymbolBySignature(const std::string &library, const std::vector<uint8_t> &signature) const;
};

}  // namespace utils
}  // namespace echidna
