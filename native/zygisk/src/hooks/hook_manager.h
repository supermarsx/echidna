#pragma once

/**
 * @file hook_manager.h
 * @brief Base interface for per-API hook manager classes which install and
 * maintain individual hook sets (AAudio, OpenSL, libc read, etc.).
 */

#include <string>

namespace echidna
{
  namespace hooks
  {

    class HookManager
    {
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

  } // namespace hooks
} // namespace echidna
