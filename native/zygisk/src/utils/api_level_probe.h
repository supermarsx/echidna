#pragma once

/**
 * @file api_level_probe.h
 * @brief Small helper to detect Android API level at runtime using
 * system properties.
 */

namespace echidna
{
  namespace utils
  {

    class ApiLevelProbe
    {
    public:
      /**
       * @brief Returns device API level (android.os.Build.VERSION.SDK_INT).
       */
      int apiLevel() const;
    };

  } // namespace utils
} // namespace echidna
