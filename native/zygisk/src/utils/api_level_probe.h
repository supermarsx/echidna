#pragma once

namespace echidna {
namespace utils {

class ApiLevelProbe {
  public:
    /**
     * @brief Returns device API level (android.os.Build.VERSION.SDK_INT).
     */
    int apiLevel() const;
};

}  // namespace utils
}  // namespace echidna
