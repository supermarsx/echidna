#pragma once

#include <string>

namespace echidna {
namespace utils {

std::string CurrentProcessName();
/**
 * @brief Returns cached process name (computed once per process).
 */
const std::string &CachedProcessName();

}  // namespace utils
}  // namespace echidna
