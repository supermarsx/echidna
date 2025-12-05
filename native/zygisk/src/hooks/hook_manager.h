#pragma once

#include <string>

namespace echidna {
namespace hooks {

class HookManager {
  public:
    virtual ~HookManager() = default;
    /**
     * @brief Installs the hook(s) managed by this instance.
     * @return true on success.
     */
    virtual bool install() = 0;
    /**
     * @brief Returns a short human-readable hook name.
     */
    virtual const char *name() const = 0;
};

}  // namespace hooks
}  // namespace echidna
