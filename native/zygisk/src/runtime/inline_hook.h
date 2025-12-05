#pragma once

/**
 * @file inline_hook.h
 * @brief Small helper for installing in-place function trampolines / inline
 * hooks. This class handles protecting memory pages and restoring original
 * bytes on destruction.
 */

#include <cstddef>
#include <mutex>

namespace echidna
{
  namespace runtime
  {

    class InlineHook
    {
    public:
      InlineHook();
      ~InlineHook();

      InlineHook(const InlineHook &) = delete;
      InlineHook &operator=(const InlineHook &) = delete;
      InlineHook(InlineHook &&) = delete;
      InlineHook &operator=(InlineHook &&) = delete;

      /**
       * @brief Install an inline patch which jumps from target to replacement.
       *
       * If successful `original` will be set to a callable trampoline pointer
       * that allows invoking the original implementation.
       */
      bool install(void *target, void *replacement, void **original);

    private:
      /** Change protection on memory pages covering address/length. */
      bool protect(void *address, size_t length, int prot);

      std::mutex mutex_;
      bool installed_;
      void *target_;
      void *trampoline_;
      size_t trampoline_size_;
      size_t patch_size_;
      alignas(8) unsigned char original_bytes_[32];
    };

  } // namespace runtime
} // namespace echidna
