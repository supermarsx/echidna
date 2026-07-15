#pragma once

#include "effect_abi.h"

#if defined(_WIN32)
#if defined(ECHIDNA_PREPROC_BUILD)
#define ECHIDNA_PREPROC_EXPORT __declspec(dllexport)
#else
#define ECHIDNA_PREPROC_EXPORT __declspec(dllimport)
#endif
#else
#define ECHIDNA_PREPROC_EXPORT __attribute__((visibility("default")))
#endif

namespace echidna::effects::legacy
{
    inline constexpr effect_uuid_t kEffectTypeUuid = {
        0xc83e3db3U,
        0xd4f5U,
        0x5f2cU,
        0xa095U,
        {0x87U, 0x75U, 0xc1U, 0xedU, 0xfcU, 0x6dU},
    };
    inline constexpr effect_uuid_t kEffectImplementationUuid = {
        0x3e66a36eU,
        0xdee9U,
        0x5d81U,
        0xa0d6U,
        {0x49U, 0xfcU, 0x3bU, 0x86U, 0x35U, 0x30U},
    };
} // namespace echidna::effects::legacy

extern "C" ECHIDNA_PREPROC_EXPORT audio_effect_library_t
    AUDIO_EFFECT_LIBRARY_INFO_SYM;
