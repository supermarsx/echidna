#pragma once

#include <cstddef>
#include <cstdint>

#include "echidna_api.h"

namespace echidna::jni
{

    bool InitialiseRuntime();
    bool IsRuntimeReady();
    void SetRuntimeBypass(bool bypass);
    bool SetRuntimeProfile(const char *json, size_t length);
    int RuntimeStatus();

    echidna_result_t ProcessRuntimeBlock(const float *input,
                                         float *output,
                                         uint32_t frames,
                                         uint32_t sample_rate,
                                         uint32_t channels);

} // namespace echidna::jni
