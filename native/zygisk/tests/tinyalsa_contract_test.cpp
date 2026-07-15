#include "hooks/tinyalsa_contract.h"

#include <cstdio>
#include <limits>

int main()
{
    using echidna::audio::PcmFormat;
    using namespace echidna::hooks;

    TinyAlsaConfigPrefix config{2, 48000, 240, 4, 0};
    const auto pcm16 = ParseTinyAlsaContract(kTinyAlsaPcmIn, &config);
    if (!pcm16 || pcm16->format != PcmFormat::kSigned16 ||
        TinyAlsaBytesForFrames(*pcm16, 10) != 40)
    {
        return 1;
    }
    config.format = 1;
    const auto pcm32 = ParseTinyAlsaContract(kTinyAlsaPcmIn, &config);
    if (!pcm32 || pcm32->format != PcmFormat::kSigned32 ||
        TinyAlsaBytesForFrames(*pcm32, 10) != 80)
    {
        return 1;
    }
    config.format = 4;
    const auto packed24 = ParseTinyAlsaContract(kTinyAlsaPcmIn, &config);
    if (!packed24 || packed24->format != PcmFormat::kSigned24Packed ||
        TinyAlsaBytesForFrames(*packed24, 10) != 60)
    {
        return 1;
    }

    config.format = 2;
    if (ParseTinyAlsaContract(kTinyAlsaPcmIn, &config))
    {
        return 1;
    }
    config.format = 3;
    if (ParseTinyAlsaContract(kTinyAlsaPcmIn, &config))
    {
        return 1;
    }
    config.format = 0;
    if (ParseTinyAlsaContract(0, &config) ||
        ParseTinyAlsaContract(kTinyAlsaPcmIn, nullptr))
    {
        return 1;
    }
    config.channels = 0;
    if (ParseTinyAlsaContract(kTinyAlsaPcmIn, &config))
    {
        return 1;
    }
    config.channels = std::numeric_limits<uint32_t>::max();
    if (ParseTinyAlsaContract(kTinyAlsaPcmIn, &config))
    {
        return 1;
    }

    std::fprintf(stderr, "tinyalsa_contract_test: all checks passed\n");
    return 0;
}
