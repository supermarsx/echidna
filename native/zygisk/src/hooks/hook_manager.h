#pragma once

#include <string>

namespace echidna {
namespace hooks {

class HookManager {
  public:
    virtual ~HookManager() = default;
    virtual bool install() = 0;
    virtual const char *name() const = 0;
};

}  // namespace hooks
}  // namespace echidna
