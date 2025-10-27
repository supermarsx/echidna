#pragma once

#include <mutex>

namespace echidna {
namespace runtime {

class InlineHook {
  public:
    InlineHook();

    bool install(void *target, void *replacement, void **original);

  private:
    std::mutex mutex_;
    bool installed_;
};

}  // namespace runtime
}  // namespace echidna
