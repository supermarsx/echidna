#include "runtime/inline_hook.h"

#include <atomic>
#include <cstring>

namespace echidna {
namespace runtime {

InlineHook::InlineHook() : installed_(false) {}

bool InlineHook::install(void *target, void *replacement, void **original) {
    if (!target || !replacement || !original) {
        return false;
    }

    std::scoped_lock lock(mutex_);
    if (installed_) {
        return true;
    }

    *original = target;
    installed_ = true;
    return true;
}

}  // namespace runtime
}  // namespace echidna
