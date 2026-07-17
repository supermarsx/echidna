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
             *
             * ABI coverage: a relocating trampoline is implemented for @c aarch64
             * (primary), @c x86_64, and @c armeabi-v7a (ARM/Thumb-2). Each
             * relocator is conservative and fails closed (returns false, patches
             * nothing) on any prologue it cannot safely relocate — see
             * @c armv7_instruction.h for the ARM32/Thumb-2 decode+relocation
             * logic and its host test. On-hardware install/execution of the
             * armv7 path is device-gated (highest crash-risk). Any other ABI is
             * unimplemented: install() returns false after emitting an explicit
             * "hook_unsupported_abi" log/telemetry signal rather than failing
             * silently.
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
