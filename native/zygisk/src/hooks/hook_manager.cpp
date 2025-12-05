#include "hooks/hook_manager.h"

/**
 * @file hook_manager.cpp
 * @brief Empty translation unit - HookManager is a pure interface and lives
 * in headers; this file provides a compilation unit to avoid inline linking
 * issues.
 */

namespace echidna
{
    namespace hooks
    {

        // This translation unit intentionally left empty: HookManager only exposes
        // an inline defaulted destructor in the header so that consumers can link
        // without duplicate definitions.

    } // namespace hooks
} // namespace echidna
