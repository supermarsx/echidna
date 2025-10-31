#pragma once

#include <cstddef>

namespace echidna::dsp::runtime {

void apply_gain(float *data, size_t samples, float gain);
void mix_in(float *dst, const float *src, size_t samples, float gain);

}  // namespace echidna::dsp::runtime
